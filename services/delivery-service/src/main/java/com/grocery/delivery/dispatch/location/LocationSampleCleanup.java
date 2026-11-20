package com.grocery.delivery.dispatch.location;

import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.dispatch.repository.LocationTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Housekeeping of the delivery trail (docs/api/phase-10-event-driven-hardening.md §6): every
 * {@code grocery.delivery.location-sample-cleanup-interval} (1 h, fixed delay) it deletes the {@code location_sample}
 * rows older than {@code grocery.delivery.location-sample-retention} (30 days), in batches of {@value #BATCH_SIZE}
 * (oldest first), each batch its own short statement — so it never holds locks a location report would wait for for
 * long — until a batch comes back short, and logs what it deleted. Scheduled with the dispatch poller
 * ({@code grocery.delivery.pollers-enabled}); safe in every replica (deleting the same old rows twice is harmless).
 */
@Component
public class LocationSampleCleanup {

    static final int BATCH_SIZE = 1000;
    /** Upper bound of batches per run (a million rows), so one run never takes the scheduler thread for long. */
    static final int MAX_BATCHES_PER_RUN = 1000;
    private static final Logger log = LoggerFactory.getLogger(LocationSampleCleanup.class);

    private final LocationTrail trail;
    private final Duration retention;
    private final Clock clock;

    public LocationSampleCleanup(LocationTrail trail, DispatchProperties properties, Clock clock) {
        this.trail = trail;
        this.retention = properties.locationSampleRetention();
        this.clock = clock;
    }

    /** Scheduled only when {@code grocery.delivery.pollers-enabled} (default true), see DispatchConfig. */
    @Scheduled(initialDelayString = "${grocery.delivery.location-sample-cleanup-interval:1h}",
            fixedDelayString = "${grocery.delivery.location-sample-cleanup-interval:1h}")
    public void scheduled() {
        run();
    }

    /** One run at the clock's current time; returns how many samples were deleted. */
    public int run() {
        Instant cutoff = clock.instant().minus(retention);
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int rows;
            try {
                rows = trail.deleteRecordedBefore(cutoff, BATCH_SIZE);
            } catch (RuntimeException e) {
                log.warn("Location sample cleanup failed after {} row(s), retried in the next run: {}", deleted,
                        e.toString());
                break;
            }
            deleted += rows;
            if (rows < BATCH_SIZE) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Location sample cleanup: {} sample(s) recorded before {} deleted", deleted, cutoff);
        } else {
            log.debug("Location sample cleanup: nothing recorded before {}", cutoff);
        }
        return deleted;
    }
}
