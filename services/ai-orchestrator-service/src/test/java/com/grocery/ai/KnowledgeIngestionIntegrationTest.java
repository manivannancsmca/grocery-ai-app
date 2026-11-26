package com.grocery.ai;

import com.grocery.ai.knowledge.KnowledgeTestFiles;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The document ingestion pipeline (docs/rag/RAG-DOCUMENT-INGESTION.md) end to end: upload through the admin API,
 * extraction, chunking, embeddings, the Elasticsearch index and MySQL; duplicates, new versions, reindex, delete and
 * failures.
 */
class KnowledgeIngestionIntegrationTest extends AbstractKnowledgeIntegrationTest {

    static final String REFUNDS_V1 = """
            # Refund policy

            A cancelled order that was already paid is refunded automatically to the same payment method.

            Refunds usually reach your bank account within seven working days after the cancellation.

            Returned items are refunded after the store accepts the return request.""";

    static final String REFUNDS_V2 = """
            # Refund policy

            A cancelled paid order is refunded automatically to the same payment method.

            Refunds now reach your bank account within three working days.""";

    @Test
    void anUploadIsIndexedInTheBackgroundAndBecomesSearchable() throws Exception {
        ResultActions uploaded = upload("refund-policy.md", REFUNDS_V1.getBytes(StandardCharsets.UTF_8),
                "Refund policy", "POLICIES", "CUSTOMER").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.searchable").value(false))
                .andExpect(jsonPath("$.contentVersion").value(1))
                .andExpect(jsonPath("$.format").value("MARKDOWN"))
                .andExpect(jsonPath("$.audiences[0]").value("CUSTOMER"));
        UUID id = id(uploaded);

        assertThat(worker.drain()).isEqualTo(1);

        JsonNode document = body(mvc.perform(get("/api/admin/ai/knowledge/documents/" + id)
                .with(as(ADMIN, "SUPPORT"))).andExpect(status().isOk()));
        assertThat(document.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(document.get("searchable").asBoolean()).isTrue();
        assertThat(document.get("embeddingModel").asString()).isEqualTo("nomic-embed-text");
        int chunks = document.get("chunkCount").asInt();
        assertThat(chunks).isGreaterThan(1);
        assertThat(chunksInIndex(id)).isEqualTo(chunks);
        assertThat(chunkRows(id)).isEqualTo(chunks);
        // every chunk was embedded with the document prefix and its title
        assertThat(OLLAMA.embeddedTexts()).hasSize(chunks)
                .allSatisfy(text -> assertThat(text).startsWith("search_document: Refund policy\n\n"));

        assertThat(rawSearch("how many days until my refund reaches the bank")).first()
                .satisfies(hit -> {
                    assertThat(hit.documentId()).isEqualTo(id);
                    assertThat(hit.text()).contains("seven working days");
                });
        assertThat(OLLAMA.embeddedTexts().getLast()).startsWith("search_query: ");
    }

    @Test
    void theSameFileUploadedAgainCreatesAndEmbedsNothing() throws Exception {
        UUID id = id(upload("refunds.md", REFUNDS_V1, "POLICIES", "CUSTOMER").andExpect(status().isAccepted()));
        worker.drain();
        int embedded = OLLAMA.embeddedTexts().size();

        upload("refunds-copy.md", REFUNDS_V1, "POLICIES", "CUSTOMER").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        assertThat(worker.drain()).isZero();
        assertThat(OLLAMA.embeddedTexts()).hasSize(embedded);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM knowledge_document").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aNewVersionKeepsTheOldOneSearchableUntilItIsIndexed() throws Exception {
        UUID id = id(upload("refunds.md", REFUNDS_V1, "POLICIES", "CUSTOMER"));
        worker.drain();

        replace(id, "refunds.md", REFUNDS_V2).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.contentVersion").value(2))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.searchable").value(true));
        // meanwhile the first version still answers
        assertThat(generationsInIndex(id)).containsExactly(1);
        assertThat(rawSearch("refund bank days").getFirst().text()).contains("seven working days");

        worker.drain();

