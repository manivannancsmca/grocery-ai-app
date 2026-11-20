package com.grocery.notification.dispatch;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rupee amounts as people read them in India: {@code ₹1,234.50}, {@code ₹12,34,567.00} (lakh / crore grouping). */
public final class Money {

    private static final String RUPEE = "₹";

    private Money() {
    }

    /** {@code null} → {@code null} (callers choose the wording without an amount). */
    public static String inr(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        String plain = value.abs().toPlainString();
        int dot = plain.indexOf('.');
        String sign = value.signum() < 0 ? "-" : "";
        return sign + RUPEE + group(plain.substring(0, dot)) + plain.substring(dot);
    }

    /** "1234567" → "12,34,567": the last three digits, then groups of two. */
    static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String head = digits.substring(0, digits.length() - 3);
        StringBuilder grouped = new StringBuilder();
        int first = head.length() % 2;
        if (first > 0) {
            grouped.append(head, 0, first);
        }
        for (int i = first; i < head.length(); i += 2) {
            if (!grouped.isEmpty()) {
                grouped.append(',');
            }
            grouped.append(head, i, i + 2);
        }
        return grouped.append(',').append(digits, digits.length() - 3, digits.length()).toString();
    }
}
