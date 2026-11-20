package com.grocery.payment;

import com.grocery.payment.service.PaymentReconciler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10 §3 acceptance in simulator mode (what the smoke test does against Docker Compose): the customer pays in
 * "Checkout" ({@code POST /api/payments/simulator/orders/{id}/pay} SUCCESS — nothing is recorded on our side) and the
 * app never calls verify; reconciliation finds the capture in the simulator's state and captures the payment, so the
 * order is confirmed ({@code PaymentCaptured}) and the summary lists the order number.
 */
class ReconciliationSimulatorIntegrationTest extends AbstractSimulatorIntegrationTest {

    private static final Duration LATER = Duration.ofMinutes(3);

    @Autowired
    private PaymentReconciler reconciler;

    @BeforeEach
    void drainOlderPendingPayments() {
        runLater();
    }

    private PaymentReconciler.Run runLater() {
        clock.advance(LATER);
        try {
            return reconciler.run();
        } finally {
            clock.reset();
        }
    }

    private ResultActions pay(UUID orderId, String outcome) throws Exception {
        return mvc.perform(post("/api/payments/simulator/orders/{id}/pay", orderId).with(customer(customer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"" + outcome + "\"}"));
    }

    private List<JsonNode> recoveriesOf(UUID orderId) throws Exception {
        JsonNode summary = body(mvc.perform(get("/api/admin/payments/reconciliation")
                .with(as(UUID.randomUUID(), "OPS_ADMIN"))).andExpect(status().isOk()));
        return summary.get("lastRecovered").valueStream()
                .filter(r -> r.get("orderId").asString().equals(orderId.toString())).toList();
    }

    @Test
    void aSimulatedPaymentNobodyVerifiedIsCapturedByReconciliation() throws Exception {
        UUID orderId = payableOrder("359.00");
        JsonNode paid = body(pay(orderId, "SUCCESS").andExpect(status().isOk()));
        String razorpayPaymentId = paid.get("razorpayPaymentId").asString();
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");                 // the app never calls verify

        PaymentReconciler.Run run = runLater();

        assertThat(run.recovered()).isGreaterThanOrEqualTo(1);
        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED")
                .containsEntry("razorpay_payment_id", razorpayPaymentId).containsEntry("late_capture", false);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
        JsonNode captured = event(orderId, "PaymentCaptured");                    // order-service confirms the order
        assertThat(captured.get("late").asBoolean()).isFalse();
        assertThat(captured.get("orderId").asString()).isEqualTo(orderId.toString());
        List<JsonNode> recoveries = recoveriesOf(orderId);
        assertThat(recoveries).singleElement().satisfies(recovery -> {
            assertThat(recovery.get("orderNumber").asString()).isEqualTo(orderNumber(orderId));
            assertThat(recovery.get("outcome").asString()).isEqualTo("CAPTURED");
        });

        // the customer's late verify call changes nothing
        verifyRequest(customer, paid.get("razorpayOrderId").asString(), razorpayPaymentId,
                paid.get("razorpaySignature").asString()).andExpect(status().isOk());
        assertThat(events(orderId, "PaymentCaptured")).hasSize(1);
    }

    @Test
    void aSimulatedDeclineIsAlreadyRecordedAndNotRecoveredAgain() throws Exception {
        UUID orderId = payableOrder("99.00");
        pay(orderId, "FAIL").andExpect(status().isOk());                          // recorded at once
        assertThat(events(orderId, "PaymentFailed")).hasSize(1);

        runLater();

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(events(orderId, "PaymentFailed")).hasSize(1);
        assertThat(recoveriesOf(orderId)).isEmpty();
    }
}
