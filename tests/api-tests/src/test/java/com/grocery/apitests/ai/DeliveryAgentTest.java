package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** The delivery assistant: partner tools for partners, manager tools for managers, reassignment only after approval. */
@Tag("ai")
@Order(23)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI: delivery assistant")
class DeliveryAgentTest {

    Session manager;
    World.Partner partner;

    @BeforeAll
    void setUp() {
        Assistant.requireScriptedModel();
        manager = World.adminWithRole("DELIVERY_MANAGER");
        partner = World.partner();
    }

    @Test
    void partner_and_manager_tools_are_split_by_role() {
        Chat active = Chat.send(partner.session(), "delivery", "active delivery").streamed()
                .tool("getActiveDelivery", "SUCCEEDED");
        assertThat(active.answer()).containsIgnoringCase("no active delivery");
        Chat.send(partner.session(), "delivery", "use tool getMySummary {}").streamed().tool("getMySummary", "SUCCEEDED");
        Chat.send(manager, "delivery", "deliveries").streamed().tool("listDeliveries", "SUCCEEDED");

        Chat.send(partner.session(), "delivery", "deliveries").streamed().tool("listDeliveries", "BLOCKED");
        assertThat(Assistant.audited(partner.session(), "listDeliveries")).containsEntry("reason", "ROLE_NOT_ALLOWED");
        Chat.send(manager, "delivery", "active delivery").streamed().tool("getActiveDelivery", "BLOCKED");
        assertThat(Assistant.audited(manager, "getActiveDelivery")).containsEntry("reason", "ROLE_NOT_ALLOWED");
    }

    @Test
    void a_manager_reassignment_runs_only_after_approval() {
        World.Customer customer = World.newCustomer("Reassign");
        World.Store store = World.storeA();
        World.Order order = World.paidGroceryOrder(customer, store.rice(), 1);
        World.awaitStoreOrder(store.vendor(), "/api/vendor/orders", order.id(), "NEW");
        Api.as(store.vendor()).body(obj("readyInMinutes", 30)).post("/api/vendor/orders/" + order.id() + "/accept").ok();
        Await.until("delivery searching for " + order.number(), Await.seconds(40),
                () -> Api.as(manager).get("/api/admin/deliveries?q=" + order.number()),
                r -> r.status() == 200 && !r.list("content").isEmpty()
                        && "SEARCHING".equals(r.<Map<String, Object>>list("content").getFirst().get("status")));

        Chat chat = Chat.send(manager, "delivery", "reassign " + order.number()).streamed()
                .tool("reassignDelivery", "APPROVAL_REQUIRED");
        assertThat(chat.approval().getString("action")).isEqualTo("REASSIGN_DELIVERY");
        assertThat(chat.approval().getList("approverRoles", String.class)).contains("DELIVERY_MANAGER");
        assertThat(chat.approval().getBoolean("fourEyes")).isFalse();
        Api.as(World.adminWithRole("FINANCE_ADMIN")).body(obj()).post("/api/approvals/" + chat.approvalId() + "/approve")
                .problem(403, "APPROVAL_FORBIDDEN");
        Api.as(World.adminWithRole("FINANCE_ADMIN")).get("/api/approvals/" + chat.approvalId()).ok().has("canDecide", false);
        Api.as(manager).body(obj()).post("/api/approvals/" + chat.approvalId() + "/approve").ok().has("status", "COMPLETED");
    }

}
