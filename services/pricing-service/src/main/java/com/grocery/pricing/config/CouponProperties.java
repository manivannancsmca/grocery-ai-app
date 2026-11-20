package com.grocery.pricing.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code grocery.pricing.coupons.*} (Phase 12 §2).
 *
 * @param reservationTtl a RESERVED redemption without {@code OrderCreated} for this long is released by the sweeper
 *                       (env {@code COUPON_RESERVATION_TTL}, 30 min)
 * @param sweepInterval  pause between two sweeper runs (fixed delay; env {@code COUPON_SWEEP_INTERVAL}, 1 min)
 * @param sweepBatchSize redemptions claimed per sweeper transaction ({@code FOR UPDATE SKIP LOCKED})
 * @param sweeperEnabled schedule the sweeper (off in tests, which run it by hand)
 */
@Validated
@ConfigurationProperties(prefix = "grocery.pricing.coupons")
public record CouponProperties(
        @DefaultValue("30m") @NotNull Duration reservationTtl,
        @DefaultValue("1m") @NotNull Duration sweepInterval,
        @DefaultValue("100") @Min(1) @Max(1000) int sweepBatchSize,
        @DefaultValue("true") boolean sweeperEnabled) {
}
