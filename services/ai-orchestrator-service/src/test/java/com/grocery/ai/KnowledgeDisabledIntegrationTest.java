package com.grocery.ai;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the knowledge base off (the default), its API says so and nothing touches Elasticsearch. */
class KnowledgeDisabledIntegrationTest extends AbstractIntegrationTest {

    @Test
    void theAdminApiAnswersThatTheKnowledgeBaseIsOff() throws Exception {
        mvc.perform(get("/api/admin/ai/knowledge/documents").with(as(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("KNOWLEDGE_BASE_DISABLED"));
    }

    @Test
    void theServiceIsHealthyWithoutElasticsearch() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
