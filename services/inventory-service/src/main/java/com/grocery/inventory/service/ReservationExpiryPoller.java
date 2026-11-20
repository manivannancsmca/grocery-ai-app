package com.grocery.inventory.service;

import com.grocery.inventory.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Releases RESERVED reservations past {@code expires_at} (orders never paid; contract §6 "Inventory") →
 * {@code StockReleased(RESERVATION_EXPIRED)} + {@code StockReservationExpired}, which order-service turns into
 * {@code CANCELLED (RESERVATION_EXPIRED)}. Confirmed orders' reservations have no expiry and are never touched.
 *
 * <p>Every {@code grocery.inventory.expiry.interval} (30 s, fixed delay) it releases batches of
 * {@code grocery.inventory.expiry.batch-size} (20), each its own transaction, until a batch comes back short.
 * Safe with several replicas: each batch is claimed with {@code FOR UPDATE SKIP LOCKED}, so replicas take
 * disjoint batches, and a reservation is released by exactly one transaction (lock order: see
 * {@link ReservationService#releaseExpired}). A failed batch (e.g. a deadlock victim) is rolled back and simply
 * retried in the next run. The {@link Clock} is injectable, so tests can run the poller "in the future".
 */
@Component
public class ReservationExpiryPoller {

    /** Upper bound of batches per run, so one run never monopolises a connection for long. */
    static final int MAX_BATCHES_PER_RUN = 50;
    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryPoller.class);

    private final ReservationService reservations;
    private final int batchSize;
    private final Clock clock;

    public ReservationExpiryPoller(ReservationService reservations, InventoryProperties properties, Clock clock) {
        this.reservations = reservations;
        this.batchSize = properties.getExpiry().getBatchSize();
        this.clock = clock;
    }

    /** Scheduled only when {@code grocery.inventory.expiry.enabled} (default true), see InventoryConfig. */
    @Scheduled(initialDelayString = "${grocery.inventory.expiry.interval:30s}",
            fixedDelayString = "${grocery.inventory.expiry.interval:30s}")
    public void scheduled() {
        releaseExpired();
    }

    /** One run: releases everything expired at the start of each batch. Returns how many were released. */
    public int releaseExpired() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int released;
            try {
                released = reservations.releaseExpired(clock.instant(), batchSize);
            } catch (RuntimeException e) {
                log.warn("Releasing expired reservations failed, retried in the next run: {}", e.toString());
                break;
            }
            total += released;
            if (released < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Released {} expired stock reservation(s)", total);
        }
        return total;
    }
}
