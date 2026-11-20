package com.grocery.payment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/admin/refunds}, the refund poller and the admin payment monitor (contract §4). */
class AdminRefundIntegrationTest extends AbstractIntegrationTest {

    private ResultActions refund(UUID adminId, String role, String key, String body) throws Exception {
        var request = post("/api/admin/refunds").with(as(adminId, role))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private ResultActions refund(String key, String body) throws Exception {
        return refund(financeAdmin, "FINANCE_ADMIN", key, body);
    }

    private static String key() {
        return "refund-" + UUID.randomUUID();
    }

    private static String body(UUID orderId, String amount, String note) {
        return "{\"orderId\":\"" + orderId + "\"" + (amount == null ? "" : ",\"amount\":" + amount)
                + ",\"note\":\"" + note + "\"}";
    }

    /** A payment of {@link #customer} captured through verify; returns the order id. */
    private UUID paidOrder(String amount) throws Exception {
        UUID orderId = payableOrder(amount);
        String rzp = razorpayOrderId(orderId);
        long paise = new BigDecimal(amount).movePointRight(2).longValueExact();
        verifySigned(customer, rzp, RAZORPAY.addPayment(rzp, "captured", "card", paise)).andExpect(status().isOk());
        return orderId;
    }

    @Test
    void partialThenFullRefund() throws Exception {
        UUID orderId = paidOrder("125.00");

        JsonNode partial = body(refund(key(), body(orderId, "50.00", "Damaged item"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));
        assertThat(partial.get("reason").asString()).isEqualTo("ADMIN");
        assertThat(partial.get("status").asString()).isEqualTo("PROCESSED");
        assertThat(partial.get("amount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(partial.get("note").asString()).isEqualTo("Damaged item");
        assertThat(partial.get("requestedBy").asString()).isEqualTo(financeAdmin.toString());
        assertThat(partial.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(partial.get("razorpayRefundId").asString()).startsWith("rfnd_");
        assertThat(partial.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("id", "paymentId",
                "orderId", "amount", "reason", "note", "status", "razorpayRefundId", "failureReason", "requestedBy",
                "returnId", "createdAt", "processedAt");                                       // returnId: Phase 12
        assertThat(partial.get("returnId").isNull()).isTrue();
        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED").containsEntry("refund_status", "PARTIAL");

        // amount absent = everything not refunded yet
        JsonNode rest = body(refund(key(), body(orderId, null, "Order not delivered")).andExpect(status().isCreated()));
        assertThat(rest.get("amount").decimalValue()).isEqualByComparingTo("75.00");

        JsonNode view = paymentView(orderId);
        assertThat(view.get("refundStatus").asString()).isEqualTo("FULL");
        assertThat(view.get("refundedAmount").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(view.get("refunds")).hasSize(2);
        List<JsonNode> completed = events(orderId, "RefundCompleted");
        assertThat(completed).hasSize(2);
        assertThat(completed.get(0).get("totalRefunded").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(completed.get(0).get("fullyRefunded").asBoolean()).isFalse();
        assertThat(completed.get(1).get("totalRefunded").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(completed.get(1).get("fullyRefunded").asBoolean()).isTrue();

        refund(key(), body(orderId, "0.01", "Nothing left")).andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("REFUND_EXCEEDS_CAPTURED"))
                .andExpect(jsonPath("$.refundable").value(0.0));
    }

    @Test
    void idempotencyKeyReplayAndReuse() throws Exception {
        UUID orderId = paidOrder("100.00");
        String key = key();
        JsonNode first = body(refund(key, body(orderId, "10.00", "Late delivery")).andExpect(status().isCreated()));

        JsonNode replay = body(refund(key, body(orderId, "10", "Late delivery"))         // same amount, other scale
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(refundRows(orderId)).hasSize(1);
        assertThat(events(orderId, "RefundCompleted")).hasSize(1);

        refund(key, body(orderId, "20.00", "Late delivery"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        // keys are per admin: another admin may use the same key for its own refund
        refund(UUID.randomUUID(), "SUPER_ADMIN", key, body(orderId, "20.00", "Late delivery"))
                .andExpect(status().isCreated());

        refund(null, body(orderId, "1.00", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
        refund("bad key!", body(orderId, "1.00", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void rolesAndValidation() throws Exception {
        UUID orderId = paidOrder("20.00");
        refund(UUID.randomUUID(), "SUPPORT", key(), body(orderId, "1.00", "x")).andExpect(status().isForbidden());
        refund(UUID.randomUUID(), "OPS_ADMIN", key(), body(orderId, "1.00", "x")).andExpect(status().isForbidden());
        refund(customer, "CUSTOMER", key(), body(orderId, "1.00", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/refunds").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        refund(key(), "{\"orderId\":\"" + orderId + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        refund(key(), body(orderId, "-5", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        refund(key(), body(orderId, "1.234", "x")).andExpect(status().isBadRequest());
        refund(key(), body(orderId, "1.00", "n".repeat(301))).andExpect(status().isBadRequest());

        refund(UUID.randomUUID(), "SUPER_ADMIN", key(), body(orderId, "1.00", "Goodwill")).andExpect(status().isCreated());
    }

    @Test
    void notCapturedUnknownAndTooMuch() throws Exception {
        UUID pending = payableOrder("10.00");
        refund(key(), body(pending, "1.00", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_CAPTURED"));
        refund(key(), body(UUID.randomUUID(), "1.00", "x")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));

        UUID paid = paidOrder("40.00");
        String key = key();
        refund(key, body(paid, "40.01", "x")).andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("REFUND_EXCEEDS_CAPTURED"))
                .andExpect(jsonPath("$.refundable").value(40.0));
        // nothing was stored with the key: the same key works for a valid request
        refund(key, body(paid, "40.00", "x")).andExpect(status().isCreated());
        assertThat(refundRows(paid)).hasSize(1);
    }

    @Test
    void aProviderRefusalIsFailedWithRefundFailedAndTheAmountIsRefundableAgain() throws Exception {
        UUID orderId = paidOrder("60.00");
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 400, 1);

        JsonNode failed = body(refund(key(), body(orderId, "60.00", "Customer complaint"))
                .andExpect(status().isCreated()));

        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failureReason").asString()).contains("refuse");
        assertThat(failed.get("processedAt").isNull()).isTrue();
        JsonNode refundFailed = event(orderId, "RefundFailed");
        assertThat(refundFailed.get("errorCode").asString()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(refundFailed.get("reason").asString()).isEqualTo("ADMIN");
        assertThat(refundFailed.get("amount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "NONE");

        // the admin tries again with a new key
        refund(key(), body(orderId, null, "Customer complaint")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.amount").value(60.0));
    }

    @Test
    void aTemporaryProviderFailureStaysPendingAndThePollerRetriesWithBackoff() throws Exception {
        UUID orderId = paidOrder("25.00");
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 503, 2);

        JsonNode pending = body(refund(key(), body(orderId, "25.00", "Wrong item")).andExpect(status().isCreated()));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "PENDING");
        // pending refunds count against what is left
        refund(key(), body(orderId, "0.01", "x")).andExpect(status().is(422));

        refundPoller.retryDue();                                       // not due yet (1 min backoff)
        assertThat(refundRows(orderId).getFirst()).containsEntry("provider_attempts", 1);

        clock.advance(Duration.ofSeconds(61));
        refundPoller.retryDue();                                       // second outage → 2 min backoff
        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "PENDING")
                .containsEntry("provider_attempts", 2);
        clock.advance(Duration.ofSeconds(61));
        refundPoller.retryDue();
        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "PENDING");

        clock.advance(Duration.ofSeconds(60));
        refundPoller.retryDue();
        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "PROCESSED");
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "FULL");
        assertThat(events(orderId, "RefundCompleted")).hasSize(1);
        // every call carried the same idempotency key (our refund id): Razorpay refunds once
        assertThat(RAZORPAY.requests()).filteredOn(r -> r.path().endsWith("/refund"))
                .filteredOn(r -> pending.get("id").asString().equals(r.refundIdempotency()))
                .hasSize(3);
    }

    @Test
    void thePollerRechecksRefundsRazorpayHasNotReportedOn() throws Exception {
        UUID orderId = paidOrder("12.00");
        RAZORPAY.refundStatus = "pending";
        JsonNode pending = body(refund(key(), body(orderId, null, "x")).andExpect(status().isCreated()));
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        String razorpayRefundId = pending.get("razorpayRefundId").asString();
        RAZORPAY.setRefundStatus(razorpayRefundId, "processed");      // the webhook got lost

        clock.advance(Duration.ofSeconds(61));
        refundPoller.retryDue();

        assertThat(refundRows(orderId).getFirst()).containsEntry("status", "PROCESSED");
        assertThat(RAZORPAY.calls("GET", "/v1/refunds/" + razorpayRefundId)).isEqualTo(1);
    }

    @Test
    void adminPaymentMonitor() throws Exception {
        UUID paid = paidOrder("33.00");
        refund(key(), body(paid, "3.00", "x")).andExpect(status().isCreated());
        UUID open = payableOrder("44.00");

        JsonNode page = body(mvc.perform(get("/api/admin/payments").param("orderId", paid.toString())
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("orderId").asString()).isEqualTo(paid.toString());
        assertThat(row.has("attempts")).isFalse();                       // list rows come without attempts
        assertThat(row.get("refunds")).hasSize(1);
        assertThat(row.get("refundStatus").asString()).isEqualTo("PARTIAL");

        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        JsonNode partial = body(mvc.perform(get("/api/admin/payments").param("refundStatus", "PARTIAL")
                .param("from", today).param("to", today).param("size", "100")
                .with(as(UUID.randomUUID(), "DELIVERY_MANAGER"))).andExpect(status().isOk()));
        assertThat(partial.get("content").valueStream().map(p -> p.get("orderId").asString())).contains(paid.toString())
                .doesNotContain(open.toString());
        JsonNode pendingOnes = body(mvc.perform(get("/api/admin/payments").param("status", "PENDING")
                .param("size", "100").with(as(UUID.randomUUID(), "OPS_ADMIN"))).andExpect(status().isOk()));
        assertThat(pendingOnes.get("content").valueStream().map(p -> p.get("status").asString())).containsOnly("PENDING");
        JsonNode yesterday = body(mvc.perform(get("/api/admin/payments").param("orderId", paid.toString())
                .param("to", LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1).toString())
                .with(as(UUID.randomUUID(), "SUPPORT"))).andExpect(status().isOk()));
        assertThat(yesterday.get("totalElements").asLong()).isZero();

        String paymentId = row.get("id").asString();
        mvc.perform(get("/api/admin/payments/{id}", paymentId).with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts[0].status").value("CAPTURED"));
        mvc.perform(get("/api/admin/payments/{id}", UUID.randomUUID()).with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));

        mvc.perform(get("/api/admin/payments").with(customer(customer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments").param("status", "PAID").with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }
}
