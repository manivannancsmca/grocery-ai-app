package com.grocery.payment.gateway;

import com.grocery.payment.config.ProviderMode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The payment provider as payment-service uses it: the subset of Razorpay's REST API the payment flow needs.
 * Implemented by {@link RazorpayGateway} (the real API, test keys) and {@link SimulatorGateway} (in memory, local
 * development without keys), so verify, webhooks, expiry, late capture and refunds run the same code in both modes.
 *
 * <p>Money is INR rupees ({@code BigDecimal}, 2 decimals) on this interface; the implementations convert to paise.
 * <b>Never call a gateway method while holding database row locks for long</b> (see the service classes for the
 * one bounded exception, Razorpay order creation).
 *
 * @throws ProviderUnavailableException from any method: timeout, connection failure, 5xx, 429 or an open circuit
 *                                      breaker — the outcome is unknown, retrying later is safe
 * @throws ProviderRejectedException    from any method: the provider answered 4xx (bad request, unknown id, auth)
 */
public interface PaymentGateway {

    ProviderMode mode();

    /** {@code POST /orders}: a Razorpay order the customer pays in Checkout. */
    ProviderOrder createOrder(NewOrder order);

    /** {@code GET /payments/{id}}. */
    ProviderPayment fetchPayment(String razorpayPaymentId);

    /** {@code POST /payments/{id}/capture} of an {@code authorized} payment. */
    ProviderPayment capturePayment(String razorpayPaymentId, BigDecimal amount, String currency);

    /** {@code GET /orders/{id}/payments}: every payment attempt of a Razorpay order. */
    List<ProviderPayment> listOrderPayments(String razorpayOrderId);

    /** {@code POST /payments/{id}/refund}. */
    ProviderRefund createRefund(NewRefund refund);

    /** {@code GET /refunds/{id}}. */
    ProviderRefund fetchRefund(String razorpayRefundId);

    /**
     * @param receipt our order number (≤ 40 characters at Razorpay)
     * @param notes   free key/values stored with the order ({@code orderId}, {@code paymentId})
     */
    record NewOrder(BigDecimal amount, String currency, String receipt, Map<String, String> notes) {
    }

    /**
     * @param idempotencyKey our refund id: the same refund is never created twice at the provider on a retry
     * @param notes          include {@code refundId} so that webhooks can be matched even when our call timed out
     */
    record NewRefund(String razorpayPaymentId, BigDecimal amount, String idempotencyKey, Map<String, String> notes) {
    }

    record ProviderOrder(String id, BigDecimal amount, String currency, String receipt, String status) {
    }

    record ProviderPayment(String id, String orderId, ProviderPaymentStatus status, String method, BigDecimal amount,
                           String currency, String errorCode, String errorDescription) {
    }

    /**
     * @param refundId our refund id from {@code notes.refundId} (null when the refund was not created by us)
     */
    record ProviderRefund(String id, String paymentId, BigDecimal amount, ProviderRefundStatus status,
                          String refundId) {
    }

    /** Razorpay payment {@code status}. */
    enum ProviderPaymentStatus {
        CREATED, AUTHORIZED, CAPTURED, REFUNDED, FAILED;

        public static ProviderPaymentStatus of(String value) {
            if (value == null) {
                return CREATED;
            }
            return switch (value) {
                case "authorized" -> AUTHORIZED;
                case "captured" -> CAPTURED;
                case "refunded" -> REFUNDED;
                case "failed" -> FAILED;
                default -> CREATED;
            };
        }

        /** {@code captured} or {@code refunded} (a refunded payment was captured first). */
        public boolean wasCaptured() {
            return this == CAPTURED || this == REFUNDED;
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Razorpay refund {@code status}. */
    enum ProviderRefundStatus {
        PENDING, PROCESSED, FAILED;

        public static ProviderRefundStatus of(String value) {
            if ("processed".equals(value)) {
                return PROCESSED;
            }
            return "failed".equals(value) ? FAILED : PENDING;
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
