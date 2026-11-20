package com.grocery.common.housekeeping;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Clean-up of the platform tables (grocery-common 0.7.0, contract Phase 10 §6). Bound from the {@code grocery.*} root
 * so the contract's property names apply as written (environment variables by relaxed binding):
 * <pre>
 * grocery.outbox.cleanup.retention=7d          # env GROCERY_OUTBOX_CLEANUP_RETENTION
 * grocery.outbox.cleanup.interval=1h           # env GROCERY_OUTBOX_CLEANUP_INTERVAL
 * grocery.processed-events.retention=35d       # env GROCERY_PROCESSEDEVENTS_RETENTION  (> the 30-day DLT retention)
 * grocery.idempotency.retention=7d             # env GROCERY_IDEMPOTENCY_RETENTION
 * grocery.approvals.consumed-retention=30d     # env GROCERY_APPROVALS_CONSUMEDRETENTION (0.8.0)
 * grocery.housekeeping.enabled=true            # master switch
 * grocery.housekeeping.interval=1h             # processed_event, idempotency_key and consumed_approval
 * grocery.housekeeping.initial-delay=30m       # first run after the application is ready
 * grocery.housekeeping.batch-size=1000         # rows per DELETE
 * </pre>
 */
@ConfigurationProperties(prefix = "grocery")
public class HousekeepingProperties {

    private final Housekeeping housekeeping = new Housekeeping();
    private final Outbox outbox = new Outbox();
    private final Retention processedEvents = new Retention(Duration.ofDays(35));
    private final Retention idempotency = new Retention(Duration.ofDays(7));
    private final Approvals approvals = new Approvals();

    public Housekeeping getHousekeeping() {
        return housekeeping;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    /** {@code grocery.processed-events.*}: rows of {@code processed_event}. */
    public Retention getProcessedEvents() {
        return processedEvents;
    }

    /** {@code grocery.idempotency.*}: rows of {@code idempotency_key}. */
    public Retention getIdempotency() {
        return idempotency;
    }

    /** {@code grocery.approvals.*} (the clean-up part only): rows of {@code consumed_approval}. */
    public Approvals getApprovals() {
        return approvals;
    }

    public static class Approvals {

        /** Delete {@code consumed_approval} rows older than this (approval tokens live 60 s; the rows are audit). */
        private Duration consumedRetention = Duration.ofDays(30);

        public Duration getConsumedRetention() {
            return consumedRetention;
        }

        public void setConsumedRetention(Duration consumedRetention) {
            this.consumedRetention = consumedRetention;
        }
    }

    public static class Housekeeping {

        /** Run the clean-up jobs (only for the tables that exist in the service's database). */
        private boolean enabled = true;

        /**
         * Delay between the application being ready and the first run. Long enough that a service's integration
         * tests never see a run at the default settings.
         */
        private Duration initialDelay = Duration.ofMinutes(30);

        /** Pause between two runs of the {@code processed_event} and {@code idempotency_key} clean-up. */
        private Duration interval = Duration.ofHours(1);

        /** Rows deleted per statement (each its own short transaction). */
        private int batchSize = 1000;

        /** Upper bound of statements per table and run (the rest waits for the next run). */
        private int maxBatchesPerRun = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxBatchesPerRun() {
            return maxBatchesPerRun;
        }

        public void setMaxBatchesPerRun(int maxBatchesPerRun) {
            this.maxBatchesPerRun = maxBatchesPerRun;
        }
    }

    public static class Outbox {

        private final Cleanup cleanup = new Cleanup();

        public Cleanup getCleanup() {
            return cleanup;
        }
    }

    /** {@code grocery.outbox.cleanup.*}: rows of {@code outbox_event} (Debezium has long published them). */
    public static class Cleanup {

        /** Delete outbox rows older than this. */
        private Duration retention = Duration.ofDays(7);

        /** Pause between two runs of the outbox clean-up. */
        private Duration interval = Duration.ofHours(1);

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    public static class Retention {

        /** Delete rows older than this. */
        private Duration retention;

        Retention(Duration retention) {
            this.retention = retention;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }
}
