package com.grocery.vendor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * {@code grocery.stores.*} — the public store directory (docs/api/phase-3-catalog-and-search.md §9). Every value has a
 * default (the contract's numbers), so the service also starts without any of them.
 *
 * <p>{@code roadFactor} and {@code deliveryLimitKm} must match cart-service {@code grocery.cart.delivery}
 * ({@code road-factor}, {@code grocery.max-distance-km}): the stores listed as delivering to a point are exactly the
 * ones checkout accepts for that address.
 *
 * @param roadFactor      straight-line (haversine) distance × this = the road distance shown and compared (1.3)
 * @param deliveryLimitKm a store delivers to a point when its road distance, rounded to 1 decimal, is at most this
 * @param maxResults      most stores {@code GET /api/stores/nearby} returns (the nearest ones)
 */
@ConfigurationProperties(prefix = "grocery.stores")
public record StoreDirectoryProperties(
        @DefaultValue("1.3") BigDecimal roadFactor,
        @DefaultValue("15") BigDecimal deliveryLimitKm,
        @DefaultValue("50") int maxResults) {

    public StoreDirectoryProperties {
        if (roadFactor == null || roadFactor.signum() <= 0) {
            throw new IllegalArgumentException("grocery.stores.road-factor must be positive");
        }
        if (deliveryLimitKm == null || deliveryLimitKm.signum() <= 0) {
            throw new IllegalArgumentException("grocery.stores.delivery-limit-km must be positive");
        }
        if (maxResults < 1 || maxResults > 500) {
            throw new IllegalArgumentException("grocery.stores.max-results must be 1-500");
        }
    }
}
