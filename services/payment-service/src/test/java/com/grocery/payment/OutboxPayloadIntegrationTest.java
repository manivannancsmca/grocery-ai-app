package com.grocery.payment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payloads of every payment event exactly as contract §6 lists them, and the outbox routing: topic
 * {@code payment.events}, aggregate type {@code Payment}, key = orderId, version 1.
 */
class OutboxPayloadIntegrationTest extends AbstractIntegrationTest {

    private static List<String> fields(JsonNode payload) {
        return payload.properties().stream().map(Map.Entry::getKey).toList();
    }

    private void assertRouting(UUID orderId) {
        List<Map<String, Object>> rows = jdbc().sql("""
                        SELECT topic, aggregate_type, aggregate_id, event_version, correlation_id FROM outbox_event
                        WHERE aggregate_id = ?""")
                .param(orderId.toString()).query().listOfRows();
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(row)
                .containsEntry("topic", "payment.events")
                .containsEntry("aggregate_type", "Payment")
                .containsEntry("aggregate_id", orderId.toString())
                .containsEntry("event_version", 1));
    }

    @Test
    void everyPaymentEventPayload() throws Exception {
        UUID orderId = payableOrder("125.00");
        String rzp = razorpayOrderId(orderId);
        String paymentRowId = (String) paymentRow(orderId).get("id");

        JsonNode initiated = event(orderId, "PaymentInitiated");
        assertThat(fields(initiated)).containsExactlyInAnyOrder("orderId", "paymentId", "razorpayOrderId", "amount",
                "currency", "expiresAt");
        assertThat(initiated.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(initiated.get("paymentId").asString()).isEqualTo(paymentRowId);
        assertThat(initiated.get("amount").isNumber()).isTrue();
        assertThat(initiated.get("amount").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(initiated.get("currency").asString()).isEqualTo("INR");
        Instant.parse(initiated.get("expiresAt").asString());

        String declined = "pay_decl" + UUID.randomUUID().toString().substring(0, 6);
        signedWebhook(paymentWebhook("payment.failed", declined, rzp, "failed", 12500), webhookEventId())
                .andExpect(status().isOk());
        JsonNode failed = event(orderId, "PaymentFailed");
        assertThat(fields(failed)).containsExactlyInAnyOrder("orderId", "paymentId", "razorpayPaymentId", "errorCode",
                "errorDescription", "retryable");
        assertThat(failed.get("razorpayPaymentId").asString()).isEqualTo(declined);
        assertThat(failed.get("retryable").isBoolean()).isTrue();

        String paid = RAZORPAY.addPayment(rzp, "captured", "card", 12500);
        verifySigned(customer, rzp, paid).andExpect(status().isOk());
        JsonNode captured = event(orderId, "PaymentCaptured");
        assertThat(fields(captured)).containsExactlyInAnyOrder("orderId", "paymentId", "razorpayPaymentId", "amount",
                "method", "capturedAt", "late");
        assertThat(captured.get("method").asString()).isEqualTo("card");
        assertThat(captured.get("late").isBoolean()).isTrue();
        Instant.parse(captured.get("capturedAt").asString());

        mvc.perform(post("/api/admin/refunds").with(as(financeAdmin, "FINANCE_ADMIN"))
                        .header("Idempotency-Key", "payload-" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":25.50,\"note\":\"Damaged\"}"))
                .andExpect(status().isCreated());
        JsonNode completed = event(orderId, "RefundCompleted");
        assertThat(fields(completed)).containsExactlyInAnyOrder("orderId", "paymentId", "refundId", "amount", "reason",
                "totalRefunded", "fullyRefunded", "returnId");                                 // + Phase 12
        assertThat(completed.get("returnId").isNull()).isTrue();                            // not a return refund
        assertThat(completed.get("amount").decimalValue()).isEqualByComparingTo("25.50");
        assertThat(completed.get("totalRefunded").decimalValue()).isEqualByComparingTo("25.50");
        assertThat(completed.get("fullyRefunded").asBoolean()).isFalse();
        assertThat(completed.get("reason").asString()).isEqualTo("ADMIN");
        assertThat(completed.get("refundId").asString()).isEqualTo(refundRows(orderId).getFirst().get("id"));

        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 400, 1);
        mvc.perform(post("/api/admin/refunds").with(as(financeAdmin, "FINANCE_ADMIN"))
                        .header("Idempotency-Key", "payload-2-" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":10.00,\"note\":\"Again\"}"))
                .andExpect(status().isCreated());
        JsonNode refundFailed = event(orderId, "RefundFailed");
        assertThat(fields(refundFailed)).containsExactlyInAnyOrder("orderId", "paymentId", "refundId", "amount",
                "reason", "errorCode", "returnId");                                            // + Phase 12
        assertThat(refundFailed.get("returnId").isNull()).isTrue();
        assertThat(refundFailed.get("errorCode").asString()).isEqualTo("BAD_REQUEST_ERROR");

        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentFailed", "PaymentCaptured",
                "RefundCompleted", "RefundFailed");
        assertRouting(orderId);

        UUID unpaid = payableOrder("10.00");
        endWindow(unpaid);
        expiryPoller.run();
        JsonNode expired = event(unpaid, "PaymentExpired");
        assertThat(fields(expired)).containsExactlyInAnyOrder("orderId", "paymentId");
        assertRouting(unpaid);
    }
}
