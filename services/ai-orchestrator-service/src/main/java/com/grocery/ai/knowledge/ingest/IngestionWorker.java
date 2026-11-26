package com.grocery.ai.knowledge.ingest;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import com.grocery.ai.knowledge.KnowledgeMetrics;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.document.DocumentStatus;
import com.grocery.ai.knowledge.document.KnowledgeChunk;
import com.grocery.ai.knowledge.document.KnowledgeChunkRepository;
import com.grocery.ai.knowledge.document.KnowledgeDocument;
import com.grocery.ai.knowledge.document.KnowledgeDocumentRepository;
import com.grocery.ai.knowledge.document.KnowledgeDocumentTextRepository;
import com.grocery.ai.knowledge.index.KnowledgeEmbeddings;
import com.grocery.ai.knowledge.index.KnowledgeIndex;
import com.grocery.ai.model.ModelActivity;
import com.grocery.common.id.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Indexes uploaded documents in the background, one at a time per replica (embedding shares the CPU with the chat
 * model locally):
 * <pre>
 *   claim (UPLOADED → PROCESSING) → chunk the stored text → embed in batches → write the chunks to the index
 *   → COMPLETED: the new generation becomes searchable → delete the document's older generations
 * </pre>
 * <ul>
 *   <li>The claim uses {@code FOR UPDATE SKIP LOCKED}: two replicas never take the same document.</li>
 *   <li>Before each embedding batch it waits while a customer's chat uses the model ({@link ModelActivity}).</li>
 *   <li>A document replaced or deleted while it was processed is left as it is: the chunks just written are removed
 *       and the newer version is indexed next.</li>
 *   <li>A failure is tried again on the next run (every {@code poll-interval}) up to {@code max-attempts} times; then
 *       the document is FAILED with a reason the admin sees (never a stack trace). Its previous version, if any,
 *       stays searchable.</li>
 *   <li>Each run also recovers documents left PROCESSING by a stopped replica and removes chunks of deleted documents
 *       whose removal failed.</li>
 * </ul>
 * An upload wakes the worker at once ({@link #wake}); tests call {@link #drain} themselves.
 */
@Component
public class IngestionWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    /** Longest wait for chats before embedding anyway. */
    private static final Duration MAX_WAIT_FOR_CHATS = Duration.ofMinutes(2);
    private static final int CLEAN_UP_BATCH = 20;

    private final KnowledgeProperties properties;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentTextRepository texts;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeEmbeddings embeddings;
    private final KnowledgeIndex index;
    private final ModelActivity modelActivity;
    private final KnowledgeMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final TextChunker chunker;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("knowledge-ingestion").daemon().factory());
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("knowledge-ingestion-tick").daemon().factory());
    /** A run is queued and has not started yet (one queued run is enough). */
    private final AtomicBoolean queued = new AtomicBoolean();

    public IngestionWorker(KnowledgeProperties properties, KnowledgeDocumentRepository documents,
                           KnowledgeDocumentTextRepository texts, KnowledgeChunkRepository chunks,
                           KnowledgeEmbeddings embeddings, KnowledgeIndex index, ModelActivity modelActivity,
                           KnowledgeMetrics metrics, TransactionTemplate transaction, Clock clock) {
        this.properties = properties;
        this.documents = documents;
        this.texts = texts;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.index = index;
        this.modelActivity = modelActivity;
        this.metrics = metrics;
        this.transaction = transaction;
        this.clock = clock;
        this.chunker = new TextChunker(properties.chunking().chunkTokens(), properties.chunking().overlapTokens());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled() || !properties.ingestion().pollerEnabled()) {
            return;
        }
        long interval = properties.ingestion().pollInterval().toMillis();
        ticker.scheduleWithFixedDelay(this::wake, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Knowledge base ingestion worker started (every {})", properties.ingestion().pollInterval());
    }

    /** Runs the worker soon on its own thread (after an upload, and on every tick). */
    public void wake() {
        if (!properties.enabled() || !properties.ingestion().pollerEnabled()) {
            return;
        }
        if (queued.compareAndSet(false, true)) {
            runner.execute(() -> {
                queued.set(false);
                try {
                    drain();
                } catch (RuntimeException e) {
                    log.warn("Knowledge base ingestion run failed: {}", e.toString());
                }
            });
        }
    }

    /**
     * One run: recovers interrupted documents, cleans up deleted ones, then indexes uploaded documents until none is
     * left or one fails (it is tried again on the next run). Returns how many documents were indexed.
     */
    public int drain() {
        if (!properties.enabled()) {
            return 0;
        }
        recoverInterrupted();
        cleanUpDeleted();
        int indexed = 0;
        try {
            while (true) {
                Outcome outcome = processNext();
                if (outcome == Outcome.NOTHING || outcome == Outcome.RETRY || outcome == Outcome.FAILED) {
                    return indexed;
                }
                if (outcome == Outcome.COMPLETED) {
                    indexed++;
                }
            }
        } finally {
            metrics.documents(documents.countsByStatus());   // the documents-by-status gauges
        }
    }

    private enum Outcome { NOTHING, COMPLETED, STALE, RETRY, FAILED }

    /** What the worker reads of a claimed document (so the rest of the run needs no open transaction). */
    private record Claimed(UUID id, int generation, String title, KnowledgeCategory category,
                           Set<KnowledgeAudience> audiences) {
    }

    private Outcome processNext() {
        Claimed claimed = transaction.execute(status -> {
            List<String> ids = documents.claimNextUploaded();
            if (ids.isEmpty()) {
                return null;
            }
            KnowledgeDocument document = documents.findById(UUID.fromString(ids.getFirst())).orElseThrow();
            document.startProcessing(clock.instant());
            return new Claimed(document.getId(), document.getGeneration(), document.getTitle(),
                    document.getCategory(), document.getAudiences());
        });
        if (claimed == null) {
            return Outcome.NOTHING;
        }
        long started = System.nanoTime();
        Outcome outcome;
        try {
            outcome = index(claimed);
        } catch (Exception e) {
            outcome = failed(claimed, e);
        }
        metrics.ingestion(outcome.name(), System.nanoTime() - started);
        return outcome;
    }

    private Outcome index(Claimed claimed) throws IOException {
        String text = texts.findById(claimed.id()).orElseThrow(() -> new IllegalStateException(
                "The document's text is missing")).getContent();
        List<String> pieces = chunker.split(text);
        long embeddingStarted = System.nanoTime();
        List<float[]> vectors = embeddings.embedChunks(claimed.title(), pieces, this::giveWayToChats);
        metrics.embedding("document", System.nanoTime() - embeddingStarted);

        List<String> audiences = claimed.audiences().stream().map(Enum::name).toList();
        List<KnowledgeIndex.IndexedChunk> indexed = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            indexed.add(new KnowledgeIndex.IndexedChunk(Ids.newId().toString(), claimed.id().toString(),
                    claimed.generation(), i, claimed.title(), claimed.category().name(), audiences, pieces.get(i),
                    vectors.get(i)));
        }
        index.write(indexed);

        Boolean current = transaction.execute(status -> {
            KnowledgeDocument document = documents.findById(claimed.id()).orElse(null);
            if (document == null || document.isDeleted() || document.getGeneration() != claimed.generation()
                    || document.getStatus() != DocumentStatus.PROCESSING) {
                return false;
            }
            Instant now = clock.instant();
            chunks.saveAll(indexed.stream().map(chunk -> new KnowledgeChunk(UUID.fromString(chunk.chunkId()),
                    claimed.id(), claimed.generation(), chunk.chunkIndex(), chunk.text().length(), now)).toList());
            document.completed(claimed.generation(), indexed.size(), embeddings.modelName(), now);
            return true;
        });
        if (!Boolean.TRUE.equals(current)) {
            // Replaced, reindexed or deleted meanwhile: these chunks are already out of date.
            index.deleteGeneration(claimed.id(), claimed.generation());
            log.info("Knowledge document {} changed while generation {} was indexed; that generation was dropped",
                    claimed.id(), claimed.generation());
            return Outcome.STALE;
        }
        index.deleteOtherGenerations(claimed.id(), claimed.generation());
        transaction.executeWithoutResult(status -> chunks.deleteOtherGenerations(claimed.id(), claimed.generation()));
        log.info("Knowledge document {} '{}' indexed: {} chunks (generation {})", claimed.id(), claimed.title(),
                indexed.size(), claimed.generation());
        return Outcome.COMPLETED;
    }

    private Outcome failed(Claimed claimed, Exception e) {
        String reason = reason(e);
        log.warn("Indexing knowledge document {} (generation {}) failed: {}", claimed.id(), claimed.generation(),
                e.toString());
        try {
            index.deleteGeneration(claimed.id(), claimed.generation());
        } catch (Exception cleanup) {
            log.debug("Could not remove the partial chunks of {}: {}", claimed.id(), cleanup.toString());
        }
        Outcome outcome = transaction.execute(status -> {
            KnowledgeDocument document = documents.findById(claimed.id()).orElse(null);
            if (document == null || document.isDeleted() || document.getGeneration() != claimed.generation()) {
                return Outcome.STALE;
            }
            if (document.getAttempts() < properties.ingestion().maxAttempts()) {
                document.retryLater(reason + " Trying again shortly.", clock.instant());
                return Outcome.RETRY;
            }
            document.failed(reason, clock.instant());
            return Outcome.FAILED;
        });
        return outcome == null ? Outcome.STALE : outcome;
    }

    /** What the admin is told (no stack traces, no internal hosts). */
    static String reason(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        // Ollama answers 404 "model … not found, try pulling it first"; Spring AI wraps it (NonTransientAiException)
        if (e instanceof HttpClientErrorException.NotFound
                || (message.contains("not found") && (message.contains("model") || message.contains("pull")))) {
            return "The embedding model is not installed (local: ollama pull <model>).";
        }
        if (e instanceof ResourceAccessException || e instanceof TransientAiException) {
            return "The embedding model could not be reached.";
        }
        if (e instanceof IOException || e.getClass().getName().startsWith("co.elastic.clients")) {
            return "Elasticsearch could not be reached or refused the chunks.";
        }
        if (e instanceof IllegalStateException && e.getMessage() != null) {
            return e.getMessage();
        }
        return "Indexing failed (" + e.getClass().getSimpleName() + ").";
    }

    /**
     * Waits while a chat turn or an insight report uses the model (customers go first), at most two minutes of real
     * time, then embeds anyway.
     */
    private void giveWayToChats() {
        long giveUp = System.nanoTime() + MAX_WAIT_FOR_CHATS.toNanos();
        while (!modelActivity.idleFor(Duration.ZERO) && System.nanoTime() < giveUp) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Documents left PROCESSING by a replica that stopped: tried again (or FAILED after max-attempts). */
    void recoverInterrupted() {
        Instant before = clock.instant().minus(properties.ingestion().processingTimeout());
        transaction.executeWithoutResult(status -> {
            for (String id : documents.claimInterrupted(before, CLEAN_UP_BATCH)) {
                KnowledgeDocument document = documents.findById(UUID.fromString(id)).orElseThrow();
                if (document.getAttempts() < properties.ingestion().maxAttempts()) {
                    document.retryLater("Indexing was interrupted. Trying again.", clock.instant());
                } else {
                    document.failed("Indexing was interrupted too often.", clock.instant());
                }
                log.info("Knowledge document {} was left PROCESSING: now {}", id, document.getStatus());
            }
        });
    }

    /** Removes the chunks of deleted documents whose removal failed at delete time. */
    void cleanUpDeleted() {
        for (String id : documents.deletedWithChunks(CLEAN_UP_BATCH)) {
            UUID documentId = UUID.fromString(id);
            try {
                index.deleteDocument(documentId);
                transaction.executeWithoutResult(status -> chunks.deleteByDocument(documentId));
                log.info("Removed the remaining chunks of deleted knowledge document {}", documentId);
            } catch (Exception e) {
                log.warn("Still cannot remove the chunks of deleted knowledge document {}: {}", documentId,
                        e.toString());
                return;
            }
        }
    }

    @Override
    public void destroy() {
        ticker.shutdownNow();
        runner.shutdownNow();
    }
}
