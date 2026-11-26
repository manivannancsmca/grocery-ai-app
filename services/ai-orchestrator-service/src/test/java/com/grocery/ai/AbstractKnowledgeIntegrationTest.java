package com.grocery.ai;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import com.grocery.ai.knowledge.KnowledgeAudience;
import com.grocery.ai.knowledge.KnowledgeProperties;
import com.grocery.ai.knowledge.index.KnowledgeEmbeddings;
import com.grocery.ai.knowledge.index.KnowledgeIndex;
import com.grocery.ai.knowledge.ingest.IngestionWorker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * {@link AbstractIntegrationTest} with the knowledge base switched on: a real Elasticsearch 9.4.6 in a throw-away
 * container (as in catalog-query-service's tests) and {@link FakeOllama}'s bag-of-words embeddings, so texts sharing
 * words really are similar. The ingestion worker does not run on its own: tests call {@link IngestionWorker#drain}.
 * Every test starts with an empty knowledge base.
 */
@TestPropertySource(properties = {
        "grocery.ai.knowledge.enabled=true",
        "grocery.ai.knowledge.index-alias=test-knowledge",
        "grocery.ai.knowledge.embedding.dimensions=" + FakeOllama.EMBEDDING_DIMENSIONS,
        "grocery.ai.knowledge.ingestion.poller-enabled=false",
        // small chunks, so a short test document has several
        "grocery.ai.knowledge.chunking.chunk-tokens=40",
        "grocery.ai.knowledge.chunking.overlap-tokens=8",
        // bag-of-words similarities are lower than a real model's
        "grocery.ai.knowledge.retrieval.similarity-threshold=0.3",
        "grocery.ai.knowledge.retrieval.unrecognised-threshold=0.5"
})
public abstract class AbstractKnowledgeIntegrationTest extends AbstractIntegrationTest {

    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>("elasticsearch:9.4.6")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    protected static final FakeOllama OLLAMA = new FakeOllama();

    static {
        ELASTICSEARCH.start();
    }

    @DynamicPropertySource
    static void knowledgeBase(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
        registry.add("grocery.ai.ollama.base-url", OLLAMA::baseUrl);
    }

    @Autowired
    protected IngestionWorker worker;
    @Autowired
    protected KnowledgeIndex index;
    @Autowired
    protected KnowledgeEmbeddings embeddings;
    @Autowired
    protected KnowledgeProperties knowledge;
    @Autowired
    protected ElasticsearchClient elasticsearch;

    @BeforeEach
    void emptyKnowledgeBase() throws Exception {
        OLLAMA.resetEmbeddings();
        jdbc().sql("DELETE FROM knowledge_chunk").update();
        jdbc().sql("DELETE FROM knowledge_document_text").update();
        jdbc().sql("DELETE FROM knowledge_document").update();
        index.ensure();
        elasticsearch.deleteByQuery(d -> d.index(knowledge.indexAlias()).query(q -> q.matchAll(m -> m))
                .refresh(true).conflicts(Conflicts.Proceed));
    }

    // ---- admin API ------------------------------------------------------------------------------------------------

    protected static final UUID ADMIN = UUID.randomUUID();

    protected ResultActions upload(String fileName, String text, String category, String... audiences)
            throws Exception {
        return upload(fileName, text.getBytes(StandardCharsets.UTF_8), null, category, audiences);
    }

    protected ResultActions upload(String fileName, byte[] content, String title, String category,
                                   String... audiences) throws Exception {
        return mvc.perform(withDetails(multipart("/api/admin/ai/knowledge/documents"), fileName, content, title,
                category, audiences).with(as(ADMIN, "OPS_ADMIN")));
    }

    protected ResultActions replace(UUID id, String fileName, String text) throws Exception {
        return mvc.perform(withDetails(multipart(HttpMethod.PUT, "/api/admin/ai/knowledge/documents/" + id),
                fileName, text.getBytes(StandardCharsets.UTF_8), null, null).with(as(ADMIN, "OPS_ADMIN")));
    }

    private static MockMultipartHttpServletRequestBuilder withDetails(MockMultipartHttpServletRequestBuilder request,
                                                                     String fileName, byte[] content, String title,
                                                                     String category, String... audiences) {
        request.file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        if (title != null) {
            request.param("title", title);
        }
        if (category != null) {
            request.param("category", category);
        }
        if (audiences.length > 0) {
            request.param("audiences", audiences);
        }
        return request;
    }

    protected UUID id(ResultActions result) throws Exception {
        return UUID.fromString(body(result).get("id").asString());
    }

    // ---- the index ------------------------------------------------------------------------------------------------

    /** Chunks of a document in the index (any generation). */
    protected long chunksInIndex(UUID documentId) throws Exception {
        return elasticsearch.count(c -> c.index(knowledge.indexAlias())
                .query(q -> q.term(t -> t.field("documentId").value(documentId.toString())))).count();
    }

    /** The generations a document has chunks of in the index. */
    protected List<Integer> generationsInIndex(UUID documentId) throws Exception {
        return elasticsearch.search(s -> s.index(knowledge.indexAlias()).size(100)
                                .query(q -> q.term(t -> t.field("documentId").value(documentId.toString()))),
                        tools.jackson.databind.node.ObjectNode.class)
                .hits().hits().stream().map(hit -> hit.source().get("generation").asInt()).distinct().sorted()
                .toList();
    }

    /** A raw vector search (no MySQL check) as a customer would get it. */
    protected List<KnowledgeIndex.ChunkHit> rawSearch(String question) throws Exception {
        return index.search(embeddings.embedQuestion(question), Set.of(KnowledgeAudience.CUSTOMER), null, 4, 0.0);
    }

    protected long chunkRows(UUID documentId) {
        return jdbc().sql("SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = UUID_TO_BIN(?)")
                .param(documentId.toString()).query(Long.class).single();
    }
}
