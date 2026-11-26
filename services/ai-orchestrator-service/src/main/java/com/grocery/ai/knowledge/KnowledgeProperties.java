package com.grocery.ai.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * {@code grocery.ai.knowledge.*}: the help-center knowledge base the assistants answer policy and how-to questions
 * from (RAG; docs/rag/RAG-ARCHITECTURE.md). Env names are in application.yml.
 *
 * @param enabled      the knowledge base is on ({@code AI_KNOWLEDGE_ENABLED}, false). Off: no Elasticsearch or
 *                     embedding calls, the admin API answers 503 KNOWLEDGE_BASE_DISABLED and the chat works as before
 * @param indexAlias   alias of this service's own Elasticsearch index (ADR-0015)
 * @param indexVersion the concrete index is {@code <indexAlias>_v<indexVersion>}; a new embedding model needs a new
 *                     version and a reindex of every document
 */
@ConfigurationProperties(prefix = "grocery.ai.knowledge")
public record KnowledgeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("grocery-knowledge") String indexAlias,
        @DefaultValue("1") int indexVersion,
        @DefaultValue Embedding embedding,
        @DefaultValue Chunking chunking,
        @DefaultValue Retrieval retrieval,
        @DefaultValue Upload upload,
        @DefaultValue Ingestion ingestion,
        @DefaultValue Seed seed) {

    public KnowledgeProperties {
        if (chunking.overlapTokens() >= chunking.chunkTokens()) {
            throw new IllegalArgumentException("grocery.ai.knowledge.chunking.overlap-tokens must be smaller than "
                    + "chunk-tokens");
        }
        if (retrieval.topK() < 1 || retrieval.topK() > 20) {
            throw new IllegalArgumentException("grocery.ai.knowledge.retrieval.top-k must be between 1 and 20");
        }
        if (retrieval.similarityThreshold() < 0 || retrieval.similarityThreshold() > 1
                || retrieval.unrecognisedThreshold() < 0 || retrieval.unrecognisedThreshold() > 1) {
            throw new IllegalArgumentException("grocery.ai.knowledge.retrieval thresholds must be between 0 and 1");
        }
    }

    /** The concrete index behind {@link #indexAlias()}. */
    public String indexName() {
        return indexAlias + "_v" + indexVersion;
    }

    /**
     * The model that turns text into vectors. Chunks and questions must use the same model.
     *
     * @param provider       {@code ollama} (local development: the chat model's Ollama) or {@code bedrock} (AWS:
     *                       Amazon Bedrock with the task role's credentials) ({@code AI_EMBEDDING_PROVIDER})
     * @param model          the embedding model ({@code AI_EMBEDDING_MODEL}: nomic-embed-text with Ollama,
     *                       amazon.titan-embed-text-v2:0 with Bedrock)
     * @param dimensions     length of its vectors ({@code AI_EMBEDDING_DIMENSIONS}: 768 for nomic-embed-text, 1024 for
     *                       Titan V2): the index is created with it
     * @param documentPrefix put in front of every chunk before embedding (nomic-embed-text: "search_document: ")
     * @param queryPrefix    put in front of every question before embedding (nomic-embed-text: "search_query: ")
     * @param timeout        longest wait for one embedding call ({@code AI_EMBEDDING_TIMEOUT}, 60 s)
     * @param batchSize      chunks embedded per call during ingestion (16)
     * @param region         Bedrock's AWS region ({@code AI_EMBEDDING_REGION}); empty = the task's own region
     */
    public record Embedding(@DefaultValue("ollama") String provider,
                            @DefaultValue("nomic-embed-text") String model,
                            @DefaultValue("768") int dimensions,
                            @DefaultValue("") String documentPrefix,
                            @DefaultValue("") String queryPrefix,
                            @DefaultValue("60s") Duration timeout,
                            @DefaultValue("16") int batchSize,
                            @DefaultValue("") String region) {
    }

    /**
     * How documents are cut into chunks. Sizes are estimated tokens, about 4 characters each.
     *
     * @param chunkTokens   target size of one chunk ({@code AI_KNOWLEDGE_CHUNK_TOKENS}, 350)
     * @param overlapTokens text repeated from the end of one chunk at the start of the next
     *                      ({@code AI_KNOWLEDGE_OVERLAP_TOKENS}, 50), so a sentence cut at a border is still whole in
     *                      one of them
     */
    public record Chunking(@DefaultValue("350") int chunkTokens,
                           @DefaultValue("50") int overlapTokens) {
    }

    /**
     * The thresholds are cosine similarities (0–1) and depend on the embedding model: calibrate them with the
     * evaluation (docs/rag/RAG-TESTING-GUIDE.md). The defaults are nomic-embed-text's.
     *
     * @param topK                   chunks given to the model at most ({@code AI_KNOWLEDGE_TOP_K}, 4)
     * @param similarityThreshold    what a chunk needs to count as relevant for a question recognised as a policy or
     *                               how-to question, and for searchHelpCenter ({@code AI_KNOWLEDGE_SIMILARITY_THRESHOLD},
     *                               0.64)
     * @param unrecognisedThreshold  what a chunk needs to be added to a message the rules did not recognise (it may
     *                               well be about products or orders, so the bar is higher)
     *                               ({@code AI_KNOWLEDGE_UNRECOGNISED_THRESHOLD}, 0.70; 1 = never)
     * @param numCandidates          vectors Elasticsearch compares per search before keeping the best topK (100): more
     *                               finds better matches, at a small cost
     */
    public record Retrieval(@DefaultValue("4") int topK,
                            @DefaultValue("0.64") double similarityThreshold,
                            @DefaultValue("0.7") double unrecognisedThreshold,
                            @DefaultValue("100") int numCandidates) {
    }

    /**
     * @param maxFileSize       largest file accepted ({@code AI_KNOWLEDGE_MAX_FILE_SIZE}, 5 MB)
     * @param maxTextCharacters most characters of text one document may have after extraction (300,000)
     */
    public record Upload(@DefaultValue("5MB") DataSize maxFileSize,
                         @DefaultValue("300000") int maxTextCharacters) {
    }

    /**
     * @param pollerEnabled     look for uploaded documents on a schedule (tests run the worker themselves)
     * @param pollInterval      how often (15 s); an upload also wakes the worker at once
     * @param processingTimeout a document PROCESSING for longer was interrupted (a restart): it is tried again
     * @param maxAttempts       tries before a document is FAILED for good (3)
     */
    public record Ingestion(@DefaultValue("true") boolean pollerEnabled,
                            @DefaultValue("15s") Duration pollInterval,
                            @DefaultValue("10m") Duration processingTimeout,
                            @DefaultValue("3") int maxAttempts) {
    }

    /** @param enabled load the starter articles in {@code knowledge/seed/} when missing ({@code AI_KNOWLEDGE_SEED}) */
    public record Seed(@DefaultValue("false") boolean enabled) {
    }
}
