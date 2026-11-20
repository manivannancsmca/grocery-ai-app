package com.grocery.payment.service;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The payment errors of contract §3 and §4. Like the other services' exceptions they may carry problem properties
 * ({@code refundable}, the provider's error of a failed payment), which grocery-common's
 * {@code GlobalExceptionHandler} adds to the ProblemDetail body.
 */
public class PaymentException extends ApiException {

    public PaymentException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** No payment for the order yet: stock still being reserved, or OrderAwaitingPayment is on its way. */
    public static PaymentException notReady() {
        return new PaymentException(HttpStatus.CONFLICT, "PAYMENT_NOT_READY",
                "The payment for this order is not ready yet. Please try again in a second.", Map.of());
    }

    /** Unknown payment / order / Razorpay order, or another customer's (customers never learn that it exists). */
    public static PaymentException notFound() {
        return new PaymentException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found", Map.of());
    }

    public static PaymentException alreadyCaptured() {
        return new PaymentException(HttpStatus.CONFLICT, "PAYMENT_ALREADY_CAPTURED",
                "This order has already been paid", Map.of());
    }

    public static PaymentException expired() {
        return new PaymentException(HttpStatus.CONFLICT, "PAYMENT_EXPIRED",
                "The time to pay for this order is over", Map.of());
    }

    public static PaymentException cancelled() {
        return new PaymentException(HttpStatus.CONFLICT, "PAYMENT_CANCELLED",
                "This order was cancelled and can no longer be paid", Map.of());
    }

    public static PaymentException providerUnavailable() {
        return new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE",
                "The payment provider is not reachable right now. Please try again shortly.", Map.of());
    }

    public static PaymentException invalidSignature() {
        return new PaymentException(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE",
                "The payment signature is not valid", Map.of());
    }

    /**
     * The provider says the payment failed. Properties: {@code razorpayPaymentId}, {@code providerErrorCode} and
     * {@code errorDescription} (the provider's message, shown to the customer).
     */
    public static PaymentException paymentFailed(String razorpayPaymentId, String providerErrorCode,
                                                 String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("razorpayPaymentId", razorpayPaymentId);
        if (providerErrorCode != null) {
            properties.put("providerErrorCode", providerErrorCode);
        }
        if (description != null) {
            properties.put("errorDescription", description);
        }
        return new PaymentException(HttpStatus.CONFLICT, "PAYMENT_FAILED",
                description == null ? "The payment failed" : "The payment failed: " + description, properties);
    }

    public static PaymentException notCaptured() {
        return new PaymentException(HttpStatus.CONFLICT, "PAYMENT_NOT_CAPTURED",
                "Only a captured payment can be refunded", Map.of());
    }

    /** More than what is left of the captured amount (processed and pending refunds count). */
    public static PaymentException refundExceedsCaptured(BigDecimal refundable) {
        return new PaymentException(HttpStatus.UNPROCESSABLE_CONTENT, "REFUND_EXCEEDS_CAPTURED",
                "At most " + refundable + " can still be refunded", Map.of("refundable", refundable));
    }

    /** 400 VALIDATION_FAILED with field errors, shaped like bean-validation errors. */
    public static PaymentException validation(List<Map<String, String>> errors) {
        return new PaymentException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", errors));
    }
}
