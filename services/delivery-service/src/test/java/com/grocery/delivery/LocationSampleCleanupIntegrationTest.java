package com.grocery.delivery;

import com.grocery.delivery.dispatch.location.LocationSampleCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Housekeeping (Phase 10 contract §6): {@code location_sample} rows older than
 * {@code grocery.delivery.location-sample-retention} (30 days) are deleted in batches of 1000 — rows inside the
 * retention stay. The clock is pinned (T0 + 1 day), so the samples other tests record (around T0 and now) are kept.
 */
class LocationSampleCleanupIntegrationTest extends DispatchTestSupport {

    private static final String DIGITS = "(SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 "
            + "UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 "
            + "UNION ALL SELECT 9)";

    @Autowired
    private LocationSampleCleanup cleanup;

    /** {@code count} samples of the delivery, one second apart, the newest recorded at {@code newest}. */
    private void samples(UUID deliveryId, int count, Instant newest) {
        jdbc().sql("INSERT INTO location_sample (delivery_id, partner_id, latitude, longitude, recorded_at) "
                        + "SELECT UUID_TO_BIN(?), UUID_TO_BIN(?), 12.9716, 77.5946, "
                        + "       ? - INTERVAL (a.d * 1000 + b.d * 100 + c.d * 10 + e.d) SECOND "
                        + "FROM " + DIGITS + " a, " + DIGITS + " b, " + DIGITS + " c, " + DIGITS + " e "
                        + "WHERE a.d * 1000 + b.d * 100 + c.d * 10 + e.d < ?")
                .param(deliveryId.toString()).param(UUID.randomUUID().toString())
                .param(LocalDateTime.ofInstant(newest, ZoneOffset.UTC)).param(count)
                .update();
    }

    private long samplesOf(UUID deliveryId, String condition, Instant at) {
        return jdbc().sql("SELECT COUNT(*) FROM location_sample WHERE delivery_id = UUID_TO_BIN(?) AND recorded_at "
                        + condition + " ?")
                .param(deliveryId.toString()).param(LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Long.class).single();
    }

    @Test
    void samplesOlderThanThirtyDaysAreDeletedInBatches() {
        Instant now = T0.plus(Duration.ofDays(1));
        clock.set(now);
        Instant cutoff = now.minus(Duration.ofDays(30));
        UUID old = deliveryId(confirmedOrder());
        UUID recent = deliveryId(confirmedOrder());
        samples(old, 2_500, cutoff.minusSeconds(1));                   // three batches: 1000, 1000, 500
        samples(old, 2, cutoff.plusSeconds(1));                        // inside the retention
        samples(recent, 5, now.minus(Duration.ofDays(29)));
        samples(recent, 1, cutoff);                                    // exactly 30 days old: kept

        int deleted = cleanup.run();

        assertThat(deleted).isGreaterThanOrEqualTo(2_500);
        assertThat(samplesOf(old, "<", cutoff)).isZero();
        assertThat(samplesOf(old, ">=", cutoff)).isEqualTo(2);
        assertThat(samplesOf(recent, ">=", cutoff)).isEqualTo(6);

        assertThat(cleanup.run()).isZero();                            // nothing left to delete
        assertThat(samplesOf(old, ">=", cutoff)).isEqualTo(2);
    }

    @Test
    void theRetentionIsThirtyDaysByDefault() {
        assertThat(dispatch.locationSampleRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(dispatch.locationSampleCleanupInterval()).isEqualTo(Duration.ofHours(1));
    }
}
