package com.grocery.ai;

import com.grocery.ai.SseClient.Event;
import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment assistant (docs/api/payment-and-support-agents.md): real HTTP chat, the scripted model, ToolGuard and
 * the fake gateway. It reads the customer's own orders and payments with the customer's token and explains failed,
 * pending, successful and refunded payments from the APIs' data (PaymentGuidance), never from the model's guesses.
 */
class PaymentAssistantIntegrationTest extends AbstractIntegrationTest {

    /** An OrderSummary as {@code GET /api/orders} lists it. */
    private static Map<String, Object> summary(UUID id, String number, String status, String paymentStatus,
                                               Instant dueAt) {
        return Fixtures.map("id", id.toString(), "orderNumber", number, "type", "GROCERY", "status", status,
                "store", Fixtures.map("name", "Fresh Mart"), "itemCount", 2, "total", new BigDecimal("260.00"),
                "currency", "INR", "payment", Fixtures.map("status", paymentStatus,
                        "dueAt", dueAt == null ? null : dueAt.toString()),
                "createdAt", "2026-11-19T08:00:00Z");
    }

    /** An OrderView with the given order and payment status. */
    private static Map<String, Object> order(UUID id, String number, String status, String paymentStatus,
                                             Instant dueAt, Instant paidAt) {
        Map<String, Object> order = Fixtures.order(id, number, status, false, "260.00");
        order.put("payment", Fixtures.map("status", paymentStatus, "dueAt", dueAt == null ? null : dueAt.toString(),
                "paidAt", paidAt == null ? null : paidAt.toString(), "refundedAmount", new BigDecimal("0.00"),
                "lastFailure", null));
        return order;
    }

    private void stubOrders(List<Map<String, Object>> summaries) {
        // The list answers its status filter like order-service (the payment assistant asks for AWAITING_PAYMENT).
        GATEWAY.on("GET", "/api/orders", request -> {
            String status = request.param("status");
            List<Map<String, Object>> content = summaries.stream()
                    .filter(summary -> status == null || status.equals(summary.get("status"))).toList();
            return FakeGateway.Response.json(200, Fixtures.page(content));
        });
    }

    private static JsonNode lastToolResult(TestModels.Switchable model) {
        ToolResponseMessage response = (ToolResponseMessage) model.prompts().getLast().getInstructions().getLast();
        return Fixtures.JSON.readTree(response.getResponses().getFirst().responseData());
    }

