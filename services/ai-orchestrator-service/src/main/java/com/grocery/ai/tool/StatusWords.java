package com.grocery.ai.tool;

import java.util.Map;

/**
 * Plain words for the business codes the payment and support tools return ({@code OUT_FOR_DELIVERY} → "out for
 * delivery"). The tools send both: the code (exact, for the model's reasoning) and these words (for the answer), so a
 * small model does not show internal codes to customers.
 */
final class StatusWords {

    private StatusWords() {
    }

    private static final Map<String, String> ORDER = Map.ofEntries(
            Map.entry("CREATED", "placed, the store is checking stock"),
            Map.entry("AWAITING_PAYMENT", "waiting for your payment"),
            Map.entry("CONFIRMED", "paid, waiting for the store to accept it"),
            Map.entry("ACCEPTED", "accepted by the store"),
            Map.entry("PREPARING", "being prepared"),
            Map.entry("READY_FOR_PICKUP", "ready, waiting for the delivery partner"),
            Map.entry("OUT_FOR_DELIVERY", "out for delivery"),
            Map.entry("DELIVERED", "delivered"),
            Map.entry("DELIVERY_FAILED", "not delivered (the delivery failed)"),
            Map.entry("REJECTED", "rejected by the store"),
            Map.entry("CANCELLED", "cancelled"));

    /** order-service's view of an order's payment ({@code OrderView.payment.status}). */
    private static final Map<String, String> ORDER_PAYMENT = Map.of(
            "NOT_STARTED", "not open yet (the store is checking stock)",
            "PENDING", "waiting for payment",
            "PAID", "paid",
            "EXPIRED", "the time to pay ran out (nothing charged)",
            "NOT_REQUIRED", "nothing to pay (cancelled before payment)",
            "REFUND_PENDING", "refund in progress",
            "REFUNDED", "refunded",
            "PARTIALLY_REFUNDED", "partly refunded");

    /** Why an order was cancelled ({@code OrderView.cancellation.reason}), as a clause ("because …"). */
    private static final Map<String, String> CANCELLATION = Map.of(
            "CUSTOMER_CANCELLED", "you cancelled it",
            "ADMIN_CANCELLED", "our team cancelled it",
            "PAYMENT_TIMEOUT", "the time to pay ran out",
            "RESERVATION_EXPIRED", "the items could not be reserved in time",
            "STORE_REJECTED", "the store could not accept it",
            "STORE_NO_RESPONSE", "the store did not answer in time");

    /** delivery-service's delivery status ({@code GET /api/delivery/orders/{orderId}/tracking}). */
    private static final Map<String, String> DELIVERY = Map.of(
            "PENDING", "waiting for the order to be ready",
            "SEARCHING", "looking for a delivery partner",
            "ASSIGNED", "a delivery partner is on the way to the store",
            "PICKED_UP", "picked up, on the way to you",
            "DELIVERED", "delivered",
            "FAILED", "the delivery failed",
            "CANCELLED", "the delivery was cancelled");

    private static final Map<String, String> RETURN = Map.of(
            "REQUESTED", "waiting for the store's decision",
            "ESCALATED", "with our support team (the store did not decide in time)",
            "APPROVED", "approved, the refund is on its way",
            "REJECTED", "rejected",
            "REFUNDED", "refunded");

    private static final Map<String, String> RETURN_REASON = Map.of(
            "DAMAGED", "damaged",
            "WRONG_ITEM", "wrong item",
            "MISSING", "missing item",
            "QUALITY", "quality problem",
            "OTHER", "other");

    private static final Map<String, String> COMPLAINT = Map.of(
            "OPEN", "received, waiting for our support team",
            "IN_PROGRESS", "being looked into by our support team",
            "RESOLVED", "resolved");

    /** The complaint categories (contract §2.2) with the words customers use for them. */
    static final Map<String, String> COMPLAINT_CATEGORY = Map.of(
            "DELIVERY", "delivery (late, not delivered, the delivery partner)",
            "PAYMENT", "payment (charged but not confirmed, charged twice, refund not received)",
            "ITEMS", "items (quality, damaged, wrong or missing)",
            "STORE", "the store's or restaurant's service",
            "APP", "the app or website",
            "OTHER", "something else");

    /** Why a refund was made, as a clause ("because …"). */
    private static final Map<String, String> REFUND_REASON = Map.of(
            "ORDER_CANCELLED", "the order was cancelled",
            "LATE_CAPTURE", "the payment arrived after the time to pay had run out",
            "ADMIN", "our support team issued it",
            "RETURN", "a return was approved");

    private static final Map<String, String> PAYMENT_METHOD = Map.of(
            "upi", "UPI",
            "card", "card",
            "netbanking", "net banking",
            "wallet", "wallet",
            "emi", "EMI",
            "paylater", "pay later");

    static String order(String code) {
        return words(ORDER, code);
    }

    static String cancellation(String code) {
        return words(CANCELLATION, code);
    }

    static String delivery(String code) {
        return words(DELIVERY, code);
    }

    static String orderPayment(String code) {
        return words(ORDER_PAYMENT, code);
    }

    static String returnStatus(String code) {
        return words(RETURN, code);
    }

    static String returnReason(String code) {
        return words(RETURN_REASON, code);
    }

    static String complaint(String code) {
        return words(COMPLAINT, code);
    }

    static String refundReason(String code) {
        return words(REFUND_REASON, code);
    }

    /** "upi" → "UPI"; unknown methods as they are; null stays null. */
    static String paymentMethod(String code) {
        return code == null ? null : PAYMENT_METHOD.getOrDefault(code.toLowerCase(java.util.Locale.ROOT), code);
    }

    /** The words for a code, or the code in lower case with spaces ("SOME_CODE" → "some code"); null stays null. */
    private static String words(Map<String, String> words, String code) {
        if (code == null) {
            return null;
        }
        return words.getOrDefault(code, code.toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }
}
