package com.grocery.pricing.service;

import com.grocery.pricing.entity.CouponScope;
import com.grocery.pricing.entity.CouponStoreType;
import com.grocery.pricing.entity.CouponTerms;
import com.grocery.pricing.entity.DiscountType;
import com.grocery.pricing.entity.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure coupon rules: amounts, reasons and their order, status, code normalisation. */
class CouponRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID STORE = UUID.randomUUID();

    private static CouponTerms terms(DiscountType type, String value, String maxDiscount, String minOrder) {
        return new CouponTerms("SAVE10", "Save", null, CouponScope.PLATFORM, CouponStoreType.ANY, null, type,
                value == null ? null : new BigDecimal(value), maxDiscount == null ? null : new BigDecimal(maxDiscount),
                new BigDecimal(minOrder), NOW.minusSeconds(60), NOW.plusSeconds(3600), null, 1, true, true);
    }

    private static CouponTerms storeTerms(CouponStoreType type, UUID storeId) {
        return new CouponTerms("STORE10", "Store", null, CouponScope.STORE, type, storeId, DiscountType.FLAT,
                new BigDecimal("10.00"), null, new BigDecimal("0.00"), NOW.minusSeconds(60), NOW.plusSeconds(3600),
                null, 1, true, true);
    }

    private static CouponRules.Amounts amounts(CouponTerms terms, String subtotal, String deliveryFee) {
        return CouponRules.amounts(terms, new BigDecimal(subtotal), new BigDecimal(deliveryFee));
    }

    @Test
    void percentOfTheSubtotalRoundedHalfUpAndCappedByMaxDiscount() {
        CouponRules.Amounts uncapped = amounts(terms(DiscountType.PERCENT, "15.00", null, "0"), "333.33", "25.00");
        assertThat(uncapped.discount()).isEqualByComparingTo("50.00");          // 49.9995 → 50.00
        assertThat(uncapped.discount().scale()).isEqualTo(2);
        assertThat(uncapped.deliveryFeeDiscount()).isEqualByComparingTo("0.00");

        CouponRules.Amounts capped = amounts(terms(DiscountType.PERCENT, "50.00", "75.00", "0"), "1000.00", "25.00");
        assertThat(capped.discount()).isEqualByComparingTo("75.00");             // 500 capped at 75

        CouponRules.Amounts underCap = amounts(terms(DiscountType.PERCENT, "10.00", "75.00", "0"), "300.00", "0.00");
        assertThat(underCap.discount()).isEqualByComparingTo("30.00");
    }

    @Test
    void flatIsCappedAtTheSubtotal() {
        assertThat(amounts(terms(DiscountType.FLAT, "100.00", null, "0"), "450.00", "30.00").discount())
                .isEqualByComparingTo("100.00");
        assertThat(amounts(terms(DiscountType.FLAT, "200.00", null, "0"), "150.00", "30.00").discount())
                .isEqualByComparingTo("150.00");
        assertThat(amounts(terms(DiscountType.FLAT, "200.00", null, "0"), "0.00", "30.00").discount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void freeDeliveryWaivesTheDeliveryFeeOnly() {
        CouponRules.Amounts free = amounts(terms(DiscountType.FREE_DELIVERY, null, null, "0"), "450.00", "37.00");
        assertThat(free.discount()).isEqualByComparingTo("0.00");
        assertThat(free.deliveryFeeDiscount()).isEqualByComparingTo("37.00");
    }

    @Test
    void atLeastOneRupeeStaysPayableTheDiscountIsCutFirst() {
        // FLAT equal to the subtotal with free delivery: ₹1.00 stays
        CouponRules.Amounts flat = amounts(terms(DiscountType.FLAT, "150.00", null, "0"), "150.00", "0.00");
        assertThat(flat.discount()).isEqualByComparingTo("149.00");
        assertThat(flat.deliveryFeeDiscount()).isEqualByComparingTo("0.00");
        // with a delivery fee to pay the whole subtotal may go
        assertThat(amounts(terms(DiscountType.FLAT, "150.00", null, "0"), "150.00", "30.00").discount())
                .isEqualByComparingTo("150.00");
        // a 90 % coupon on a ₹1.00 order leaves ₹1.00: nothing off
        assertThat(amounts(terms(DiscountType.PERCENT, "90", null, "0"), "1.00", "0.00").discount())
                .isEqualByComparingTo("0.00");
        // free delivery on a ₹0.50 cart (all lines unpriced but one): the fee discount is cut to keep ₹1.00
        CouponRules.Amounts free = amounts(terms(DiscountType.FREE_DELIVERY, null, null, "0"), "0.50", "25.00");
        assertThat(free.discount()).isEqualByComparingTo("0.00");
        assertThat(free.deliveryFeeDiscount()).isEqualByComparingTo("24.50");
        // both at once: the discount goes first, then the delivery fee discount
        CouponRules.Amounts both = CouponRules.keepMinimumPayable(
                new CouponRules.Amounts(new BigDecimal("10.00"), new BigDecimal("5.00")), new BigDecimal("10.00"),
                new BigDecimal("5.00"));
        assertThat(both.discount()).isEqualByComparingTo("9.00");
        assertThat(both.deliveryFeeDiscount()).isEqualByComparingTo("5.00");
        CouponRules.Amounts below = CouponRules.keepMinimumPayable(
                new CouponRules.Amounts(new BigDecimal("0.40"), new BigDecimal("0.30")), new BigDecimal("0.40"),
                new BigDecimal("0.30"));
        assertThat(below.discount()).isEqualByComparingTo("0.00");
        assertThat(below.deliveryFeeDiscount()).isEqualByComparingTo("0.00");
    }

    @Test
    void reasonsInContractOrder() {
        BigDecimal subtotal = new BigDecimal("100.00");
        CouponTerms fine = terms(DiscountType.FLAT, "10", null, "50.00");
        assertThat(CouponRules.termsReason(fine, NOW, STORE, OrderType.GROCERY, subtotal)).isNull();

        CouponTerms paused = new CouponTerms("X", "X", null, CouponScope.PLATFORM, CouponStoreType.RESTAURANT, null,
                DiscountType.FLAT, BigDecimal.TEN, null, new BigDecimal("500.00"), NOW.plusSeconds(60),
                NOW.plusSeconds(120), null, 1, false, true);
        // paused wins over not started, wrong store and minimum order
        assertThat(CouponRules.termsReason(paused, NOW, STORE, OrderType.GROCERY, subtotal)).isEqualTo("COUPON_PAUSED");

        CouponTerms notStarted = new CouponTerms("X", "X", null, CouponScope.PLATFORM, CouponStoreType.RESTAURANT,
                null, DiscountType.FLAT, BigDecimal.TEN, null, new BigDecimal("500.00"), NOW.plusSeconds(60),
                NOW.plusSeconds(120), null, 1, true, true);
        assertThat(CouponRules.termsReason(notStarted, NOW, STORE, OrderType.GROCERY, subtotal))
                .isEqualTo("COUPON_NOT_STARTED");

        CouponTerms expired = new CouponTerms("X", "X", null, CouponScope.PLATFORM, CouponStoreType.ANY, null,
                DiscountType.FLAT, BigDecimal.TEN, null, BigDecimal.ZERO, NOW.minusSeconds(120), NOW, null, 1, true,
                true);
        assertThat(CouponRules.termsReason(expired, NOW, STORE, OrderType.GROCERY, subtotal))
                .as("validUntil is exclusive").isEqualTo("COUPON_EXPIRED");

        CouponTerms restaurantsOnly = new CouponTerms("X", "X", null, CouponScope.PLATFORM,
                CouponStoreType.RESTAURANT, null, DiscountType.FLAT, BigDecimal.TEN, null, new BigDecimal("500.00"),
                NOW.minusSeconds(60), NOW.plusSeconds(60), null, 1, true, true);
        assertThat(CouponRules.termsReason(restaurantsOnly, NOW, STORE, OrderType.GROCERY, subtotal))
                .isEqualTo("COUPON_WRONG_STORE");
        assertThat(CouponRules.termsReason(restaurantsOnly, NOW, STORE, OrderType.RESTAURANT, subtotal))
                .isEqualTo("COUPON_MIN_ORDER");

        assertThat(CouponRules.termsReason(fine, NOW, STORE, OrderType.GROCERY, new BigDecimal("49.99")))
                .isEqualTo("COUPON_MIN_ORDER");
        assertThat(CouponRules.termsReason(fine, NOW, STORE, OrderType.GROCERY, new BigDecimal("50.00"))).isNull();
    }

    @Test
    void storeCouponsOnlyAtTheirOwnStore() {
        CouponTerms vendorCoupon = storeTerms(CouponStoreType.GROCERY, STORE);
        assertThat(CouponRules.usableAt(vendorCoupon, STORE, OrderType.GROCERY)).isTrue();
        assertThat(CouponRules.usableAt(vendorCoupon, UUID.randomUUID(), OrderType.GROCERY)).isFalse();
        assertThat(CouponRules.usableAt(vendorCoupon, STORE, OrderType.RESTAURANT)).as("the store id decides").isTrue();
        CouponTerms anyStore = terms(DiscountType.FLAT, "10", null, "0");
        assertThat(CouponRules.usableAt(anyStore, UUID.randomUUID(), OrderType.RESTAURANT)).isTrue();
    }

    @Test
    void limitsCountReservedAndConfirmedUses() {
        CouponTerms limited = new CouponTerms("X", "X", null, CouponScope.PLATFORM, CouponStoreType.ANY, null,
                DiscountType.FLAT, BigDecimal.TEN, null, BigDecimal.ZERO, NOW.minusSeconds(60), NOW.plusSeconds(60),
                3, 2, true, true);
        assertThat(CouponRules.limitReason(limited, 2, 1)).isNull();
        assertThat(CouponRules.limitReason(limited, 3, 0)).isEqualTo("COUPON_USAGE_LIMIT");
        assertThat(CouponRules.limitReason(limited, 3, 2)).as("total first").isEqualTo("COUPON_USAGE_LIMIT");
        assertThat(CouponRules.limitReason(limited, 2, 2)).isEqualTo("COUPON_CUSTOMER_LIMIT");
        CouponTerms unlimited = terms(DiscountType.FLAT, "10", null, "0");
        assertThat(CouponRules.limitReason(unlimited, 1_000_000, 0)).isNull();
    }

    @Test
    void statusAndCodes() {
        assertThat(CouponRules.status(terms(DiscountType.FLAT, "10", null, "0"), NOW)).isEqualTo("ACTIVE");
        assertThat(CouponRules.status(terms(DiscountType.FLAT, "10", null, "0"), NOW.plusSeconds(3600)))
                .isEqualTo("EXPIRED");
        assertThat(CouponRules.status(terms(DiscountType.FLAT, "10", null, "0"), NOW.minusSeconds(120)))
                .isEqualTo("SCHEDULED");
        assertThat(CouponRules.normalizeCode("  save-50 ")).isEqualTo("SAVE-50");
        assertThat(CouponRules.message("COUPON_MIN_ORDER", "SAVE50", new BigDecimal("199")))
                .isEqualTo("Coupon SAVE50 needs a minimum order of ₹199.00");
    }
}
