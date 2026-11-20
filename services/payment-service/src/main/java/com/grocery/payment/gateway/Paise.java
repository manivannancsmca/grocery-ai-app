package com.grocery.payment.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is stored in rupees (DECIMAL(12,2)) and converted to paise only at the provider boundary (ADR-0010). */
public final class Paise {

    private Paise() {
    }

    public static long of(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static BigDecimal toRupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }
}
