package com.grocery.delivery.stats;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * {@code grocery.delivery.pay.*} — how a delivery partner's estimated earnings are worked out for their dashboard:
 * for every delivered order, {@code basePerDelivery} plus {@code perKm} for each km of the trip (the straight-line
 * store → customer distance, as in the partner's history). Failed and cancelled deliveries earn nothing.
 * <p>
 * The defaults are placeholders, not a pay policy: set the real rates with {@code DELIVERY_PAY_BASE} and
 * {@code DELIVERY_PAY_PER_KM}. Earnings are worked out when they are read, so a new rate also changes the estimate of
 * past deliveries.
 *
 * @param basePerDelivery amount per delivered order ({@code ${DELIVERY_PAY_BASE:25.00}})
 * @param perKm           amount per km of trip distance ({@code ${DELIVERY_PAY_PER_KM:6.00}})
 * @param currency        ISO currency of both amounts (INR)
 */
@ConfigurationProperties(prefix = "grocery.delivery.pay")
public record PayProperties(
        @DefaultValue("25.00") BigDecimal basePerDelivery,
        @DefaultValue("6.00") BigDecimal perKm,
        @DefaultValue("INR") String currency) {

    public PayProperties {
        if (basePerDelivery.signum() < 0 || perKm.signum() < 0) {
            throw new IllegalArgumentException("grocery.delivery.pay rates must not be negative");
        }
    }
}
