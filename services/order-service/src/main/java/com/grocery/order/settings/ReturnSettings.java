package com.grocery.order.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Duration;

/**
 * The return settings in force (area {@code returns}): an admin's override of each field, else the
 * {@code grocery.order.returns.*} value.
 *
 * @param returnWindow      a delivered order can be returned until {@code deliveredAt + this}; checked when the
 *                          customer asks, so a change applies to every delivered order at once
 *                          ({@code returns.window}, {@code ORDER_RETURN_WINDOW})
 * @param storeDecisionTime a new return must be decided by {@code requestedAt + this}, then it goes to the admins;
 *                          stored on the return when it is made, so open returns keep their deadline
 *                          ({@code returns.decision-timeout}, {@code RETURN_DECISION_TIMEOUT})
 */
public record ReturnSettings(Duration returnWindow, Duration storeDecisionTime) {

    /** {@code PUT /api/admin/settings/returns}: every field of the area; a null field goes back to the default. */
    public record Update(@NotNull @Valid Values values, @NotNull @PositiveOrZero Long expectedVersion) {
    }

    /** The fields as the admin API shows them. */
    public record Values(
            @Min(value = 1, message = "must be between 1 and 720 hours")
            @Max(value = 720, message = "must be between 1 and 720 hours") Integer returnWindowHours,
            @Min(value = 1, message = "must be between 1 and 168 hours")
            @Max(value = 168, message = "must be between 1 and 168 hours") Integer storeDecisionHours) {
    }
}
