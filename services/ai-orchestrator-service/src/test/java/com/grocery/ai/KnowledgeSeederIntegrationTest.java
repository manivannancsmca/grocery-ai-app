package com.grocery.ai;

import com.grocery.ai.knowledge.document.KnowledgeSeeder;
import com.grocery.ai.knowledge.search.KnowledgeSearch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The starter help-center articles (knowledge/seed/*.md): loaded once into an empty knowledge base, never again. */
class KnowledgeSeederIntegrationTest extends AbstractKnowledgeIntegrationTest {

    @Autowired
    private KnowledgeSeeder seeder;
    @Autowired
    private KnowledgeSearch search;

    @Test
    void theStarterArticlesAreLoadedIntoAnEmptyKnowledgeBaseAndIndexed() throws Exception {
        int articles = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/seed/*.md").length;
        assertThat(articles).isGreaterThanOrEqualTo(10);

        assertThat(seeder.seed()).isEqualTo(articles);
        assertThat(worker.drain()).isEqualTo(articles);

        assertThat(jdbc().sql("SELECT COUNT(*) FROM knowledge_document WHERE status = 'COMPLETED'")
                .query(Long.class).single()).isEqualTo(articles);
        assertThat(jdbc().sql("SELECT created_by_name FROM knowledge_document LIMIT 1").query(String.class).single())
                .isEqualTo("System");
        // the header's audiences: the vendor guide is not for customers
        assertThat(jdbc().sql("SELECT audiences FROM knowledge_document WHERE title = 'Selling as a vendor or restaurant'")
                .query(String.class).single()).isEqualTo("VENDOR,RESTAURANT_OWNER,STAFF");
        // (the test embeddings are word counts: retrieval quality is measured with the real model, docs/rag/)
        KnowledgeSearch.Result payment = search.forReader("Razorpay payment methods UPI wallets cash",
                Set.of("CUSTOMER"));
        assertThat(payment.snippets()).extracting(KnowledgeSearch.Snippet::title)
                .contains("Paying for your order and payment methods");
    }

    @Test
    void nothingIsLoadedAgainOnceTheKnowledgeBaseWasUsed() throws Exception {
        seeder.seed();
        worker.drain();
        UUID first = UUID.fromString(jdbc().sql("SELECT BIN_TO_UUID(id) FROM knowledge_document ORDER BY title LIMIT 1")
                .query(String.class).single());
        mvc.perform(delete("/api/admin/ai/knowledge/documents/" + first).with(as(ADMIN, "OPS_ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(seeder.seed()).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM knowledge_document WHERE deleted_at IS NULL")
                .query(Long.class).single()).isEqualTo(
                new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/seed/*.md").length - 1);
    }
}
