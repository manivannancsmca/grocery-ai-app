package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP pilot (2026-11-20) from the assistant's side: {@code searchProducts} and {@code getOrderStatus} reach
 * catalog-query's and order-service's MCP servers (stateless Streamable HTTP through the gateway) with the customer's
 * own token, still through ToolGuard, audited as transport MCP; a server that cannot be reached falls back to the
 * REST API (audited REST), and the order server's ORDER_NOT_FOUND reads like the REST lookup's.
 */
class McpPilotIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void mcpOn(DynamicPropertyRegistry registry) {
        registry.add("grocery.ai.mcp.enabled", () -> "true");
    }

    /** The fake gateway answers {@code path} as a stateless MCP server; {@code tool} gives the tools/call answers. */
    private static void mcpServer(String path, Function<JsonNode, Map<String, Object>> tool) {
        GATEWAY.on("POST", path, request -> {
            JsonNode message = request.json();
            String method = message.path("method").asString();
            if (!message.has("id")) {
                return FakeGateway.Response.json(202, "");                  // notifications/initialized
            }
            Object result = switch (method) {
                case "initialize" -> Map.of("protocolVersion", message.path("params").path("protocolVersion")
                                .asString(), "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "fake", "version", "1.0.0"));
                case "tools/call" -> tool.apply(message.path("params"));
                default -> Map.of();
            };
            return FakeGateway.Response.json(200, JSON.writeValueAsString(Map.of("jsonrpc", "2.0",
                    "id", JSON.treeToValue(message.get("id"), Object.class), "result", result)));
        });
    }

    private static Map<String, Object> text(String json, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", json)), "isError", error);
    }

    private static List<JsonNode> toolCalls(String path) {
        return GATEWAY.requests("POST", path).stream().map(FakeGateway.Request::json)
                .filter(m -> "tools/call".equals(m.path("method").asString())).toList();
    }

    @Test
    void theProductSearchIsCatalogQuerysMcpToolCalledWithTheCustomersToken() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        mcpServer("/api/mcp/catalog", params -> text(Fixtures.searchResults(List.of(Fixtures.map(
                "id", UUID.randomUUID().toString(), "name", "India Gate Basmati Rice", "unitLabel", "1 kg",
                "vendor", Fixtures.map("id", UUID.randomUUID().toString(), "name", "Fresh Mart"),
                "price", Fixtures.map("mrp", new BigDecimal("120.00"), "sellingPrice", new BigDecimal("110.00"),
                        "currency", "INR"), "inStock", true))), false));

        Result result = chat(token, "shopping", "find basmati rice");

        assertThat(result.named("error")).isEmpty();
        assertThat(result.named("tool").getLast().text("status")).isEqualTo("SUCCEEDED");
        assertThat(result.answer()).contains("India Gate Basmati Rice");
        List<JsonNode> calls = toolCalls("/api/mcp/catalog");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().path("params").path("name").asString()).isEqualTo("searchProducts");
        assertThat(calls.getFirst().path("params").path("arguments").path("q").asString()).isEqualTo("basmati rice");
        assertThat(calls.getFirst().path("params").path("arguments").path("size").asInt()).isEqualTo(5);
        // every MCP request carries this customer's own token, the initialize request included
        assertThat(GATEWAY.requests("POST", "/api/mcp/catalog")).isNotEmpty().allSatisfy(request ->
                assertThat(request.header("Authorization")).isEqualTo("Bearer " + token));
        assertThat(GATEWAY.requests("GET", "/api/search/products")).isEmpty();
        assertThat(auditRows(customer)).singleElement().satisfies(row -> {
            assertThat(row.get("tool")).isEqualTo("searchProducts");
            assertThat(row.get("transport")).isEqualTo("MCP");
        });

        // the next customer's call carries their token, on the same MCP client
        UUID other = UUID.randomUUID();
        String otherToken = TOKENS.token(other, "CUSTOMER");
        chat(otherToken, "shopping", "find basmati rice");
        assertThat(GATEWAY.requests("POST", "/api/mcp/catalog").getLast().header("Authorization"))
                .isEqualTo("Bearer " + otherToken);
    }

    @Test
    void anOrdersStatusIsOrderServicesMcpToolAndAnUnknownNumberReadsAsBefore() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        mcpServer("/api/mcp/orders", params -> {
            String number = params.path("arguments").path("orderNumber").asString();
            return "GA-7K3F9Q2M".equals(number)
                    ? text(Fixtures.json(Fixtures.order(orderId, number, "OUT_FOR_DELIVERY", false, "225.00")), false)
                    : text("{\"code\":\"ORDER_NOT_FOUND\",\"message\":\"Order not found\",\"status\":404}", true);
        });

        Result found = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "status of GA-7K3F9Q2M");

        assertThat(found.named("tool").getLast().text("status")).isEqualTo("SUCCEEDED");
        assertThat(found.named("tool").getLast().data().get("links").get(0).get("path").asString())
                .isEqualTo("/orders/" + orderId);
        // not the REST lookup: no paging through the order list (size 100), no GET /api/orders/{id}
        assertThat(GATEWAY.requests("GET", "/api/orders")).noneMatch(r -> r.query() != null
                && r.query().contains("size=100"));
        assertThat(GATEWAY.requests("GET", "/api/orders/.+")).isEmpty();

        Result unknown = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "status of GA-AAAAAAAA");

        assertThat(unknown.named("tool").getLast().text("status")).isEqualTo("FAILED");
        assertThat(unknown.named("tool").getLast().text("summary")).isEqualTo("There is no order GA-AAAAAAAA.");
        assertThat(auditRows(customer)).extracting(row -> row.get("tool") + ":" + row.get("status") + ":"
                + row.get("transport")).containsExactly("getOrderStatus:SUCCEEDED:MCP", "getOrderStatus:FAILED:MCP");
    }

    @Test
    void aServerThatCannotBeReachedLeavesTheCallToTheRestApi() {
        UUID customer = UUID.randomUUID();
        // no MCP route: the gateway answers 404 like a service without its MCP server yet
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(Fixtures.map(
                "id", UUID.randomUUID().toString(), "name", "Sona Masoori Rice", "unitLabel", "5 kg",
                "price", Fixtures.map("mrp", new BigDecimal("400.00"), "sellingPrice", new BigDecimal("380.00"),
                        "currency", "INR"), "inStock", true))));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "find rice");

        assertThat(result.named("tool").getLast().text("status")).isEqualTo("SUCCEEDED");
        assertThat(result.answer()).contains("Sona Masoori Rice");
        assertThat(GATEWAY.requests("GET", "/api/search/products")).hasSize(1);
        assertThat(auditRows(customer)).singleElement()
                .satisfies(row -> assertThat(row.get("transport")).isEqualTo("REST"));
    }
}
