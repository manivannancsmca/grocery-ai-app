package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import com.grocery.ai.audit.AiMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
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
 * The {@code ollama} provider with the real Spring AI 2.0.2 Ollama client and chat model against {@link FakeOllama}:
 * streaming with tool calling through ToolGuard, thinking off and temperature 0.2 on the wire, structured output for
 * insights, the status probe, and graceful degradation once Ollama is gone (AI_UNAVAILABLE, available: false, rules).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grocery.ai.provider=ollama",
        "grocery.ai.ollama.model=qwen3:4b",
        "grocery.ai.shopping-commands=false",                  // "show my cart" must reach the (fake) Ollama here
        "grocery.ai.approvals.pollers-enabled=false",
        "grocery.ai.status-cache-ttl=0s",
        "grocery.ai.model-timeout=5s",
        "spring.datasource.hikari.connection-timeout=30000",
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OllamaProviderIntegrationTest {

    static final FakeOllama OLLAMA = new FakeOllama();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.MYSQL::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.MYSQL::getPassword);
        registry.add("grocery.ai.gateway-url", AbstractIntegrationTest.GATEWAY::baseUrl);
        registry.add("grocery.ai.ollama.base-url", OLLAMA::baseUrl);
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

    /** Phase 13: {@code grocery_ai_model_available{provider="ollama"}}, after a refresh. */
    private double modelAvailable() {
        metrics.refresh();
        return registry.get(AiMetrics.MODEL_AVAILABLE).tag("provider", "ollama").gauge().value();
    }

    private long modelCalls(String agent, String outcome) {
        Timer timer = registry.find(AiMetrics.MODEL_LATENCY).tag("agent", agent).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private Result chat(UUID user, String message) {
        return SseClient.post(port, "/api/ai/agents/shopping/chat", AbstractIntegrationTest.TOKENS.token(user,
                "CUSTOMER"), "{\"message\":\"" + message + "\"}", "text/event-stream");
    }

    @Test
    @Order(1)
    void theOllamaChatModelIsTheOneInUseAndTheStatusProbesTheModel() throws Exception {
        assertThat(chatModel).isInstanceOf(OllamaChatModel.class);
        mvc.perform(get("/api/ai/status").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ollama"))
                .andExpect(jsonPath("$.model").value("qwen3:4b"))
                .andExpect(jsonPath("$.available").value(true));
        assertThat(modelAvailable()).isEqualTo(1.0);
        OLLAMA.pulledModel("llama3.2:3b");
        mvc.perform(get("/api/ai/status").with(jwt())).andExpect(jsonPath("$.available").value(false));
        assertThat(modelAvailable()).as("the probe's answer: the model is not pulled").isEqualTo(0.0);
        OLLAMA.pulledModel("qwen3:4b");
        assertThat(modelAvailable()).isEqualTo(1.0);
    }

    @Test
    @Order(2)
    void streamingWithToolCallingRunsThroughToolGuard() {
        UUID customer = UUID.randomUUID();
        AbstractIntegrationTest.GATEWAY.reset();
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/cart", 200, Fixtures.json(Fixtures.defaultCart()));
        int before = OLLAMA.chatRequests().size();
        long answered = modelCalls("shopping", AiMetrics.SUCCESS);

        Result result = chat(customer, "what is in my cart?");
        assertThat(modelCalls("shopping", AiMetrics.SUCCESS)).isEqualTo(answered + 1);

        assertThat(result.names()).containsSubsequence("conversation", "tool", "tool", "token", "token", "done");
        assertThat(result.named("tool")).extracting(e -> e.text("tool") + ":" + e.text("status"))
                .containsExactly("getCart:STARTED", "getCart:SUCCEEDED");
        assertThat(result.answer()).isEqualTo("Your cart has 3 items.");
        assertThat(result.last().text("finishReason")).isEqualTo("STOP");

        List<JsonNode> requests = OLLAMA.chatRequests().subList(before, OLLAMA.chatRequests().size());
        assertThat(requests).hasSize(2);
        JsonNode first = requests.getFirst();
        assertThat(first.get("model").asString()).isEqualTo("qwen3:4b");
        assertThat(first.get("stream").asBoolean()).isTrue();
        assertThat(first.get("think").asBoolean()).isFalse();
        assertThat(first.get("options").get("temperature").asDouble()).isEqualTo(0.2);
        // Ollama's default window (4096) is too small for the shopping prompt, its tools and one tool result
        assertThat(first.get("options").get("num_ctx").asInt()).isEqualTo(8192);
        assertThat(first.get("tools").valueStream().map(t -> t.get("function").get("name").asString()).toList())
                .contains("searchProducts", "getCart", "addToCart", "placeOrder", "cancelOrder", "requestReturn")
                .doesNotContain("refundOrder", "suspendVendor", "adjustStock", "reassignDelivery");
        // a list parameter reaches the model as an array of objects
        JsonNode returnItems = first.get("tools").valueStream()
                .filter(t -> "requestReturn".equals(t.get("function").get("name").asString())).findFirst()
                .orElseThrow().get("function").get("parameters").get("properties").get("items");
        assertThat(returnItems.get("type").asString()).isEqualTo("array");
        assertThat(returnItems.get("items").get("properties").has("quantity")).isTrue();
        assertThat(first.get("messages").get(0).get("role").asString()).isEqualTo("system");
        // Ollama reuses the start of the prompt it read last time (the rules, then the tools, in qwen3's template):
        // this user's context line comes after them, as a separate system message right before the user's message
        List<JsonNode> sent = first.get("messages").valueStream().toList();
        assertThat(sent.getFirst().get("content").asString()).doesNotContain("Context (from the server");
        JsonNode contextLine = sent.get(sent.size() - 2);
        assertThat(contextLine.get("role").asString()).isEqualTo("system");
        assertThat(contextLine.get("content").asString()).startsWith("Context (from the server, trustworthy)");
        assertThat(sent.getLast().get("role").asString()).isEqualTo("user");
        // the second request carries the tool call and ToolGuard's result
        JsonNode second = requests.getLast();
        JsonNode toolMessage = second.get("messages").valueStream()
                .filter(m -> "tool".equals(m.get("role").asString())).findFirst().orElseThrow();
        assertThat(toolMessage.get("content").asString()).contains("\"cart\"").contains("Fresh Mart");
    }

    @Test
    @Order(3)
    void insightsUseOllamasStructuredOutput() throws Exception {
        AbstractIntegrationTest.GATEWAY.reset();
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/stats", 200, Fixtures.stats(42, "10250.00"));
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/orders/stuck", 200, "[]");
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, "{\"recovered\":0,"
                + "\"failures\":0,\"checked\":0,\"lastRecovered\":[]}");

        mvc.perform(post("/api/ai/agents/admin-bi/insights").with(AbstractIntegrationTest.as(UUID.randomUUID(),
                        "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy.provider").value("ollama"))
                .andExpect(jsonPath("$.generatedBy.model").value("qwen3:4b"))
                .andExpect(jsonPath("$.headline").value("Steady day"));
        JsonNode request = OLLAMA.chatRequests().getLast();
        assertThat(request.get("stream").asBoolean()).isFalse();
        assertThat(request.get("format").get("type").asString()).isEqualTo("object");      // JSON schema of Draft
        assertThat(request.get("format").get("properties").has("headline")).isTrue();
    }

    @Test
    @Order(4)
    void withOllamaGoneTheChatDegradesGracefully() throws Exception {
        OLLAMA.stop();
        AbstractIntegrationTest.GATEWAY.reset();
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/cart", 200, Fixtures.json(Fixtures.defaultCart()));
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/stats", 200, Fixtures.stats(42, "10250.00"));
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/orders/stuck", 200, "[]");
        AbstractIntegrationTest.GATEWAY.json("GET", "/api/admin/payments/reconciliation", 200, "{}");

        long unavailable = modelCalls("shopping", "AI_UNAVAILABLE");
        Result result = chat(UUID.randomUUID(), "what is in my cart?");

        assertThat(result.names()).containsExactly("conversation", "error");
        assertThat(result.last().text("code")).isEqualTo("AI_UNAVAILABLE");
        assertThat(modelCalls("shopping", "AI_UNAVAILABLE")).isEqualTo(unavailable + 1);
        mvc.perform(get("/api/ai/status").with(jwt())).andExpect(jsonPath("$.available").value(false));
        assertThat(modelAvailable()).isEqualTo(0.0);
        mvc.perform(post("/api/ai/agents/admin-bi/insights").with(AbstractIntegrationTest.as(UUID.randomUUID(),
                        "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedBy.provider").value("rules"));
        // everything else keeps working
        mvc.perform(get("/api/ai/conversations").with(AbstractIntegrationTest.as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isOk());
    }
}
