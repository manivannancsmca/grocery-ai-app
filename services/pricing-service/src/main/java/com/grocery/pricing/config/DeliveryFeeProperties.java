package com.grocery.pricing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * {@code grocery.pricing.delivery-fees.*}: the delivery fees of the checkout preview, in INR and km. They are the
 * defaults of the admin platform settings' {@code delivery-fees} area (docs/api/platform-settings.md); cart-service
 * charges the values in force, which it reads from {@code GET /internal/delivery-fees}. cart-service keeps the same
 * numbers in its own {@code grocery.cart.delivery.*} only as a fallback for when this service cannot be reached, so
 * keep the two equal. The road factor and the maximum distance stay in cart-service's config.
 *
 * @param groceryBaseFee       grocery: the fee for the first {@code groceryIncludedKm}
 * @param groceryIncludedKm    grocery: kilometres covered by the base fee
 * @param groceryPerExtraKm    grocery: charged per started kilometre beyond them
 * @param groceryFreeAbove     grocery: delivery is free from this subtotal on
 * @param groceryMinimumOrder  grocery: a smaller subtotal cannot be ordered (BELOW_MINIMUM_ORDER)
 * @param restaurantBaseFee    restaurant: the fee for the first {@code restaurantIncludedKm}
 * @param restaurantIncludedKm restaurant: kilometres covered by the base fee
 * @param restaurantPerExtraKm restaurant: charged per started kilometre beyond them
 */
@ConfigurationProperties(prefix = "grocery.pricing.delivery-fees")
public record DeliveryFeeProperties(
        @DefaultValue("25.00") BigDecimal groceryBaseFee,
        @DefaultValue("3") BigDecimal groceryIncludedKm,
        @DefaultValue("8.00") BigDecimal groceryPerExtraKm,
        @DefaultValue("499.00") BigDecimal groceryFreeAbove,
        @DefaultValue("99.00") BigDecimal groceryMinimumOrder,
        @DefaultValue("30.00") BigDecimal restaurantBaseFee,
        @DefaultValue("3") BigDecimal restaurantIncludedKm,
        @DefaultValue("10.00") BigDecimal restaurantPerExtraKm) {
}
