package com.grocery.order.service;

import com.grocery.common.web.ApiException;
import com.grocery.order.client.CartPreview;
import com.grocery.order.entity.ComplaintStatus;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.ReturnStatus;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The order errors of contract §2 and §4. Unlike grocery-common's fixed-status exceptions they carry problem
 * properties ({@code issues}, {@code expectedTotal} / {@code currentTotal}, Phase 11 {@code expectedCartUpdatedAt} /
 * {@code currentCartUpdatedAt}, {@code dependency}), which grocery-common's
 * {@code GlobalExceptionHandler} adds to the ProblemDetail body.
 */
public class OrderException extends ApiException {

    public OrderException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** Unknown order, or another customer's (customers never learn that it exists). */
    public static OrderException orderNotFound() {
        return new OrderException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found", Map.of());
    }

    public static OrderException notCancellable(OrderStatus status) {
        return new OrderException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE",
                "An order that is " + status + " cannot be cancelled", Map.of("currentStatus", status.name()));
    }

    /** The live checkout preview has blocking issues; {@code issues} = the preview's issues, unchanged. */
    public static OrderException checkoutBlocked(List<CartPreview.Issue> issues) {
        return new OrderException(HttpStatus.CONFLICT, "CHECKOUT_BLOCKED",
                "The order cannot be placed right now. Please review your cart.", Map.of("issues", issues));
    }

    public static OrderException totalChanged(BigDecimal expectedTotal, BigDecimal currentTotal) {
        return new OrderException(HttpStatus.CONFLICT, "TOTAL_CHANGED",
                "The order total changed from " + expectedTotal + " to " + currentTotal + ". Please review it.",
                Map.of("expectedTotal", expectedTotal, "currentTotal", currentTotal));
    }

    /**
     * Phase 11: the cart changed after the preview the customer saw ({@code expectedCartUpdatedAt} ≠ the live cart's
     * {@code updatedAt}). Both as ISO-8601 instants; {@code currentCartUpdatedAt} is left out for a cart that was never
     * written (problem properties cannot be null).
     */
    public static OrderException cartChanged(Instant expectedCartUpdatedAt, Instant currentCartUpdatedAt) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("expectedCartUpdatedAt", expectedCartUpdatedAt.toString());
        if (currentCartUpdatedAt != null) {
            properties.put("currentCartUpdatedAt", currentCartUpdatedAt.toString());
        }
        return new OrderException(HttpStatus.CONFLICT, "CART_CHANGED",
                "Your cart changed after you reviewed it. Please review it again.", properties);
    }

    public static OrderException addressNotFound() {
        return new OrderException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "Address not found", Map.of());
    }

    /** Nothing was stored; the client may retry with the same Idempotency-Key. */
    public static OrderException dependencyUnavailable(String dependency) {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "The order cannot be placed right now. Please try again shortly.", Map.of("dependency", dependency));
    }

    /**
     * Phase 12 §2: pricing-service refused to redeem the cart's coupon (or the cart reports it invalid); no order is
     * placed. {@code reason} = COUPON_EXPIRED, COUPON_USAGE_LIMIT, …; {@code minOrderSubtotal} only for
     * COUPON_MIN_ORDER.
     */
    public static OrderException couponNotApplicable(String reason, BigDecimal minOrderSubtotal) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", reason == null || reason.isBlank() ? "COUPON_NOT_APPLICABLE" : reason);
        if (minOrderSubtotal != null) {
            properties.put("minOrderSubtotal", minOrderSubtotal);
        }
        return new OrderException(HttpStatus.CONFLICT, "COUPON_NOT_APPLICABLE",
                "The coupon cannot be applied to this order. Please review your cart.", properties);
    }

    // ---- returns (Phase 12 §3) ---------------------------------------------------------------------------------

    /** Unknown return, another customer's, or (for a store) another store's. */
    public static OrderException returnNotFound() {
        return new OrderException(HttpStatus.NOT_FOUND, "RETURN_NOT_FOUND", "Return not found", Map.of());
    }

    /** The order cannot be returned now (its state, the window, an open request); {@code properties} say which. */
    public static OrderException returnNotAllowed(String message, Map<String, Object> properties) {
        return new OrderException(HttpStatus.UNPROCESSABLE_CONTENT, "RETURN_NOT_ALLOWED", message, properties);
    }

    /** More than what is left of a line (or a line the order does not have); {@code items} = the offending lines. */
    public static OrderException returnQuantityExceeded(List<Map<String, Object>> items) {
        return new OrderException(HttpStatus.UNPROCESSABLE_CONTENT, "RETURN_QUANTITY_EXCEEDED",
                "You asked to return more than is left of these items.", Map.of("items", items));
    }

    /** The return was already decided (or escalated, for the store). */
    public static OrderException returnNotPending(ReturnStatus status) {
        return new OrderException(HttpStatus.CONFLICT, "RETURN_NOT_PENDING",
                "A return that is " + status + " cannot be decided", Map.of("currentStatus", status.name()));
    }

    // ---- complaints (docs/api/payment-and-support-agents.md §2) ------------------------------------------------

    /** Unknown complaint, or (for a customer) another customer's. */
    public static OrderException complaintNotFound() {
        return new OrderException(HttpStatus.NOT_FOUND, "COMPLAINT_NOT_FOUND", "Complaint not found", Map.of());
    }

    /** The order already has an open (OPEN / IN_PROGRESS) complaint; the detail names its number. */
    public static OrderException complaintAlreadyOpen(UUID complaintId, String complaintNumber) {
        return new OrderException(HttpStatus.CONFLICT, "COMPLAINT_ALREADY_OPEN",
                "Complaint " + complaintNumber + " about this order is still open",
                Map.of("complaintId", complaintId.toString(), "complaintNumber", complaintNumber));
    }

    /** The customer already has {@code maxOpen} open complaints ({@code grocery.order.complaints.max-open}). */
    public static OrderException tooManyOpenComplaints(int maxOpen) {
        return new OrderException(HttpStatus.CONFLICT, "TOO_MANY_OPEN_COMPLAINTS",
                "You already have " + maxOpen + " open complaints. Please wait until one of them is resolved.",
                Map.of("maxOpen", maxOpen));
    }

    /** Start of a complaint that is not OPEN, or resolve of a RESOLVED one; {@code action} = "started" / "resolved". */
    public static OrderException invalidComplaintStatus(ComplaintStatus status, String action) {
        return new OrderException(HttpStatus.CONFLICT, "INVALID_COMPLAINT_STATUS",
                "A complaint that is " + status + " cannot be " + action, Map.of("currentStatus", status.name()));
    }

    /** 400 VALIDATION_FAILED with field errors, shaped like bean-validation errors. */
    public static OrderException validation(List<Map<String, String>> errors) {
        return new OrderException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", errors));
    }
}
