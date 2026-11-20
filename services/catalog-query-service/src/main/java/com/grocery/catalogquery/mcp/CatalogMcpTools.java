package com.grocery.catalogquery.mcp;

import com.grocery.catalogquery.controller.SearchController;
import com.grocery.catalogquery.dto.CatalogDtos.ProductSearchResponse;
import com.grocery.common.web.ApiException;
import com.grocery.common.web.BadRequestException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The catalog's MCP tools (MCP pilot, 2026-11-20; DEVELOPMENT_PLAN "REST vs MCP"): the product search, owned by
 * the team that owns the search index, for any MCP client acting for a signed-in user. The shopping assistant
 * (ai-orchestrator-service) is one: it calls this tool through its ToolGuard, with the customer's token.
 * <p>
 * Stateless Streamable HTTP at {@code POST /api/mcp/catalog} ({@code spring.ai.mcp.server.*}); unlike the public
 * search, the endpoint needs a valid token (it is not under {@code grocery.security.public-paths}). The tool answers
 * exactly what {@code GET /api/search/products} answers, through the same controller method and validation.
 */
@Component
public class CatalogMcpTools {

    private final SearchController search;

    public CatalogMcpTools(SearchController search) {
        this.search = search;
    }

    @Tool(name = "searchProducts", description = "Search the grocery catalog. The same answer as GET "
            + "/api/search/products: content (product cards with price, stock, store and shortDescription), "
            + "totalElements (every match), page, size and facets. Matches names, brands, descriptions and categories.")
    public ProductSearchResponse searchProducts(
            @ToolParam(required = false, description = "The words to look for; leave out to browse") String q,
            @ToolParam(required = false, description = "A category id (its subcategories included)")
            String categoryId,
            @ToolParam(required = false, description = "Only these stores (vendor ids, at most 50)")
            List<String> vendorIds,
            @ToolParam(required = false, description = "Only products in stock") Boolean inStock,
            @ToolParam(required = false, description = "Lowest selling price in rupees (inclusive)")
            BigDecimal minPrice,
            @ToolParam(required = false, description = "Highest selling price in rupees (inclusive)")
            BigDecimal maxPrice,
            @ToolParam(required = false, description = "RELEVANCE, NEWEST, NAME_ASC, PRICE_ASC, PRICE_DESC or RATING")
            String sort,
            @ToolParam(required = false, description = "Products per page, 1 to 50 (default 20)") Integer size) {
        try {
            return search.searchProducts(q, uuid("categoryId", categoryId), null, uuids(vendorIds), null, inStock,
                    minPrice, maxPrice, sort, 0, size);
        } catch (ApiException e) {
            throw McpToolErrors.of(e);
        }
    }

    private static UUID uuid(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw McpToolErrors.of(new BadRequestException("INVALID_PARAMETER", name + " must be a UUID"));
        }
    }

    private static List<UUID> uuids(List<String> values) {
        return values == null ? null : values.stream().map(value -> uuid("vendorIds", value)).toList();
    }
}
