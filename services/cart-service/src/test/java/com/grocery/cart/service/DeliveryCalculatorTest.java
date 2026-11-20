package com.grocery.cart.service;

import com.grocery.cart.config.CartProperties;
import com.grocery.cart.service.CartPricer.Coordinates;
import com.grocery.cart.service.DeliveryCalculator.DeliveryQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Delivery maths with the contract's default table (no Spring context). */
class DeliveryCalculatorTest {

    private static final Coordinates STORE = new Coordinates(12.9716, 77.5946);
    private static final double KM_PER_DEGREE = DeliveryCalculator.EARTH_RADIUS_KM * Math.PI / 180;

    private final CartProperties.Delivery rules = new CartProperties().getDelivery();
    private final DeliveryCalculator calculator = new DeliveryCalculator(new CartProperties());

    /** A point whose road distance (× 1.3) from the store is {@code roadKm}. */
    private static Coordinates roadKmAway(double roadKm) {
        return new Coordinates(STORE.latitude() + roadKm / 1.3 / KM_PER_DEGREE, STORE.longitude());
    }

    private DeliveryQuote grocery(double roadKm, String subtotal) {
        return calculator.quote(rules.getGrocery(), STORE, roadKmAway(roadKm), new BigDecimal(subtotal));
    }

    @Test
    void haversineMatchesAKnownDistance() {
        // MG Road → Indiranagar (Bengaluru): about 5.06 km in a straight line
        double km = DeliveryCalculator.haversineKm(STORE, new Coordinates(12.9784, 77.6408));
        assertThat(km).isBetween(5.0, 5.1);
        DeliveryQuote quote = calculator.quote(rules.getGrocery(), STORE, new Coordinates(12.9784, 77.6408),
                new BigDecimal("100.00"));
        assertThat(quote.distanceKm()).isEqualByComparingTo("6.6");              // × 1.3, 1 decimal
        assertThat(quote.fee()).isEqualByComparingTo("57.00");                   // 25 + 4 started km × 8
    }

    @Test
    void groceryFees() {
        assertThat(grocery(0.4, "100.00").fee()).isEqualByComparingTo("25.00");
        assertThat(grocery(3.0, "100.00").fee()).isEqualByComparingTo("25.00");  // included distance
        assertThat(grocery(3.1, "100.00").fee()).isEqualByComparingTo("33.00");  // 1 started km
        assertThat(grocery(4.0, "100.00").fee()).isEqualByComparingTo("33.00");
        assertThat(grocery(4.1, "100.00").fee()).isEqualByComparingTo("41.00");
        assertThat(grocery(15.0, "100.00").fee()).isEqualByComparingTo("121.00"); // 25 + 12 × 8
        assertThat(grocery(4.1, "498.99").fee()).isEqualByComparingTo("41.00");
        assertThat(grocery(4.1, "499.00").fee()).isEqualByComparingTo("0.00");   // free from the threshold
        assertThat(grocery(4.1, "499.00").fee().scale()).isEqualTo(2);
    }

    @Test
    void maximumDistance() {
        assertThat(grocery(15.0, "100.00").deliverable()).isTrue();
        assertThat(grocery(15.1, "100.00").deliverable()).isFalse();
        DeliveryQuote restaurant = calculator.quote(rules.getRestaurant(), STORE, roadKmAway(12.1),
                new BigDecimal("2000.00"));
        assertThat(restaurant.deliverable()).isFalse();
        assertThat(restaurant.fee()).isEqualByComparingTo("130.00");             // 30 + 10 started km × 10, never free
    }
}
