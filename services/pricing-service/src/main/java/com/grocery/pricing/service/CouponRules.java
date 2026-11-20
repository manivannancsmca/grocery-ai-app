package com.grocery.pricing.service;

import com.grocery.pricing.dto.CouponDtos;
import com.grocery.pricing.entity.CouponScope;
import com.grocery.pricing.entity.CouponTerms;
import com.grocery.pricing.entity.OrderType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The coupon rules of contract §2, as pure functions (unit-tested in {@code CouponRulesTest}).
 *
 * <h2>Reasons, in the order they are checked</h2>
 * COUPON_NOT_FOUND (no such code; the caller's business), COUPON_PAUSED, COUPON_NOT_STARTED ({@code now <
 * validFrom}), COUPON_EXPIRED ({@code now ≥ validUntil}), COUPON_WRONG_STORE (a PLATFORM coupon for another store
 * type, or a STORE coupon of another store), COUPON_MIN_ORDER ({@code subtotal < minOrderSubtotal}), then the limits:
 * COUPON_USAGE_LIMIT (RESERVED + CONFIRMED uses ≥ {@code totalUsageLimit}) and COUPON_CUSTOMER_LIMIT (the customer's
 * RESERVED + CONFIRMED uses ≥ {@code perCustomerLimit}).
 *
 * <h2>Amounts (2 decimals, HALF_UP)</h2>
 * PERCENT: {@code subtotal × value / 100}, at most {@code maxDiscount} (when set) and the subtotal; FLAT:
 * {@code value}, at most the subtotal; FREE_DELIVERY: no discount, {@code deliveryFeeDiscount} = the delivery fee.
 * Then at least ₹1.00 stays payable ({@link #keepMinimumPayable}: the discount is cut first).
 */
public final class CouponRules {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    /** The least a coupon leaves to pay (subtotal + delivery fee − discounts). */
    public static final BigDecimal MIN_PAYABLE = new BigDecimal("1.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CouponRules() {
    }

    /** What a valid coupon takes off: the items and the delivery fee. */
    public record Amounts(BigDecimal discount, BigDecimal deliveryFeeDiscount) {

        public static final Amounts NONE = new Amounts(ZERO, ZERO);
    }

    /** Codes are stored and compared upper-case, without surrounding blanks. */
    public static String normalizeCode(String code) {
        return code == null ? null : code.strip().toUpperCase(Locale.ROOT);
    }

    public static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** The first reason (paused … minimum order) that needs no usage counts, or null. */
    public static String termsReason(CouponTerms terms, Instant now, UUID storeId, OrderType orderType,
                                     BigDecimal subtotal) {
        if (!terms.active()) {
            return CouponDtos.COUPON_PAUSED;
        }
        if (now.isBefore(terms.validFrom())) {
            return CouponDtos.COUPON_NOT_STARTED;
        }
        if (!now.isBefore(terms.validUntil())) {
            return CouponDtos.COUPON_EXPIRED;
        }
        if (!usableAt(terms, storeId, orderType)) {
            return CouponDtos.COUPON_WRONG_STORE;
        }
        if (subtotal.compareTo(terms.minOrderSubtotal()) < 0) {
            return CouponDtos.COUPON_MIN_ORDER;
        }
        return null;
    }

    /** COUPON_USAGE_LIMIT / COUPON_CUSTOMER_LIMIT from the RESERVED + CONFIRMED counts, or null. */
    public static String limitReason(CouponTerms terms, long used, long usedByCustomer) {
        if (terms.totalUsageLimit() != null && used >= terms.totalUsageLimit()) {
            return CouponDtos.COUPON_USAGE_LIMIT;
        }
        if (usedByCustomer >= terms.perCustomerLimit()) {
            return CouponDtos.COUPON_CUSTOMER_LIMIT;
        }
        return null;
    }

    /**
     * PLATFORM: any store of an accepted type; STORE: exactly its own store (the id alone decides: vendor and
     * restaurant ids never collide, and an admin's STORE coupon may carry an inferred store type).
     */
    public static boolean usableAt(CouponTerms terms, UUID storeId, OrderType orderType) {
        if (terms.scope() == CouponScope.STORE) {
            return terms.storeId() != null && terms.storeId().equals(storeId);
        }
        return terms.storeType().accepts(orderType);
    }

    public static Amounts amounts(CouponTerms terms, BigDecimal subtotal, BigDecimal deliveryFee) {
        BigDecimal items = money(subtotal);
        BigDecimal fee = money(deliveryFee);
        Amounts amounts = switch (terms.discountType()) {
            case PERCENT -> {
                BigDecimal discount = money(items.multiply(terms.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
                if (terms.maxDiscount() != null) {
                    discount = discount.min(terms.maxDiscount());
                }
                yield new Amounts(money(discount.min(items)), ZERO);
            }
            case FLAT -> new Amounts(money(terms.value().min(items)), ZERO);
            case FREE_DELIVERY -> new Amounts(ZERO, fee);
        };
        return keepMinimumPayable(amounts, items, fee);
    }

    /**
     * A coupon never brings the payable total below {@link #MIN_PAYABLE} (payment-service cannot take a ₹0 payment):
     * when {@code subtotal + deliveryFee − discount − deliveryFeeDiscount} would be lower, the {@code discount} is cut
     * first, then the {@code deliveryFeeDiscount}. An order worth less than ₹1.00 gets nothing off.
     */
    public static Amounts keepMinimumPayable(Amounts amounts, BigDecimal subtotal, BigDecimal deliveryFee) {
        BigDecimal maxOff = subtotal.add(deliveryFee).subtract(MIN_PAYABLE).max(ZERO);
        BigDecimal excess = amounts.discount().add(amounts.deliveryFeeDiscount()).subtract(maxOff);
        if (excess.signum() <= 0) {
            return amounts;
        }
        BigDecimal cut = excess.min(amounts.discount());
        BigDecimal discount = amounts.discount().subtract(cut);
        BigDecimal deliveryFeeDiscount = amounts.deliveryFeeDiscount().subtract(excess.subtract(cut)).max(ZERO);
        return new Amounts(money(discount), money(deliveryFeeDiscount));
    }

    /** ACTIVE, PAUSED, SCHEDULED (not started) or EXPIRED at {@code now}. */
    public static String status(CouponTerms terms, Instant now) {
        if (!terms.active()) {
            return "PAUSED";
        }
        if (now.isBefore(terms.validFrom())) {
            return "SCHEDULED";
        }
        if (!now.isBefore(terms.validUntil())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    /** A sentence for a reason (the problem's {@code detail}). */
    public static String message(String reason, String code, BigDecimal minOrderSubtotal) {
        String coupon = "Coupon " + code;
        return switch (reason) {
            case CouponDtos.COUPON_NOT_FOUND -> coupon + " does not exist";
            case CouponDtos.COUPON_PAUSED -> coupon + " is paused";
            case CouponDtos.COUPON_NOT_STARTED -> coupon + " is not valid yet";
            case CouponDtos.COUPON_EXPIRED -> coupon + " has expired";
            case CouponDtos.COUPON_WRONG_STORE -> coupon + " cannot be used at this store";
            case CouponDtos.COUPON_MIN_ORDER -> coupon + " needs a minimum order of ₹"
                    + (minOrderSubtotal == null ? "?" : money(minOrderSubtotal).toPlainString());
            case CouponDtos.COUPON_USAGE_LIMIT -> coupon + " has been fully used";
            case CouponDtos.COUPON_CUSTOMER_LIMIT -> coupon + " has already been used the allowed number of times";
            default -> coupon + " cannot be used";
        };
    }
}
