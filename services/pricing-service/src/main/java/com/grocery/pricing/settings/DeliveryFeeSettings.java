package com.grocery.pricing.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * The delivery fees in force (area {@code delivery-fees}): an admin's override of each field, else the
 * {@code grocery.pricing.delivery-fees.*} value. Also the body of {@code GET /internal/delivery-fees}, which
 * cart-service reads for every checkout preview (cached briefly): a change applies to the next preview and so to new
 * orders; placed orders keep the fee they were charged. Money in INR, distances in km.
 */
public record DeliveryFeeSettings(BigDecimal groceryBaseFee, BigDecimal groceryIncludedKm,
                                  BigDecimal groceryPerExtraKm, BigDecimal groceryFreeAbove,
                                  BigDecimal groceryMinimumOrder, BigDecimal restaurantBaseFee,
                                  BigDecimal restaurantIncludedKm, BigDecimal restaurantPerExtraKm) {

    /** {@code PUT /api/admin/settings/delivery-fees}: every field; a null field goes back to the default. */
    public record Update(@NotNull @Valid Values values, @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /** The fields as the admin API shows them: rupees with at most 2 decimals, kilometres with at most 1. */
    public record Values(
            @DecimalMin(value = "0", message = "must be between 0 and 500")
            @DecimalMax(value = "500", message = "must be between 0 and 500")
            @Digits(integer = 3, fraction = 2, message = "must have at most two decimals")
            BigDecimal groceryBaseFee,
            @DecimalMin(value = "0", message = "must be between 0 and 20 km")
            @DecimalMax(value = "20", message = "must be between 0 and 20 km")
            @Digits(integer = 2, fraction = 1, message = "must have at most one decimal")
            BigDecimal groceryIncludedKm,
            @DecimalMin(value = "0", message = "must be between 0 and 100")
            @DecimalMax(value = "100", message = "must be between 0 and 100")
            @Digits(integer = 3, fraction = 2, message = "must have at most two decimals")
            BigDecimal groceryPerExtraKm,
            @DecimalMin(value = "0", message = "must be between 0 and 10000")
            @DecimalMax(value = "10000", message = "must be between 0 and 10000")
            @Digits(integer = 5, fraction = 2, message = "must have at most two decimals")
            BigDecimal groceryFreeAbove,
            @DecimalMin(value = "0", message = "must be between 0 and 5000")
            @DecimalMax(value = "5000", message = "must be between 0 and 5000")
            @Digits(integer = 4, fraction = 2, message = "must have at most two decimals")
            BigDecimal groceryMinimumOrder,
            @DecimalMin(value = "0", message = "must be between 0 and 500")
            @DecimalMax(value = "500", message = "must be between 0 and 500")
            @Digits(integer = 3, fraction = 2, message = "must have at most two decimals")
            BigDecimal restaurantBaseFee,
            @DecimalMin(value = "0", message = "must be between 0 and 20 km")
            @DecimalMax(value = "20", message = "must be between 0 and 20 km")
            @Digits(integer = 2, fraction = 1, message = "must have at most one decimal")
            BigDecimal restaurantIncludedKm,
            @DecimalMin(value = "0", message = "must be between 0 and 100")
            @DecimalMax(value = "100", message = "must be between 0 and 100")
            @Digits(integer = 3, fraction = 2, message = "must have at most two decimals")
            BigDecimal restaurantPerExtraKm) {
    }
}
