package com.grocery.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment of one order (at most one per order id), created from {@code OrderAwaitingPayment}.
 * <pre>
 * CREATED ──Razorpay order──► PENDING ──capture──► CAPTURED
 *    │                           │
 *    └──── window over ──────────┴──► EXPIRED     (capture afterwards = late capture → CAPTURED, refunded)
 *    └──── OrderCancelled ───────┴──► CANCELLED   (capture afterwards = late capture → CAPTURED, refunded)
 * </pre>
 * Every change happens on a row locked with {@code SELECT … FOR UPDATE} (see {@code service/PaymentLocks}); the
 * rules live here. {@code refundedAmount} counts PROCESSED refunds only; {@code refundStatus} is the summary shown
 * in {@code PaymentView}, kept in step by the refund ledger.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, updatable = false, length = 40)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 10)
    private RefundSummary refundStatus;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "razorpay_order_id", length = 40)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 40)
    private String razorpayPaymentId;

    /** When the Razorpay order was created (Phase 10: reconciliation asks about it once it is old enough). */
    @Column(name = "provider_order_at")
    private Instant providerOrderAt;

    @Column(length = 30)
    private String method;

    @Column(name = "late_capture", nullable = false)
    private boolean lateCapture;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    /** Expiry poller: claimed by a poller instance until then (it is calling the provider for this payment). */
    @Column(name = "poll_lease_until")
    private Instant pollLeaseUntil;

    /** Reconciliation (Phase 10): claimed by a run until then (it is calling the provider for this payment). */
    @Column(name = "reconcile_lease_until")
    private Instant reconcileLeaseUntil;

    /** Reconciliation: the start of the run that last checked this payment at the provider. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Payment() {
    }

    /** A new payment waiting for its Razorpay order. */
    public static Payment create(UUID id, UUID orderId, String orderNumber, UUID customerId, BigDecimal amount,
                                 String currency, String contactPhone, Instant expiresAt, Instant now) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A payment needs a positive amount");
        }
        Payment payment = new Payment();
        payment.id = id;
        payment.orderId = orderId;
        payment.orderNumber = orderNumber;
        payment.customerId = customerId;
        payment.status = PaymentStatus.CREATED;
        payment.amount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        payment.currency = currency;
        payment.refundedAmount = BigDecimal.ZERO.setScale(2);
        payment.refundStatus = RefundSummary.NONE;
        payment.contactPhone = contactPhone;
        payment.expiresAt = expiresAt;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    /** CREATED → PENDING once its Razorpay order exists (set exactly once). */
    public void providerOrderCreated(String razorpayOrderId, Instant now) {
        if (this.razorpayOrderId != null || status != PaymentStatus.CREATED) {
            throw new IllegalStateException("Payment " + id + " already has a Razorpay order or is " + status);
        }
        this.razorpayOrderId = razorpayOrderId;
        this.providerOrderAt = now;
        this.status = PaymentStatus.PENDING;
        this.updatedAt = now;
    }

    /**
     * Records the capture of {@code razorpayPaymentId}. Accepted in every state but CAPTURED: an EXPIRED or
     * CANCELLED payment becomes a late capture.
     *
     * @return true for a late capture (the caller refunds it)
     */
    public boolean capture(String razorpayPaymentId, String method, Instant now) {
        if (status == PaymentStatus.CAPTURED || this.razorpayPaymentId != null) {
            throw new IllegalStateException("Payment " + id + " is already captured");
        }
        boolean late = !status.isOpen();
        this.status = PaymentStatus.CAPTURED;
        this.razorpayPaymentId = razorpayPaymentId;
        this.method = method;
        this.capturedAt = now;
        this.lateCapture = late;
        this.updatedAt = now;
        return late;
    }

    /** CREATED / PENDING → EXPIRED (the payment window is over). */
    public void expire(Instant now) {
        requireOpen();
        this.status = PaymentStatus.EXPIRED;
        this.updatedAt = now;
    }

    /** CREATED / PENDING → CANCELLED (the order was cancelled). */
    public void cancel(Instant now) {
        requireOpen();
        this.status = PaymentStatus.CANCELLED;
        this.updatedAt = now;
    }

    /** A refund of {@code refunded} was processed by the provider. */
    public void refunded(BigDecimal refunded, boolean anyRefundPending, Instant now) {
        BigDecimal total = refundedAmount.add(refunded);
        if (total.compareTo(amount) > 0) {
            throw new IllegalStateException("Payment " + id + " would be refunded " + total + " of " + amount);
        }
        this.refundedAmount = total;
        refreshRefundStatus(anyRefundPending, now);
    }

    /** Re-derives {@link #getRefundStatus()} (a refund was started, failed or finished). */
    public void refreshRefundStatus(boolean anyRefundPending, Instant now) {
        this.refundStatus = RefundSummary.of(anyRefundPending, refundedAmount, amount);
        this.updatedAt = now;
    }

    /** The expiry poller works on this payment until {@code until} (another poller skips it meanwhile). */
    public void claimForExpiry(Instant until) {
        this.pollLeaseUntil = until;
    }

    public void releaseExpiryClaim() {
        this.pollLeaseUntil = null;
    }

    /** A reconciliation run works on this payment until {@code until} (another run skips it meanwhile). */
    public void claimForReconciliation(Instant until) {
        this.reconcileLeaseUntil = until;
    }

    /** The run that started at {@code runStartedAt} is done with this payment (it is not taken again in that run). */
    public void finishReconciliation(Instant runStartedAt) {
        this.reconcileLeaseUntil = null;
        this.reconciledAt = runStartedAt;
    }

    /** Open and past the end of the payment window. */
    public boolean isPastWindow(Instant now) {
        return status.isOpen() && !now.isBefore(expiresAt);
    }

    public boolean isFullyRefunded() {
        return refundedAmount.compareTo(amount) >= 0;
    }

    private void requireOpen() {
        if (!status.isOpen()) {
            throw new IllegalStateException("Payment " + id + " is " + status + ", not CREATED / PENDING");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public RefundSummary getRefundStatus() {
        return refundStatus;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public String getMethod() {
        return method;
    }

    public boolean isLateCapture() {
        return lateCapture;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getPollLeaseUntil() {
        return pollLeaseUntil;
    }

    public Instant getProviderOrderAt() {
        return providerOrderAt;
    }

    public Instant getReconcileLeaseUntil() {
        return reconcileLeaseUntil;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
