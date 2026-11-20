package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolGuard (Phase 11 §3), driven through chat with the scripted model's {@code use tool <name> <json>}: the agent's
 * allowlist (TOOL_NOT_ALLOWED), the caller's role (ROLE_NOT_ALLOWED) and the arguments (INVALID_ARGUMENTS) — the call
 * never reaches a business API, nothing changes, and every block is audited with its reason.
 */
@Tag("ai")
@Tag("security")
@Order(24)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI: ToolGuard blocks")
class ToolGuardTest {

    World.Customer customer;
    World.Store store;

    @BeforeAll
    void setUp() {
        Assistant.requireScriptedModel();
        customer = World.newCustomer("Guarded");
        store = World.storeA();
    }

    private static void blocked(Session user, String agent, String message, String tool, String reason) {
        Chat chat = Chat.send(user, agent, message).streamed().tool(tool, "BLOCKED");
        assertThat(chat.events("approval")).as("no approval for a blocked call").isEmpty();
        assertThat(chat.answer()).startsWith("I can't do that");
        Map<String, Object> audit = Assistant.audited(user, tool);
        assertThat(audit).containsEntry("status", "BLOCKED").containsEntry("reason", reason).containsEntry("agent", agent);
        assertThat(audit.get("httpStatus")).isNull();
    }

    @Test
    void tools_outside_the_agents_allowlist_are_TOOL_NOT_ALLOWED() {
        blocked(customer.session(), "shopping", "use tool refundOrder {\"orderNumber\":\"GA-AAAAAAAA\",\"note\":\"money\"}",
                "refundOrder", "TOOL_NOT_ALLOWED");
        blocked(customer.session(), "shopping", "use tool suspendVendor {\"vendorId\":\"" + store.vendorId()
                + "\",\"reason\":\"x\"}", "suspendVendor", "TOOL_NOT_ALLOWED");
        blocked(customer.session(), "shopping", "use tool deleteAllOrders {}", "deleteAllOrders", "TOOL_NOT_ALLOWED");
        blocked(store.vendor(), "vendor", "use tool placeOrder {}", "placeOrder", "TOOL_NOT_ALLOWED");
        blocked(World.adminWithRole("OPS_ADMIN"), "admin-bi", "use tool addToCart {\"productId\":\"" + store.rice().id()
                + "\",\"quantity\":1}", "addToCart", "TOOL_NOT_ALLOWED");
        Api.as(customer.session()).get("/api/admin/vendors/" + store.vendorId()).problem(403, "FORBIDDEN");
        Api.as(World.admin()).get("/api/admin/vendors/" + store.vendorId()).ok().has("status", "APPROVED");
    }

    @Test
    void tools_the_callers_role_may_not_propose_are_ROLE_NOT_ALLOWED() {
        blocked(World.adminWithRole("SUPPORT"), "admin-bi", "suspend vendor " + store.vendorId() + " because test",
                "suspendVendor", "ROLE_NOT_ALLOWED");
        blocked(World.adminWithRole("FINANCE_ADMIN"), "admin-bi", "cancel GA-AAAAAAAA because test",
                "cancelOrderAsAdmin", "ROLE_NOT_ALLOWED");
        blocked(World.adminWithRole("DELIVERY_MANAGER"), "admin-bi", "refund 1 for GA-AAAAAAAA because test",
                "refundOrder", "ROLE_NOT_ALLOWED");
        Api.as(World.admin()).get("/api/admin/vendors/" + store.vendorId()).ok().has("status", "APPROVED");
    }

    @Test
    void invalid_arguments_are_INVALID_ARGUMENTS() {
        blocked(customer.session(), "shopping", "use tool addToCart {\"productId\":\"" + store.rice().id()
                + "\",\"quantity\":50}", "addToCart", "INVALID_ARGUMENTS");
        Session other = World.newCustomer("Guarded2").session();
        blocked(other, "shopping", "use tool searchProducts {\"query\":\"" + "x".repeat(101) + "\"}",
                "searchProducts", "INVALID_ARGUMENTS");
        Session third = World.newCustomer("Guarded3").session();
        blocked(third, "shopping", "use tool getProduct {\"productId\":\"' OR 1=1 --\"}", "getProduct", "INVALID_ARGUMENTS");
        blocked(World.adminWithRole("SUPER_ADMIN"), "admin-bi",
                "use tool refundOrder {\"orderNumber\":\"GA-AAAAAAAA\",\"amount\":\"0\",\"note\":\"x\"}",
                "refundOrder", "INVALID_ARGUMENTS");
        Session fourth = World.newCustomer("Guarded4").session();
        blocked(fourth, "shopping", "use tool addToCart not-json", "addToCart", "INVALID_ARGUMENTS");
        Res cart = Api.as(customer.session()).get("/api/cart").ok();
        assertThat(cart.<Object>list("lines")).as("nothing was added").isEmpty();
    }

    @Test
    void a_refused_agent_is_audited_with_no_tool() {
        Session intruder = World.newCustomer("WrongAgent").session();
        Api.as(intruder).accept("text/event-stream").body(Map.of("message", "stats"))
                .post("/api/ai/agents/admin-bi/chat").problem(403, "AGENT_NOT_ALLOWED");
        Res audit = Api.as(World.adminWithRole("OPS_ADMIN"))
                .get("/api/admin/ai/tool-invocations?userId=" + intruder.userId() + "&size=10").ok().page();
        List<Map<String, Object>> rows = audit.list("content");
        assertThat(rows).anySatisfy(r -> assertThat(r).containsEntry("reason", "AGENT_NOT_ALLOWED")
                .containsEntry("status", "BLOCKED").containsEntry("tool", null));
        Res summary = Api.as(World.adminWithRole("OPS_ADMIN")).get("/api/admin/ai/summary").ok();
        assertThat(summary.integer("blocked")).isPositive();
    }

    @Test
    @Disabled("AI_TOOL_LIMIT (more than 6 tool calls for one message) cannot be reached with the scripted model, which "
            + "makes at most two tool calls per message; it is covered by ai-orchestrator-service's own tests")
    void a_seventh_tool_call_ends_the_turn_with_AI_TOOL_LIMIT() {
    }
}
