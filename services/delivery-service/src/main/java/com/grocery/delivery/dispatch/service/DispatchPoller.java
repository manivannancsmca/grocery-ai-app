package com.grocery.delivery.dispatch.service;

import com.grocery.delivery.dispatch.config.DispatchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * The dispatch poller (contract §2 "Pollers"): every {@code grocery.delivery.poll-interval} (5 s, fixed delay) it
 * claims SEARCHING deliveries whose {@code next_search_at} has come and advances them — which expires their overdue
 * offers, sends the next wave when every offer of the last one was rejected or expired, and runs the retry of a failed
 * search after {@code retry-interval}. One delivery's {@code next_search_at} is the earliest of these moments, so a
 * single claim query serves all three duties.
 *
 * <p>Batches of {@code poll-batch-size} (20), each its own transaction, until a batch comes back short. Safe with
 * several replicas: each batch is claimed with {@code FOR UPDATE SKIP LOCKED}, so replicas take disjoint batches and a
 * delivery a partner is accepting right now (row locked) is skipped and seen as ASSIGNED by the next run. The
 * {@link Clock} is injectable, so tests can run the poller "in the future".
 */
@Component
public class DispatchPoller {

    /** Upper bound of batches per run, so one run never monopolises a connection for long. */
    static final int MAX_BATCHES_PER_RUN = 50;
    private static final Logger log = LoggerFactory.getLogger(DispatchPoller.class);

    private final AssignmentEngine engine;
    private final int batchSize;
    private final Clock clock;

    public DispatchPoller(AssignmentEngine engine, DispatchProperties properties, Clock clock) {
        this.engine = engine;
        this.batchSize = properties.pollBatchSize();
        this.clock = clock;
    }

    /** Scheduled only when {@code grocery.delivery.pollers-enabled} (default true), see DispatchConfig. */
    @Scheduled(initialDelayString = "${grocery.delivery.poll-interval:5s}",
            fixedDelayString = "${grocery.delivery.poll-interval:5s}")
    public void scheduled() {
        run();
    }

    /** One run at the clock's current time. Returns how many deliveries were claimed. */
    public int run() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int claimed;
            try {
                claimed = engine.advanceDue(clock.instant(), batchSize);
            } catch (RuntimeException e) {
                log.warn("Dispatch poller run failed, retried in the next run: {}", e.toString());
                break;
            }
            total += claimed;
            if (claimed < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.debug("Dispatch poller advanced {} delivery(ies)", total);
        }
        return total;
    }
}
