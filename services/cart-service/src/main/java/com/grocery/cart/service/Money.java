package com.grocery.cart.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** INR amounts: BigDecimal, 2 decimals, HALF_UP. */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() {
    }

    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal times(BigDecimal unitPrice, int quantity) {
        return of(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /** "₹50.00" */
    public static String format(BigDecimal amount) {
        return "₹" + of(amount).toPlainString();
    }
}
