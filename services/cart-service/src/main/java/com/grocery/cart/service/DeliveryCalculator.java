package com.grocery.cart.service;

import com.grocery.cart.config.CartProperties;
import com.grocery.cart.config.CartProperties.DeliveryRule;
import com.grocery.cart.service.CartPricer.Coordinates;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Delivery distance and fee (contract §3) with a store type's rule: the fees in force come from
 * {@link DeliveryFeeRules} (admin platform settings), road factor and maximum distance from
 * {@code grocery.cart.delivery.*}.
 * <ul>
 *   <li>{@code distanceKm} = haversine distance between store and address × road factor (1.3), 1 decimal HALF_UP;</li>
 *   <li>{@code fee} = base fee + every started kilometre beyond the included distance × per-km fee
 *       ({@code ceil(distanceKm − includedKm) × perExtraKm}), computed on the rounded distance;</li>
 *   <li>free when the subtotal is at least {@code freeAbove} (when the store type has one);</li>
 *   <li>deliverable when {@code distanceKm ≤ maxDistanceKm}.</li>
 * </ul>
 */
@Component
public class DeliveryCalculator {

    static final double EARTH_RADIUS_KM = 6371.0;

    private final BigDecimal roadFactor;

    public DeliveryCalculator(CartProperties properties) {
        this.roadFactor = properties.getDelivery().getRoadFactor();
    }

    public DeliveryQuote quote(DeliveryRule rule, Coordinates store, Coordinates address, BigDecimal subtotal) {
        BigDecimal distanceKm = BigDecimal.valueOf(haversineKm(store, address))
                .multiply(roadFactor)
                .setScale(1, RoundingMode.HALF_UP);
        BigDecimal fee = rule.getBaseFee();
        BigDecimal extraKm = distanceKm.subtract(rule.getIncludedKm());
        if (extraKm.signum() > 0) {
            fee = fee.add(extraKm.setScale(0, RoundingMode.CEILING).multiply(rule.getPerExtraKm()));
        }
        if (rule.getFreeAbove() != null && subtotal.compareTo(rule.getFreeAbove()) >= 0) {
            fee = BigDecimal.ZERO;
        }
        boolean deliverable = distanceKm.compareTo(rule.getMaxDistanceKm()) <= 0;
        return new DeliveryQuote(distanceKm, Money.of(fee), deliverable, rule);
    }

    /** Great-circle distance in km. */
    static double haversineKm(Coordinates from, Coordinates to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public record DeliveryQuote(BigDecimal distanceKm, BigDecimal fee, boolean deliverable, DeliveryRule rule) {
    }
}
