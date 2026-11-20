package com.grocery.pricing.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@code grocery_coupon_redemptions_total{outcome}} (docs/api/phase-13-observability-and-testing.md §1):
 * <ul>
 *   <li>{@code RESERVED} — {@code redeem} reserved a new use for an order (a retry for the same order is not counted);</li>
 *   <li>{@code CONFIRMED} — {@code OrderCreated} confirmed a reservation;</li>
 *   <li>{@code RELEASED} — a use given back: {@code OrderCancelled} / {@code OrderRejected}, or the sweeper (no
 *       {@code OrderCreated} within the reservation TTL);</li>
 *   <li>{@code REFUSED} — {@code redeem} refused: the coupon cannot be used (409 {@code COUPON_NOT_APPLICABLE}) or the
 *       order already has another redemption (409 {@code COUPON_REDEMPTION_CONFLICT}).</li>
 * </ul>
 * A change made in a transaction is counted after that transaction commits (a rolled-back one is not counted).
 */
@Component
public class CouponMetrics {

    public static final String REDEMPTIONS = "grocery.coupon.redemptions";

    public enum Outcome {
        RESERVED, CONFIRMED, RELEASED, REFUSED
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public CouponMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder(REDEMPTIONS)
                    .description("Coupon redemptions by outcome (RESERVED, CONFIRMED, RELEASED, REFUSED)")
                    .tag("outcome", outcome.name())
                    .register(registry));
        }
    }

    /** Counts {@code count} redemptions with this outcome: after the current transaction commits, or now without one. */
    public void count(Outcome outcome, int count) {
        if (count <= 0) {
            return;
        }
        Counter counter = counters.get(outcome);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counter.increment(count);
                }
            });
        } else {
            counter.increment(count);
        }
    }
}
