package com.grocery.catalogquery;

import com.grocery.catalogquery.TestEvents.Cat;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.catalogquery.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MCP pilot's catalog server (stateless Streamable HTTP at /api/mcp/catalog): a token is needed, the server lists
 * {@code searchProducts}, and the tool answers what {@code GET /api/search/products} answers; a bad parameter is the
 * tool's error with the API's code.
 */
class McpSearchIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Cat vegetables = Cat.of("Fresh Vegetables");
    private final String vendor = id();

    private static MockHttpServletRequestBuilder mcp(String method, Map<String, Object> params) {
        return post("/api/mcp/catalog")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method,
                        "params", params)));
    }

    private JsonNode call(Map<String, Object> arguments) throws Exception {
        JsonNode response = body(mvc.perform(mcp("tools/call", Map.of("name", "searchProducts",
                "arguments", arguments)).with(as(UUID.randomUUID(), "CUSTOMER"))).andExpect(status().isOk()));
        return response.get("result");
    }

    @Test
    void theCatalogsSearchIsAnMcpToolForSignedInCallers() throws Exception {
        for (String name : List.of("Carrot", "Garden Soup Mix", "Banana")) {
            String productId = id();
            publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, name, 1, List.of(vegetables)));
        }
        refresh();

        mvc.perform(mcp("tools/list", Map.of())).andExpect(status().isUnauthorized());

        JsonNode tools = body(mvc.perform(mcp("tools/list", Map.of()).with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        tools.get("result").get("tools").forEach(tool -> names.add(tool.get("name").asString()));
        assertThat(names).containsExactly("searchProducts");

        JsonNode result = call(Map.of("q", "carrot", "vendorIds", List.of(vendor), "size", 5));
        assertThat(result.get("isError").asBoolean()).isFalse();
        JsonNode search = JSON.readTree(result.get("content").get(0).get("text").asString());
        List<String> found = new ArrayList<>();
        search.get("content").forEach(card -> found.add(card.get("name").asString()));
        assertThat(found).containsExactly("Carrot");
        assertThat(search.get("totalElements").asInt()).isEqualTo(1);
        assertThat(search.get("content").get(0).get("shortDescription").asString())
                .isEqualTo("Fresh carrot from local farms");

        JsonNode bad = call(Map.of("q", "carrot", "minPrice", 50, "maxPrice", 10));
        assertThat(bad.get("isError").asBoolean()).isTrue();
        JsonNode error = JSON.readTree(bad.get("content").get(0).get("text").asString());
        assertThat(error.get("code").asString()).isEqualTo("INVALID_PARAMETER");
        assertThat(error.get("status").asInt()).isEqualTo(400);
    }
}
