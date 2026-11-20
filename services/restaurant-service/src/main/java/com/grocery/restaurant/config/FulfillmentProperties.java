package com.grocery.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * {@code grocery.fulfillment.*} — the restaurant order board (docs/api/phase-8-vendor-and-restaurant-fulfillment.md
 * §2, §3). Every value has a default (the contract's numbers), so the service also starts without any of them.
 *
 * @param acceptanceTimeout     how long a received order waits for acceptance: {@code acceptBy = receivedAt + this}
 *                              ({@code ${ACCEPTANCE_TIMEOUT:5m}}); the default of the admin platform setting (area
 *                              {@code restaurants})
 * @param pollInterval          pause between two runs of the auto-reject poller ({@code ${ACCEPTANCE_POLL_INTERVAL:15s}})
 * @param pollBatchSize         orders claimed and rejected per poller transaction ({@code FOR UPDATE SKIP LOCKED})
 * @param autoRejectEnabled     whether this instance runs the poller (tests switch it off and run pollers themselves;
 *                              any number of replicas may run it)
 * @param defaultReadyInMinutes {@code readyInMinutes} of an accept without one (20 for restaurants)
 * @param timezone              the days of the sales report (India time); an unknown zone fails the startup
 */
@ConfigurationProperties(prefix = "grocery.fulfillment")
public record FulfillmentProperties(
        @DefaultValue("5m") Duration acceptanceTimeout,
        @DefaultValue("15s") Duration pollInterval,
        @DefaultValue("20") int pollBatchSize,
        @DefaultValue("true") boolean autoRejectEnabled,
        @DefaultValue("20") int defaultReadyInMinutes,
        @DefaultValue("Asia/Kolkata") String timezone) {

    /** Bounds of {@code readyInMinutes} (accept request and default). */
    public static final int MIN_READY_IN_MINUTES = 5;
    public static final int MAX_READY_IN_MINUTES = 180;

    public FulfillmentProperties {
        if (acceptanceTimeout == null || acceptanceTimeout.isNegative() || acceptanceTimeout.isZero()) {
            throw new IllegalArgumentException("grocery.fulfillment.acceptance-timeout must be positive");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("grocery.fulfillment.poll-interval must be positive");
        }
        if (pollBatchSize < 1 || pollBatchSize > 500) {
            throw new IllegalArgumentException("grocery.fulfillment.poll-batch-size must be 1-500");
        }
        if (defaultReadyInMinutes < MIN_READY_IN_MINUTES || defaultReadyInMinutes > MAX_READY_IN_MINUTES) {
            throw new IllegalArgumentException("grocery.fulfillment.default-ready-in-minutes must be 5-180");
        }
        ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    /** The same settings with another batch size (tests run small pollers). */
    public FulfillmentProperties withPollBatchSize(int batchSize) {
        return new FulfillmentProperties(acceptanceTimeout, pollInterval, batchSize, autoRejectEnabled,
                defaultReadyInMinutes, timezone);
    }
}
