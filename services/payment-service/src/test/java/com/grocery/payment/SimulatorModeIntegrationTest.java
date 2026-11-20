package com.grocery.payment;

import com.grocery.common.id.Ids;
import com.grocery.payment.gateway.Signatures;
import com.grocery.payment.gateway.SimulatorGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simulator mode end to end (contract §3.3): the checkout session, {@code POST /api/payments/simulator/orders/{id}/pay}
 * and the same verify / webhook / late-capture / refund code as with Razorpay. Razorpay's fake is never called.
 */
class SimulatorModeIntegrationTest extends AbstractSimulatorIntegrationTest {

    private ResultActions pay(UUID orderId, UUID customerId, String outcome) throws Exception {
        return mvc.perform(post("/api/payments/simulator/orders/{id}/pay", orderId).with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"" + outcome + "\"}"));
    }

    /** What the React app does: pay SUCCESS, then post the signed result to verify. */
    private ResultActions payAndVerify(UUID orderId) throws Exception {
        JsonNode result = body(pay(orderId, customer, "SUCCESS").andExpect(status().isOk()));
        return verifyRequest(customer, result.get("razorpayOrderId").asString(),
                result.get("razorpayPaymentId").asString(), result.get("razorpaySignature").asString());
    }

    @Test
    void checkoutSessionIsASimulatorSession() throws Exception {
        int razorpayCalls = RAZORPAY.requests().size();
        UUID orderId = payableOrder("125.00");

        JsonNode session = checkout(orderId);

        assertThat(session.get("provider").asString()).isEqualTo("SIMULATOR");
        assertThat(session.get("keyId").asString()).isEqualTo("rzp_test_simulator");
        assertThat(session.get("razorpayOrderId").asString()).startsWith("order_");
        assertThat(session.get("amount").asLong()).isEqualTo(12500);
        assertThat(RAZORPAY.requests()).hasSize(razorpayCalls);       // the real (fake) API is never called
    }

    @Test
    void paySuccessThenVerifyCaptures() throws Exception {
        UUID orderId = payableOrder("125.00");
        String rzp = razorpayOrderId(orderId);

        JsonNode result = body(pay(orderId, customer, "SUCCESS").andExpect(status().isOk()));
        assertThat(result.get("razorpayOrderId").asString()).isEqualTo(rzp);
        String paymentId = result.get("razorpayPaymentId").asString();
        assertThat(paymentId).startsWith("pay_");
        assertThat(result.get("razorpaySignature").asString())
                .isEqualTo(Signatures.checkout(rzp, paymentId, SimulatorGateway.KEY_SECRET));
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");     // nothing changes until verify

        verifyRequest(customer, rzp, paymentId, result.get("razorpaySignature").asString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.method").value("upi"))
                .andExpect(jsonPath("$.razorpayPaymentId").value(paymentId));
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");

        // paying an already paid order again
        pay(orderId, customer, "SUCCESS").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_ALREADY_CAPTURED"));
    }

    @Test
    void payFailIsRecordedLikeAPaymentFailedWebhook() throws Exception {
        UUID orderId = payableOrder("20.00");

        JsonNode result = body(pay(orderId, customer, "FAIL").andExpect(status().isOk()));

        assertThat(result.get("razorpaySignature").isNull()).isTrue();
        String declined = result.get("razorpayPaymentId").asString();
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(attemptRows(orderId)).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("razorpay_payment_id", declined).containsEntry("status", "FAILED"));
        JsonNode failed = event(orderId, "PaymentFailed");
        assertThat(failed.get("retryable").asBoolean()).isTrue();
        assertThat(failed.get("errorCode").asString()).isEqualTo("BAD_REQUEST_ERROR");

        // the customer tries again and succeeds
        payAndVerify(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentFailed", "PaymentCaptured");
    }

    /** The smoke test's late capture: cancel the order, then pay in the still-open simulator "tab" and verify. */
    @Test
    void payingACancelledOrderIsALateCaptureRefundedAutomatically() throws Exception {
        UUID orderId = payableOrder("125.00");
        cancelOrder(orderId);
        assertThat(paymentStatus(orderId)).isEqualTo("CANCELLED");

        JsonNode view = body(payAndVerify(orderId).andExpect(status().isOk()));

        assertThat(view.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(view.get("refunds")).singleElement().satisfies(r -> {
            assertThat(r.get("reason").asString()).isEqualTo("LATE_CAPTURE");
            assertThat(r.get("status").asString()).isEqualTo("PROCESSED");
        });
        assertThat(view.get("refundStatus").asString()).isEqualTo("FULL");
        assertThat(event(orderId, "PaymentCaptured").get("late").asBoolean()).isTrue();
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured", "RefundCompleted");
    }

    @Test
    void payingAnExpiredPaymentIsALateCaptureToo() throws Exception {
        UUID orderId = payableOrder("10.00");
        endWindow(orderId);
        expiryPoller.run();                                          // the simulator has no payment for it
        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");

        payAndVerify(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));

        assertThat(refundRows(orderId)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("reason", "LATE_CAPTURE").containsEntry("status", "PROCESSED"));
    }

    @Test
    void adminRefundsWorkInSimulatorMode() throws Exception {
        UUID orderId = payableOrder("80.00");
        payAndVerify(orderId).andExpect(status().isOk());

        mvc.perform(post("/api/admin/refunds").with(as(UUID.randomUUID(), "FINANCE_ADMIN"))
                        .header("Idempotency-Key", "sim-refund-" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":30.00,\"note\":\"Cold food\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "PARTIAL");
    }

    @Test
    void payNeedsTheCallersPaymentWithASimulatorOrder() throws Exception {
        pay(Ids.newId(), customer, "SUCCESS").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_READY"));
        UUID orderId = payableOrder("10.00");
        pay(orderId, otherCustomer, "SUCCESS").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
        // a payment whose window was over before its order was created never got a simulator order
        UUID late = Ids.newId();
        orderListener.on(awaitingPayment(newEventId(), late, customer, "10.00", java.time.Instant.now().minusSeconds(1)));
        pay(late, customer, "SUCCESS").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_READY"));
        mvc.perform(post("/api/payments/simulator/orders/{id}/pay", orderId).with(customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/payments/simulator/orders/{id}/pay", orderId).with(as(customer, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void webhooksCanBePostedByHandWithTheSimulatorSecret() throws Exception {
        UUID orderId = payableOrder("10.00");
        String body = paymentWebhook("payment.failed", "pay_byhand1", razorpayOrderId(orderId), "failed", 1000);

        signedWebhook(body, webhookEventId()).andExpect(status().isOk());

        assertThat(events(orderId, "PaymentFailed")).hasSize(1);
        webhook(body, Signatures.hmacHex(WEBHOOK_SECRET, body.getBytes()), webhookEventId())
                .andExpect(status().isBadRequest());                 // the Razorpay-mode secret is not valid here
    }
}
