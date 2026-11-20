package com.grocery.cart.web;

import com.grocery.cart.client.Dependency;
import com.grocery.cart.model.Store;
import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cart's error responses (ProblemDetail with {@code errorCode}, rendered by grocery-common's
 * GlobalExceptionHandler; extra properties are added to the problem body).
 */
public final class CartErrors {

    private CartErrors() {
    }

    /** An {@link ApiException} with any status and optional problem properties. */
    public static class CartException extends ApiException {

        public CartException(HttpStatus status, String errorCode, String message) {
            super(status, errorCode, message);
        }

        public CartException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
            super(status, errorCode, message, properties);
        }
    }

    public static CartException storeConflict(Store current) {
        return new CartException(HttpStatus.CONFLICT, "CART_STORE_CONFLICT",
                "Your cart has items from " + current.name() + ". Start a new cart to add this item.",
                Map.of("currentStoreId", current.id().toString(),
                        "currentStoreName", current.name() == null ? "" : current.name(),
                        "currentStoreType", current.type().name()));
    }

    public static CartException itemUnavailable(String name) {
        return conflict("ITEM_UNAVAILABLE", label(name, "This item") + " is not available right now");
    }

    public static CartException outOfStock(String name) {
        return conflict("OUT_OF_STOCK", label(name, "This item") + " is out of stock");
    }

    public static CartException insufficientStock(String name, int available) {
        return new CartException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Only " + available + " of " + label(name, "this item") + " left in stock",
                Map.of("available", available));
    }

    public static CartException storeClosed(String storeName) {
        return conflict("STORE_CLOSED", label(storeName, "The restaurant") + " is closed right now");
    }

    public static CartException notAcceptingOrders(String storeName) {
        return conflict("NOT_ACCEPTING_ORDERS", label(storeName, "The restaurant") + " is not accepting orders right now");
    }

    public static CartException quantityLimit(int max) {
        return new CartException(HttpStatus.UNPROCESSABLE_CONTENT, "QUANTITY_LIMIT",
                "At most " + max + " of one item per order", Map.of("max", max));
    }

    public static CartException cartLimitReached(int max) {
        return new CartException(HttpStatus.UNPROCESSABLE_CONTENT, "CART_LIMIT_REACHED",
                "A cart can hold at most " + max + " different items", Map.of("max", max));
    }

    public static CartException cartItemNotFound() {
        return new CartException(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "This item is not in your cart");
    }

    public static CartException addressNotFound() {
        return new CartException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "Address not found");
    }

    public static CartException concurrentModification() {
        return conflict("CONCURRENT_MODIFICATION", "Your cart was changed at the same time elsewhere. Please try again.");
    }

    public static CartException cartUnavailable(Throwable cause) {
        CartException exception = new CartException(HttpStatus.SERVICE_UNAVAILABLE, "CART_UNAVAILABLE",
                "Your cart is not available right now. Please try again shortly.");
        exception.initCause(cause);
        return exception;
    }

    public static CartException dependencyUnavailable(Dependency dependency) {
        return new CartException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "This item cannot be checked right now. Please try again shortly.",
                Map.of("dependency", dependency.id()));
    }

    /**
     * Phase 12: PUT /api/cart/coupon with a coupon that cannot be used for this cart → 422 COUPON_NOT_APPLICABLE with
     * {@code reason} (pricing-service's, or EMPTY_CART), {@code code} and, for COUPON_MIN_ORDER,
     * {@code minOrderSubtotal}. Nothing is stored.
     */
    public static CartException couponNotApplicable(String reason, String code, BigDecimal minOrderSubtotal,
                                                    String message) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", reason);
        properties.put("code", code);
        if (minOrderSubtotal != null) {
            properties.put("minOrderSubtotal", minOrderSubtotal);
        }
        return new CartException(HttpStatus.UNPROCESSABLE_CONTENT, "COUPON_NOT_APPLICABLE", message, properties);
    }

    /** 400 VALIDATION_FAILED with one field error, like Bean Validation failures. */
    public static CartException invalidField(String field, String message) {
        return new CartException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    private static CartException conflict(String code, String message) {
        return new CartException(HttpStatus.CONFLICT, code, message);
    }

    private static String label(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
