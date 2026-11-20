package com.grocery.common.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the platform-table clean-up on the library's own single daemon thread (no {@code @EnableScheduling} needed in
 * the service): it starts when the application is ready, first runs after {@code grocery.housekeeping.initial-delay}
 * (30 min, so a service's integration tests never see it), then
 * <ul>
 *   <li>{@code outbox_event} every {@code grocery.outbox.cleanup.interval};</li>
 *   <li>{@code processed_event}, {@code idempotency_key} and (0.8.0) {@code consumed_approval} every
 *       {@code grocery.housekeeping.interval}.</li>
 * </ul>
 * Stops (between two batches) when the application shuts down. A failing run is logged and tried again at the next one.
 */
public class HousekeepingScheduler implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingScheduler.class);

    private final TableCleaner cleaner;
    private final HousekeepingProperties properties;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile ScheduledExecutorService executor;

    public HousekeepingScheduler(TableCleaner cleaner, HousekeepingProperties properties) {
        this.cleaner = cleaner;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /** Schedules the jobs (once). */
    public void start() {
        if (!started.compareAndSet(false, true) || stopping) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "grocery-housekeeping");
            thread.setDaemon(true);
            return thread;
        });
        executor = scheduler;
        HousekeepingProperties.Housekeeping settings = properties.getHousekeeping();
        HousekeepingProperties.Cleanup outbox = properties.getOutbox().getCleanup();
        long initialDelay = Math.max(0, settings.getInitialDelay().toMillis());
        scheduler.scheduleWithFixedDelay(() -> run(List.of(TableCleaner.outboxEvents(outbox.getRetention()))),
                initialDelay, positive(outbox.getInterval()), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> run(List.of(
                        TableCleaner.processedEvents(properties.getProcessedEvents().getRetention()),
                        TableCleaner.idempotencyKeys(properties.getIdempotency().getRetention()),
                        TableCleaner.consumedApprovals(properties.getApprovals().getConsumedRetention()))),
                initialDelay, positive(settings.getInterval()), TimeUnit.MILLISECONDS);
        log.info("Housekeeping scheduled (first run in {}): outbox_event older than {} every {}; processed_event older "
                        + "than {}, idempotency_key older than {} and consumed_approval older than {} every {} (only "
                        + "the tables this service has)",
                settings.getInitialDelay(), outbox.getRetention(), outbox.getInterval(),
                properties.getProcessedEvents().getRetention(), properties.getIdempotency().getRetention(),
                properties.getApprovals().getConsumedRetention(), settings.getInterval());
    }

    private void run(List<TableCleaner.Target> targets) {
        for (TableCleaner.Target target : targets) {
            if (stopping) {
                return;
            }
            try {
                cleaner.clean(target, () -> stopping);
            } catch (Exception e) {
                log.warn("Housekeeping of {} failed (tried again at the next run): {}", target.table(), e.toString());
            }
        }
    }

    private static long positive(Duration interval) {
        return Math.max(1_000, interval.toMillis());
    }

    @Override
    public void destroy() {
        stopping = true;
        ScheduledExecutorService scheduler = executor;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Housekeeping still running at shutdown; abandoned");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
