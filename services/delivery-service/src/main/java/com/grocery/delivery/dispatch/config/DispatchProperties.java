package com.grocery.delivery.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * {@code grocery.delivery.*} — assignment rules of docs/api/phase-9-delivery.md §2. Every value has a default (the
 * contract's numbers), so the service also starts without any of them. {@code offerTtl}, {@code offerBatchSize},
 * {@code searchRadiiKm} and {@code locationStaleAfter} are the defaults of the admin platform settings (area
 * {@code delivery}); dispatch reads the values in force from {@code settings.DeliverySettingsService}.
 *
 * @param offerTtl            how long a partner has to accept an offer ({@code ${DELIVERY_OFFER_TTL:30s}})
 * @param offerBatchSize      partners offered per wave, the nearest available ones ({@code ${DELIVERY_OFFER_BATCH_SIZE:2}})
 * @param searchRadiiKm       radius steps around the pickup point, strictly ascending
 *                            ({@code ${DELIVERY_SEARCH_RADII_KM:3,6,10}})
 * @param pollInterval        pause between two runs of the dispatch poller ({@code ${DELIVERY_POLL_INTERVAL:5s}})
 * @param retryInterval       a fresh search after nobody was found inside the largest radius
 *                            ({@code ${DELIVERY_RETRY_INTERVAL:60s}})
 * @param locationStaleAfter  a partner whose last stored location is older is not available
 *                            ({@code ${DELIVERY_LOCATION_STALE_AFTER:60s}})
 * @param locationMinInterval location reports closer together are accepted but neither stored nor published (2 s);
 *                            also the {@code delivery.location} throttle (at most one event per partner per 2 s)
 * @param trailSampleInterval at most one {@code location_sample} row per delivery per this interval (15 s)
 * @param pollBatchSize       deliveries claimed per poller transaction ({@code FOR UPDATE SKIP LOCKED})
 * @param pollersEnabled      whether this instance runs the poller (tests switch it off and run it themselves; any
 *                            number of replicas may run it)
 * @param timezone            the days of the partner's delivery summary (India time); an unknown zone fails startup
 * @param locationSampleRetention         Phase 10: {@code location_sample} rows older than this are deleted (30d)
 * @param locationSampleCleanupInterval   Phase 10: pause between two runs of that cleanup (1h, fixed delay)
 */
@ConfigurationProperties(prefix = "grocery.delivery")
public record DispatchProperties(
        @DefaultValue("30s") Duration offerTtl,
        @DefaultValue("2") int offerBatchSize,
        @DefaultValue({"3", "6", "10"}) List<Double> searchRadiiKm,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("60s") Duration retryInterval,
        @DefaultValue("60s") Duration locationStaleAfter,
        @DefaultValue("2s") Duration locationMinInterval,
        @DefaultValue("15s") Duration trailSampleInterval,
        @DefaultValue("20") int pollBatchSize,
        @DefaultValue("true") boolean pollersEnabled,
        @DefaultValue("Asia/Kolkata") String timezone,
        @DefaultValue("30d") Duration locationSampleRetention,
        @DefaultValue("1h") Duration locationSampleCleanupInterval) {

    public DispatchProperties {
        requirePositive(offerTtl, "offer-ttl");
        requirePositive(pollInterval, "poll-interval");
        requirePositive(retryInterval, "retry-interval");
        requirePositive(locationStaleAfter, "location-stale-after");
        requireNotNegative(locationMinInterval, "location-min-interval");
        requireNotNegative(trailSampleInterval, "trail-sample-interval");
        requirePositive(locationSampleRetention, "location-sample-retention");
        requirePositive(locationSampleCleanupInterval, "location-sample-cleanup-interval");
        if (offerBatchSize < 1 || offerBatchSize > 50) {
            throw new IllegalArgumentException("grocery.delivery.offer-batch-size must be 1-50");
        }
        if (pollBatchSize < 1 || pollBatchSize > 500) {
            throw new IllegalArgumentException("grocery.delivery.poll-batch-size must be 1-500");
        }
        if (searchRadiiKm == null || searchRadiiKm.isEmpty()) {
            throw new IllegalArgumentException("grocery.delivery.search-radii-km needs at least one radius");
        }
        double previous = 0;
        for (Double radius : searchRadiiKm) {
            if (radius == null || radius <= previous) {
                throw new IllegalArgumentException(
                        "grocery.delivery.search-radii-km must be positive and strictly ascending: " + searchRadiiKm);
            }
            previous = radius;
        }
        searchRadiiKm = List.copyOf(searchRadiiKm);
        ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public double largestRadiusKm() {
        return searchRadiiKm.getLast();
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("grocery.delivery." + name + " must be positive");
        }
    }

    private static void requireNotNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException("grocery.delivery." + name + " must not be negative");
        }
    }
}
