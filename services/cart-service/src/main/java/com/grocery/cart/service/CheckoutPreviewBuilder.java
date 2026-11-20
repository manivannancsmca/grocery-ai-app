package com.grocery.cart.service;

import com.grocery.cart.client.DownstreamDtos.CustomerAddress;
import com.grocery.cart.config.CartProperties.DeliveryRule;
import com.grocery.cart.dto.CartDtos.AddressView;
import com.grocery.cart.dto.CartDtos.CartView;
import com.grocery.cart.dto.CartDtos.CheckoutPreview;
import com.grocery.cart.dto.CartDtos.DeliveryView;
import com.grocery.cart.dto.CartDtos.Issue;
import com.grocery.cart.service.CartPricer.Coordinates;
import com.grocery.cart.service.CartPricer.PricedCart;
import com.grocery.cart.service.CouponChecker.AppliedCoupon;
import com.grocery.cart.service.DeliveryCalculator.DeliveryQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The checkout preview (contract §3): the priced cart plus the delivery address, the delivery fee and the
 * preview-only issues EMPTY_CART, ADDRESS_REQUIRED, ADDRESS_LOCATION_MISSING, OUT_OF_DELIVERY_AREA and
 * BELOW_MINIMUM_ORDER. {@code canCheckout} = no blocking issue; {@code total} = subtotal + delivery fee − discount −
 * deliveryFeeDiscount (Phase 12: the coupon, checked for the delivery fee; an unusable coupon adds the blocking
 * COUPON_NOT_APPLICABLE and takes nothing off).
 *
 * <p>{@code delivery} is null when the cart is empty, there is no address with a location, or the store's
 * location is unknown. A store that answered without a location gets STORE_UNAVAILABLE (it cannot be delivered
 * from); a store that could not be reached already carries its dependency issue.
 */
@Component
public class CheckoutPreviewBuilder {

    private final DeliveryCalculator delivery;

    public CheckoutPreviewBuilder(DeliveryCalculator delivery) {
        this.delivery = delivery;
    }

    /**
     * @param rules  the delivery fee rules in force ({@link DeliveryFeeRules})
     * @param coupon checks the cart's coupon for a delivery fee (null result: no coupon); called once, after the
     *               delivery fee is known. Its issue (if any) is added last.
     */
    public CheckoutPreview build(PricedCart priced, CustomerAddress address, DeliveryFeeRules.Rules rules,
                                 Instant pricedAt, Function<BigDecimal, AppliedCoupon> coupon) {
        CartView cart = priced.view();
        List<Issue> issues = new ArrayList<>(cart.issues());
        boolean empty = cart.lines().isEmpty();
        if (empty) {
            issues.add(IssueCode.EMPTY_CART.issue(null, "Your cart is empty"));
        }
        if (address == null) {
            issues.add(IssueCode.ADDRESS_REQUIRED.issue(null, "Add a delivery address to check out"));
        } else if (!address.hasLocation()) {
            issues.add(IssueCode.ADDRESS_LOCATION_MISSING.issue(null,
                    "Pin this address on the map so the delivery can be worked out"));
        }

        DeliveryView deliveryView = null;
        if (!empty) {
            DeliveryRule rule = rules.of(cart.store().type());
            String storeName = cart.store().name();
            if (address != null && address.hasLocation()) {
                Coordinates storeLocation = priced.storeLocation();
                if (storeLocation != null) {
                    DeliveryQuote quote = delivery.quote(rule, storeLocation,
                            new Coordinates(address.latitude(), address.longitude()), cart.subtotal());
                    deliveryView = new DeliveryView(quote.distanceKm(), quote.fee(), Money.of(rule.getFreeAbove()),
                            rule.getMaxDistanceKm(), Money.of(rule.getMinimumOrder()));
                    if (!quote.deliverable()) {
                        issues.add(IssueCode.OUT_OF_DELIVERY_AREA.issue(null,
                                "This address is " + quote.distanceKm().toPlainString() + " km away; " + storeName
                                        + " delivers up to " + rule.getMaxDistanceKm().toPlainString() + " km"));
                    }
                } else if (priced.storeChecked()) {
                    issues.add(IssueCode.STORE_UNAVAILABLE.issue(null,
                            storeName + " cannot deliver yet (its location is not set)"));
                }
            }
            BigDecimal minimum = rule.getMinimumOrder();
            if (minimum != null && priced.pricingAvailable() && cart.subtotal().compareTo(minimum) < 0) {
                issues.add(IssueCode.BELOW_MINIMUM_ORDER.issue(null,
                        "Add " + Money.format(minimum.subtract(cart.subtotal())) + " more to reach the minimum order of "
                                + Money.format(minimum)));
            }
        }

        BigDecimal deliveryFee = deliveryView == null ? Money.ZERO : Money.of(deliveryView.fee());
        AppliedCoupon applied = coupon == null ? null : coupon.apply(deliveryFee);
        BigDecimal discount = Money.ZERO;
        BigDecimal deliveryFeeDiscount = Money.ZERO;
        if (applied != null) {
            discount = applied.discount();
            deliveryFeeDiscount = applied.deliveryFeeDiscount();
            if (applied.issue() != null) {
                issues.add(applied.issue());
            }
        }
        BigDecimal total = Money.of(cart.subtotal().add(deliveryFee).subtract(discount).subtract(deliveryFeeDiscount)
                .max(Money.ZERO));
        boolean canCheckout = issues.stream().noneMatch(Issue::blocking);
        return new CheckoutPreview(cart.store(), cart.lines(), cart.itemCount(), cart.subtotal(), cart.mrpTotal(),
                cart.savings(), cart.currency(), List.copyOf(issues), cart.updatedAt(), view(address), deliveryView,
                applied == null ? null : applied.view(), discount, deliveryFeeDiscount, total, canCheckout, pricedAt);
    }

    private static AddressView view(CustomerAddress address) {
        if (address == null) {
            return null;
        }
        return new AddressView(address.id(), address.label(), address.recipientName(), address.phone(),
                address.line1(), address.line2(), address.landmark(), address.city(), address.state(),
                address.postalCode(), address.latitude(), address.longitude());
    }
}
