package com.grocery.payment.gateway;

import com.grocery.payment.gateway.PaymentGateway.ProviderOrder;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPaymentStatus;
import com.grocery.payment.gateway.PaymentGateway.ProviderRefund;
import com.grocery.payment.gateway.PaymentGateway.ProviderRefundStatus;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Reads Razorpay's JSON entities (API responses and webhook {@code payload.*.entity}), tolerant of missing fields.
 * Amounts arrive in paise.
 * <pre>
 * order   { "id", "amount", "currency", "receipt", "status" }
 * payment { "id", "order_id", "status": created|authorized|captured|refunded|failed, "method", "amount", "currency",
 *           "error_code", "error_description" }
 * refund  { "id", "payment_id", "amount", "status": pending|processed|failed, "notes": { "refundId" } }
 * </pre>
 */
public final class RazorpayJson {

    private RazorpayJson() {
    }

    public static ProviderOrder order(JsonNode node) {
        return new ProviderOrder(text(node, "id"), rupees(node, "amount"), text(node, "currency"),
                text(node, "receipt"), text(node, "status"));
    }

    /**
     * A {@code refunded} payment with {@code "captured": false} is an authorization Razorpay released (auto-refund of
     * an uncaptured payment): it was never captured, so it is read as {@code created} (not paid).
     */
    public static ProviderPayment payment(JsonNode node) {
        ProviderPaymentStatus status = ProviderPaymentStatus.of(text(node, "status"));
        JsonNode captured = node == null ? null : node.get("captured");
        if (status == ProviderPaymentStatus.REFUNDED && captured != null && captured.isBoolean()
                && !captured.asBoolean()) {
            status = ProviderPaymentStatus.CREATED;
        }
        return new ProviderPayment(text(node, "id"), text(node, "order_id"), status, text(node, "method"),
                rupees(node, "amount"), text(node, "currency"), text(node, "error_code"),
                text(node, "error_description"));
    }

    public static ProviderRefund refund(JsonNode node) {
        JsonNode notes = node == null ? null : node.get("notes");
        return new ProviderRefund(text(node, "id"), text(node, "payment_id"), rupees(node, "amount"),
                ProviderRefundStatus.of(text(node, "status")), notes == null ? null : text(notes, "refundId"));
    }

    /** A string field; null when absent, JSON null or empty. */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isEmpty() ? null : text;
    }

    static BigDecimal rupees(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return Paise.toRupees(value.asLong());
    }
}
