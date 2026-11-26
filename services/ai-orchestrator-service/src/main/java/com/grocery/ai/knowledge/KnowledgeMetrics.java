package com.grocery.ai.knowledge;

import com.grocery.ai.knowledge.document.DocumentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The knowledge base's metrics (Prometheus names in docs/rag/RAG-TROUBLESHOOTING.md), next to the assistants'
 * {@code grocery.ai.*} ones ({@code AiMetrics}). Spring AI adds its own embedding observations
 * ({@code gen_ai.client.operation}) to the traces.
 */
@Component
public class KnowledgeMetrics {

    public static final String INGESTIONS = "grocery.ai.knowledge.ingestions";
    public static final String INGESTION_LATENCY = "grocery.ai.knowledge.ingestion.latency";
    public static final String EMBEDDING_LATENCY = "grocery.ai.knowledge.embedding.latency";
    public static final String SEARCH_LATENCY = "grocery.ai.knowledge.search.latency";
    public static final String RETRIEVALS = "grocery.ai.knowledge.retrievals";
    public static final String RETRIEVED_CHUNKS = "grocery.ai.knowledge.retrieved.chunks";

    /** Retrieval outcomes. */
    public static final String HIT = "HIT";
    public static final String NO_MATCH = "NO_MATCH";
    public static final String ERROR = "ERROR";

    public static final String DOCUMENTS = "grocery.ai.knowledge.documents";

    private final MeterRegistry registry;
    /** Documents per status, refreshed by every ingestion run (gauges read them). */
    private final Map<String, AtomicLong> documents = new ConcurrentHashMap<>();

    public KnowledgeMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (DocumentStatus status : DocumentStatus.values()) {
            AtomicLong count = new AtomicLong();
            documents.put(status.name(), count);
            Gauge.builder(DOCUMENTS, count, AtomicLong::get).description("Knowledge base documents by status")
                    .tag("status", status.name()).register(registry);
        }
    }

    /** The current number of documents per status (statuses missing from the map have none). */
    public void documents(Map<String, Long> counts) {
        documents.forEach((status, count) -> count.set(counts.getOrDefault(status, 0L)));
    }

    /** One document indexing run ended: COMPLETED, FAILED, RETRY or STALE (a newer version came in meanwhile). */
    public void ingestion(String outcome, long nanos) {
        Counter.builder(INGESTIONS).description("Knowledge base document indexing runs").tag("outcome", outcome)
                .register(registry).increment();
        Timer.builder(INGESTION_LATENCY).description("Time to index one knowledge base document")
                .tag("outcome", outcome).publishPercentileHistogram().register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** One embedding call: {@code operation} = document (ingestion batch) or question (search). */
    public void embedding(String operation, long nanos) {
        Timer.builder(EMBEDDING_LATENCY).description("Embedding model call latency").tag("operation", operation)
                .publishPercentileHistogram().register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** One vector search in Elasticsearch. */
    public void search(long nanos) {
        Timer.builder(SEARCH_LATENCY).description("Knowledge index vector search latency")
                .publishPercentileHistogram().register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * One retrieval for an assistant: HIT (chunks found), NO_MATCH (nothing relevant: the controlled answer) or ERROR
     * (unavailable); {@code source} = before (a recognised question, asked before the model), unrecognised (any other
     * message, asked before the model with the higher threshold: NO_MATCH is the normal outcome there) or tool (the
     * model called searchHelpCenter). The chunk count is recorded for hits only.
     */
    public void retrieval(String agent, String source, String outcome, int chunks) {
        Counter.builder(RETRIEVALS).description("Knowledge base retrievals for the assistants")
                .tag("agent", agent).tag("source", source).tag("outcome", outcome).register(registry).increment();
        if (HIT.equals(outcome)) {
            DistributionSummary.builder(RETRIEVED_CHUNKS).description("Chunks given to the model per retrieval")
                    .tag("agent", agent).register(registry).record(chunks);
        }
    }
}
