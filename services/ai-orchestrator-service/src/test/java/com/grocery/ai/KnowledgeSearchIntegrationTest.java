package com.grocery.ai;

import com.grocery.ai.knowledge.search.KnowledgeSearch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retrieval (docs/rag/RAG-ARCHITECTURE.md, "Retrieval"): the reader's audiences, the similarity threshold, and the
 * MySQL check that keeps deleted documents, old versions and removed audiences out at once.
 */
class KnowledgeSearchIntegrationTest extends AbstractKnowledgeIntegrationTest {

    private static final String CUSTOMER_REFUNDS = """
            Refund policy for customers.

            A cancelled paid order is refunded automatically to the original payment method within seven working days.""";
    private static final String VENDOR_PAYOUTS = """
            Vendor payouts.

            Vendors receive payouts for delivered orders every week, after the commission is deducted.""";

    @Autowired
    private KnowledgeSearch search;

    @Test
    void readersOnlyGetDocumentsMeantForThem() throws Exception {
        upload("refunds.md", CUSTOMER_REFUNDS, "POLICIES", "CUSTOMER");
        upload("payouts.md", VENDOR_PAYOUTS, "VENDOR", "VENDOR", "STAFF");
        worker.drain();

        KnowledgeSearch.Result customer = search.forReader("when do vendors receive payouts after commission",
                Set.of("CUSTOMER"));
        KnowledgeSearch.Result vendor = search.forReader("when do vendors receive payouts after commission",
                Set.of("VENDOR"));
        KnowledgeSearch.Result admin = search.forReader("when do vendors receive payouts after commission",
                Set.of("FINANCE_ADMIN"));

        assertThat(customer.snippets()).noneMatch(s -> s.title().equals("payouts"));
        assertThat(vendor.status()).isEqualTo(KnowledgeSearch.Status.FOUND);
        assertThat(vendor.snippets().getFirst().title()).isEqualTo("payouts");
        assertThat(admin.snippets().getFirst().title()).isEqualTo("payouts");
        assertThat(search.forReader("refund after cancellation", Set.of()).status())
                .isEqualTo(KnowledgeSearch.Status.NO_MATCH);
    }

    @Test
    void aQuestionTheHelpCenterDoesNotCoverIsNoMatch() throws Exception {
        upload("refunds.md", CUSTOMER_REFUNDS, "POLICIES", "CUSTOMER");
        worker.drain();

        assertThat(search.forReader("weather forecast in Paris tomorrow", Set.of("CUSTOMER")).status())
                .isEqualTo(KnowledgeSearch.Status.NO_MATCH);
        assertThat(search.forReader("refund of a cancelled paid order", Set.of("CUSTOMER")).status())
                .isEqualTo(KnowledgeSearch.Status.FOUND);
    }

    @Test
    void aDocumentDeletedInMysqlIsNeverReturnedEvenWhileItsChunksAreStillIndexed() throws Exception {
        UUID id = id(upload("refunds.md", CUSTOMER_REFUNDS, "POLICIES", "CUSTOMER"));
        worker.drain();
        // as if removing the chunks from the index had failed
        jdbc().sql("UPDATE knowledge_document SET status = 'DELETED', deleted_at = NOW(6), indexed_generation = NULL "
                + "WHERE id = UUID_TO_BIN(?)").param(id.toString()).update();

        assertThat(chunksInIndex(id)).isPositive();
        assertThat(search.forReader("refund of a cancelled paid order", Set.of("CUSTOMER")).status())
                .isEqualTo(KnowledgeSearch.Status.NO_MATCH);

        worker.drain();                                    // the clean-up removes them
        assertThat(chunksInIndex(id)).isZero();
    }

    @Test
    void anAudienceRemovedByAnAdminAppliesBeforeTheDocumentIsIndexedAgain() throws Exception {
        UUID id = id(upload("refunds.md", CUSTOMER_REFUNDS, "POLICIES", "CUSTOMER", "STAFF"));
        worker.drain();

        mvc.perform(multipart(HttpMethod.PUT, "/api/admin/ai/knowledge/documents/" + id)
                        .file(new MockMultipartFile("file", "refunds.md", "text/markdown",
                                CUSTOMER_REFUNDS.getBytes(StandardCharsets.UTF_8)))
                        .param("audiences", "STAFF")
                        .with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(status().isAccepted());

        // not indexed again yet, but customers no longer get it
        assertThat(search.forReader("refund of a cancelled paid order", Set.of("CUSTOMER")).status())
                .isEqualTo(KnowledgeSearch.Status.NO_MATCH);
        assertThat(search.forReader("refund of a cancelled paid order", Set.of("SUPPORT")).status())
                .isEqualTo(KnowledgeSearch.Status.FOUND);
    }

    @Test
    void adminsTestTheRetrievalAsAnAudience() throws Exception {
        upload("payouts.md", VENDOR_PAYOUTS, "VENDOR", "VENDOR");
        worker.drain();

        mvc.perform(post("/api/admin/ai/knowledge/search").with(as(ADMIN, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"weekly vendor payouts\",\"audience\":\"VENDOR\",\"topK\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.results[0].title").value("payouts"))
                .andExpect(jsonPath("$.results[0].similarity").isNumber())
                .andExpect(jsonPath("$.results[0].text").value(containsString("every week")));
        mvc.perform(post("/api/admin/ai/knowledge/search").with(as(ADMIN, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"weekly vendor payouts\",\"audience\":\"CUSTOMER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_MATCH"));
        mvc.perform(post("/api/admin/ai/knowledge/search").with(as(ADMIN, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("question"));
    }

    @Test
    void anUnreachableEmbeddingModelIsUnavailableNotAnError() throws Exception {
        upload("refunds.md", CUSTOMER_REFUNDS, "POLICIES", "CUSTOMER");
        worker.drain();
        OLLAMA.failEmbeddings(500);

        assertThat(search.forReader("refund of a cancelled paid order", Set.of("CUSTOMER")).status())
                .isEqualTo(KnowledgeSearch.Status.UNAVAILABLE);
    }
}
