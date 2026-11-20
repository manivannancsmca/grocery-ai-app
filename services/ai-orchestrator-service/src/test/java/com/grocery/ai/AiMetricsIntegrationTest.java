package com.grocery.ai;

import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.audit.AiMetrics;
import com.grocery.ai.gateway.GatewayClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §1: ai-orchestrator-service's meters with the scripted model — tool calls by agent, tool (an invented name
 * is {@code unknown}) and status, pending approvals per action, the model's availability and the latency of a chat
 * turn — and the trace context reaching the business APIs: a tool call carries the user's {@code traceparent}.
 */
class AiMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MeterRegistry registry;
    @Autowired
    AiMetrics metrics;

    private double toolCalls(String agent, String tool, String status) {
        Counter counter = registry.find(AiMetrics.TOOL_CALLS).tag("agent", agent).tag("tool", tool)
                .tag("status", status).counter();
        return counter == null ? 0 : counter.count();
    }

    private long modelCalls(String agent, String outcome) {
        Timer timer = registry.find(AiMetrics.MODEL_LATENCY).tag("agent", agent).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double pending(ApprovalAction action) {
        return registry.get(AiMetrics.APPROVALS_PENDING).tag("action", action.name()).gauge().value();
    }

    private long pendingInDatabase(ApprovalAction action) {
        return jdbc().sql("SELECT COUNT(*) FROM approval_request WHERE status = 'PENDING_APPROVAL' AND action = ?")
                .param(action.name()).query(Long.class).single();
    }

    @Test
    void everyGaugeSeriesIsRegisteredFromTheStart() {
        assertThat(registry.find(AiMetrics.APPROVALS_PENDING).gauges()).map(g -> g.getId().getTag("action"))
                .containsExactlyInAnyOrder(Arrays.stream(ApprovalAction.values()).map(Enum::name).toArray(String[]::new));
        metrics.refresh();
        assertThat(registry.get(AiMetrics.MODEL_AVAILABLE).tag("provider", "scripted").gauge().value())
                .as("the scripted model is always available").isEqualTo(1.0);
    }

    @Test
    void toolCallsAreCountedByAgentToolAndStatusWithInventedNamesAsUnknown() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        double getCart = toolCalls("shopping", "getCart", "SUCCEEDED");
        double foreign = toolCalls("shopping", "refundOrder", "BLOCKED");
        double invented = toolCalls("shopping", AiMetrics.UNKNOWN, "BLOCKED");
        long turns = modelCalls("shopping", AiMetrics.SUCCESS);

        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool getCart {}");
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping",
                "use tool refundOrder {\"orderNumber\":\"GA-7K3F9Q2M\",\"note\":\"free money\"}");
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool transferMoney {\"to\":\"me\",\"amount\":1}");

        assertThat(toolCalls("shopping", "getCart", "SUCCEEDED")).isEqualTo(getCart + 1);
        assertThat(toolCalls("shopping", "refundOrder", "BLOCKED")).as("another agent's tool keeps its name")
                .isEqualTo(foreign + 1);
        assertThat(toolCalls("shopping", AiMetrics.UNKNOWN, "BLOCKED")).as("an invented name is never a tag value")
                .isEqualTo(invented + 1);
        assertThat(registry.find(AiMetrics.TOOL_CALLS).tag("tool", "transferMoney").counters()).isEmpty();
        // the three turns were answered: their latency is recorded with the agent and the outcome
        assertThat(modelCalls("shopping", AiMetrics.SUCCESS)).isEqualTo(turns + 3);
    }

    @Test
    void theModelsFailureIsTheOutcomeOfTheLatency() {
        long failed = modelCalls("shopping", "AI_UNAVAILABLE");
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.failing(new ResourceAccessException("I/O error on POST request for "
                + "\"http://ollama:11434/api/chat\": Connection refused", new ConnectException("Connection refused"))));

        chat(TOKENS.token(UUID.randomUUID(), "CUSTOMER"), "shopping", "show my cart");

        assertThat(modelCalls("shopping", "AI_UNAVAILABLE")).isEqualTo(failed + 1);
    }

    @Test
    void pendingApprovalsAreCountedPerActionOnEachRefresh() {
        metrics.refresh();
        double before = pending(ApprovalAction.PLACE_ORDER);

        proposeOrder(UUID.randomUUID(), "245.00");
        assertThat(pending(ApprovalAction.PLACE_ORDER)).as("never queried on a scrape").isEqualTo(before);
        metrics.refresh();

        assertThat(pending(ApprovalAction.PLACE_ORDER)).isEqualTo(pendingInDatabase(ApprovalAction.PLACE_ORDER))
                .isGreaterThanOrEqualTo(before + 1);
        assertThat(pending(ApprovalAction.SUSPEND_VENDOR)).isEqualTo(pendingInDatabase(ApprovalAction.SUSPEND_VENDOR));
    }

    @Test
    void aToolCallContinuesTheUsersTrace() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        // as the gateway sends it: the user's request is a span of this trace
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ai/agents/shopping/chat"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + TOKENS.token(customer, "CUSTOMER"))
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"use tool getCart {}\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:done");

        List<FakeGateway.Request> calls = GATEWAY.requests("GET", "/api/cart");
        assertThat(calls).isNotEmpty();
        assertThat(calls.getLast().header("traceparent")).as("built from Spring Boot's RestClient.Builder (observed)")
                .matches("00-" + traceId + "-[0-9a-f]{16}-01");
        assertThat(GatewayClient.class.getConstructors()[0].getParameterTypes())
                .contains(org.springframework.web.client.RestClient.Builder.class);
    }
}
