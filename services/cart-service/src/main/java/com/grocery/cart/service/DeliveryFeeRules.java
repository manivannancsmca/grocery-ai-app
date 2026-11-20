package com.grocery.cart.service;

import com.grocery.cart.client.CallContext;
import com.grocery.cart.client.DownstreamDtos.DeliveryFees;
import com.grocery.cart.client.PricingClient;
import com.grocery.cart.config.CartProperties;
import com.grocery.cart.config.CartProperties.DeliveryRule;
import com.grocery.cart.model.StoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
/**
 * The delivery fee rules in force (docs/api/platform-settings.md, area {@code delivery-fees}). pricing-service owns
 * the fees, which admins can change; this keeps its answer ({@code GET /internal/delivery-fees}) for
 * {@code grocery.cart.delivery.settings-cache-ttl} (15 s), so checkout previews ask it at most once per interval.
 *
 * <p>A checkout never fails because of this. When pricing-service cannot be reached (or does not have the endpoint
 * yet), the last fees it gave stay in use, and before its first answer the fees of {@code grocery.cart.delivery.*};
 * the next attempt is one interval later, so a dead pricing-service costs one warning per interval. The road factor
 * and each store type's maximum distance always come from this service's config (vendor-service's "stores near
 * you" uses the same numbers).
 */
@Component
public class DeliveryFeeRules {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFeeRules.class);

    /** The rule of each store type. */
    public record Rules(DeliveryRule grocery, DeliveryRule restaurant) {

        public DeliveryRule of(StoreType type) {
            return type == StoreType.GROCERY ? grocery : restaurant;
        }
    }

    /** What is known, whether pricing-service said so, and until when it is used without asking again. */
    private record Known(Rules rules, boolean fromPricing, long expiresAtNanos) {

        boolean isFresh() {
            return System.nanoTime() - expiresAtNanos < 0;
        }
    }

    private final PricingClient pricing;
    private final CartProperties.Delivery config;
    private volatile Known known;

    public DeliveryFeeRules(PricingClient pricing, CartProperties properties) {
        this.pricing = pricing;
        this.config = properties.getDelivery();
        this.known = new Known(new Rules(config.getGrocery(), config.getRestaurant()), false, System.nanoTime());
    }

    /**
     * The rules to charge now. Asks pricing-service (with the caller's token, as every call of the cart) when the
     * known fees are older than the interval; requests arriving during that call wait for it rather than use fees
     * that may be out of date.
     */
    public Rules current(CallContext context) {
        Known current = known;
        if (current.isFresh()) {
            return current.rules();
        }
        synchronized (this) {
            current = known;
            if (current.isFresh()) {
                return current.rules();                 // another request has just asked
            }
            long next = System.nanoTime() + config.getSettingsCacheTtl().toNanos();
            DeliveryFees fees = pricing.deliveryFees(context).valueOrNull();
            if (fees != null) {
                current = new Known(merged(fees), true, next);
            } else {
                log.warn("pricing-service did not answer with the delivery fees; using {} for the next {}",
                        current.fromPricing() ? "the last fees it gave" : "grocery.cart.delivery.*",
                        config.getSettingsCacheTtl());
                current = new Known(current.rules(), current.fromPricing(), next);
            }
            known = current;
            return current.rules();
        }
    }

    /** Forgets when the fees were read: the next preview asks pricing-service again (tests). */
    public void expire() {
        known = new Known(known.rules(), known.fromPricing(), System.nanoTime());
    }

    /** pricing-service's fees on top of this service's rules (a field it did not send keeps the configured value). */
    private Rules merged(DeliveryFees fees) {
        DeliveryRule grocery = copy(config.getGrocery());
        grocery.setBaseFee(or(fees.groceryBaseFee(), grocery.getBaseFee()));
        grocery.setIncludedKm(or(fees.groceryIncludedKm(), grocery.getIncludedKm()));
        grocery.setPerExtraKm(or(fees.groceryPerExtraKm(), grocery.getPerExtraKm()));
        grocery.setFreeAbove(or(fees.groceryFreeAbove(), grocery.getFreeAbove()));
        grocery.setMinimumOrder(or(fees.groceryMinimumOrder(), grocery.getMinimumOrder()));
        DeliveryRule restaurant = copy(config.getRestaurant());
        restaurant.setBaseFee(or(fees.restaurantBaseFee(), restaurant.getBaseFee()));
        restaurant.setIncludedKm(or(fees.restaurantIncludedKm(), restaurant.getIncludedKm()));
        restaurant.setPerExtraKm(or(fees.restaurantPerExtraKm(), restaurant.getPerExtraKm()));
        return new Rules(grocery, restaurant);
    }

    private static DeliveryRule copy(DeliveryRule source) {
        DeliveryRule rule = new DeliveryRule();
        rule.setIncludedKm(source.getIncludedKm());
        rule.setBaseFee(source.getBaseFee());
        rule.setPerExtraKm(source.getPerExtraKm());
        rule.setFreeAbove(source.getFreeAbove());
        rule.setMaxDistanceKm(source.getMaxDistanceKm());
        rule.setMinimumOrder(source.getMinimumOrder());
        return rule;
    }

    private static BigDecimal or(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }
}
