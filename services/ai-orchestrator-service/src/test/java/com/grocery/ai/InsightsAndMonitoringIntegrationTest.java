package com.grocery.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin insights (structured output with the rules fallback), the agents list, the model status, the audit list and
 * the monitoring summary (contract §2, §6).
 */
class InsightsAndMonitoringIntegrationTest extends AbstractIntegrationTest {

    private void stubInsightData() {
        GATEWAY.json("GET", "/api/admin/stats", 200, Fixtures.stats(42, "10250.00"));
        GATEWAY.json("GET", "/api/admin/orders/stuck", 200, Fixtures.json(List.of(Fixtures.map(
                "orderId", UUID.randomUUID().toString(), "orderNumber", "GA-STUCK01", "type", "GROCERY",
                "status", "CONFIRMED", "reason", "STORE_RESPONSE_OVERDUE", "since", "2026-11-13T08:00:00Z",
                "minutesOverdue", 25, "store", Fixtures.map("id", UUID.randomUUID().toString(), "name", "Fresh Mart"),
                "customerId", UUID.randomUUID().toString()))));
        GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, Fixtures.json(Fixtures.map(
                "lastRunAt", "2026-11-13T09:00:00Z", "checked", 12, "recovered", 2, "failures", 1,
                "lastRecovered", List.of())));
    }

    @Test
    void theModelWritesATypedReportFromDataCollectedWithTheAdminsRights() throws Exception {
        UUID admin = UUID.randomUUID();
        stubInsightData();

        JsonNode report = body(mvc.perform(post("/api/ai/agents/admin-bi/insights").with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-11-13\"}"))
                .andExpect(status().isOk()));

        assertThat(report.get("date").asString()).isEqualTo("2026-11-13");
        assertThat(report.get("generatedBy").get("provider").asString()).isEqualTo("scripted");
        assertThat(report.get("generatedBy").get("model").asString()).isEqualTo("scripted");
        assertThat(report.get("headline").asString()).isEqualTo("42 orders on 2026-11-13, ₹10250.00 confirmed revenue");
        assertThat(report.get("kpis").valueStream().map(k -> k.get("name").asString()).toList())
                .contains("Orders", "Confirmed revenue", "Stuck orders", "Payments recovered");
        assertThat(report.get("risks").valueStream().map(JsonNode::asString).toList())
                .anyMatch(risk -> risk.contains("GA-STUCK01"));
        assertThat(report.get("suggestedActions")).isNotEmpty();
        assertThat(report.get("generatedAt").asString()).isNotBlank();

        assertThat(GATEWAY.requests("GET", "/api/admin/stats").getFirst().param("date")).isEqualTo("2026-11-13");
        assertThat(GATEWAY.requests("GET", "/api/admin/stats").getFirst().header("Authorization"))
                .isEqualTo("Bearer token-of-" + admin);
        // the data was read through the guarded tools: audited
        assertThat(auditRows(admin)).extracting(r -> r.get("tool") + ":" + r.get("status")).containsExactly(
                "getOrderStats:SUCCEEDED", "listStuckOrders:SUCCEEDED", "getReconciliationSummary:SUCCEEDED");
        // the model got the data, never instructions from it
        String prompt = TestModels.lastUserText(model.prompts().getLast());
        assertThat(prompt).contains("INSIGHT_DATA_BEGIN").contains("GA-STUCK01");
        // times in India time, as people read them (the APIs answer in UTC)
        assertThat(prompt).contains("13 Nov, 1:30 pm IST").contains("13 Nov, 2:30 pm IST")
                .doesNotContain("2026-11-13T08:00:00Z").doesNotContain("2026-11-13T09:00:00Z");
    }

    /** What the model got from the last tool call. */
    private JsonNode lastToolResult() {
        ToolResponseMessage response = (ToolResponseMessage) model.prompts().getLast().getInstructions().getLast();
        return Fixtures.JSON.readTree(response.getResponses().getLast().responseData());
    }

    @Test
    void theAdminListsGiveIndiaTimesNotTheApisUtc() {
        String token = TOKENS.token(UUID.randomUUID(), "SUPER_ADMIN");
        stubInsightData();
        GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, Fixtures.json(Fixtures.map(
                "lastRunAt", "2026-11-13T09:00:00Z", "checked", 12, "recovered", 1, "failures", 0,
                "lastRecovered", List.of(Fixtures.map("orderId", UUID.randomUUID().toString(),
                        "orderNumber", "GA-LATE0001", "outcome", "CONFIRMED", "at", "2026-11-13T08:30:00Z")))));
        GATEWAY.json("GET", "/api/admin/orders", 200, Fixtures.page(List.of(Fixtures.map(
                "id", UUID.randomUUID().toString(), "orderNumber", "GA-LIST0001", "type", "GROCERY",
                "status", "CONFIRMED", "total", 260.00, "itemCount", 2, "customerId", UUID.randomUUID().toString(),
                "store", Fixtures.map("name", "Fresh Mart"), "payment", Fixtures.map("status", "PAID"),
                "createdAt", "2026-11-13T07:00:00Z"))));
        GATEWAY.json("GET", "/api/admin/payments", 200, Fixtures.page(List.of(Fixtures.map(
                "orderNumber", "GA-LIST0001", "status", "CAPTURED", "amount", 260.00, "refundedAmount", 0,
                "refundStatus", "NONE", "method", "upi", "createdAt", "2026-11-13T07:30:00Z"))));

        chat(token, "admin-bi", "use tool listStuckOrders {}");
        assertThat(lastToolResult().get("stuckOrders").get(0).get("since").asString()).isEqualTo("13 Nov, 1:30 pm IST");

        chat(token, "admin-bi", "use tool getReconciliationSummary {}");
        JsonNode reconciliation = lastToolResult().get("reconciliation");
        assertThat(reconciliation.get("lastRunAt").asString()).isEqualTo("13 Nov, 2:30 pm IST");
        assertThat(reconciliation.get("lastRecovered").get(0).get("at").asString()).isEqualTo("13 Nov, 2:00 pm IST");

        chat(token, "admin-bi", "use tool listOrders {}");
        JsonNode listed = lastToolResult().get("orders").get(0);
        assertThat(listed.get("placedAt").asString()).isEqualTo("13 Nov, 12:30 pm IST");
        assertThat(listed.has("createdAt")).isFalse();

        chat(token, "admin-bi", "use tool listPayments {}");
        assertThat(lastToolResult().get("payments").get(0).get("createdAt").asString()).isEqualTo("13 Nov, 1:00 pm IST");
    }

    @Test
    void anUnavailableModelFallsBackToTheRules() throws Exception {
        stubInsightData();
        model.use(TestModels.failing(new ResourceAccessException("Connection refused",
                new ConnectException("Connection refused"))));

        mvc.perform(post("/api/ai/agents/admin-bi/insights").with(as(UUID.randomUUID(), "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy.provider").value("rules"))
                .andExpect(jsonPath("$.headline").value("42 orders on " + java.time.LocalDate.now(
                        java.time.ZoneId.of("Asia/Kolkata")) + ", ₹10250.00 confirmed revenue"))
                .andExpect(jsonPath("$.kpis[0].name").value("Orders"))
                .andExpect(jsonPath("$.risks[0]").value(org.hamcrest.Matchers.containsString("stuck")));
    }

    @Test
    void invalidModelOutputFallsBackToTheRules() throws Exception {
        stubInsightData();
        for (String answer : List.of("I think business is good!", "{\"headline\":\"\",\"kpis\":[]}")) {
            model.use(TestModels.text(answer));
            mvc.perform(post("/api/ai/agents/admin-bi/insights").with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generatedBy.provider").value("rules"));
        }
        model.use(TestModels.text("{\"headline\":\"Quiet day\",\"kpis\":[{\"name\":\"Orders\",\"value\":\"42\"}],"
                + "\"observations\":[\"ok\"],\"risks\":[],\"suggestedActions\":[\"none\"]}"));
        mvc.perform(post("/api/ai/agents/admin-bi/insights").with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy.provider").value("scripted"))
                .andExpect(jsonPath("$.headline").value("Quiet day"))
                .andExpect(jsonPath("$.kpis[0].note").doesNotExist());
    }

    @Test
    void missingDataStillGivesAReport() throws Exception {
        GATEWAY.on("GET", "/api/admin/stats", request -> FakeGateway.Response.problem(503, "SERVICE_UNAVAILABLE",
                "down"));
        GATEWAY.json("GET", "/api/admin/orders/stuck", 200, "[]");
        GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, "{\"checked\":0,\"recovered\":0,"
                + "\"failures\":0,\"lastRecovered\":[]}");
        model.use(TestModels.failing(new IllegalStateException("model down")));

        mvc.perform(post("/api/ai/agents/admin-bi/insights").with(as(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy.provider").value("rules"))
                .andExpect(jsonPath("$.risks[0]").value("Order statistics could not be loaded."));
    }

    @Test
    void insightsAreForAdminsOnly() throws Exception {
        for (String role : List.of("CUSTOMER", "VENDOR", "RESTAURANT_OWNER", "DELIVERY_PARTNER")) {
            mvc.perform(post("/api/ai/agents/admin-bi/insights").with(as(UUID.randomUUID(), role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void agentsAreListedByRoleAndTheStatusReportsTheModel() throws Exception {
        mvc.perform(get("/api/ai/agents").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("shopping"))
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].examplePrompts.length()").value(4))
                // the customer's payment and support assistants (docs/api/payment-and-support-agents.md)
                .andExpect(jsonPath("$[1].id").value("payment"))
                .andExpect(jsonPath("$[1].name").value("Payment assistant"))
                .andExpect(jsonPath("$[1].examplePrompts.length()").value(4))
                .andExpect(jsonPath("$[2].id").value("support"))
                .andExpect(jsonPath("$[2].name").value("Customer support assistant"))
                .andExpect(jsonPath("$[2].examplePrompts.length()").value(4));
        mvc.perform(get("/api/ai/agents").with(as(UUID.randomUUID(), "DELIVERY_MANAGER")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("admin-bi"))
                .andExpect(jsonPath("$[1].id").value("delivery"));
        mvc.perform(get("/api/ai/agents").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("vendor"))
                .andExpect(jsonPath("$[0].name").value("Vendor assistant"))
                .andExpect(jsonPath("$[0].examplePrompts.length()").value(4));
        mvc.perform(get("/api/ai/agents").with(as(UUID.randomUUID(), "RESTAURANT_OWNER")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("restaurant"))
                .andExpect(jsonPath("$[0].name").value("Restaurant assistant"));
        mvc.perform(get("/api/ai/agents").with(as(UUID.randomUUID(), "DELIVERY_PARTNER")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("delivery"))
                .andExpect(jsonPath("$[0].name").value("Delivery assistant"));
        mvc.perform(get("/api/ai/agents").with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("admin-bi"));
        mvc.perform(get("/api/ai/status").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("scripted"))
                .andExpect(jsonPath("$.model").value("scripted"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());
        mvc.perform(get("/api/ai/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void theAuditListIsFilteredAndAdminOnly() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "show my cart");
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool refundOrder {}");
        chat(TOKENS.token(customer, "CUSTOMER"), "admin-bi", "stats");

        JsonNode all = body(mvc.perform(get("/api/admin/ai/tool-invocations").param("userId", customer.toString())
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        assertThat(all.get("totalElements").asInt()).isEqualTo(3);
        JsonNode newest = all.get("content").get(0);
        assertThat(newest.get("tool").isNull()).isTrue();                     // AGENT_NOT_ALLOWED, newest first
        assertThat(newest.get("reason").asString()).isEqualTo("AGENT_NOT_ALLOWED");
        assertThat(newest.get("arguments").isObject()).isTrue();
        assertThat(newest.get("roles").get(0).asString()).isEqualTo("CUSTOMER");
        assertThat(newest.has("conversationId")).isTrue();
        JsonNode blocked = all.get("content").get(1);
        assertThat(blocked.get("tool").asString()).isEqualTo("refundOrder");
        assertThat(blocked.get("status").asString()).isEqualTo("BLOCKED");
        assertThat(blocked.get("arguments").isObject()).isTrue();
        assertThat(blocked.get("createdAt").asString()).isNotBlank();
        assertThat(blocked.has("latencyMs")).isTrue();
        assertThat(blocked.has("approvalId")).isTrue();

        assertThat(body(mvc.perform(get("/api/admin/ai/tool-invocations").param("userId", customer.toString())
                .param("status", "SUCCEEDED").param("tool", "getCart").param("agent", "shopping")
                .param("from", "2026-01-01").param("to", java.time.LocalDate.now().plusDays(1).toString())
                .with(as(UUID.randomUUID(), "SUPPORT")))).get("totalElements").asInt()).isEqualTo(1);
        assertThat(body(mvc.perform(get("/api/admin/ai/tool-invocations").param("userId", customer.toString())
                .param("to", "2026-01-01").with(as(UUID.randomUUID(), "SUPPORT")))).get("totalElements").asInt())
                .isZero();
        mvc.perform(get("/api/admin/ai/tool-invocations").param("status", "WHATEVER")
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/ai/tool-invocations").with(as(customer, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSummaryCountsTheDay() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "show my cart");
        proposeOrder(customer, "285.00");

        JsonNode summary = body(mvc.perform(get("/api/admin/ai/summary").with(as(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isOk()));
        assertThat(summary.get("date").asString()).isEqualTo(java.time.LocalDate.now(
                java.time.ZoneId.of("Asia/Kolkata")).toString());
        assertThat(summary.get("conversations").asLong()).isPositive();
        assertThat(summary.get("messages").asLong()).isPositive();
        assertThat(summary.get("toolCalls").asLong()).isPositive();
        assertThat(summary.get("approvals").get("requested").asLong()).isPositive();
        assertThat(summary.get("approvals").get("pending").asLong()).isPositive();
        assertThat(summary.get("approvals").has("cancelled")).isTrue();
        assertThat(summary.has("avgModelLatencyMs")).isTrue();
        assertThat(summary.has("modelErrors")).isTrue();
        assertThat(summary.get("provider").asString()).isEqualTo("scripted");
        assertThat(summary.get("available").asBoolean()).isTrue();

        JsonNode past = body(mvc.perform(get("/api/admin/ai/summary").param("date", "2020-01-01")
                .with(as(UUID.randomUUID(), "OPS_ADMIN"))).andExpect(status().isOk()));
        assertThat(past.get("conversations").asLong()).isZero();
        assertThat(past.get("avgModelLatencyMs").isNull()).isTrue();
        mvc.perform(get("/api/admin/ai/summary").with(as(customer, "CUSTOMER"))).andExpect(status().isForbidden());
    }
}
