package com.grocery.payment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/payments/razorpay/verify} (contract §3.1 "Verify"). */
class VerifyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void goodSignatureAndACapturedPaymentCaptures() throws Exception {
        UUID orderId = payableOrder("125.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 12500);
        Instant before = Instant.now();

        JsonNode view = body(verifySigned(customer, rzp, paymentId).andExpect(status().isOk()));

        assertThat(view.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(view.get("razorpayPaymentId").asString()).isEqualTo(paymentId);
        assertThat(view.get("method").asString()).isEqualTo("upi");
        assertThat(Instant.parse(view.get("capturedAt").asString())).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(view.get("attempts")).singleElement().satisfies(a -> {
            assertThat(a.get("razorpayPaymentId").asString()).isEqualTo(paymentId);
            assertThat(a.get("status").asString()).isEqualTo("CAPTURED");
            assertThat(a.get("method").asString()).isEqualTo("upi");
            assertThat(a.get("errorCode").isNull()).isTrue();
        });
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
        JsonNode captured = event(orderId, "PaymentCaptured");
        assertThat(captured.get("late").asBoolean()).isFalse();
        assertThat(captured.get("razorpayPaymentId").asString()).isEqualTo(paymentId);
        assertThat(captured.get("amount").decimalValue()).isEqualByComparingTo("125.00");
        // asked Razorpay, did not need to capture
        assertThat(RAZORPAY.calls("GET", "/v1/payments/" + paymentId)).isEqualTo(1);
        assertThat(RAZORPAY.calls("POST", "/v1/payments/" + paymentId + "/capture")).isZero();
    }

    @Test
    void anAuthorizedPaymentIsCapturedAtRazorpayFirst() throws Exception {
        UUID orderId = payableOrder("49.99");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "authorized", "card", 4999);

        verifySigned(customer, rzp, paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.method").value("card"));

        assertThat(RAZORPAY.payment(paymentId).get("status")).isEqualTo("captured");
        assertThat(RAZORPAY.requests()).filteredOn(r -> r.path().equals("/v1/payments/" + paymentId + "/capture"))
                .singleElement()
                .satisfies(r -> {
                    JsonNode body = json.readTree(r.body());
                    assertThat(body.get("amount").asLong()).isEqualTo(4999);
                    assertThat(body.get("currency").asString()).isEqualTo("INR");
                });
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
    }

    @Test
    void aBadSignatureIs400AndChangesNothing() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 1000);

        verifyRequest(customer, rzp, paymentId, "00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
        // signed with another secret / for another payment id
        verifyRequest(customer, rzp, paymentId,
                com.grocery.payment.gateway.Signatures.checkout(rzp, paymentId, "wrong_secret"))
                .andExpect(status().isBadRequest());
        verifyRequest(customer, rzp, paymentId,
                com.grocery.payment.gateway.Signatures.checkout(rzp, "pay_other", KEY_SECRET))
                .andExpect(status().isBadRequest());

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        assertThat(RAZORPAY.calls("GET", "/v1/payments/" + paymentId)).isZero();
    }

