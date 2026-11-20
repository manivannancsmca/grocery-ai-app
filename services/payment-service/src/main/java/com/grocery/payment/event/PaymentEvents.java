package com.grocery.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code payment.events} (v1, docs/api/phase-6-orders-and-payments.md §6), aggregate type
 * {@code Payment}, key = orderId (every event of one order stays in order). Consumer: order-service
 * ({@code order-service.payment-events}); notification later. Money = JSON number, 2 decimals.
 */
public final class PaymentEvents {

    public static final String TOPIC = "payment.events";
    public static final String AGGREGATE = "Payment";

    public static final String PAYMENT_INITIATED = "PaymentInitiated";
    public static final String PAYMENT_CAPTURED = "PaymentCaptured";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_EXPIRED = "PaymentExpired";
    public static final String REFUND_COMPLETED = "RefundCompleted";
    public static final String REFUND_FAILED = "RefundFailed";

    private PaymentEvents() {
    }

    /** The Razorpay order exists: the customer can pay until {@code expiresAt}. */
    public record PaymentInitiated(UUID orderId, UUID paymentId, String razorpayOrderId, BigDecimal amount,
                                   String currency, Instant expiresAt) {
    }

    /** {@code late} = the payment had expired or was cancelled; a full LATE_CAPTURE refund has started. */
    public record PaymentCaptured(UUID orderId, UUID paymentId, String razorpayPaymentId, BigDecimal amount,
                                  String method, Instant capturedAt, boolean late) {
    }

    /** A declined attempt. {@code retryable} = the customer can still pay (payment open, window not over). */
    public record PaymentFailed(UUID orderId, UUID paymentId, String razorpayPaymentId, String errorCode,
                                String errorDescription, boolean retryable) {
    }

    /** The payment window ended unpaid (and the provider confirmed nothing was captured). */
    public record PaymentExpired(UUID orderId, UUID paymentId) {
    }

    /**
     * A refund was processed. {@code totalRefunded} = everything refunded for the payment so far;
     * {@code fullyRefunded} = nothing of the captured amount is left. Phase 12: {@code returnId} = the return of a
     * RETURN refund (order-service marks it REFUNDED), null otherwise.
     */
    public record RefundCompleted(UUID orderId, UUID paymentId, UUID refundId, BigDecimal amount, String reason,
                                  BigDecimal totalRefunded, boolean fullyRefunded, UUID returnId) {
    }

    /**
     * A refund failed for good (not a temporary provider outage). Phase 12: {@code returnId} = the return of a RETURN
     * refund, null otherwise.
     */
    public record RefundFailed(UUID orderId, UUID paymentId, UUID refundId, BigDecimal amount, String reason,
                               String errorCode, UUID returnId) {
    }
}
