package com.grocery.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code grocery.inventory.*} — the order saga's reservation settings (docs/api/phase-6-orders-and-payments.md §6).
 * Every value has a default (the contract's numbers), so the service also starts without any of these properties.
 */
@ConfigurationProperties(prefix = "grocery.inventory")
public class InventoryProperties {

    /**
     * How long stock is held for a grocery order that is not paid. Deliberately longer than the payment window
     * (15 min), so normally the payment expiry cancels the order first and this is only the safety net.
     */
    private Duration reservationTtl = Duration.ofMinutes(20);

    private final Expiry expiry = new Expiry();

    public Duration getReservationTtl() {
        return reservationTtl;
    }

    public void setReservationTtl(Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("grocery.inventory.reservation-ttl must be positive");
        }
        this.reservationTtl = reservationTtl;
    }

    public Expiry getExpiry() {
        return expiry;
    }

    /** The reservation expiry poller ({@code service/ReservationExpiryPoller.java}). */
    public static class Expiry {
        /** Off = no poller in this instance (tests; another replica still polls). */
        private boolean enabled = true;
        /** Pause between the end of one run and the start of the next. */
        private Duration interval = Duration.ofSeconds(30);
        /** Reservations claimed and released per transaction. */
        private int batchSize = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
            if (batchSize < 1 || batchSize > 500) {
                throw new IllegalArgumentException("grocery.inventory.expiry.batch-size must be 1-500");
            }
            this.batchSize = batchSize;
        }
    }
}
