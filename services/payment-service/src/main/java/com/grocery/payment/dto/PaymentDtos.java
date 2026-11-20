package com.grocery.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.grocery.payment.config.ProviderMode;
import com.grocery.payment.entity.AttemptStatus;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.RefundReason;
import com.grocery.payment.entity.RefundStatus;
import com.grocery.payment.entity.RefundSummary;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of docs/api/phase-6-orders-and-payments.md §3 and §4 (Phase 10 §3: the reconciliation
 * summary). Money in rupees (2 decimals).
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    // ---- customer -----------------------------------------------------------------------------------------

    /**
     * Everything Razorpay Checkout needs; the key secret never leaves payment-service.
     *
     * @param amount        paise (integer), what Checkout expects
     * @param displayAmount rupees
     */
    public record CheckoutSession(ProviderMode provider, String keyId, String razorpayOrderId, long amount,
                                  BigDecimal displayAmount, String currency, UUID orderId, String orderNumber,
                                  String merchantName, String description, Prefill prefill, Instant expiresAt) {
    }

    /** From the caller's token (name, email) and the order's delivery address (contact). */
    public record Prefill(String name, String email, String contact) {
    }

    /** What Razorpay Checkout (or the simulator) hands back after a successful payment. */
    public record VerifyRequest(@NotBlank @Size(max = 100) String razorpayOrderId,
                                @NotBlank @Size(max = 100) String razorpayPaymentId,
                                @NotBlank @Size(max = 200) String razorpaySignature) {
    }

    /** {@code attempts} is left out of admin list rows. */
    public record PaymentView(UUID id, UUID orderId, String orderNumber, UUID customerId, PaymentStatus status,
                              BigDecimal amount, String currency, String method, String razorpayOrderId,
                              String razorpayPaymentId, BigDecimal refundedAmount, RefundSummary refundStatus,
                              Instant expiresAt, Instant capturedAt, Instant createdAt,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<AttemptView> attempts,
                              List<RefundView> refunds) {
    }

    /** {@code at} = when the attempt reached its current status. */
    public record AttemptView(String razorpayPaymentId, AttemptStatus status, String method, String errorCode,
                              String errorDescription, Instant at) {
    }

    /**
     * {@code requestedBy} = the admin user id of an ADMIN refund; {@code returnId} (Phase 12) = the return of a RETURN
     * refund, null otherwise.
     */
    public record RefundView(UUID id, UUID paymentId, UUID orderId, BigDecimal amount, RefundReason reason,
                             String note, RefundStatus status, String razorpayRefundId, String failureReason,
                             UUID requestedBy, UUID returnId, Instant createdAt, Instant processedAt) {
    }

    // ---- simulator ----------------------------------------------------------------------------------------

    public enum SimulatedOutcome { SUCCESS, FAIL }

    public record SimulatedPayRequest(@NotNull SimulatedOutcome outcome) {
    }

    /**
     * SUCCESS: the signed ids to post to verify. FAIL: the declined payment's ids, no signature (the decline was
     * recorded like a {@code payment.failed} webhook).
     */
    public record SimulatedPayment(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {
    }

    // ---- admin --------------------------------------------------------------------------------------------

    /** {@code amount} absent = everything not yet refunded. */
    public record AdminRefundRequest(@NotNull UUID orderId,
                                     @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                     @NotBlank @Size(max = 300) String note) {
    }

    /**
     * GET /api/admin/payments/reconciliation (Phase 10 §3): the job's counters since it first ran, over every replica —
     * {@code checked} payments asked about at the provider, {@code recovered} captures / declines it applied that
     * nobody had reported, {@code failures} checks that failed (retried in the next run) — when a run last finished
     * (null: never), and the last 20 recoveries, newest first.
     */
    public record ReconciliationSummary(Instant lastRunAt, long checked, long recovered, long failures,
                                        List<RecoveredPayment> lastRecovered) {
    }

    /**
     * {@code outcome} = what the provider had: CAPTURED (an unreported capture), AUTHORIZED (captured by us after finding
     * it authorized) or FAILED (an unreported declined attempt).
     */
    public record RecoveredPayment(UUID orderId, String orderNumber, String outcome, Instant at) {
    }
}
