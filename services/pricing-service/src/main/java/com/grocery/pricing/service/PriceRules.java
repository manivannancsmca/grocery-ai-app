package com.grocery.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The price arithmetic of contract §3 (pure functions). */
public final class PriceRules {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceRules() {
    }

    /** Money with exactly 2 decimals. The request was validated to have at most 2, so this never rounds. */
    public static BigDecimal money(BigDecimal value) {
        return value.stripTrailingZeros().setScale(2, RoundingMode.UNNECESSARY);
    }

    /** {@code (new − old) / old × 100} on the selling price, signed, 2 decimals HALF_UP. */
    public static BigDecimal changePercent(BigDecimal oldSellingPrice, BigDecimal newSellingPrice) {
        return newSellingPrice.subtract(oldSellingPrice).multiply(HUNDRED)
                .divide(oldSellingPrice, 2, RoundingMode.HALF_UP);
    }

    /** {@code round((mrp − sellingPrice) / mrp × 100)} HALF_UP, kept within 0–99. */
    public static int discountPercent(BigDecimal mrp, BigDecimal sellingPrice) {
        int discount = mrp.subtract(sellingPrice).multiply(HUNDRED)
                .divide(mrp, 0, RoundingMode.HALF_UP).intValue();
        return Math.clamp(discount, 0, 99);
    }

    /**
     * A change needs an admin when the (rounded, as shown) change percent is beyond the threshold in either
     * direction; exactly the threshold is applied at once.
     */
    public static boolean needsApproval(BigDecimal changePercent, BigDecimal thresholdPercent) {
        return changePercent.abs().compareTo(thresholdPercent) > 0;
    }
}