    @Test
    void aFailedPaymentIs409PaymentFailedWithTheProvidersError() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "failed", "card", 1000);

        verifySigned(customer, rzp, paymentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.razorpayPaymentId").value(paymentId))
                .andExpect(jsonPath("$.providerErrorCode").value("BAD_REQUEST_ERROR"))
                .andExpect(jsonPath("$.errorDescription").value("Payment declined by the bank"));

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");                 // the customer may try again
        assertThat(attemptRows(orderId)).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("status", "FAILED")
                        .containsEntry("error_code", "BAD_REQUEST_ERROR"));
        JsonNode failed = event(orderId, "PaymentFailed");
        assertThat(failed.get("retryable").asBoolean()).isTrue();
        assertThat(failed.get("errorDescription").asString()).isEqualTo("Payment declined by the bank");

        // verifying the same failed payment again: same answer, no second event
        verifySigned(customer, rzp, paymentId).andExpect(status().isConflict());
        assertThat(events(orderId, "PaymentFailed")).hasSize(1);
    }

    @Test
    void verifyIsIdempotent() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 1000);
        JsonNode first = body(verifySigned(customer, rzp, paymentId).andExpect(status().isOk()));

        JsonNode again = body(verifySigned(customer, rzp, paymentId).andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        assertThat(events(orderId, "PaymentCaptured")).hasSize(1);
        assertThat(RAZORPAY.calls("GET", "/v1/payments/" + paymentId)).isEqualTo(1);    // answered from our state
    }

    @Test
    void anotherCustomersOrAnUnknownRazorpayOrderIsNotFound() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 1000);

        verifySigned(otherCustomer, rzp, paymentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
        verifySigned(customer, "order_unknown123", paymentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
        // a payment of a different Razorpay order, correctly signed for this one
        UUID otherOrder = payableOrder("10.00");
        String foreignPayment = RAZORPAY.addPayment(razorpayOrderId(otherOrder), "captured", "upi", 1000);
        verifySigned(customer, rzp, foreignPayment).andExpect(status().isNotFound());

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(paymentStatus(otherOrder)).isEqualTo("PENDING");
    }

    @Test
    void razorpayUnreachableIs503AndVerifyCanBeRepeated() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 1000);
        RAZORPAY.failNext(FakeRazorpay.FETCH_PAYMENT, 503, 1);

        verifySigned(customer, rzp, paymentId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");

        verifySigned(customer, rzp, paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void verifyAfterTheOrderWasCancelledIsALateCaptureRefundedAtOnce() throws Exception {
        UUID orderId = payableOrder("125.00");
        String rzp = razorpayOrderId(orderId);
        cancelOrder(orderId);
        assertThat(paymentStatus(orderId)).isEqualTo("CANCELLED");
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "card", 12500);   // paid in an old Checkout tab

        JsonNode view = body(verifySigned(customer, rzp, paymentId).andExpect(status().isOk()));

        assertThat(view.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(view.get("refundStatus").asString()).isEqualTo("FULL");
        assertThat(view.get("refundedAmount").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(view.get("refunds")).singleElement().satisfies(r -> {
            assertThat(r.get("reason").asString()).isEqualTo("LATE_CAPTURE");
            assertThat(r.get("status").asString()).isEqualTo("PROCESSED");
            assertThat(r.get("amount").decimalValue()).isEqualByComparingTo("125.00");
            assertThat(r.get("razorpayRefundId").asString()).startsWith("rfnd_");
            assertThat(r.get("processedAt").isNull()).isFalse();
        });
        assertThat(paymentRow(orderId).get("late_capture")).isEqualTo(true);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured", "RefundCompleted");
        assertThat(event(orderId, "PaymentCaptured").get("late").asBoolean()).isTrue();
        JsonNode completed = event(orderId, "RefundCompleted");
        assertThat(completed.get("reason").asString()).isEqualTo("LATE_CAPTURE");
        assertThat(completed.get("fullyRefunded").asBoolean()).isTrue();

        // and a later OrderCancelled replay finds nothing left to refund
        cancelOrder(orderId);
        assertThat(refundRows(orderId)).hasSize(1);
    }

    @Test
    void lateCaptureAfterExpiryIsRefundedToo() throws Exception {
        UUID orderId = payableOrder("40.00");
        String rzp = razorpayOrderId(orderId);
        endWindow(orderId);
        expiryPoller.run();
        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 4000);

        verifySigned(customer, rzp, paymentId).andExpect(status().isOk());

        List<Map<String, Object>> refunds = refundRows(orderId);
        assertThat(refunds).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("reason", "LATE_CAPTURE").containsEntry("status", "PROCESSED");
            assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("40.00");
        });
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentExpired", "PaymentCaptured",
                "RefundCompleted");
    }

    @Test
    void invalidBodyIs400ValidationFailed() throws Exception {
        mvc.perform(post("/api/payments/razorpay/verify").with(customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"razorpayOrderId\":\"order_x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
