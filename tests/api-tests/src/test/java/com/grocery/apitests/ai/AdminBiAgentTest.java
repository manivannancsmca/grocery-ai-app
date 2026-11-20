package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** The admin BI assistant: read tools, structured insights, and the sensitive tools as approvals for the right deciders. */
@Tag("ai")
@Order(21)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI: admin BI assistant")
class AdminBiAgentTest {

    Session ops;
    Session finance;

    @BeforeAll
    void setUp() {
        Assistant.requireScriptedModel();
        ops = World.adminWithRole("OPS_ADMIN");
        finance = World.adminWithRole("FINANCE_ADMIN");
    }

    @Test
    void read_tools_answer_from_the_admin_apis() {
        Chat stats = Chat.send(ops, "admin-bi", "stats").streamed().tool("getOrderStats", "SUCCEEDED");
        assertThat(stats.answer()).contains("orders").contains("₹");
        Chat.send(ops, "admin-bi", "stuck orders").streamed().tool("listStuckOrders", "SUCCEEDED");
        Chat.send(ops, "admin-bi", "use tool listPayments {}").streamed().tool("listPayments", "SUCCEEDED");
        Chat.send(ops, "admin-bi", "use tool getReconciliationSummary {}").streamed()
                .tool("getReconciliationSummary", "SUCCEEDED");
        Chat.send(ops, "admin-bi", "use tool listOrders {\"status\":\"CONFIRMED\"}").streamed()
                .tool("listOrders", "SUCCEEDED");
    }

    @Test
    void insights_are_a_typed_report_from_the_model() {
        Res report = Api.as(ops).body(obj()).post("/api/ai/agents/admin-bi/insights").ok()
                .has("generatedBy.provider", "scripted");
        assertThat(report.str("headline")).isNotBlank();
        assertThat(report.map("$")).containsKeys("date", "kpis", "observations", "risks", "suggestedActions",
                "generatedAt");
        assertThat(report.<Map<String, Object>>list("kpis")).isNotEmpty()
                .allSatisfy(k -> assertThat(k).containsKeys("name", "value"));
    }

    @Test
    void a_refund_is_proposed_by_ops_and_decided_by_finance_only() {
        World.Customer customer = World.newCustomer("Refundee");
        World.Order order = World.paidGroceryOrder(customer, World.storeA().rice(), 1);
        Chat chat = Chat.send(ops, "admin-bi", "refund 10 for " + order.number() + " because Damaged packet").streamed()
                .tool("refundOrder", "APPROVAL_REQUIRED");
        JsonPath approval = chat.approval();
        String id = approval.getString("id");
        assertThat(approval.getString("action")).isEqualTo("REFUND_PAYMENT");
        assertThat(approval.getList("approverRoles", String.class)).contains("FINANCE_ADMIN", "SUPER_ADMIN")
                .doesNotContain("OPS_ADMIN");
        assertThat(approval.getBoolean("fourEyes")).isFalse();
        assertThat(new BigDecimal(approval.getString("summary.amount"))).isEqualByComparingTo("10.00");
        assertThat(approval.getBoolean("canDecide")).isFalse();
        Api.as(ops).get("/api/approvals/" + id).ok().has("canDecide", false);
        Api.as(ops).body(obj()).post("/api/approvals/" + id + "/approve").problem(403, "APPROVAL_FORBIDDEN");
        Api.as(World.adminWithRole("SUPPORT")).body(obj()).post("/api/approvals/" + id + "/approve")
                .problem(403, "APPROVAL_FORBIDDEN");
        Api.as(finance).get("/api/approvals?scope=to-decide").ok().page().bodyContains(id);
        Api.as(finance).get("/api/payments/orders/" + order.id()).ok().has("refundedAmount", BigDecimal.ZERO);

        Res done = Api.as(finance).body(obj("comment", "ok")).post("/api/approvals/" + id + "/approve").ok()
                .has("status", "COMPLETED").has("decidedBy", finance.userId());
        assertThat(done.str("executedAt")).isNotBlank();
        Await.until("₹10.00 refunded once", Await.seconds(30),
                () -> Api.as(finance).get("/api/payments/orders/" + order.id()),
                r -> r.status() == 200 && r.list("refunds").size() == 1
                        && r.dec("refundedAmount").compareTo(new BigDecimal("10.00")) == 0);

        Chat tooMuch = Chat.send(ops, "admin-bi", "refund 99999 for " + order.number() + " because Too much").streamed()
                .tool("refundOrder", "BLOCKED");
        assertThat(tooMuch.events("approval")).isEmpty();
        assertThat(Assistant.audited(ops, "refundOrder")).containsEntry("reason", "INVALID_ARGUMENTS");
    }

    @Test
    void an_admin_cancellation_runs_only_after_approval() {
        World.Customer customer = World.newCustomer("AdminCancel");
        World.Order order = World.groceryOrder(customer, World.storeA().rice(), 1);
        World.awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        Session support = World.adminWithRole("SUPPORT");
        Chat chat = Chat.send(support, "admin-bi", "cancel " + order.number() + " because Duplicate order").streamed()
                .tool("cancelOrderAsAdmin", "APPROVAL_REQUIRED");
        String id = chat.approvalId();
        assertThat(chat.approval().getString("action")).isEqualTo("ADMIN_CANCEL_ORDER");
        assertThat(chat.approval().getList("approverRoles", String.class)).contains("OPS_ADMIN", "SUPER_ADMIN");
        World.awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        Api.as(support).body(obj()).post("/api/approvals/" + id + "/approve").problem(403, "APPROVAL_FORBIDDEN");
        Api.as(ops).body(obj()).post("/api/approvals/" + id + "/approve").ok().has("status", "COMPLETED");
        Res cancelled = World.awaitOrderStatus(customer, order.id(), "CANCELLED");
        cancelled.has("cancellation.reason", "ADMIN_CANCELLED").has("cancellation.by", "ADMIN");
        List<Map<String, Object>> notifications = Await.until("requester told it is done", Await.seconds(30),
                () -> Api.as(support).get("/api/notifications"),
                r -> r.status() == 200 && r.body().contains("AI_ACTION_COMPLETED") && r.body().contains(id)).list("content");
        assertThat(notifications).anySatisfy(n -> assertThat(n).containsEntry("type", "AI_ACTION_COMPLETED"));
    }
}
