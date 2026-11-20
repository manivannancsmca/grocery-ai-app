package com.grocery.ai;

import com.grocery.ai.SseClient.Event;
import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolGuard (contract §3) with real tool calls from the scripted model and a runaway fake model: allowlist, role,
 * arguments, unknown names, the tool call limit; every call audited; what the model gets back.
 */
class ToolGuardIntegrationTest extends AbstractIntegrationTest {

    private static String lastToolResult(Prompt prompt) {
        return ((ToolResponseMessage) prompt.getInstructions().getLast()).getResponses().getLast().responseData();
    }

    @Test
    void aCustomerAskingForAnAdminToolIsBlockedToolNotAllowedAndAudited() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping",
                "use tool refundOrder {\"orderNumber\":\"GA-7K3F9Q2M\",\"note\":\"free money\"}");

        List<Event> tools = result.named("tool");
        assertThat(tools).extracting(e -> e.text("status")).containsExactly("STARTED", "BLOCKED");
        assertThat(tools.getLast().text("tool")).isEqualTo("refundOrder");
        assertThat(result.answer()).startsWith("I can't do that: The tool 'refundOrder' is not available");
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(json.readTree(lastToolResult(model.prompts().getLast())))
                .isEqualTo(json.readTree("{\"status\":\"BLOCKED\",\"reason\":\"TOOL_NOT_ALLOWED\",\"message\":"
                        + "\"The tool 'refundOrder' is not available to the Shopping assistant.\"}"));
        assertThat(auditRows(customer)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tool", "refundOrder").containsEntry("status", "BLOCKED")
                .containsEntry("reason", "TOOL_NOT_ALLOWED"));
        assertThat(GATEWAY.requests("POST", "/api/admin/refunds")).isEmpty();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM approval_request WHERE requested_by = UUID_TO_BIN(?)")
                .param(customer.toString()).query(Long.class).single()).isZero();
    }

    @Test
    void anInventedToolNameIsBlockedInsteadOfFailingTheTurn() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping",
                "use tool transferMoney {\"to\":\"me\",\"amount\":1000}");

        assertThat(result.named("tool")).extracting(e -> e.text("status")).containsExactly("STARTED", "BLOCKED");
        assertThat(result.named("error")).isEmpty();
        assertThat(result.last().name()).isEqualTo("done");
        Map<String, Object> row = auditRows(customer).getFirst();
        assertThat(row).containsEntry("tool", "transferMoney").containsEntry("reason", "TOOL_NOT_ALLOWED");
        assertThat(json.readTree((String) row.get("arguments")).get("amount").asInt()).isEqualTo(1000);
    }

    @Test
    void supportProposingASuspensionIsBlockedRoleNotAllowed() {
        UUID support = UUID.randomUUID();

        Result result = chat(TOKENS.token(support, "SUPPORT"), "admin-bi",
                "suspend vendor " + UUID.randomUUID() + " because too many complaints");

        assertThat(result.named("tool").getLast().text("status")).isEqualTo("BLOCKED");
        assertThat(result.answer()).isEqualTo("I can't do that: Your role may not use 'suspendVendor'.");
        assertThat(auditRows(support)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("tool", "suspendVendor").containsEntry("reason", "ROLE_NOT_ALLOWED"));
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    void invalidArgumentsAreBlockedBeforeAnyCall() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Smart Rice", "100.00", true))));

        // quantity 25 > 20
        Result tooMany = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "add 25 rice");
        List<Event> tools = tooMany.named("tool");
        assertThat(tools).extracting(e -> e.text("tool") + ":" + e.text("status")).containsExactly(
                "searchProducts:STARTED", "searchProducts:SUCCEEDED", "addToCart:STARTED", "addToCart:BLOCKED");
        assertThat(tools.getLast().text("summary")).isEqualTo("Invalid arguments: quantity must be between 1 and 20.");
        assertThat(GATEWAY.requests("POST", "/api/cart/items")).isEmpty();

        // a query longer than 100 characters, a malformed id, not even JSON
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping",
                "use tool searchProducts {\"query\":\"" + "a".repeat(101) + "\"}");
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool getProduct {\"productId\":\"42\"}");
        chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool getCart [1,2");

        List<Map<String, Object>> audit = auditRows(customer);
        assertThat(audit).extracting(row -> row.get("tool") + ":" + row.get("status") + ":" + row.get("reason"))
                .containsExactly("searchProducts:SUCCEEDED:null", "addToCart:BLOCKED:INVALID_ARGUMENTS",
                        "searchProducts:BLOCKED:INVALID_ARGUMENTS", "getProduct:BLOCKED:INVALID_ARGUMENTS",
                        "getCart:BLOCKED:INVALID_ARGUMENTS");
        assertThat(audit.get(2).get("result_summary"))
                .isEqualTo("Invalid arguments: query must have at most 100 characters.");
        assertThat(audit.get(3).get("result_summary")).isEqualTo("Invalid arguments: productId must be an id (UUID).");
        assertThat(json.readTree((String) audit.get(4).get("arguments")).get("raw").asString()).isEqualTo("[1,2");
        assertThat(GATEWAY.requests("GET", "/api/search/products")).hasSize(1);
        assertThat(GATEWAY.requests("GET", "/api/catalog/products/.*")).isEmpty();
    }

    @Test
    void readAndLowRiskWritesRunAsTheCustomer() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.cart(List.of()));
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Smart Rice", "100.00", true))));
        GATEWAY.json("POST", "/api/cart/items", 200, Fixtures.json(Fixtures.cart(List.of(
                Fixtures.cartLine(Fixtures.RICE, "Smart Rice", 2, "100.00")))));

        Result result = chat(token, "shopping", "add 2 smart rice");

        assertThat(result.named("tool")).extracting(e -> e.text("tool") + ":" + e.text("status")).containsExactly(
                "searchProducts:STARTED", "searchProducts:SUCCEEDED", "addToCart:STARTED", "addToCart:SUCCEEDED");
        assertThat(result.named("tool").getLast().text("summary"))
                .isEqualTo("Added 2 × Smart Rice · Cart: 2 items from Fresh Mart, subtotal ₹200.00");
        assertThat(result.answer()).isEqualTo("Added 2 × Smart Rice to your cart. Your cart from Fresh Mart: "
                + "Smart Rice × 2 — subtotal ₹200.00.");
        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.param("inStock")).isEqualTo("true");
        FakeGateway.Request add = GATEWAY.requests("POST", "/api/cart/items").getFirst();
        assertThat(add.json()).isEqualTo(json.readTree("{\"type\":\"PRODUCT\",\"productId\":\"" + Fixtures.RICE
                + "\",\"quantity\":2}"));
        assertThat(add.header("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    void anApiErrorIsReportedToTheModelWithItsCode() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.problem(409, "OUT_OF_STOCK",
                "Smart Rice is out of stock"));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping",
                "use tool addToCart {\"productId\":\"" + Fixtures.RICE + "\",\"quantity\":1}");

        assertThat(result.named("tool").getLast().text("status")).isEqualTo("FAILED");
        assertThat(json.readTree(lastToolResult(model.prompts().getLast()))).isEqualTo(json.readTree(
                "{\"status\":\"ERROR\",\"code\":\"OUT_OF_STOCK\",\"message\":\"Smart Rice is out of stock\"}"));
        assertThat(auditRows(customer).getFirst()).containsEntry("status", "FAILED")
                .containsEntry("reason", "OUT_OF_STOCK").containsEntry("http_status", 409);
    }

    @Test
    void aSeventhToolCallEndsTheTurnWithAiToolLimit() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.toolLoop("getCart", "{}"));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "show my cart forever");

        assertThat(result.last().name()).isEqualTo("error");
        assertThat(result.last().text("code")).isEqualTo("AI_TOOL_LIMIT");
        assertThat(result.named("done")).isEmpty();
        assertThat(result.named("tool").stream().filter(e -> "SUCCEEDED".equals(e.text("status")))).hasSize(6);
        List<Map<String, Object>> audit = auditRows(customer);
        assertThat(audit).hasSize(7);
        assertThat(audit.subList(0, 6)).allSatisfy(row -> assertThat(row).containsEntry("status", "SUCCEEDED"));
        assertThat(audit.get(6)).containsEntry("tool", "getCart").containsEntry("status", "BLOCKED")
                .containsEntry("reason", "AI_TOOL_LIMIT");
        // six tool calls plus the context line's cart summary
        assertThat(GATEWAY.requests("GET", "/api/cart")).hasSize(7);
    }
}
