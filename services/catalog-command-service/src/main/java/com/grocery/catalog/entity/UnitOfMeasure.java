package com.grocery.catalog.entity;

import java.math.BigDecimal;

/** Pack size units, with the short label shown to customers ("500 g", "1.5 kg", "1 L", "6 pcs"). */
public enum UnitOfMeasure {
    G("g"),
    KG("kg"),
    ML("ml"),
    L("L"),
    PCS("pcs"),
    PACK("pack");

    private final String symbol;

    UnitOfMeasure(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** "500 g": the value without trailing zeros, then the unit symbol. */
    public String label(BigDecimal value) {
        return plain(value).toPlainString() + " " + symbol;
    }

    /** 500.000 → 500, 1.500 → 1.5 (never in exponent form, so JSON shows a plain number). */
    public static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
