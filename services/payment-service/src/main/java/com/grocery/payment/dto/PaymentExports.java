package com.grocery.payment.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.entity.Payment;

import java.util.Locale;
import java.util.Map;

/**
 * The CSV / Excel / PDF export of the admin payment monitor: the fields of the list's rows and the payment drawer
 * (never the contact phone, signatures, key secrets or anything about cards — payment-service stores none of those
 * in a payment) and the orders a client may choose.
 */
public final class PaymentExports {

    private PaymentExports() {
    }

    /**
     * Newest first like the list; a client may sort by order number, amount, status or the amount refunded (the
     * page's column keys {@code order}, {@code amount}, {@code status}, {@code refunds}; never-null columns).
     */
    public static final ExportSort<Payment> ADMIN_SORT = ExportSort.<Payment>by(
                    Key.desc("createdAt", Payment::getCreatedAt), Key.desc("id", Payment::getId))
            .option("order", "orderNumber", Payment::getOrderNumber)
            .option("amount", "amount", Payment::getAmount)
            .option("status", "status", Payment::getStatus)
            .option("refunds", "refundedAmount", Payment::getRefundedAmount)
            .build();

    /** {@code GET /api/admin/payments/export}: no contact details (the customer by id only). */
    public static final ExportTable<PaymentView> ADMIN_PAYMENTS = ExportTable.<PaymentView>named(
                    "payments", "Payments")
            .text("Order number", PaymentView::orderNumber)
            .dateTime("Created", PaymentView::createdAt)
            .money("Amount (INR)", PaymentView::amount)
            .label("Status", PaymentView::status)
            .label("Refund status", PaymentView::refundStatus)
            .money("Refunded (INR)", PaymentView::refundedAmount)
            .integer("Refunds", view -> view.refunds() == null ? null : view.refunds().size())
            .text("Method", view -> method(view.method()))
            .dateTime("Captured", PaymentView::capturedAt)
            .dateTime("Payment window ends", PaymentView::expiresAt)
            .text("Razorpay order", PaymentView::razorpayOrderId)
            .text("Razorpay payment", PaymentView::razorpayPaymentId)
            .text("Order ID", PaymentView::orderId)
            .text("Customer ID", PaymentView::customerId)
            .text("Payment ID", PaymentView::id)
            .build();

    /** Razorpay's payment {@code method} codes in the page's words ("upi" → "UPI"). */
    private static final Map<String, String> METHODS = Map.of("upi", "UPI", "card", "Card", "netbanking",
            "Net banking", "wallet", "Wallet", "emi", "EMI", "cardless_emi", "Cardless EMI", "paylater", "Pay later");

    private static String method(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String known = METHODS.get(code.trim().toLowerCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        String words = code.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
