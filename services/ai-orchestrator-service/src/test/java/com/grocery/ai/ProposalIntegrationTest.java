package com.grocery.ai;

import com.grocery.ai.SseClient.Event;
import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proposals (contract §4): SENSITIVE tools never run; the server builds the exact call from fresh data, renders the
 * summary, stores canonical JSON with its hash, and returns the same pending request for a duplicate.
 */
class ProposalIntegrationTest extends AbstractIntegrationTest {

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Instant instant(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC)
                : ((Timestamp) value).toInstant();
    }

    @Test
    void checkoutCreatesAPlaceOrderApprovalFromAFreshPreview() throws Exception {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubDefaultCartAndPreview();

        Result result = chat(token, "shopping", "checkout");

        assertThat(result.named("tool")).extracting(e -> e.text("tool") + ":" + e.text("status")).containsExactly(
                "previewCheckout:STARTED", "previewCheckout:SUCCEEDED", "placeOrder:STARTED",
                "placeOrder:APPROVAL_REQUIRED");
        assertThat(result.names()).containsSubsequence("tool", "tool", "approval", "token", "done");
        Event approval = result.first("approval");
        JsonNode view = approval.data();
        UUID approvalId = UUID.fromString(view.get("id").asString());
        assertThat(view.get("action").asString()).isEqualTo("PLACE_ORDER");
        assertThat(view.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.get("agent").asString()).isEqualTo("shopping");
        assertThat(view.get("requestedBy").asString()).isEqualTo(customer.toString());
        assertThat(view.get("onBehalfOf").asString()).isEqualTo(customer.toString());
        assertThat(view.get("approverUserId").asString()).isEqualTo(customer.toString());
        assertThat(view.get("approverRoles")).isEmpty();
        assertThat(view.get("fourEyes").asBoolean()).isFalse();
        assertThat(view.get("canDecide").asBoolean()).isTrue();
        assertThat(view.get("rationale").asString()).isEqualTo("You asked me to check out.");
        assertThat(view.get("conversationId").asString()).isEqualTo(result.first("conversation")
                .text("conversationId"));

        // the summary is the server's rendering of the preview: exact items and total
        JsonNode summary = view.get("summary");
        assertThat(summary.get("title").asString()).isEqualTo("Place order: 3 items from Fresh Mart");
        assertThat(summary.get("amount").decimalValue()).isEqualByComparingTo("285.00");
        assertThat(summary.get("currency").asString()).isEqualTo("INR");
        assertThat(summary.get("lines").valueStream().map(l -> l.get("label").asString() + "=" + l.get("value")
                .asString()).toList()).containsExactly(
                "Store=Fresh Mart",
                "Smart Rice × 2 @ ₹100.00=₹200.00",
                "Toor Dal × 1 @ ₹60.00=₹60.00",
                "Subtotal=₹260.00",
                "Delivery fee=₹25.00",
                "Total=₹285.00",
                "Deliver to=Home, 12 MG Road, Bengaluru");

        // stored: the exact call and the hash of its body
        Map<String, Object> row = approvalRow(approvalId);
        String body = (String) row.get("request_body");
        assertThat(body).isEqualTo("{\"addressId\":\"" + Fixtures.ADDRESS + "\",\"expectedCartUpdatedAt\":\""
                + Fixtures.CART_UPDATED_AT + "\",\"expectedTotal\":285.00}");
        assertThat(row).containsEntry("http_method", "POST").containsEntry("http_path", "/api/orders")
                .containsEntry("body_sha256", sha256(body));
        assertThat(view.get("fingerprint").asString()).isEqualTo(sha256(body).substring(0, 8));
        assertThat(Duration.between(instant(row.get("created_at")), instant(row.get("expires_at"))))
                .isEqualTo(Duration.ofMinutes(15));

        // the model got APPROVAL_REQUIRED, never an order; nothing was posted to orders
        assertThat(GATEWAY.requests("POST", "/api/orders")).isEmpty();
        assertThat(result.answer()).contains("Your order total is ₹285.00.")
                .contains("Nothing happens until you confirm it in the app");
        assertThat(auditRows(customer)).extracting(r -> r.get("tool") + ":" + r.get("status") + ":"
                + r.get("approval_id")).containsExactly("previewCheckout:SUCCEEDED:null",
                "placeOrder:APPROVAL_REQUIRED:" + approvalId);

        // ApprovalRequested on ai.events
        assertThat(eventTypes(approvalId)).containsExactly("ApprovalRequested");
        JsonNode event = event(approvalId, "ApprovalRequested");
        assertThat(event.get("approverUserId").asString()).isEqualTo(customer.toString());
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("285.00");
        assertThat(event.get("title").asString()).isEqualTo("Place order: 3 items from Fresh Mart");

        // the conversation view shows the approval on the assistant message
        UUID conversationId = UUID.fromString(result.first("conversation").text("conversationId"));
        JsonNode conversation = body(mvc.perform(get("/api/ai/conversations/{id}", conversationId)
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(conversation.get("messages").get(1).get("approvals").get(0).get("id").asString())
                .isEqualTo(approvalId.toString());
    }

    @Test
    void aDuplicateProposalReturnsTheSamePendingApproval() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubDefaultCartAndPreview();

        String first = chat(token, "shopping", "checkout").first("approval").text("id");
        String second = chat(token, "shopping", "place my order").first("approval").text("id");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM approval_request WHERE requested_by = UUID_TO_BIN(?)")
                .param(customer.toString()).query(Long.class).single()).isEqualTo(1L);
        assertThat(eventTypes(UUID.fromString(first))).containsExactly("ApprovalRequested");
    }

    @Test
    void aBlockedCheckoutCreatesNoApproval() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/cart/preview", 200, Fixtures.preview(false));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "use tool placeOrder {}");

        assertThat(result.named("approval")).isEmpty();
        assertThat(result.named("tool").getLast().text("status")).isEqualTo("FAILED");
        assertThat(result.answer()).isEqualTo("That didn't work: The order cannot be placed right now: Fresh Mart "
                + "is closed now. (CHECKOUT_BLOCKED)");
    }

    @Test
    void cancelOrderFindsTheCustomersOrderByNumber() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/orders", 200, Fixtures.page(List.of(
                Fixtures.map("id", UUID.randomUUID().toString(), "orderNumber", "GA-OTHER1"),
                Fixtures.map("id", orderId.toString(), "orderNumber", "GA-7K3F9Q2M"))));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-7K3F9Q2M",
                "CONFIRMED", true, "225.00")));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "cancel order ga-7k3f9q2m");

        JsonNode view = result.first("approval").data();
        assertThat(view.get("action").asString()).isEqualTo("CANCEL_ORDER");
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Cancel order GA-7K3F9Q2M");
        Map<String, Object> row = approvalRow(UUID.fromString(view.get("id").asString()));
        assertThat(row).containsEntry("http_path", "/api/orders/" + orderId + "/cancel")
                .containsEntry("request_body", "{\"reason\":\"Requested through the assistant\"}");

        // an order that can no longer be cancelled is refused at once
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-7K3F9Q2M",
                "DELIVERED", false, "225.00")));
        Result refused = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "cancel order GA-7K3F9Q2M");
        assertThat(refused.named("approval")).isEmpty();
        assertThat(refused.answer()).contains("ORDER_NOT_CANCELLABLE");
        assertThat(chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "cancel order GA-NOPE00").answer())
                .contains("ORDER_NOT_FOUND");
    }

    private void stubRefundable(UUID orderId, String number, String amount, String refunded) {
        GATEWAY.json("GET", "/api/admin/orders", 200, Fixtures.page(List.of(Fixtures.map("id", orderId.toString(),
                "orderNumber", number, "total", new BigDecimal(amount)))));
        GATEWAY.json("GET", "/api/admin/payments", 200, Fixtures.page(List.of(
                Fixtures.payment(orderId, number, amount, refunded, "CAPTURED"))));
    }

    @Test
    void refundIsResolvedAgainstThePaymentAndFourEyesAboveTheLimit() throws Exception {
        UUID finance = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(finance, "FINANCE_ADMIN");
        stubRefundable(orderId, "GA-REFUND01", "3000.00", "500.00");

        // explicit amount within the limit
        JsonNode small = chat(token, "admin-bi", "refund 150.50 for GA-REFUND01 because damaged items")
                .first("approval").data();
        assertThat(small.get("action").asString()).isEqualTo("REFUND_PAYMENT");
        assertThat(small.get("approverUserId").isNull()).isTrue();
        assertThat(small.get("approverRoles").valueStream().map(JsonNode::asString).toList())
                .containsExactly("FINANCE_ADMIN", "SUPER_ADMIN");
        assertThat(small.get("fourEyes").asBoolean()).isFalse();
        assertThat(small.get("onBehalfOf").isNull()).isTrue();
        assertThat(small.get("summary").get("lines").valueStream().map(l -> l.get("label").asString() + "="
                + l.get("value").asString()).toList()).containsExactly("Order=GA-REFUND01", "Paid amount=₹3000.00",
                "Already refunded=₹500.00", "Refund amount=₹150.50", "Note=damaged items");
        Map<String, Object> row = approvalRow(UUID.fromString(small.get("id").asString()));
        assertThat(row.get("request_body")).isEqualTo("{\"amount\":150.50,\"note\":\"damaged items\",\"orderId\":\""
                + orderId + "\"}");
        assertThat(Duration.between(instant(row.get("created_at")), instant(row.get("expires_at"))))
                .isEqualTo(Duration.ofHours(24));
        assertThat(GATEWAY.requests("GET", "/api/admin/orders").getFirst().param("q")).isEqualTo("GA-REFUND01");
        assertThat(GATEWAY.requests("GET", "/api/admin/payments").getFirst().param("orderId"))
                .isEqualTo(orderId.toString());

        // "all" = everything still refundable (2500.00 > 2000.00: four-eyes)
        JsonNode all = chat(token, "admin-bi", "refund all for GA-REFUND01 because wrong order")
                .first("approval").data();
        assertThat(all.get("summary").get("amount").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(all.get("fourEyes").asBoolean()).isTrue();
        assertThat(all.get("canDecide").asBoolean()).as("the requester may not decide a four-eyes request")
                .isFalse();

        // more than refundable → INVALID_ARGUMENTS, no approval
        Result tooMuch = chat(token, "admin-bi", "refund 2600 for GA-REFUND01 because goodwill");
        assertThat(tooMuch.named("approval")).isEmpty();
        assertThat(tooMuch.named("tool").getLast().text("status")).isEqualTo("BLOCKED");
        assertThat(auditRows(finance).getLast()).containsEntry("reason", "INVALID_ARGUMENTS");
        assertThat(tooMuch.answer()).isEqualTo("I can't do that: The amount ₹2600.00 is more than what is still "
                + "refundable for GA-REFUND01 (₹2500.00).");
    }

    @Test
    void suspensionsAlwaysNeedASecondAdmin() {
        UUID ops = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        GATEWAY.json("GET", "/api/admin/vendors/" + vendorId, 200, Fixtures.json(Fixtures.map("id",
                vendorId.toString(), "businessName", "Fresh Mart", "status", "APPROVED")));

        JsonNode view = chat(TOKENS.token(ops, "OPS_ADMIN"), "admin-bi", "suspend vendor " + vendorId
                + " because repeated hygiene complaints").first("approval").data();

        assertThat(view.get("action").asString()).isEqualTo("SUSPEND_VENDOR");
        assertThat(view.get("fourEyes").asBoolean()).isTrue();
        assertThat(view.get("summary").get("title").asString()).isEqualTo("Suspend vendor Fresh Mart");
        assertThat(view.get("summary").get("amount").isNull()).isTrue();
        assertThat(view.get("summary").get("currency").isNull()).isTrue();
        assertThat(approvalRow(UUID.fromString(view.get("id").asString())))
                .containsEntry("http_path", "/api/admin/vendors/" + vendorId + "/suspend")
                .containsEntry("request_body", "{\"reason\":\"repeated hygiene complaints\"}");
    }

    @Test
    void anAdminCancellationUsesTheAdminOrderView() {
        UUID support = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        GATEWAY.json("GET", "/api/admin/orders", 200, Fixtures.page(List.of(Fixtures.map("id", orderId.toString(),
                "orderNumber", "GA-ADMIN01"))));
        Map<String, Object> order = Fixtures.order(orderId, "GA-ADMIN01", "PREPARING", false, "410.00");
        order.put("adminCancellable", true);
        order.put("customerId", UUID.randomUUID().toString());
        GATEWAY.json("GET", "/api/admin/orders/" + orderId, 200, Fixtures.json(order));

        JsonNode view = chat(TOKENS.token(support, "SUPPORT"), "admin-bi", "cancel GA-ADMIN01 because "
                + "customer called").first("approval").data();

        assertThat(view.get("action").asString()).isEqualTo("ADMIN_CANCEL_ORDER");
        assertThat(view.get("fourEyes").asBoolean()).isFalse();
        assertThat(view.get("approverRoles").valueStream().map(JsonNode::asString).toList())
                .containsExactly("OPS_ADMIN", "SUPER_ADMIN");
        assertThat(view.get("canDecide").asBoolean()).as("SUPPORT may propose but not decide").isFalse();
        assertThat(approvalRow(UUID.fromString(view.get("id").asString())))
                .containsEntry("http_path", "/api/admin/orders/" + orderId + "/cancel")
                .containsEntry("request_body", "{\"reason\":\"customer called\"}");
    }
}
