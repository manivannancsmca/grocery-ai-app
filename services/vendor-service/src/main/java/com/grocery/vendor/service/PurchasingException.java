package com.grocery.vendor.service;

import com.grocery.common.web.ApiException;
import com.grocery.vendor.entity.PurchaseOrderStatus;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The purchasing errors of Phase 12 §4. They may carry problem properties ({@code currentStatus}, {@code productId},
 * {@code openQuantity}, {@code errors}), which grocery-common's {@code GlobalExceptionHandler} adds to the
 * ProblemDetail body.
 */
public class PurchasingException extends ApiException {

    public PurchasingException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** Unknown supplier, or another vendor's. */
    public static PurchasingException supplierNotFound() {
        return new PurchasingException(HttpStatus.NOT_FOUND, "SUPPLIER_NOT_FOUND", "Supplier not found", Map.of());
    }

    /** Deactivating a supplier that open purchase orders (DRAFT, SENT, PARTIALLY_RECEIVED) still name. */
    public static PurchasingException supplierInUse(long openPurchaseOrders) {
        return new PurchasingException(HttpStatus.CONFLICT, "SUPPLIER_IN_USE",
                "The supplier has " + openPurchaseOrders + " open purchase order(s); receive, close or cancel them first",
                Map.of("openPurchaseOrders", openPurchaseOrders));
    }

    /** A purchase order naming an unknown, another vendor's or an inactive supplier. */
    public static PurchasingException invalidSupplier(UUID supplierId, String why) {
        return new PurchasingException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SUPPLIER",
                "The supplier " + why, Map.of("supplierId", supplierId.toString()));
    }

    /** A line naming a product that is unknown here, another vendor's, or archived. */
    public static PurchasingException invalidProduct(UUID productId, String why) {
        return new PurchasingException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PRODUCT",
                "Product " + productId + " " + why + ". A product created a moment ago can take a few seconds to "
                        + "appear here.", Map.of("productId", productId.toString()));
    }

    /** Unknown purchase order, or another vendor's (a vendor never learns that it exists). */
    public static PurchasingException purchaseOrderNotFound() {
        return new PurchasingException(HttpStatus.NOT_FOUND, "PURCHASE_ORDER_NOT_FOUND", "Purchase order not found",
                Map.of());
    }

    /** PUT of a purchase order that is no longer a DRAFT. */
    public static PurchasingException notEditable(PurchaseOrderStatus status) {
        return new PurchasingException(HttpStatus.CONFLICT, "PURCHASE_ORDER_NOT_EDITABLE",
                "Only a DRAFT purchase order can be edited; this one is " + status,
                Map.of("currentStatus", status.name()));
    }

    /** send / cancel / close / receive from a status that does not allow it. */
    public static PurchasingException invalidTransition(PurchaseOrderStatus status, String action) {
        return new PurchasingException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "A purchase order that is " + status + " cannot " + action, Map.of("currentStatus", status.name()));
    }

    /** A receipt line for more than is still open on the purchase order (or for a product not on it). */
    public static PurchasingException receiptExceedsOrdered(UUID productId, int openQuantity, int requested) {
        return new PurchasingException(HttpStatus.UNPROCESSABLE_CONTENT, "RECEIPT_EXCEEDS_ORDERED",
                openQuantity == 0 && requested > 0
                        ? "Nothing of product " + productId + " is open on this purchase order"
                        : "Only " + openQuantity + " unit(s) of product " + productId + " are still open (received "
                        + requested + ")",
                Map.of("productId", productId.toString(), "openQuantity", openQuantity));
    }

    /** 400 VALIDATION_FAILED with one field error, shaped like bean-validation errors. */
    public static PurchasingException validation(String field, String message) {
        return validation(List.of(Map.of("field", field, "message", message)));
    }

    public static PurchasingException validation(List<Map<String, String>> errors) {
        return new PurchasingException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", errors));
    }
}
