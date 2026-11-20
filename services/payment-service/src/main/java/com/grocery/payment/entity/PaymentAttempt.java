package com.grocery.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One Razorpay payment (an attempt to pay the payment's Razorpay order), as learnt from verify, a webhook or the
 * expiry poller. At most one row per Razorpay payment id; it only moves forward
 * (AUTHORIZED → CAPTURED | FAILED), so a late or repeated report never undoes a newer one.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    static final int MAX_CODE = 60;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_METHOD = 30;

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "razorpay_payment_id", nullable = false, updatable = false, length = 40)
    private String razorpayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AttemptStatus status;

    @Column(length = 30)
    private String method;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_description", length = 500)
    private String errorDescription;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentAttempt() {
    }

    /** A Razorpay payment seen for the first time. Error details are kept for FAILED attempts only. */
    public static PaymentAttempt of(UUID id, UUID paymentId, String razorpayPaymentId, AttemptStatus status,
                                    String method, BigDecimal amount, String errorCode, String errorDescription,
                                    Instant now) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = id;
        attempt.paymentId = paymentId;
        attempt.razorpayPaymentId = razorpayPaymentId;
        attempt.createdAt = now;
        attempt.apply(status, method, amount, errorCode, errorDescription, now);
        return attempt;
    }

    /**
     * AUTHORIZED → CAPTURED or FAILED. CAPTURED and FAILED are final; any other move is ignored.
     *
     * @return true when the attempt changed
     */
    public boolean moveTo(AttemptStatus target, String method, BigDecimal amount, String errorCode,
                          String errorDescription, Instant now) {
        if (status != AttemptStatus.AUTHORIZED || target == AttemptStatus.AUTHORIZED) {
            return false;
        }
        apply(target, method == null ? this.method : method, amount == null ? this.amount : amount, errorCode,
                errorDescription, now);
        return true;
    }

    private void apply(AttemptStatus target, String method, BigDecimal amount, String errorCode,
                       String errorDescription, Instant now) {
        this.status = target;
        this.method = cut(method, MAX_METHOD);
        this.amount = amount;
        this.errorCode = target == AttemptStatus.FAILED ? cut(errorCode, MAX_CODE) : null;
        this.errorDescription = target == AttemptStatus.FAILED ? cut(errorDescription, MAX_DESCRIPTION) : null;
        this.updatedAt = now;
    }

    static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public String getMethod() {
        return method;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