    @Test
    void aFailedPaymentIsExplainedWithItsReasonAndTheTimeLeftToTryAgain() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        Instant dueAt = Instant.now().plus(Duration.ofMinutes(10));
        stubOrders(List.of(summary(orderId, "GA-PAY0001", "AWAITING_PAYMENT", "PENDING", dueAt)));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(order(orderId, "GA-PAY0001",
                "AWAITING_PAYMENT", "PENDING", dueAt, null)));
        Map<String, Object> payment = Fixtures.payment(orderId, "GA-PAY0001", "260.00", "0.00", "PENDING");
        payment.put("razorpayOrderId", "order_SECRET123");
        payment.put("attempts", List.of(Fixtures.map("razorpayPaymentId", "pay_SECRET456", "status", "FAILED",
                "method", "card", "errorCode", "BAD_REQUEST_ERROR", "errorDescription",
                "Your card was declined by the bank", "at", Instant.now().minus(Duration.ofMinutes(2)).toString())));
        GATEWAY.json("GET", "/api/payments/orders/" + orderId, 200, Fixtures.json(payment));

        Result result = chat(token, "payment", "payment of GA-PAY0001");

        assertThat(toolEvents(result)).containsExactly("getPaymentStatus:STARTED", "getPaymentStatus:SUCCEEDED");
        Event tool = result.named("tool").getLast();
        assertThat(tool.text("label")).isEqualTo("Checked a payment");
        assertThat(tool.text("summary")).isEqualTo("Payment for GA-PAY0001: last attempt failed, can try again");
        JsonNode links = tool.data().get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("label").asString()).isEqualTo("Pay for order GA-PAY0001");
        assertThat(links.get(0).get("path").asString()).isEqualTo("/orders/" + orderId);
        assertThat(result.answer()).startsWith("Your last payment attempt by card at ")
                .contains(" failed: Your card was declined by the bank. A failed attempt is not charged to your "
                        + "order. You can try again before ")
                .contains("(10 minutes left): open the order in the app and tap Pay now.");
        // read with the customer's own token
        assertThat(GATEWAY.requests("GET", "/api/payments/orders/" + orderId).getFirst().header("Authorization"))
                .isEqualTo("Bearer " + token);
        // the model gets the facts and the guidance, never the provider's ids
        JsonNode remembered = lastToolResult(model);
        assertThat(remembered.get("situation").asString()).isEqualTo("PAYMENT_FAILED_CAN_RETRY");
        assertThat(remembered.get("canPayNow").asBoolean()).isTrue();
        assertThat(remembered.get("payment").get("minutesLeftToPay").asInt()).isEqualTo(10);
        assertThat(remembered.get("payment").get("attempts").get(0).get("failureReason").asString())
                .isEqualTo("Your card was declined by the bank");
        assertThat(remembered.toString()).doesNotContain("SECRET");
        // the live context: the latest order's payment and how many orders wait for payment
        String context = TestModels.systemText(model.prompts().getLast());
        assertThat(context).contains("latest order GA-PAY0001 (₹260.00, from Fresh Mart): order status "
                + "AWAITING_PAYMENT, payment status PENDING, pay by ").contains("orders waiting for payment: 1")
                .contains(", what happened \"Your last payment attempt by card at ")
                .contains("\", next step \"You can try again before ");
    }

    @Test
    void theContextLineTellsTheLatestPaymentsStoryInPlainWords() {
        // Live with qwen3:4b: told only "order status CANCELLED, payment status REFUNDED", the model answered "did my
        // last payment go through?" without a tool and said the payment "was not completed".
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubOrders(List.of(summary(orderId, "GA-PAY0007", "CANCELLED", "REFUNDED", null)));
        Map<String, Object> view = order(orderId, "GA-PAY0007", "CANCELLED", "REFUNDED", null,
                Instant.parse("2026-11-19T15:13:00Z"));
        view.put("cancellation", Fixtures.map("reason", "STORE_REJECTED", "by", "STORE",
                "at", "2026-11-19T15:14:00Z"));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(view));
        Map<String, Object> payment = Fixtures.payment(orderId, "GA-PAY0007", "260.00", "260.00", "CAPTURED");
        payment.put("refunds", List.of(Fixtures.map("amount", new BigDecimal("260.00"), "reason", "ORDER_CANCELLED",
                "status", "PROCESSED", "createdAt", "2026-11-19T15:14:00Z", "processedAt", "2026-11-19T15:14:30Z")));
        GATEWAY.json("GET", "/api/payments/orders/" + orderId, 200, Fixtures.json(payment));

        Result result = chat(token, "payment", "payment of GA-PAY0007");

        String story = "You paid ₹260.00 on 19 Nov, 8:43 pm IST by UPI. ₹260.00 was refunded in full on 19 Nov, "
                + "8:44 pm IST because the order was cancelled (the store could not accept it).";
        assertThat(result.answer()).startsWith(story);
        String context = TestModels.systemText(model.prompts().getLast());
        assertThat(context).contains("latest order GA-PAY0007 (₹260.00, from Fresh Mart): order status CANCELLED, "
                + "payment status REFUNDED, what happened \"" + story + "\", next step \"It went back to the payment "
                + "method you used (UPI); nothing to do.");
        // read with the customer's own token, nothing written
        assertThat(GATEWAY.requests("GET", "/api/payments/orders/" + orderId))
                .allSatisfy(request -> assertThat(request.header("Authorization")).isEqualTo("Bearer " + token));
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
    }

    @Test
    void paidAndRefundedPaymentsAndTheOrdersStillToPay() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        UUID unpaid = UUID.randomUUID();
        UUID paid = UUID.randomUUID();
        UUID refunded = UUID.randomUUID();
        Instant dueAt = Instant.now().plus(Duration.ofMinutes(14));
        stubOrders(List.of(summary(unpaid, "GA-PAY0001", "AWAITING_PAYMENT", "PENDING", dueAt),
                summary(paid, "GA-PAY0002", "PREPARING", "PAID", null),
                summary(refunded, "GA-PAY0003", "CANCELLED", "REFUND_PENDING", null)));
        GATEWAY.json("GET", "/api/orders/" + paid, 200, Fixtures.json(order(paid, "GA-PAY0002", "PREPARING", "PAID",
                null, Instant.parse("2026-11-19T08:10:00Z"))));
        GATEWAY.json("GET", "/api/payments/orders/" + paid, 200, Fixtures.json(Fixtures.payment(paid, "GA-PAY0002",
                "260.00", "0.00", "CAPTURED")));
        GATEWAY.json("GET", "/api/orders/" + refunded, 200, Fixtures.json(order(refunded, "GA-PAY0003", "CANCELLED",
                "REFUND_PENDING", null, Instant.parse("2026-11-19T07:00:00Z"))));
        Map<String, Object> refundedPayment = Fixtures.payment(refunded, "GA-PAY0003", "260.00", "0.00", "CAPTURED");
        refundedPayment.put("refunds", List.of(Fixtures.map("amount", new BigDecimal("260.00"), "reason",
                "ORDER_CANCELLED", "status", "PENDING", "createdAt", "2026-11-19T07:30:00Z")));
        GATEWAY.json("GET", "/api/payments/orders/" + refunded, 200, Fixtures.json(refundedPayment));

        assertThat(chat(token, "payment", "payment of GA-PAY0002").answer()).isEqualTo("You paid ₹260.00 on 19 Nov, "
                + "1:40 pm IST by UPI. The order is being prepared. Nothing to do for the payment.");
        assertThat(chat(token, "payment", "refund for GA-PAY0003").answer()).isEqualTo("You paid ₹260.00 on 19 Nov, "
                + "12:30 pm IST by UPI. A refund of ₹260.00 was started on 19 Nov, 1:00 pm IST because the order was "
                + "cancelled. It is being processed. It goes back to the payment method you used (UPI); nothing to "
                + "do. Banks usually take 5–7 working days to show a refund in your account.");

        Result toPay = chat(token, "payment", "Which orders do I still have to pay?");
        assertThat(toolEvents(toPay)).containsExactly("listMyPayments:STARTED", "listMyPayments:SUCCEEDED");
        assertThat(GATEWAY.requests("GET", "/api/orders").getLast().param("status")).isEqualTo("AWAITING_PAYMENT");
        assertThat(toPay.named("tool").getLast().text("summary")).isEqualTo("1 order waiting for payment");
        assertThat(toPay.answer()).startsWith("1 order: GA-PAY0001 (waiting for payment, pay by ")
                .endsWith(", ₹260.00).");

        Result refunds = chat(token, "payment", "my refunds");
        assertThat(refunds.named("tool").getLast().text("summary")).isEqualTo("1 order with a refund");
        assertThat(refunds.answer()).isEqualTo("1 order: GA-PAY0003 (refund in progress, ₹260.00).");
    }

    @Test
    void ordersWithoutAPaymentYetUnknownOrdersAndAnUnreachablePaymentService() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        UUID checking = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        Instant dueAt = Instant.now().plus(Duration.ofMinutes(5));
        stubOrders(List.of(summary(checking, "GA-PAY0004", "CREATED", "NOT_STARTED", null),
                summary(waiting, "GA-PAY0005", "AWAITING_PAYMENT", "PENDING", dueAt)));
        GATEWAY.json("GET", "/api/orders/" + checking, 200, Fixtures.json(order(checking, "GA-PAY0004", "CREATED",
                "NOT_STARTED", null, null)));
        GATEWAY.on("GET", "/api/payments/orders/" + checking, request -> FakeGateway.Response.problem(404,
                "PAYMENT_NOT_FOUND", "No payment for this order."));
        GATEWAY.json("GET", "/api/orders/" + waiting, 200, Fixtures.json(order(waiting, "GA-PAY0005",
                "AWAITING_PAYMENT", "PENDING", dueAt, null)));
        GATEWAY.on("GET", "/api/payments/orders/" + waiting, request -> FakeGateway.Response.problem(503,
                "SERVICE_UNAVAILABLE", "payment-service is down"));

        // no payment yet: the store is still checking stock
        assertThat(chat(token, "payment", "payment of GA-PAY0004").answer()).isEqualTo("The store is checking that "
                + "everything is in stock. Payment opens as soon as that is done: then open the order in the app and "
                + "tap Pay now.");
        // payment-service down: the answer still comes from the order's own payment status, with a note
        Result waitingResult = chat(token, "payment", "payment of GA-PAY0005");
        assertThat(waitingResult.answer()).startsWith("The order is waiting for your payment of ₹260.00. Pay before ");
        assertThat(lastToolResult(model).get("note").asString()).startsWith("The payment details (attempts and "
                + "refunds) could not be loaded right now");
        // an order that is not the customer's: the API's own refusal, nothing invented
        assertThat(chat(token, "payment", "payment of GA-NOPE0001").answer()).isEqualTo("That didn't work: There is "
                + "no order GA-NOPE0001. (ORDER_NOT_FOUND)");
        // tools of other assistants are not available here
        assertThat(chat(token, "payment", "use tool fileComplaint {\"category\":\"PAYMENT\",\"description\":"
                + "\"I was charged twice\"}").answer()).isEqualTo("I can't do that: The tool 'fileComplaint' is not "
                + "available to the Payment assistant.");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
    }

    @Test
    void cancellingAnUnpaidOrderIsOnlyProposedForTheCustomerToConfirm() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubOrders(List.of(summary(orderId, "GA-PAY0006", "AWAITING_PAYMENT", "PENDING",
                Instant.now().plus(Duration.ofMinutes(9)))));
        Map<String, Object> view = order(orderId, "GA-PAY0006", "AWAITING_PAYMENT", "PENDING",
                Instant.now().plus(Duration.ofMinutes(9)), null);
        view.put("cancellable", true);
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(view));

        Result result = chat(token, "payment", "cancel order GA-PAY0006");

        assertThat(toolEvents(result)).containsExactly("cancelOrder:STARTED", "cancelOrder:APPROVAL_REQUIRED");
        JsonNode approval = result.first("approval").data();
        assertThat(approval.get("action").asString()).isEqualTo("CANCEL_ORDER");
        assertThat(approval.get("agent").asString()).isEqualTo("payment");
        assertThat(approval.get("approverUserId").asString()).isEqualTo(customer.toString());
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
    }

    @Test
    void onlyCustomersHaveThePaymentAssistant() {
        UUID vendor = UUID.randomUUID();

        Result result = chat(TOKENS.token(vendor, "VENDOR"), "payment", "my refunds");

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.problem().get("errorCode").asString()).isEqualTo("AGENT_NOT_ALLOWED");
    }
}
