package com.grocery.payment;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §3: order.events {@code ReturnApproved} → a RETURN refund of the return's {@code refundAmount}, once per
 * return (redelivered or repeated events change nothing), never more than what is still refundable (capped and
 * recorded), and {@code RefundCompleted} / {@code RefundFailed} with the {@code returnId}; the refund poller retries a
 * temporary provider failure as for every refund.
 */
class ReturnRefundIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anApprovedReturnIsRefundedExactlyOnce() throws Exception {
        UUID orderId = paidOrder("245.00");
        UUID returnId = Ids.newId();
        String eventId = newEventId();

        orderListener.on(returnApproved(eventId, orderId, returnId, "50.00"));

        List<Map<String, Object>> refunds = refundRows(orderId);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.getFirst()).containsEntry("reason", "RETURN").containsEntry("status", "PROCESSED");
        assertThat((BigDecimal) refunds.getFirst().get("amount")).isEqualByComparingTo("50.00");
        Map<String, Object> extra = returnRow(orderId, returnId);
        assertThat(extra).containsEntry("return_id", returnId.toString());
        assertThat((BigDecimal) extra.get("requested_amount")).isEqualByComparingTo("50.00");
        assertThat(extra.get("note")).isEqualTo("Return " + returnNumber(returnId));
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "PARTIAL");
        assertThat((BigDecimal) paymentRow(orderId).get("refunded_amount")).isEqualByComparingTo("50.00");

        JsonNode completed = event(orderId, "RefundCompleted");
        assertThat(completed.get("reason").asString()).isEqualTo("RETURN");
        assertThat(completed.get("returnId").asString()).isEqualTo(returnId.toString());
        assertThat(completed.get("amount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(completed.get("totalRefunded").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(completed.get("fullyRefunded").asBoolean()).isFalse();
        assertThat(completed.get("refundId").asString()).isEqualTo(refunds.getFirst().get("id"));

        // the provider got the return in the notes, and the payment view shows it
        Map<String, Object> providerRefund = RAZORPAY.refund((String) refunds.getFirst().get("razorpay_refund_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> notes = (Map<String, Object>) providerRefund.get("notes");
        assertThat(notes).containsEntry("returnId", returnId.toString()).containsEntry("reason", "RETURN");
        JsonNode view = paymentView(orderId);
        assertThat(view.get("refunds").get(0).get("returnId").asString()).isEqualTo(returnId.toString());
        assertThat(view.get("refunds").get(0).get("reason").asString()).isEqualTo("RETURN");

        // the same event again, and another event of the same return: nothing more
        orderListener.on(returnApproved(eventId, orderId, returnId, "50.00"));
        orderListener.on(returnApproved(newEventId(), orderId, returnId, "50.00"));
        assertThat(refundRows(orderId)).hasSize(1);
        assertThat(events(orderId, "RefundCompleted")).hasSize(1);
        assertThat(RAZORPAY.requests()).filteredOn(r -> r.path().endsWith("/refund"))
                .filteredOn(r -> refunds.getFirst().get("id").equals(r.refundIdempotency()))
                .hasSize(1);

        // a second return of the same order is a second refund
        orderListener.on(returnApproved(newEventId(), orderId, Ids.newId(), "195.00"));
        assertThat(refundRows(orderId)).hasSize(2);
        JsonNode last = events(orderId, "RefundCompleted").getLast();
        assertThat(last.get("totalRefunded").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(last.get("fullyRefunded").asBoolean()).isTrue();
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "FULL");
    }

    @Test
    void aReturnIsNeverRefundedBeyondWhatIsLeft() throws Exception {
        UUID orderId = paidOrder("100.00");
        mvc.perform(post("/api/admin/refunds").with(as(financeAdmin, "FINANCE_ADMIN"))
                        .header("Idempotency-Key", "return-cap-" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"amount\":80.00,\"note\":\"Goodwill\"}"))
                .andExpect(status().isCreated());

        // asked 50.00, only 20.00 left: capped, and both recorded
        UUID capped = Ids.newId();
        orderListener.on(returnApproved(newEventId(), orderId, capped, "50.00"));
        Map<String, Object> row = returnRow(orderId, capped);
        assertThat(row).containsEntry("return_id", capped.toString()).containsEntry("status", "PROCESSED");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("20.00");
        assertThat((BigDecimal) row.get("requested_amount")).isEqualByComparingTo("50.00");
        assertThat((String) row.get("note")).contains("capped").contains("20.00").contains("50.00");
        JsonNode completed = events(orderId, "RefundCompleted").getLast();
        assertThat(completed.get("returnId").asString()).isEqualTo(capped.toString());
        assertThat(completed.get("amount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(completed.get("totalRefunded").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(completed.get("fullyRefunded").asBoolean()).isTrue();
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "FULL");

        // nothing left: recorded as FAILED at once, RefundFailed with the return, no provider call
        long providerRefunds = RAZORPAY.requests().stream().filter(r -> r.path().endsWith("/refund")).count();
        UUID nothing = Ids.newId();
        orderListener.on(returnApproved(newEventId(), orderId, nothing, "10.00"));
        Map<String, Object> failed = returnRow(orderId, nothing);
        assertThat(failed).containsEntry("return_id", nothing.toString()).containsEntry("status", "FAILED")
                .containsEntry("failure_code", "NOTHING_REFUNDABLE");
        assertThat((BigDecimal) failed.get("amount")).isEqualByComparingTo("10.00");
        JsonNode refundFailed = event(orderId, "RefundFailed");
        assertThat(refundFailed.get("returnId").asString()).isEqualTo(nothing.toString());
        assertThat(refundFailed.get("reason").asString()).isEqualTo("RETURN");
        assertThat(refundFailed.get("errorCode").asString()).isEqualTo("NOTHING_REFUNDABLE");
        assertThat(RAZORPAY.requests().stream().filter(r -> r.path().endsWith("/refund")).count())
                .isEqualTo(providerRefunds);
        assertThat((BigDecimal) paymentRow(orderId).get("refunded_amount")).isEqualByComparingTo("100.00");
    }

    @Test
    void aProviderRefusalFailsTheReturnRefundWithItsReturnId() throws Exception {
        UUID orderId = paidOrder("60.00");
        UUID returnId = Ids.newId();
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 400, 1);

        orderListener.on(returnApproved(newEventId(), orderId, returnId, "60.00"));

        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "FAILED")
                .containsEntry("failure_code", "BAD_REQUEST_ERROR");
        JsonNode failed = event(orderId, "RefundFailed");
        assertThat(failed.get("returnId").asString()).isEqualTo(returnId.toString());
        assertThat(failed.get("reason").asString()).isEqualTo("RETURN");
        assertThat(failed.get("amount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "NONE");
        // the return keeps its one refund; an admin refunds by hand (POST /api/admin/refunds) if needed
        orderListener.on(returnApproved(newEventId(), orderId, returnId, "60.00"));
        assertThat(refundRows(orderId)).hasSize(1);
    }

    @Test
    void aProviderOutageIsRetriedByTheRefundPoller() throws Exception {
        UUID orderId = paidOrder("30.00");
        UUID returnId = Ids.newId();
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 503, 1);

        orderListener.on(returnApproved(newEventId(), orderId, returnId, "30.00"));
        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "PENDING")
                .containsEntry("provider_attempts", 1);
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "PENDING");

        clock.advance(Duration.ofSeconds(61));
        refundPoller.retryDue();

        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "PROCESSED");
        JsonNode completed = event(orderId, "RefundCompleted");
        assertThat(completed.get("returnId").asString()).isEqualTo(returnId.toString());
        assertThat(completed.get("fullyRefunded").asBoolean()).isTrue();
    }

    @Test
    void brokenEventsAreInvalidAndOrdersWithoutAPaymentAreSkipped() {
        UUID orderId = Ids.newId();
        Map<String, Object> noReturn = payload(orderId, null, "10.00");
        assertThatThrownBy(() -> orderListener.on(orderEvent("ReturnApproved", newEventId(), orderId, noReturn)))
                .isInstanceOf(InvalidEventException.class);
        Map<String, Object> negative = payload(orderId, Ids.newId(), "-1.00");
        assertThatThrownBy(() -> orderListener.on(orderEvent("ReturnApproved", newEventId(), orderId, negative)))
                .isInstanceOf(InvalidEventException.class);
        Map<String, Object> tooPrecise = payload(orderId, Ids.newId(), "1.005");
        assertThatThrownBy(() -> orderListener.on(orderEvent("ReturnApproved", newEventId(), orderId, tooPrecise)))
                .isInstanceOf(InvalidEventException.class);

        // an order this service never saw a payment for: marked processed, nothing else
        String eventId = newEventId();
        orderListener.on(returnApproved(eventId, orderId, Ids.newId(), "10.00"));
        assertThat(paymentCount(orderId)).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? "
                        + "AND consumer = 'payment-service.order-events'")
                .param(eventId).query(Long.class).single()).isOne();

        // an unpaid payment has nothing to refund: recorded as FAILED with RefundFailed
        UUID unpaid = payableOrder("15.00");
        UUID returnId = Ids.newId();
        orderListener.on(returnApproved(newEventId(), unpaid, returnId, "15.00"));
        assertThat(refundRows(unpaid).getFirst()).containsEntry("status", "FAILED")
                .containsEntry("failure_code", "NOTHING_REFUNDABLE");
        assertThat(event(unpaid, "RefundFailed").get("returnId").asString()).isEqualTo(returnId.toString());
        assertThat(paymentStatus(unpaid)).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- helpers

    /** A payment of {@link #customer} captured through verify; returns the order id. */
    private UUID paidOrder(String amount) throws Exception {
        UUID orderId = payableOrder(amount);
        String rzp = razorpayOrderId(orderId);
        long paise = new BigDecimal(amount).movePointRight(2).longValueExact();
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", "card", paise)).andExpect(status().isOk());
        return orderId;
    }

    private static String returnNumber(UUID returnId) {
        return "RT-" + returnId.toString().replace("-", "").substring(24).toUpperCase();
    }

    /** ReturnApproved as order-service publishes it (schemas 1.5.0). */
    private ConsumerRecord<String, String> returnApproved(String eventId, UUID orderId, UUID returnId,
                                                          String refundAmount) {
        return orderEvent("ReturnApproved", eventId, orderId, payload(orderId, returnId, refundAmount));
    }

    private Map<String, Object> payload(UUID orderId, UUID returnId, String refundAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnId", returnId == null ? null : returnId.toString());
        payload.put("returnNumber", returnId == null ? "RT-00000000" : returnNumber(returnId));
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", orderNumber(orderId));
        payload.put("orderType", "GROCERY");
        payload.put("customerId", customer.toString());
        payload.put("storeId", UUID.randomUUID().toString());
        payload.put("items", List.of(Map.of("itemId", UUID.randomUUID().toString(), "name", "Basmati Rice",
                "quantity", 1, "refundAmount", new BigDecimal(refundAmount))));
        payload.put("refundAmount", new BigDecimal(refundAmount));
        payload.put("restock", true);
        payload.put("decidedBy", UUID.randomUUID().toString());
        payload.put("decidedByRole", "STORE");
        payload.put("decidedAt", Instant.now().toString());
        return payload;
    }

    /** The order's refund of one return, with the Phase 12 columns. */
    private Map<String, Object> returnRow(UUID orderId, UUID returnId) {
        return jdbc().sql("""
                        SELECT BIN_TO_UUID(return_id) AS return_id, requested_amount, amount, note, status, failure_code
                        FROM refund WHERE order_id = UUID_TO_BIN(?) AND return_id = UUID_TO_BIN(?)""")
                .param(orderId.toString()).param(returnId.toString()).query().singleRow();
    }
}
