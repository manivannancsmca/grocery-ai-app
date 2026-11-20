package com.grocery.ai.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** INR amounts as shown to people: {@code ₹285.00} (2 decimals, no grouping). */
public final class Money {

    private Money() {
    }

    public static String inr(BigDecimal amount) {
        return amount == null ? "—" : "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static BigDecimal scale(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** A price filter as words: " up to ₹50.00", " from ₹100.00", " between ₹10.00 and ₹50.00", or "". */
    public static String range(BigDecimal min, BigDecimal max) {
        if (min != null && max != null) {
            return " between " + inr(min) + " and " + inr(max);
        }
        if (max != null) {
            return " up to " + inr(max);
        }
        return min != null ? " from " + inr(min) : "";
    }
}
