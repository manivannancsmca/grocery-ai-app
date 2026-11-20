package com.grocery.delivery.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * The delivery settings in force (area {@code delivery}): an admin's override of each field, else the
 * {@code grocery.delivery.*} value. Read by dispatch every time it searches, so a change applies to the next wave of
 * every search; offers already sent keep their expiry.
 *
 * @param searchRadiiKm     radius steps around the pickup point, strictly ascending ({@code search-radii-km})
 * @param offersPerWave     partners offered per wave, the nearest available ones ({@code offer-batch-size})
 * @param offerTimeLimit    how long a partner has to accept an offer ({@code offer-ttl})
 * @param locationFreshness a partner whose last location is older is not available ({@code location-stale-after})
 */
public record DeliverySettings(List<Double> searchRadiiKm, int offersPerWave, Duration offerTimeLimit,
                               Duration locationFreshness) {

    public double largestRadiusKm() {
        return searchRadiiKm.getLast();
    }

    /** {@code PUT /api/admin/settings/delivery}: every field of the area; a null field goes back to the default. */
    public record Update(@NotNull @Valid Values values, @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /**
     * The fields as the admin API shows them. Bounds keep dispatch working: at least 30 s of location freshness
     * because the partner app re-sends a still position every 25 s.
     */
    public record Values(
            @Size(min = 1, max = 5, message = "must have 1 to 5 steps")
            List<@NotNull(message = "must not be empty")
                 @DecimalMin(value = "0.5", message = "must be between 0.5 and 50 km")
                 @DecimalMax(value = "50", message = "must be between 0.5 and 50 km")
                 @Digits(integer = 2, fraction = 1, message = "must have at most one decimal") BigDecimal> searchRadiiKm,
            @Min(value = 1, message = "must be between 1 and 10")
            @Max(value = 10, message = "must be between 1 and 10") Integer offersPerWave,
            @Min(value = 10, message = "must be between 10 and 300 seconds")
            @Max(value = 300, message = "must be between 10 and 300 seconds") Integer offerTimeLimitSeconds,
            @Min(value = 30, message = "must be between 30 and 600 seconds")
            @Max(value = 600, message = "must be between 30 and 600 seconds") Integer locationFreshnessSeconds) {
    }
}
