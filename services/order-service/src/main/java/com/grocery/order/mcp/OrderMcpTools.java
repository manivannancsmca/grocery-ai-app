package com.grocery.order.mcp;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.ApiException;
import com.grocery.common.web.ForbiddenOperationException;
import com.grocery.order.dto.OrderDtos.OrderView;
import com.grocery.order.service.OrderQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The order service's MCP tools (MCP pilot, 2026-11-20; DEVELOPMENT_PLAN "REST vs MCP"): a customer's order status,
 * owned by the team that owns the orders, for any MCP client acting for a signed-in customer. The customer assistants
 * (ai-orchestrator-service) call it through their ToolGuard with the customer's token.
 * <p>
 * Stateless Streamable HTTP at {@code POST /api/mcp/orders} ({@code spring.ai.mcp.server.*}), behind the same JWT check
 * as the REST API. A stateless server in a servlet app runs the tool on the request's own thread, so the caller is the
 * request's verified token: as {@code GET /api/orders/{id}}, customers only and their own orders only.
 */
@Component
public class OrderMcpTools {

    private final OrderQueryService queries;

    public OrderMcpTools(OrderQueryService queries) {
        this.queries = queries;
    }

    @Tool(name = "getOrderStatus", description = "One of the signed-in customer's orders by its number: the same "
            + "answer as GET /api/orders/{id} (status, items, totals, store, payment, delivery, returns). Another "
            + "customer's order is ORDER_NOT_FOUND, like an unknown one.")
    public OrderView getOrderStatus(
            @ToolParam(description = "The order number, e.g. GA-7K3F9Q2M (any case)") String orderNumber) {
        try {
            return queries.customerOrderByNumber(customer().userId(), orderNumber);
        } catch (ApiException e) {
            throw McpToolErrors.of(e);
        }
    }

    /** The request's caller, who must be a customer (as {@code @PreAuthorize("hasRole('CUSTOMER')")} on the API). */
    private static CurrentUser customer() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new ForbiddenOperationException("FORBIDDEN", "You are not allowed to perform this operation.");
        }
        CurrentUser user = CurrentUser.from(token.getToken());
        if (!user.hasRole(Roles.CUSTOMER)) {
            throw new ForbiddenOperationException("FORBIDDEN", "You are not allowed to perform this operation.");
        }
        return user;
    }
}
