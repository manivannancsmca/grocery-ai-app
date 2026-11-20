package com.grocery.restaurant.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Duration;

/**
 * The restaurant settings in force (area {@code restaurants}): an admin's override, else the
 * {@code grocery.fulfillment.*} value. Read when an order reaches the board, so a change applies to new orders; an
 * order already waiting keeps its {@code acceptBy}.
 *
 * @param acceptanceTime how long a new order waits for the restaurant to accept it before it is rejected automatically
 *                       ({@code acceptance-timeout})
 */
public record RestaurantSettings(Duration acceptanceTime) {

    /** {@code PUT /api/admin/settings/restaurants}: every field; a null field goes back to the default. */
    public record Update(@NotNull @Valid Values values, @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /** The fields as the admin API shows them. */
    public record Values(
            @Min(value = 1, message = "must be between 1 and 60 minutes")
            @Max(value = 60, message = "must be between 1 and 60 minutes") Integer acceptanceTimeMinutes) {
    }
}
