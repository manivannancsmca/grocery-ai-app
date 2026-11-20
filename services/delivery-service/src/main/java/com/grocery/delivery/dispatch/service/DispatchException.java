package com.grocery.delivery.dispatch.service;

import com.grocery.common.web.ApiException;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.entity.OfferStatus;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * The errors of contract §3–§5. Conflict errors carry the current status as {@code currentStatus} (never a second
 * {@code status}: ProblemDetail already has a numeric one); grocery-common's GlobalExceptionHandler adds the properties
 * to the ProblemDetail body.
 */
public class DispatchException extends ApiException {

    public DispatchException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** 403: the caller has no APPROVED delivery partner profile. */
    public static DispatchException partnerNotActive() {
        return new DispatchException(HttpStatus.FORBIDDEN, "PARTNER_NOT_ACTIVE",
                "An approved delivery partner account is required", Map.of());
    }

    /** 409: going offline while delivering. */
    public static DispatchException activeDelivery() {
        return new DispatchException(HttpStatus.CONFLICT, "ACTIVE_DELIVERY",
                "Finish the active delivery before going offline", Map.of());
    }

    /** 409: a location report while offline. */
    public static DispatchException partnerOffline() {
        return new DispatchException(HttpStatus.CONFLICT, "PARTNER_OFFLINE",
                "Go online before reporting a location", Map.of());
    }

    /** 404: unknown offer, or another partner's (a partner never learns that it exists). */
    public static DispatchException offerNotFound() {
        return new DispatchException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Offer not found", Map.of());
    }

    /** 409: the offer is no longer PENDING, has expired, or the delivery was taken. */
    public static DispatchException offerNotAvailable(OfferStatus status) {
        return new DispatchException(HttpStatus.CONFLICT, "OFFER_NOT_AVAILABLE",
                "The offer is " + status + " and can no longer be accepted",
                Map.of("currentStatus", status.name()));
    }

    /** 404: unknown delivery, or one the caller is not assigned to. */
    public static DispatchException deliveryNotFound() {
        return new DispatchException(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", "Delivery not found", Map.of());
    }

    /** 409: pickup before the store marked the order ready. */
    public static DispatchException orderNotReady(DeliveryStatus status) {
        return new DispatchException(HttpStatus.CONFLICT, "ORDER_NOT_READY",
                "The store has not marked the order ready for pickup yet",
                Map.of("currentStatus", status.name()));
    }

    public static DispatchException invalidTransition(DeliveryStatus status, String target) {
        return new DispatchException(HttpStatus.CONFLICT, "INVALID_DELIVERY_TRANSITION",
                "A " + status + " delivery cannot be " + target, Map.of("currentStatus", status.name()));
    }

    /** 404: no delivery for the order yet, or another customer's order. */
    public static DispatchException trackingNotFound() {
        return new DispatchException(HttpStatus.NOT_FOUND, "TRACKING_NOT_FOUND", "No delivery to track for this order",
                Map.of());
    }

    /** 409: manual assignment to a partner who is not APPROVED, online and free. */
    public static DispatchException partnerUnavailable(String why) {
        return new DispatchException(HttpStatus.CONFLICT, "PARTNER_UNAVAILABLE",
                "The partner cannot take this delivery: " + why, Map.of());
    }

    /** 400 VALIDATION_FAILED with one field error, shaped like bean-validation errors. */
    public static DispatchException validation(String field, String message) {
        return new DispatchException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
