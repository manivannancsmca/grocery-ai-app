package com.grocery.inventory.service;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The inventory errors of contract §2. Unlike grocery-common's fixed-status exceptions they carry problem
 * properties ({@code available}, {@code reserved}, and {@code productId} for a failing bulk item), which
 * grocery-common's {@code GlobalExceptionHandler} adds to the ProblemDetail body.
 */
public class InventoryException extends ApiException {

    public static final int MAX_ON_HAND = 1_000_000;

    public InventoryException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** Unknown product, or another vendor's. */
    public static InventoryException productNotFound() {
        return new InventoryException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                "Product not found. A product created a moment ago can take a few seconds to appear here.", Map.of());
    }

    public static InventoryException productArchived() {
        return new InventoryException(HttpStatus.CONFLICT, "PRODUCT_ARCHIVED",
                "The product is archived; its stock can no longer be changed", Map.of());
    }

    /** DECREASE by more than the available (unreserved) stock. */
    public static InventoryException insufficientStock(int available, int reserved) {
        return new InventoryException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Only " + available + " unit(s) are available (" + reserved + " reserved for orders)",
                Map.of("available", available, "reserved", reserved));
    }

    /** SET to less than what is reserved for orders. */
    public static InventoryException belowReserved(int available, int reserved) {
        return new InventoryException(HttpStatus.CONFLICT, "STOCK_BELOW_RESERVED",
                "Stock cannot be set below the " + reserved + " unit(s) reserved for orders",
                Map.of("available", available, "reserved", reserved));
    }

    public static InventoryException limitExceeded(long requestedOnHand) {
        return new InventoryException(HttpStatus.UNPROCESSABLE_CONTENT, "STOCK_LIMIT_EXCEEDED",
                "Stock on hand may not exceed " + MAX_ON_HAND + " (would be " + requestedOnHand + ")",
                Map.of("maxOnHand", MAX_ON_HAND));
    }

    /** 400 VALIDATION_FAILED with one field error, shaped like bean-validation errors. */
    public static InventoryException validation(String field, String message) {
        return new InventoryException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    /** The same error, naming the bulk item that caused it ({@code productId} property). */
    public InventoryException forProduct(UUID productId) {
        Map<String, Object> properties = new LinkedHashMap<>(properties());
        properties.put("productId", productId.toString());
        return new InventoryException(status(), errorCode(), getMessage(), properties);
    }
}