        assertThat(generationsInIndex(id)).containsExactly(2);
        assertThat(rawSearch("refund bank days").getFirst().text()).contains("three working days");
        assertThat(jdbc().sql("SELECT DISTINCT generation FROM knowledge_chunk WHERE document_id = UUID_TO_BIN(?)")
                .param(id.toString()).query(Integer.class).list()).containsExactly(2);
    }

    @Test
    void theSameFileWithNewDetailsIsIndexedAgainWithThem() throws Exception {
        UUID id = id(upload("refunds.md", REFUNDS_V1, "POLICIES", "CUSTOMER"));
        worker.drain();

        mvc.perform(multipart(HttpMethod.PUT, "/api/admin/ai/knowledge/documents/" + id)
                        .file(new MockMultipartFile("file", "refunds.md", "text/markdown",
                                REFUNDS_V1.getBytes(StandardCharsets.UTF_8)))
                        .param("audiences", "CUSTOMER", "STAFF")
                        .with(as(ADMIN, "SUPER_ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.contentVersion").value(1));
        worker.drain();

        assertThat(generationsInIndex(id)).containsExactly(2);
        mvc.perform(get("/api/admin/ai/knowledge/documents/" + id).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.audiences.length()").value(2));
        // the same file again without new details changes nothing
        replace(id, "refunds.md", REFUNDS_V1).andExpect(status().isOk());
        assertThat(worker.drain()).isZero();
    }

    @Test
    void reindexWritesANewGenerationFromTheStoredText() throws Exception {
        UUID id = id(upload("refunds.md", REFUNDS_V1, "POLICIES", "CUSTOMER"));
        worker.drain();

        mvc.perform(post("/api/admin/ai/knowledge/documents/" + id + "/reindex").with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("UPLOADED"));
        worker.drain();

        assertThat(generationsInIndex(id)).containsExactly(2);
        mvc.perform(get("/api/admin/ai/knowledge/documents/" + id).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void aDeletedDocumentIsUnsearchableAtOnceAndItsChunksAreRemoved() throws Exception {
        UUID id = id(upload("refunds.md", REFUNDS_V1, "POLICIES", "CUSTOMER"));
        worker.drain();

        mvc.perform(delete("/api/admin/ai/knowledge/documents/" + id).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(chunksInIndex(id)).isZero();
        assertThat(chunkRows(id)).isZero();
        mvc.perform(get("/api/admin/ai/knowledge/documents/" + id).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("DELETED")).andExpect(jsonPath("$.searchable").value(false));
        mvc.perform(get("/api/admin/ai/knowledge/documents").with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/ai/knowledge/documents?status=DELETED").with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.content[0].id").value(id.toString()));
        mvc.perform(post("/api/admin/ai/knowledge/documents/" + id + "/reindex").with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void aMissingEmbeddingModelIsTriedAgainThenFailsWithAReason() throws Exception {
        OLLAMA.failEmbeddings(404);
        UUID id = id(upload("refunds.md", REFUNDS_V1, "POLICIES", "CUSTOMER"));

        worker.drain();
        mvc.perform(get("/api/admin/ai/knowledge/documents/" + id).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.failureReason").value(containsString("not installed")));
        worker.drain();
        worker.drain();

        mvc.perform(get("/api/admin/ai/knowledge/documents/" + id).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.searchable").value(false))
                .andExpect(jsonPath("$.failureReason").value(
                        "The embedding model is not installed (local: ollama pull <model>)."));
        assertThat(chunksInIndex(id)).isZero();
    }

    @Test
    void pdfAndWordDocumentsAreIndexed() throws Exception {
        UUID pdf = id(upload("delivery.pdf", KnowledgeTestFiles.pdf(
                "Delivery partners pick up orders from the store"), null, "DELIVERY", "DELIVERY_PARTNER"));
        UUID word = id(upload("vendor-onboarding.docx", KnowledgeTestFiles.docx(
                "How do vendors register?", "Fill in the store details and wait for approval."), null, "VENDOR",
                "VENDOR"));

        assertThat(worker.drain()).isEqualTo(2);

        assertThat(chunksInIndex(pdf)).isPositive();
        assertThat(chunksInIndex(word)).isPositive();
    }

    @Test
    void badFilesAndDetailsAreRefusedWithTheirReason() throws Exception {
        upload("setup.exe", "MZ", "POLICIES", "CUSTOMER").andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_DOCUMENT_TYPE"));
        upload("policy.pdf", "only text", "POLICIES", "CUSTOMER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DOCUMENT"));
        upload("empty.txt", "  \n ", "POLICIES", "CUSTOMER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("EMPTY_DOCUMENT"));
        upload("faq.txt", "Text", null, "CUSTOMER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("category"));
        upload("faq.txt", "Text", "POLICIES").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("audiences"));
        upload("faq.txt", "Text", "POLICIES", "EVERYONE").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM knowledge_document").query(Long.class).single()).isZero();
    }

    @Test
    void anyAdminReadsButOnlyOperationsAdminsChange() throws Exception {
        UUID financeAdmin = UUID.randomUUID();
        mvc.perform(multipart("/api/admin/ai/knowledge/documents")
                        .file(new MockMultipartFile("file", "faq.txt", "text/plain",
                                "Text".getBytes(StandardCharsets.UTF_8)))
                        .param("category", "SUPPORT").param("audiences", "CUSTOMER")
                        .with(as(financeAdmin, "FINANCE_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ai/knowledge/documents").with(as(financeAdmin, "FINANCE_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/ai/knowledge/documents").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
    }
}
