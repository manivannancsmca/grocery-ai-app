package com.grocery.payment;

import com.grocery.payment.gateway.Signatures;
import com.grocery.payment.repository.WebhookEventStore;
import com.grocery.payment.repository.WebhookEventStore.StoredEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/payments/razorpay/webhook} (contract §3.2): public, signed, de-duplicated, source of truth. */
class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookEventStore store;

    private long storedEvents(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM webhook_event WHERE event_id = ?").param(eventId)
                .query(Long.class).single();
    }

    @Test
    void wrongOrMissingSignatureIs400AndNothingIsStored() throws Exception {
        UUID orderId = payableOrder("10.00");
        String body = paymentWebhook("payment.captured", "pay_forged1", razorpayOrderId(orderId), "captured", 1000);
        String eventId = webhookEventId();

        webhook(body, "00", eventId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
        webhook(body, null, eventId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
        webhook(body, Signatures.hmacHex(KEY_SECRET, body.getBytes(StandardCharsets.UTF_8)), eventId)
                .andExpect(status().isBadRequest());                         // signed with the key secret, not the webhook secret
        // the signature covers the raw bytes: the same JSON re-formatted no longer matches
        String signature = Signatures.hmacHex(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
        webhook(body.replace(",", ", "), signature, eventId).andExpect(status().isBadRequest());

        assertThat(storedEvents(eventId)).isZero();
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
    }

    @Test
    void paymentCapturedCapturesAndARepeatedEventIdIsIgnored() throws Exception {
        UUID orderId = payableOrder("125.00");
        String rzp = razorpayOrderId(orderId);
        String body = paymentWebhook("payment.captured", "pay_wh" + UUID.randomUUID().toString().substring(0, 8), rzp,
                "captured", 12500);
        String eventId = webhookEventId();

        signedWebhook(body, eventId).andExpect(status().isOk());
        signedWebhook(body, eventId).andExpect(status().isOk());                  // Razorpay retries: same event id

        assertThat(paymentStatus(orderId)).isEqualTo("CAPTURED");
        assertThat(paymentRow(orderId).get("method")).isEqualTo("upi");
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
        assertThat(storedEvents(eventId)).isEqualTo(1);
        assertThat(store.find(eventId)).get().extracting(StoredEvent::eventType, StoredEvent::processed)
                .containsExactly("payment.captured", true);

        // the same capture reported again under a NEW event id changes nothing either (unique payment id)
        signedWebhook(body, webhookEventId()).andExpect(status().isOk());
        assertThat(events(orderId, "PaymentCaptured")).hasSize(1);
        // and the browser's verify afterwards just returns the captured payment
        String paymentId = json.readTree(body).at("/payload/payment/entity/id").asString();
        verifySigned(customer, rzp, paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertThat(events(orderId, "PaymentCaptured")).hasSize(1);
    }

    @Test
    void paymentFailedRecordsADeclinedAttemptOnce() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = "pay_decl" + UUID.randomUUID().toString().substring(0, 8);
        String body = paymentWebhook("payment.failed", paymentId, rzp, "failed", 1000);
        String eventId = webhookEventId();

        for (int i = 0; i < 3; i++) {
            signedWebhook(body, eventId).andExpect(status().isOk());
        }

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(attemptRows(orderId)).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("razorpay_payment_id", paymentId)
                        .containsEntry("status", "FAILED"));
        JsonNode failed = event(orderId, "PaymentFailed");
        assertThat(failed.get("razorpayPaymentId").asString()).isEqualTo(paymentId);
        assertThat(failed.get("errorCode").asString()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(failed.get("retryable").asBoolean()).isTrue();
        JsonNode attempt = paymentView(orderId).get("attempts").get(0);
        assertThat(attempt.get("errorDescription").asString()).isEqualTo("Payment declined by the bank");
    }

    @Test
    void aDeclineAfterTheWindowIsNotRetryable() throws Exception {
        UUID orderId = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        endWindow(orderId);
        signedWebhook(paymentWebhook("payment.failed", "pay_late" + UUID.randomUUID().toString().substring(0, 6), rzp,
                "failed", 1000), webhookEventId()).andExpect(status().isOk());
        assertThat(event(orderId, "PaymentFailed").get("retryable").asBoolean()).isFalse();
    }

    @Test
    void paymentAuthorizedIsCapturedAtRazorpay() throws Exception {
        UUID orderId = payableOrder("30.00");
        String rzp = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "authorized", "netbanking", 3000);

        signedWebhook(paymentWebhook("payment.authorized", paymentId, rzp, "authorized", 3000), webhookEventId())
                .andExpect(status().isOk());

        assertThat(RAZORPAY.calls("POST", "/v1/payments/" + paymentId + "/capture")).isEqualTo(1);
        assertThat(paymentStatus(orderId)).isEqualTo("CAPTURED");
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
        // Razorpay's payment.captured follows: nothing more happens
        signedWebhook(paymentWebhook("payment.captured", paymentId, rzp, "captured", 3000), webhookEventId())
                .andExpect(status().isOk());
        assertThat(events(orderId, "PaymentCaptured")).hasSize(1);
        assertThat(attemptRows(orderId)).singleElement().satisfies(a -> assertThat(a).containsEntry("status", "CAPTURED"));
    }

    @Test
    void capturedWebhookAfterCancellationIsALateCaptureRefundedAfterCommit() throws Exception {
        UUID orderId = payableOrder("55.00");
        String rzp = razorpayOrderId(orderId);
        cancelOrder(orderId);
        String paymentId = RAZORPAY.addPayment(rzp, "captured", "upi", 5500);

        signedWebhook(paymentWebhook("payment.captured", paymentId, rzp, "captured", 5500), webhookEventId())
                .andExpect(status().isOk());

        assertThat(event(orderId, "PaymentCaptured").get("late").asBoolean()).isTrue();
        assertThat(refundRows(orderId)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("reason", "LATE_CAPTURE").containsEntry("status", "PROCESSED"));
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured", "RefundCompleted");
    }

    @Test
    void refundWebhooksFinishPendingRefunds() throws Exception {
        // two paid orders whose refunds Razorpay accepts but does not process at once (outside test mode)
        UUID first = paidOrder("20.00");
        UUID second = paidOrder("30.00");
        RAZORPAY.refundStatus = "pending";
        cancelOrder(first);
        cancelOrder(second);
        Map<String, Object> pendingOne = refundRows(first).getFirst();
        Map<String, Object> pendingTwo = refundRows(second).getFirst();
        assertThat(pendingOne).containsEntry("status", "PENDING");
        String refundOne = (String) pendingOne.get("razorpay_refund_id");
        String refundTwo = (String) pendingTwo.get("razorpay_refund_id");
        assertThat(refundOne).startsWith("rfnd_");
        String payOne = (String) paymentRow(first).get("razorpay_payment_id");
        String payTwo = (String) paymentRow(second).get("razorpay_payment_id");

        signedWebhook(refundWebhook("refund.processed", refundOne, payOne, 2000, "processed", null), webhookEventId())
                .andExpect(status().isOk());
        // matched by our id in notes.refundId (e.g. our create call timed out and never learnt Razorpay's id)
        jdbc().sql("UPDATE refund SET razorpay_refund_id = NULL WHERE id = UUID_TO_BIN(?)")
                .param(pendingTwo.get("id")).update();
        signedWebhook(refundWebhook("refund.failed", refundTwo, payTwo, 3000, "failed",
                UUID.fromString((String) pendingTwo.get("id"))), webhookEventId()).andExpect(status().isOk());

        assertThat(refundRows(first).getFirst()).containsEntry("status", "PROCESSED");
        assertThat(paymentRow(first)).containsEntry("refund_status", "FULL");
        JsonNode completed = event(first, "RefundCompleted");
        assertThat(completed.get("totalRefunded").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(completed.get("fullyRefunded").asBoolean()).isTrue();

        assertThat(refundRows(second).getFirst()).containsEntry("status", "FAILED")
                .containsEntry("razorpay_refund_id", refundTwo).containsEntry("failure_code", "REFUND_FAILED");
        assertThat(paymentRow(second)).containsEntry("refund_status", "NONE");
        JsonNode refundFailed = event(second, "RefundFailed");
        assertThat(refundFailed.get("reason").asString()).isEqualTo("ORDER_CANCELLED");
        assertThat(refundFailed.get("errorCode").asString()).isEqualTo("REFUND_FAILED");

        // a late repeat (new event id) of a finished refund changes nothing
        signedWebhook(refundWebhook("refund.processed", refundOne, payOne, 2000, "processed", null), webhookEventId())
                .andExpect(status().isOk());
        assertThat(events(first, "RefundCompleted")).hasSize(1);
    }

    @Test
    void unknownEventsAndUnknownOrdersAreStoredAndIgnored() throws Exception {
        String orderPaid = "{\"entity\":\"event\",\"event\":\"order.paid\",\"payload\":{}}";
        String eventId = webhookEventId();
        signedWebhook(orderPaid, eventId).andExpect(status().isOk());
        assertThat(store.find(eventId)).get().extracting(StoredEvent::eventType, StoredEvent::processed)
                .containsExactly("order.paid", false);
        assertThat(jdbc().sql("SELECT payload FROM webhook_event WHERE event_id = ?").param(eventId)
                .query(String.class).single()).isEqualTo(orderPaid);

        String unknownOrder = paymentWebhook("payment.captured", "pay_x1", "order_notours1", "captured", 100);
        String unknownId = webhookEventId();
        signedWebhook(unknownOrder, unknownId).andExpect(status().isOk());
        assertThat(store.find(unknownId)).get().satisfies(e -> {
            assertThat(e.processed()).isFalse();
            assertThat(e.outcome()).contains("unknown Razorpay order");
        });

        String unknownRefund = refundWebhook("refund.processed", "rfnd_notours", "pay_x1", 100, "processed", null);
        String refundEventId = webhookEventId();
        signedWebhook(unknownRefund, refundEventId).andExpect(status().isOk());
        assertThat(store.find(refundEventId)).get().extracting(StoredEvent::processed).isEqualTo(false);

        // signed but not JSON: stored as unreadable
        String garbage = "not json";
        String garbageId = webhookEventId();
        signedWebhook(garbage, garbageId).andExpect(status().isOk());
        assertThat(store.find(garbageId)).get().extracting(StoredEvent::eventType).isEqualTo("unreadable");

        // no event id header: the body's hash stands in, so an identical redelivery is still recognised
        signedWebhook(orderPaid.replace("order.paid", "invoice.paid"), null).andExpect(status().isOk());
        signedWebhook(orderPaid.replace("order.paid", "invoice.paid"), null).andExpect(status().isOk());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM webhook_event WHERE event_type = 'invoice.paid' AND event_id LIKE 'sha256:%'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theWebhookIsPublicButNothingElseIs() throws Exception {
        signedWebhook("{\"event\":\"ping\"}", webhookEventId()).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/payments"))
                .andExpect(status().isUnauthorized());
    }

    /** A payment of {@link #customer} captured through verify. */
    private UUID paidOrder(String amount) throws Exception {
        UUID orderId = payableOrder(amount);
        String rzp = razorpayOrderId(orderId);
        long paise = new java.math.BigDecimal(amount).movePointRight(2).longValueExact();
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", "upi", paise)).andExpect(status().isOk());
        return orderId;
    }
}
