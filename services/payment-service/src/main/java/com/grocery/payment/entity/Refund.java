package com.grocery.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A refund of (part of) a captured payment. PENDING until the provider says PROCESSED or FAILED. PENDING refunds
 * count against what can still be refunded. Changed only while its payment row is locked (lock order: payment,
 * then refund).
 *
 * <p>Retry bookkeeping: {@code leaseUntil} = a provider call for it is in flight until then (the refund poller
 * skips it); {@code nextAttemptAt} = when the refund poller may (re)try it — create it at the provider if it has no
 * provider id yet, otherwise re-check its status (a lost webhook).
 */
@Entity
@Table(name = "refund")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private RefundReason reason;

    @Column(length = 300, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RefundStatus status;

    @Column(name = "razorpay_refund_id", length = 40)
    private String razorpayRefundId;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    /** Phase 12: the return this refund is for (reason RETURN; unique), null otherwise. */
    @Column(name = "return_id", updatable = false)
    private UUID returnId;

    /** Phase 12: what {@code ReturnApproved} asked for; {@code amount} may be less (capped at the refundable). */
    @Column(name = "requested_amount", precision = 12, scale = 2, updatable = false)
    private BigDecimal requestedAmount;

    @Column(name = "provider_attempts", nullable = false)
    private int providerAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Refund() {
    }

    /**
     * A new PENDING refund. The caller makes the provider call right after commit, so it is leased until
     * {@code leaseUntil}; if that call never happens (crash), the refund poller takes it afterwards.
     */
    public static Refund pending(UUID id, Payment payment, BigDecimal amount, RefundReason reason, String note,
                                 UUID requestedBy, Instant now, Instant leaseUntil) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A refund needs a positive amount");
        }
        Refund refund = new Refund();
        refund.id = id;
        refund.paymentId = payment.getId();
        refund.orderId = payment.getOrderId();
        refund.amount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        refund.reason = reason;
        refund.note = note;
        refund.status = RefundStatus.PENDING;
        refund.requestedBy = requestedBy;
        refund.nextAttemptAt = now;
        refund.leaseUntil = leaseUntil;
        refund.createdAt = now;
        refund.updatedAt = now;
        return refund;
    }

    /**
     * Phase 12: a new PENDING refund of a return (reason RETURN): {@code amount} = {@code requestedAmount} capped at
     * what the payment could still refund.
     */
    public static Refund pendingForReturn(UUID id, Payment payment, BigDecimal amount, BigDecimal requestedAmount,
                                          UUID returnId, String note, Instant now, Instant leaseUntil) {
        Refund refund = pending(id, payment, amount, RefundReason.RETURN, note, null, now, leaseUntil);
        refund.returnId = returnId;
        refund.requestedAmount = requestedAmount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        return refund;
    }

    public void processed(String razorpayRefundId, Instant now) {
        requirePending();
        rememberProviderId(razorpayRefundId);
        this.status = RefundStatus.PROCESSED;
        this.processedAt = now;
        finish(now);
    }

    public void failed(String razorpayRefundId, String code, String reason, Instant now) {
        requirePending();
        rememberProviderId(razorpayRefundId);
        this.status = RefundStatus.FAILED;
        this.failureCode = PaymentAttempt.cut(code, PaymentAttempt.MAX_CODE);
        this.failureReason = PaymentAttempt.cut(reason, PaymentAttempt.MAX_DESCRIPTION);
        finish(now);
    }

    /** The provider accepted the refund but has not processed it yet: re-check at {@code nextCheck}. */
    public void acceptedByProvider(String razorpayRefundId, Instant nextCheck, Instant now) {
        requirePending();
        rememberProviderId(razorpayRefundId);
        this.providerAttempts++;
        this.nextAttemptAt = nextCheck;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** The provider could not be reached: try again at {@code next}. */
    public void retryAt(Instant next, Instant now) {
        requirePending();
        this.providerAttempts++;
        this.nextAttemptAt = next;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** Claimed by a refund poller: a provider call is in flight until {@code until}. */
    public void lease(Instant until) {
        this.leaseUntil = until;
    }

    private void rememberProviderId(String razorpayRefundId) {
        if (razorpayRefundId != null && this.razorpayRefundId == null) {
            this.razorpayRefundId = razorpayRefundId;
        }
    }

    private void finish(Instant now) {
        this.nextAttemptAt = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    private void requirePending() {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Refund " + id + " is " + status + ", not PENDING");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getRazorpayRefundId() {
        return razorpayRefundId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public UUID getReturnId() {
        return returnId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public int getProviderAttempts() {
        return providerAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
