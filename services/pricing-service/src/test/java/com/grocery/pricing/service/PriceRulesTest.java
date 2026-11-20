package com.grocery.pricing.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceRulesTest {

    private static final BigDecimal TWENTY = new BigDecimal("20");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void moneyHasExactlyTwoDecimals() {
        assertThat(PriceRules.money(d("60"))).hasToString("60.00");
        assertThat(PriceRules.money(d("49.5"))).hasToString("49.50");
        assertThat(PriceRules.money(d("49.500"))).hasToString("49.50");
        assertThat(PriceRules.money(d("1E+2"))).hasToString("100.00");
    }

    @Test
    void changePercentIsSignedWithTwoDecimalsHalfUp() {
        assertThat(PriceRules.changePercent(d("49.00"), d("45.00"))).isEqualByComparingTo("-8.16");    // -8.163
        assertThat(PriceRules.changePercent(d("49.00"), d("25.00"))).isEqualByComparingTo("-48.98");   // -48.979
        assertThat(PriceRules.changePercent(d("100.00"), d("120.01"))).isEqualByComparingTo("20.01");
        assertThat(PriceRules.changePercent(d("3.00"), d("3.01"))).isEqualByComparingTo("0.33");       // 0.333
        assertThat(PriceRules.changePercent(d("8.00"), d("8.01"))).isEqualByComparingTo("0.13");       // 0.125 → up
        assertThat(PriceRules.changePercent(d("1.00"), d("1000000.00"))).isEqualByComparingTo("99999900.00");
    }

    @Test
    void exactlyTheThresholdIsAppliedBeyondItNeedsApproval() {
        assertThat(PriceRules.needsApproval(d("20.00"), TWENTY)).isFalse();
        assertThat(PriceRules.needsApproval(d("-20.00"), TWENTY)).isFalse();
        assertThat(PriceRules.needsApproval(d("20.01"), TWENTY)).isTrue();
        assertThat(PriceRules.needsApproval(d("-20.01"), TWENTY)).isTrue();
        assertThat(PriceRules.needsApproval(d("0.00"), BigDecimal.ZERO)).isFalse();
    }

    @Test
    void discountPercentIsRoundedHalfUpAndKeptWithin0To99() {
        assertThat(PriceRules.discountPercent(d("60.00"), d("49.00"))).isEqualTo(18);    // 18.33
        assertThat(PriceRules.discountPercent(d("100.00"), d("100.00"))).isZero();
        assertThat(PriceRules.discountPercent(d("8.00"), d("7.00"))).isEqualTo(13);      // 12.5 → 13
        assertThat(PriceRules.discountPercent(d("100.00"), d("1.00"))).isEqualTo(99);
        assertThat(PriceRules.discountPercent(d("1000000.00"), d("1.00"))).isEqualTo(99); // 99.9999 → 100 → 99
    }
}
