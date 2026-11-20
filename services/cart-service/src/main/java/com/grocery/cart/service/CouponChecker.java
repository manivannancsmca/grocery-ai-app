package com.grocery.cart.service;

import com.grocery.cart.client.CallContext;
import com.grocery.cart.client.DownstreamDtos.CouponEvaluation;
import com.grocery.cart.client.DownstreamDtos.CouponEvaluationRequest;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.client.PricingClient;
import com.grocery.cart.dto.CartDtos.CouponView;
import com.grocery.cart.dto.CartDtos.Issue;
import com.grocery.cart.model.Store;
import com.grocery.cart.model.StoredCart;
import com.grocery.cart.model.StoredCoupon;
import com.grocery.cart.service.CartPricer.PricedCart;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * The cart's coupon (Phase 12 §2), checked live with pricing-service's {@code evaluate} every time the cart or the
 * preview is shown — one call, after the cart is priced (it needs the subtotal and, in the preview, the delivery fee).
 * A coupon that cannot be used stays visible with {@code valid: false}, its reason and message, and the blocking
 * issue COUPON_NOT_APPLICABLE. It is not checked (reason COUPON_UNAVAILABLE, blocking) when the cart's prices are
 * unavailable or pricing-service cannot be reached — never an error.
 */
@Component
public class CouponChecker {

    /** pricing-service could not be asked, or the cart's prices are unknown. */
    public static final String COUPON_UNAVAILABLE = "COUPON_UNAVAILABLE";
    /** The cart has no items (no store to evaluate the coupon for). */
    public static final String EMPTY_CART = "EMPTY_CART";
    static final String COUPON_MIN_ORDER = "COUPON_MIN_ORDER";
    /** The least a coupon leaves to pay (subtotal + delivery fee − discounts). */
    static final BigDecimal MIN_PAYABLE = new BigDecimal("1.00");

    private final PricingClient pricing;

    public CouponChecker(PricingClient pricing) {
        this.pricing = pricing;
    }

    /** The checked coupon: what the view shows, its issue (null when valid) and the amounts to take off. */
    public record AppliedCoupon(CouponView view, Issue issue, BigDecimal discount, BigDecimal deliveryFeeDiscount) {
    }

    /** Codes are compared upper-case, without surrounding blanks. */
    public static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }

    /** One {@code evaluate} call for the cart's store. */
    public Outcome<CouponEvaluation> evaluate(CallContext context, UUID customerId, String code, Store store,
                                             BigDecimal subtotal, BigDecimal deliveryFee) {
        return pricing.evaluate(context, new CouponEvaluationRequest(code, customerId, store.id(),
                store.type().name(), Money.of(subtotal), Money.of(deliveryFee)));
    }

    /**
     * Checks the cart's coupon against the priced cart; null when the cart has no coupon.
     *
     * @param deliveryFee the delivery fee the coupon may waive (0 outside the preview)
     */
    public AppliedCoupon check(CallContext context, UUID customerId, StoredCart cart, PricedCart priced,
                               BigDecimal deliveryFee) {
        StoredCoupon coupon = cart.coupon();
        if (coupon == null) {
            return null;
        }
        String storeName = priced.view().store() == null ? null : priced.view().store().name();
        if (cart.isEmpty()) {
            return invalid(coupon.code(), EMPTY_CART, null, BigDecimal.ZERO, storeName);
        }
        BigDecimal subtotal = priced.view().subtotal();
        if (!priced.pricingAvailable()) {
            return invalid(coupon.code(), COUPON_UNAVAILABLE, null, subtotal, storeName);
        }
        CouponEvaluation result = evaluate(context, customerId, coupon.code(), cart.store(), subtotal, deliveryFee)
                .valueOrNull();
        if (result == null) {
            return invalid(coupon.code(), COUPON_UNAVAILABLE, null, subtotal, storeName);
        }
        return from(coupon.code(), result, subtotal, deliveryFee, storeName);
    }

    /** The view of an evaluation already made (the response of PUT /api/cart/coupon). */
    public AppliedCoupon from(String code, CouponEvaluation result, BigDecimal subtotal, BigDecimal deliveryFee,
                              String storeName) {
        if (!result.isValid()) {
            String reason = result.reason() == null ? "COUPON_NOT_APPLICABLE" : result.reason();
            return invalid(code, reason, result.minOrderSubtotal(), subtotal, storeName);
        }
        // never more than what there is to discount, and at least ₹1.00 stays payable (pricing-service's rule too:
        // payment-service cannot take ₹0) — the discount is cut first
        BigDecimal discount = orZero(result.discount()).min(Money.of(subtotal)).max(Money.ZERO);
        BigDecimal deliveryFeeDiscount = orZero(result.deliveryFeeDiscount()).min(Money.of(deliveryFee))
                .max(Money.ZERO);
        BigDecimal maxOff = Money.of(subtotal).add(Money.of(deliveryFee)).subtract(MIN_PAYABLE).max(Money.ZERO);
        BigDecimal excess = discount.add(deliveryFeeDiscount).subtract(maxOff);
        if (excess.signum() > 0) {
            BigDecimal cut = excess.min(discount);
            discount = discount.subtract(cut);
            deliveryFeeDiscount = deliveryFeeDiscount.subtract(excess.subtract(cut)).max(Money.ZERO);
        }
        CouponView view = new CouponView(code, true, Money.of(discount), Money.of(deliveryFeeDiscount), null, null,
                null);
        return new AppliedCoupon(view, null, view.discount(), view.deliveryFeeDiscount());
    }

    private static AppliedCoupon invalid(String code, String reason, BigDecimal minOrderSubtotal, BigDecimal subtotal,
                                         String storeName) {
        BigDecimal minimum = COUPON_MIN_ORDER.equals(reason) ? Money.of(minOrderSubtotal) : null;
        String message = message(reason, code, minimum, subtotal, storeName);
        CouponView view = new CouponView(code, false, Money.ZERO, Money.ZERO, reason, message, minimum);
        return new AppliedCoupon(view, IssueCode.COUPON_NOT_APPLICABLE.issue(null, message), Money.ZERO, Money.ZERO);
    }

    /** What the customer reads (the coupon view's {@code message}, the issue and the 422 detail). */
    public static String message(String reason, String code, BigDecimal minOrderSubtotal, BigDecimal subtotal,
                                 String storeName) {
        String coupon = "Coupon " + code;
        return switch (reason) {
            case "COUPON_NOT_FOUND" -> coupon + " does not exist";
            case "COUPON_PAUSED" -> coupon + " is not available right now";
            case "COUPON_NOT_STARTED" -> coupon + " is not valid yet";
            case "COUPON_EXPIRED" -> coupon + " has expired";
            case "COUPON_WRONG_STORE" -> coupon + " cannot be used at "
                    + (storeName == null || storeName.isBlank() ? "this store" : storeName);
            case COUPON_MIN_ORDER -> minOrderSubtotal == null || subtotal == null
                    ? coupon + " needs a higher order value"
                    : "Add " + Money.format(minOrderSubtotal.subtract(subtotal).max(Money.ZERO)) + " more to use "
                    + code + " (minimum order " + Money.format(minOrderSubtotal) + ")";
            case "COUPON_USAGE_LIMIT" -> coupon + " has been fully used";
            case "COUPON_CUSTOMER_LIMIT" -> "You have already used coupon " + code;
            case COUPON_UNAVAILABLE -> coupon + " could not be checked. Please try again shortly.";
            case EMPTY_CART -> "Add items to your cart to use coupon " + code;
            default -> coupon + " cannot be used";
        };
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? Money.ZERO : amount;
    }
}
