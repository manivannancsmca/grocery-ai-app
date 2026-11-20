package com.grocery.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MCP pilot's order server (stateless Streamable HTTP at /api/mcp/orders): a token is needed, the server lists
 * {@code getOrderStatus}, and the tool answers what {@code GET /api/orders/{id}} answers, for the order's own customer
 * only (another customer: ORDER_NOT_FOUND; not a customer: FORBIDDEN).
 */
class McpOrderStatusIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static MockHttpServletRequestBuilder mcp(String method, Map<String, Object> params) {
        return post("/api/mcp/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method,
                        "params", params)));
    }

    private JsonNode orderStatus(String orderNumber, JwtRequestPostProcessor caller) throws Exception {
        return body(mvc.perform(mcp("tools/call", Map.of("name", "getOrderStatus",
                "arguments", Map.of("orderNumber", orderNumber))).with(caller)).andExpect(status().isOk()))
                .get("result");
    }

    private static JsonNode text(JsonNode result) {
        return JSON.readTree(result.get("content").get(0).get("text").asString());
    }

    @Test
    void aCustomersOrderStatusIsAnMcpToolForThatCustomerOnly() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);
        String number = body(mvc.perform(get("/api/orders/{id}", orderId).with(customer(customerId)))
                .andExpect(status().isOk())).get("orderNumber").asString();

        mvc.perform(mcp("tools/list", Map.of())).andExpect(status().isUnauthorized());
        JsonNode tools = body(mvc.perform(mcp("tools/list", Map.of()).with(customer(customerId)))
                .andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        tools.get("result").get("tools").forEach(tool -> names.add(tool.get("name").asString()));
        assertThat(names).containsExactly("getOrderStatus");

        JsonNode own = orderStatus(number.toLowerCase(Locale.ROOT), customer(customerId));
        assertThat(own.get("isError").asBoolean()).isFalse();
        JsonNode order = text(own);
        assertThat(order.get("id").asString()).isEqualTo(orderId.toString());
        assertThat(order.get("orderNumber").asString()).isEqualTo(number);
        assertThat(order.get("status").asString()).isEqualTo("CREATED");

        JsonNode someoneElses = orderStatus(number, customer(UUID.randomUUID()));
        assertThat(someoneElses.get("isError").asBoolean()).isTrue();
        assertThat(text(someoneElses).get("code").asString()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(text(someoneElses).get("status").asInt()).isEqualTo(404);

        JsonNode notACustomer = orderStatus(number, as(UUID.randomUUID(), "DELIVERY_PARTNER"));
        assertThat(notACustomer.get("isError").asBoolean()).isTrue();
        assertThat(text(notACustomer).get("code").asString()).isEqualTo("FORBIDDEN");
    }
}
