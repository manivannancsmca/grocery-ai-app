package com.grocery.ai.knowledge.search;

import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import com.grocery.ai.knowledge.KnowledgeMetrics;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.document.KnowledgeDocument;
import com.grocery.ai.knowledge.document.KnowledgeDocumentRepository;
import com.grocery.ai.knowledge.index.KnowledgeEmbeddings;
import com.grocery.ai.knowledge.index.KnowledgeIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Semantic search in the knowledge base (docs/rag/RAG-ARCHITECTURE.md, "Retrieval"):
 * <pre>
 *   question → embedding (with the query prefix) → kNN in the index, filtered by the reader's audiences (and a
 *   category) → only chunks at least similarity-threshold similar, at most top-k → each hit checked in MySQL
 * </pre>
 * The MySQL check keeps the index honest: a chunk counts only if its document is not deleted, the chunk belongs to the
 * generation in use, and the document is (still) meant for the reader. So a deleted document, an old version or an
 * audience just removed is never returned, even before the index has caught up.
 *
 * <p>Never throws for the chat: an outage is {@link Status#UNAVAILABLE} and the assistant says so.
 */
@Service
public class KnowledgeSearch {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearch.class);
    /** Longest question embedded (a pasted essay is cut). */
    static final int MAX_QUESTION_CHARACTERS = 1000;

    private final KnowledgeProperties properties;
    private final KnowledgeEmbeddings embeddings;
    private final KnowledgeIndex index;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeMetrics metrics;

    public KnowledgeSearch(KnowledgeProperties properties, KnowledgeEmbeddings embeddings, KnowledgeIndex index,
                           KnowledgeDocumentRepository documents, KnowledgeMetrics metrics) {
        this.properties = properties;
        this.embeddings = embeddings;
        this.index = index;
        this.documents = documents;
        this.metrics = metrics;
    }

    public enum Status {
        /** Relevant chunks were found. */
        FOUND,
        /** Nothing relevant: the assistant gives the controlled "I couldn't find a verified answer" reply. */
        NO_MATCH,
        /** Elasticsearch or the embedding model could not be reached. */
        UNAVAILABLE,
        /** The knowledge base is switched off. */
        DISABLED
    }

    /** One relevant piece of a document. {@code similarity}: cosine similarity to the question (0–1). */
    public record Snippet(UUID documentId, String title, KnowledgeCategory category, String text,
                          double similarity) {
    }

    public record Result(Status status, List<Snippet> snippets) {

        static Result of(Status status) {
            return new Result(status, List.of());
        }

        public boolean found() {
            return status == Status.FOUND;
        }

        /** The documents the snippets come from, once each, in order of their best snippet. */
        public List<Snippet> sources() {
            return snippets.stream().collect(Collectors.toMap(Snippet::documentId, Function.identity(),
                    (first, second) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** For an assistant: the reader's audiences come from their roles (the validated token). */
    public Result forReader(String question, Set<String> roles) {
        return forReader(question, roles, properties.retrieval().similarityThreshold());
    }

    /** {@link #forReader(String, Set)} with another threshold (messages not recognised as policy questions). */
    public Result forReader(String question, Set<String> roles, double threshold) {
        return search(question, KnowledgeAudience.of(roles), null, properties.retrieval().topK(), threshold);
    }

    /** The threshold for messages the rules did not recognise as policy or how-to questions. */
    public double unrecognisedThreshold() {
        return properties.retrieval().unrecognisedThreshold();
    }

    /** The search itself, for {@link #forReader} and the admin's test search. */
    public Result search(String question, Set<KnowledgeAudience> audiences, KnowledgeCategory category, int topK,
                         double threshold) {
        if (!properties.enabled()) {
            return Result.of(Status.DISABLED);
        }
        String text = question == null ? "" : question.strip();
        if (text.isEmpty() || audiences.isEmpty()) {
            return Result.of(Status.NO_MATCH);
        }
        if (text.length() > MAX_QUESTION_CHARACTERS) {
            text = text.substring(0, MAX_QUESTION_CHARACTERS);
        }
        List<KnowledgeIndex.ChunkHit> hits;
        try {
            long started = System.nanoTime();
            float[] vector = embeddings.embedQuestion(text);
            long embedded = System.nanoTime();
            metrics.embedding("question", embedded - started);
            hits = index.search(vector, audiences, category, topK, threshold);
            metrics.search(System.nanoTime() - embedded);
        } catch (Exception e) {
            log.warn("Knowledge base search failed: {}", e.toString());
            return Result.of(Status.UNAVAILABLE);
        }
        List<Snippet> snippets = current(hits, audiences);
        return snippets.isEmpty() ? Result.of(Status.NO_MATCH) : new Result(Status.FOUND, snippets);
    }

    /** The hits whose document is in use, at the indexed generation, and still meant for these audiences. */
    private List<Snippet> current(List<KnowledgeIndex.ChunkHit> hits, Set<KnowledgeAudience> audiences) {
        if (hits.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = hits.stream().map(KnowledgeIndex.ChunkHit::documentId).collect(Collectors.toSet());
        Map<UUID, KnowledgeDocument> byId = documents.findAllById(ids).stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));
        return hits.stream()
                .filter(hit -> {
                    KnowledgeDocument document = byId.get(hit.documentId());
                    return document != null && document.isSearchable()
                            && Objects.equals(document.getIndexedGeneration(), hit.generation())
                            && !Collections.disjoint(document.getAudiences(), audiences);
                })
                .map(hit -> {
                    KnowledgeDocument document = byId.get(hit.documentId());
                    return new Snippet(hit.documentId(), document.getTitle(), document.getCategory(), hit.text(),
                            Math.max(0, Math.min(1, hit.similarity())));
                })
                .toList();
    }
}
