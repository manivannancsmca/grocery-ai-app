package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The customer shopping assistant (scripted model): every tool through chat, with the customer's own rights. */
@Tag("ai")
@Order(20)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AI: shopping assistant")
class ShoppingAgentTest {

    World.Customer customer;
    World.Store store;
    String conversation;

    @BeforeAll
    void setUp() {
        Assistant.requireScriptedModel();
        customer = World.newCustomer("Assisted");
        store = World.storeA();
    }

    @Test
    @Order(1)
    void search_and_add_to_cart_run_with_the_customers_rights() {
        Chat search = Chat.send(customer.session(), "shopping", "search " + store.rice().name()).streamed()
                .tool("searchProducts", "SUCCEEDED");
        assertThat(search.answer()).contains(store.rice().name());
        conversation = search.conversationId();
        assertThat(conversation).isNotBlank();

        Chat add = Chat.send(customer.session(), "shopping", "add 2 " + store.rice().name(), conversation).streamed()
                .tool("searchProducts", "SUCCEEDED").tool("addToCart", "SUCCEEDED");
        assertThat(add.conversationId()).isEqualTo(conversation);
        assertThat(add.events("token")).isNotEmpty();
        Res cart = Api.as(customer.session()).get("/api/cart").ok().has("itemCount", 2).has("store.id", store.vendorId());
        assertThat(cart.<Map<String, Object>>list("lines")).singleElement()
                .satisfies(l -> assertThat(l).containsEntry("itemId", store.rice().id()));

        Chat.send(customer.session(), "shopping", "show my cart", conversation).streamed().tool("getCart", "SUCCEEDED");
        Map<String, Object> audit = Assistant.audited(customer.session(), "addToCart");
        assertThat(audit).containsEntry("status", "SUCCEEDED").containsEntry("agent", "shopping")
                .containsEntry("httpStatus", 200);
    }

    @Test
    @Order(2)
    void checkout_proposes_an_order_with_the_exact_items_and_the_preview_total() {
        BigDecimal previewTotal = World.previewTotal(customer);
        Chat chat = Chat.send(customer.session(), "shopping", "checkout", conversation).streamed()
                .tool("previewCheckout", "SUCCEEDED").tool("placeOrder", "APPROVAL_REQUIRED");
        JsonPath approval = chat.approval();
        assertThat(approval.getString("action")).isEqualTo("PLACE_ORDER");
        assertThat(approval.getString("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(approval.getBoolean("canDecide")).isTrue();
        assertThat(approval.getBoolean("fourEyes")).isFalse();
        assertThat(approval.getString("approverUserId")).isEqualTo(customer.userId());
        assertThat(new BigDecimal(approval.getString("summary.amount"))).isEqualByComparingTo(previewTotal);
        assertThat(approval.getList("summary.lines.label", String.class))
                .anySatisfy(label -> assertThat(label).contains(store.rice().name()).contains("2"));
        assertThat(approval.getString("fingerprint")).matches("[0-9a-f]{8}");
        assertThat(Api.as(customer.session()).get("/api/orders").ok().integer("totalElements")).isZero();

        Res conversationView = Api.as(customer.session()).get("/api/ai/conversations/" + conversation).ok()
                .has("agent", "shopping");
        List<Map<String, Object>> messages = conversationView.list("messages");
        assertThat(messages).extracting(m -> m.get("role")).contains("USER", "ASSISTANT");
        assertThat(conversationView.body()).contains(approval.getString("id"));
        Api.as(customer.session()).get("/api/ai/conversations?agent=shopping").ok().page().bodyContains(conversation);

        Api.as(customer.session()).post("/api/approvals/" + approval.getString("id") + "/cancel").ok()
                .has("status", "CANCELLED");
    }

    @Test
    @Order(3)
    void order_status_and_cancel_order_through_the_assistant() {
        World.Order order = World.groceryOrder(customer, store.dal(), 1);
        World.awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        Chat status = Chat.send(customer.session(), "shopping", "status of " + order.number(), conversation).streamed()
                .tool("getOrderStatus", "SUCCEEDED");
        assertThat(status.answer()).contains(order.number()).contains("AWAITING_PAYMENT");
        Chat.send(customer.session(), "shopping", "status of GA-NOTREAL1", conversation).streamed()
                .tool("getOrderStatus", "FAILED");

        Chat cancel = Chat.send(customer.session(), "shopping", "cancel order " + order.number(), conversation).streamed()
                .tool("cancelOrder", "APPROVAL_REQUIRED");
        assertThat(cancel.approval().getString("action")).isEqualTo("CANCEL_ORDER");
        World.awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");      // nothing ran yet
        Api.as(customer.session()).body(Map.of()).post("/api/approvals/" + cancel.approvalId() + "/approve").ok()
                .has("status", "COMPLETED");
        World.awaitOrderStatus(customer, order.id(), "CANCELLED");
    }

    @Test
    @Order(4)
    void request_return_is_proposed_for_the_customers_decision() {
        World.Delivered delivered = World.deliveredOrder();
        String returns = "/api/orders/" + delivered.order().id() + "/returns";
        int before = Api.as(delivered.customer().session()).get(returns).ok().list("$").size();
        Chat chat = Chat.send(delivered.customer().session(), "shopping",
                "return 1 " + delivered.store().dal().name() + " from " + delivered.order().number()
                        + " because the packet was torn").streamed().tool("requestReturn", "APPROVAL_REQUIRED");
        JsonPath approval = chat.approval();
        assertThat(approval.getString("action")).isEqualTo("REQUEST_RETURN");
        assertThat(approval.getString("approverUserId")).isEqualTo(delivered.customer().userId());
        Api.as(delivered.customer().session()).post("/api/approvals/" + approval.getString("id") + "/cancel").ok()
                .has("status", "CANCELLED");
        assertThat(Api.as(delivered.customer().session()).get(returns).ok().list("$")).as("no return was requested")
                .hasSize(before);
    }

    @Test
    @Order(5)
    void anything_else_gets_the_help_answer_without_tools() {
        Chat help = Chat.send(customer.session(), "shopping", "what can you do?").streamed();
        assertThat(help.events("tool")).isEmpty();
        assertThat(help.answer()).containsIgnoringCase("checkout");
        Api.as(customer.session()).delete("/api/ai/conversations/" + help.conversationId()).expect(204);
        Api.as(customer.session()).get("/api/ai/conversations/" + help.conversationId())
                .problem(404, "CONVERSATION_NOT_FOUND");
    }
}
