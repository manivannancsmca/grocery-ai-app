package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import com.grocery.ai.audit.AiMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code anthropic} provider (production, 2026-11-20) with the real Spring AI 2.0.2 Anthropic chat model and the
 * official Java SDK against {@link FakeAnthropic}: the status checks the key and the model, streaming with tool calling
 * through ToolGuard, the rules and tools marked for Anthropic's prompt cache (not the user's context line), structured
 * output for insights, and AI_UNAVAILABLE when Anthropic is overloaded or refuses the key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grocery.ai.provider=anthropic",
        "grocery.ai.anthropic.api-key=" + FakeAnthropic.KEY,
        "grocery.ai.anthropic.model=claude-opus-5-5",
        "grocery.ai.anthropic.max-retries=0",
        "grocery.ai.shopping-commands=false",                  // "what is in my cart?" must reach the (fake) model
        "grocery.ai.approvals.pollers-enabled=false",
        "grocery.ai.status-cache-ttl=0s",
        "grocery.ai.model-timeout=5s",
        "spring.datasource.hikari.connection-timeout=30000",
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnthropicProviderIntegrationTest {

    static final FakeAnthropic ANTHROPIC = new FakeAnthropic();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.MYSQL::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.MYSQL::getPassword);
        registry.add("grocery.ai.gateway-url", AbstractIntegrationTest.GATEWAY::baseUrl);
        registry.add("grocery.ai.anthropic.base-url", ANTHROPIC::baseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", AbstractIntegrationTest.TOKENS::jwksUri);
    }

    @Value("${local.server.port}")
    int port;
    @Autowired
    MockMvc mvc;
    @Autowired
    ChatModel chatModel;
    @Autowired
    AiMetrics metrics;
    @Autowired
    MeterRegistry registry;

    private double modelAvailable() {
        metrics.refresh();
        return registry.get(AiMetrics.MODEL_AVAILABLE).tag("provider", "anthropic").gauge().value();
    }

    private Result chat(UUID user, String message) {
        return SseClient.post(port, "/api/ai/agents/shopping/chat", AbstractIntegrationTest.TOKENS.token(user,
                "CUSTOMER"), "{\"message\":\"" + message + "\"}", "text/event-stream");
    }

    @Test
    @Order(1)
    void claudeIsTheModelInUseAndTheStatusChecksTheKeyAndTheModel() throws Exception {
        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
        mvc.perform(get("/api/ai/status").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.available").value(true));
        assertThat(ANTHROPIC.modelChecks().getLast()).containsEntry("x-api-key", FakeAnthropic.KEY)
                .containsEntry("anthropic-version", "2023-06-01");
        assertThat(modelAvailable()).isEqualTo(1.0);
        ANTHROPIC.knownModel("claude-older-model");
        mvc.perform(get("/api/ai/status").with(jwt())).andExpect(jsonPath("$.available").value(false));
        assertThat(modelAvailable()).as("the API does not know the model").isEqualTo(0.0);
        ANTHROPIC.knownModel("claude-opus-5-5");
        assertThat(modelAvailable()).isEqualTo(1.0);
    }

    @Test
    @Order(2)
    void streamingWithToolCallingRunsThroughToolGuardAndTheRulesAndToolsAreCached() {
        AbstractIntegrationTest.GATEWAY.reset();
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/cart", 200, Fixtures.json(Fixtures.defaultCart()));
        int before = ANTHROPIC.messageRequests().size();

        Result result = chat(UUID.randomUUID(), "what is in my cart?");

        assertThat(result.names()).containsSubsequence("conversation", "tool", "tool", "token", "token", "done");
        assertThat(result.named("tool")).extracting(e -> e.text("tool") + ":" + e.text("status"))
                .containsExactly("getCart:STARTED", "getCart:SUCCEEDED");
        assertThat(result.answer()).isEqualTo("Your cart has 3 items.");
        assertThat(result.last().text("finishReason")).isEqualTo("STOP");

        List<JsonNode> requests = ANTHROPIC.messageRequests().subList(before, ANTHROPIC.messageRequests().size());
        assertThat(requests).hasSize(2);
        JsonNode first = requests.getFirst();
        assertThat(first.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(first.get("stream").asBoolean()).isTrue();
        assertThat(first.get("max_tokens").asInt()).isEqualTo(2048);
        assertThat(first.get("temperature").asDouble()).isEqualTo(0.2);
        // the rules (the same for every user) are cached; this user's context line comes after them, not cached
        List<JsonNode> system = first.get("system").valueStream().toList();
        assertThat(system).hasSize(2);
        assertThat(system.getFirst().get("text").asString()).doesNotContain("Context (from the server");
        assertThat(system.getFirst().get("cache_control").get("type").asString()).isEqualTo("ephemeral");
        assertThat(system.getLast().get("text").asString()).startsWith("Context (from the server, trustworthy)");
        assertThat(system.getLast().has("cache_control")).isFalse();
        // the tools: the agent's allowlist only, the last one marked for the cache
        List<JsonNode> tools = first.get("tools").valueStream().toList();
        assertThat(tools.stream().map(t -> t.get("name").asString()).toList())
                .contains("searchProducts", "getCart", "addToCart", "placeOrder", "cancelOrder", "requestReturn")
                .doesNotContain("refundOrder", "suspendVendor", "adjustStock", "reassignDelivery");
        assertThat(tools.getLast().get("cache_control").get("type").asString()).isEqualTo("ephemeral");
        assertThat(first.get("messages").valueStream().toList().getLast().get("role").asString()).isEqualTo("user");
        // the second request carries the tool call and ToolGuard's result
        String toolResult = requests.getLast().get("messages").valueStream()
                .flatMap(m -> m.path("content").isArray() ? m.get("content").valueStream() : java.util.stream.Stream
                        .<JsonNode>empty())
                .filter(c -> "tool_result".equals(c.path("type").asString())).findFirst().orElseThrow().toString();
        assertThat(toolResult).contains("cart").contains("Fresh Mart");
    }

    @Test
    @Order(3)
    void insightsUseClaudesStructuredOutput() throws Exception {
        AbstractIntegrationTest.GATEWAY.reset();
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/stats", 200, Fixtures.stats(42, "10250.00"));
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/orders/stuck", 200, "[]");
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, "{\"recovered\":0,"
                + "\"failures\":0,\"checked\":0,\"lastRecovered\":[]}");

        mvc.perform(post("/api/ai/agents/admin-bi/insights").with(AbstractIntegrationTest.as(UUID.randomUUID(),
                        "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy.provider").value("anthropic"))
                .andExpect(jsonPath("$.generatedBy.model").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.headline").value("Steady day"));
        assertThat(ANTHROPIC.messageRequests().getLast().path("stream").asBoolean(false)).isFalse();
    }

    @Test
    @Order(4)
    void anOverloadedApiMeansTheAssistantIsUnavailableAndEverythingElseWorks() throws Exception {
        ANTHROPIC.failWith(529, "overloaded_error");
        try {
            AbstractIntegrationTest.GATEWAY.reset();
            AbstractIntegrationTest.GATEWAY.json("GET", "/api/cart", 200, Fixtures.json(Fixtures.defaultCart()));
            AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/stats", 200, Fixtures.stats(42, "10250.00"));
            AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/orders/stuck", 200, "[]");
            AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, "{}");

            Result result = chat(UUID.randomUUID(), "what is in my cart?");

            assertThat(result.names()).containsExactly("conversation", "error");
            assertThat(result.last().text("code")).isEqualTo("AI_UNAVAILABLE");
            mvc.perform(post("/api/ai/agents/admin-bi/insights").with(AbstractIntegrationTest.as(UUID.randomUUID(),
                            "SUPER_ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generatedBy.provider").value("rules"));
            mvc.perform(get("/api/ai/conversations").with(AbstractIntegrationTest.as(UUID.randomUUID(), "CUSTOMER")))
                    .andExpect(status().isOk());
        } finally {
            ANTHROPIC.failWith(0, null);
        }
    }
}
