package com.grocery.review.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Duration;

/**
 * The review settings in force (area {@code reviews}): an admin's override, else {@code grocery.review.review-window}.
 *
 * @param reviewWindow how long after delivery an order's items, store and partner can be reviewed; checked when the
 *                     customer asks, so a change applies to every delivered order at once ({@code REVIEW_WINDOW})
 */
public record ReviewSettings(Duration reviewWindow) {

    /** {@code PUT /api/admin/settings/reviews}: every field of the area; a null field goes back to the default. */
    public record Update(@NotNull @Valid Values values, @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /** The fields as the admin API shows them. */
    public record Values(
            @Min(value = 1, message = "must be between 1 and 365 days")
            @Max(value = 365, message = "must be between 1 and 365 days") Integer reviewWindowDays) {
    }
}
