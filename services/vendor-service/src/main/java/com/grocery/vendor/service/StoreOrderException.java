package com.grocery.vendor.service;

import com.grocery.common.web.ApiException;
import com.grocery.vendor.entity.StoreOrderStatus;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * The store order errors of contract §2. Unlike grocery-common's fixed-status exceptions they may carry problem
 * properties ({@code currentStatus}, {@code errors}), which grocery-common's {@code GlobalExceptionHandler} adds to the
 * ProblemDetail body.
 */
public class StoreOrderException extends ApiException {

    public StoreOrderException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** Unknown order, or another store's (a store never learns that it exists). */
    public static StoreOrderException notFound() {
        return new StoreOrderException(HttpStatus.NOT_FOUND, "STORE_ORDER_NOT_FOUND", "Store order not found", Map.of());
    }

    /** Accept / reject of an order that no longer waits for acceptance. */
    public static StoreOrderException notPending(StoreOrderStatus status) {
        return new StoreOrderException(HttpStatus.CONFLICT, "STORE_ORDER_NOT_PENDING",
                "The order is " + status + " and no longer waits for acceptance",
                Map.of("currentStatus", status.name()));
    }

    /** Accept of a NEW order whose acceptBy has passed (the auto-reject poller rejects it shortly). */
    public static StoreOrderException acceptanceExpired() {
        return new StoreOrderException(HttpStatus.CONFLICT, "ACCEPTANCE_EXPIRED",
                "The acceptance deadline of this order has passed", Map.of());
    }

    public static StoreOrderException invalidTransition(StoreOrderStatus status, StoreOrderStatus target) {
        return new StoreOrderException(HttpStatus.CONFLICT, "INVALID_STORE_ORDER_TRANSITION",
                "An order that is " + status + " cannot move to " + target,
                Map.of("currentStatus", status.name()));
    }

    /** 400 VALIDATION_FAILED with one field error, shaped like bean-validation errors. */
    public static StoreOrderException validation(String field, String message) {
        return new StoreOrderException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
