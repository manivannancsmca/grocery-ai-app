package com.grocery.ai.knowledge.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeCategory;
import com.grocery.ai.knowledge.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * This service's own Elasticsearch index of help-center chunks (ADR-0015): {@code grocery-knowledge_v1} behind the
 * alias {@code grocery-knowledge}, created from {@code elasticsearch/knowledge.json} with the configured vector
 * dimensions. One Elasticsearch document per chunk: its text, its vector and what a search filters on (document,
 * generation, category, audiences).
 *
 * <p>Like catalog-query-service's IndexManager, it never fails startup (Elasticsearch may still be starting): the
 * index is created at startup when possible, otherwise before the first write.
 *
 * <p>A document's chunks carry the <em>generation</em> they were made in. A new version or a reindex writes a new
 * generation next to the old one; the old one is deleted only after the new one is complete, and searches only accept
 * chunks of the generation MySQL marks as indexed ({@code KnowledgeSearch}), so there is never a moment with half a
 * document or two versions.
 */
@Component
public class KnowledgeIndex {

    static final String DEFINITION = "elasticsearch/knowledge.json";
    static final String VECTOR_FIELD = "embedding";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndex.class);

    private final ElasticsearchClient client;
    private final KnowledgeProperties properties;
    private final JsonMapper jsonMapper;
    private volatile boolean ready;

    public KnowledgeIndex(ElasticsearchClient client, KnowledgeProperties properties, JsonMapper jsonMapper) {
        this.client = client;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** One chunk as stored (and as written to Elasticsearch). */
    public record IndexedChunk(String chunkId, String documentId, int generation, int chunkIndex, String title,
                               String category, List<String> audiences, String text, float[] embedding) {
    }

    /** One search hit: the chunk without its vector, and its cosine similarity to the question (0–1). */
    public record ChunkHit(String chunkId, UUID documentId, int generation, int chunkIndex, String title,
                           KnowledgeCategory category, String text, double similarity) {
    }

    /** The stored fields a search reads back (the vector is left out). */
    record StoredChunk(String chunkId, String documentId, Integer generation, Integer chunkIndex, String title,
                       String category, List<String> audiences, String text) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createAtStartup() {
        if (properties.enabled() && !ensureQuietly()) {
            log.warn("Knowledge index '{}' is not ready yet; it will be created before the first document is indexed",
                    properties.indexAlias());
        }
    }

    /** Makes sure the alias and its index exist. Free once seen. */
    public void ensure() throws IOException {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (!ready) {
                createIfMissing();
                ready = true;
            }
        }
    }

    /** {@link #ensure} that logs instead of throwing; whether the index is ready. */
    public boolean ensureQuietly() {
        try {
            ensure();
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot prepare the knowledge index '{}': {}", properties.indexAlias(), e.getMessage());
            return false;
        }
    }

    /** Elasticsearch answers (for the status endpoint). */
    public boolean reachable() {
        try {
            return client.ping().value();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void createIfMissing() throws IOException {
        String alias = properties.indexAlias();
        String index = properties.indexName();
        if (client.indices().existsAlias(a -> a.name(alias)).value()) {
            return;
        }
        if (client.indices().exists(e -> e.index(index)).value()) {
            client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(index).alias(alias)
                    .isWriteIndex(true))));
            log.info("Re-attached alias '{}' to existing knowledge index '{}'", alias, index);
            return;
        }
        String body = definitionJson();
        try {
            client.indices().create(c -> c.index(index).withJson(new StringReader(body))
                    .aliases(alias, a -> a.isWriteIndex(true)));
            log.info("Created knowledge index '{}' behind alias '{}' ({} dimensions)", index, alias,
                    properties.embedding().dimensions());
        } catch (ElasticsearchException e) {
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    /** The index definition with the configured vector dimensions. */
    String definitionJson() {
        try (InputStream in = new ClassPathResource(DEFINITION).getInputStream()) {
            JsonNode root = jsonMapper.readTree(in);
            ((ObjectNode) root.at("/mappings/properties/" + VECTOR_FIELD)).put("dims",
                    properties.embedding().dimensions());
            return jsonMapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DEFINITION, e);
        }
    }

    /** Writes chunks; they are searchable when this returns (refresh wait_for). */
    public void write(List<IndexedChunk> chunks) throws IOException {
        if (chunks.isEmpty()) {
            return;
        }
        ensure();
        BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.WaitFor);
        for (IndexedChunk chunk : chunks) {
            bulk.operations(op -> op.index(i -> i.index(properties.indexAlias()).id(chunk.chunkId()).document(chunk)));
        }
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            String reason = response.items().stream().map(BulkResponseItem::error).filter(e -> e != null)
                    .map(e -> e.type() + ": " + e.reason()).findFirst().orElse("unknown error");
            throw new IOException("Elasticsearch refused chunks: " + reason);
        }
    }

    /**
     * The chunks most similar to {@code vector} among documents for {@code audiences} (and of {@code category} when
     * given), at least {@code threshold} similar, best first, at most {@code topK}.
     */
    public List<ChunkHit> search(float[] vector, Set<KnowledgeAudience> audiences, KnowledgeCategory category,
                                 int topK, double threshold) throws IOException {
        if (audiences.isEmpty()) {
            return List.of();
        }
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float value : vector) {
            queryVector.add(value);
        }
        List<FieldValue> audienceValues = audiences.stream().map(a -> FieldValue.of(a.name())).toList();
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.terms(t -> t.field("audiences").terms(v -> v.value(audienceValues)))));
        if (category != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("category").value(category.name()))));
        }
        int candidates = Math.max(topK, properties.retrieval().numCandidates());
        SearchResponse<StoredChunk> response = client.search(s -> s
                        .index(properties.indexAlias())
                        .knn(k -> k.field(VECTOR_FIELD)
                                .queryVector(queryVector)
                                .k(topK)
                                .numCandidates(candidates)
                                // the raw cosine similarity, not the score (Elasticsearch kNN "similarity")
                                .similarity((float) threshold)
                                .filter(filters))
                        .source(src -> src.filter(f -> f.excludes(VECTOR_FIELD)))
                        .size(topK),
                StoredChunk.class);
        List<ChunkHit> hits = new ArrayList<>();
        for (Hit<StoredChunk> hit : response.hits().hits()) {
            StoredChunk chunk = hit.source();
            if (chunk == null || hit.score() == null) {
                continue;
            }
            hits.add(new ChunkHit(chunk.chunkId(), UUID.fromString(chunk.documentId()),
                    chunk.generation() == null ? 0 : chunk.generation(),
                    chunk.chunkIndex() == null ? 0 : chunk.chunkIndex(), chunk.title(),
                    KnowledgeCategory.valueOf(chunk.category()), chunk.text(), cosine(hit.score())));
        }
        return hits;
    }

    /** Elasticsearch's kNN score for cosine is (1 + cosine) / 2. */
    static double cosine(double score) {
        return 2 * score - 1;
    }

    /** Deletes every chunk of a document (a deleted document). */
    public void deleteDocument(UUID documentId) throws IOException {
        deleteWhere(Query.of(q -> q.term(t -> t.field("documentId").value(documentId.toString()))));
    }

    /** Deletes the chunks of one generation of a document (an ingestion that did not finish). */
    public void deleteGeneration(UUID documentId, int generation) throws IOException {
        deleteWhere(Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field("documentId").value(documentId.toString())))
                .filter(f -> f.term(t -> t.field("generation").value(generation))))));
    }

    /** Deletes a document's chunks of every generation but {@code keep} (after a new generation completed). */
    public void deleteOtherGenerations(UUID documentId, int keep) throws IOException {
        deleteWhere(Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field("documentId").value(documentId.toString())))
                .mustNot(f -> f.term(t -> t.field("generation").value(keep))))));
    }

    private void deleteWhere(Query query) throws IOException {
        ensure();
        client.deleteByQuery(d -> d.index(properties.indexAlias()).query(query).refresh(true)
                .conflicts(Conflicts.Proceed));
    }
}
