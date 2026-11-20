package com.grocery.order.service;

import com.grocery.order.config.OrderProperties;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.repository.StuckOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The stuck-order watchdog (docs/api/phase-10-event-driven-hardening.md §4), every
 * {@code grocery.order.watchdog.interval} (1 min, fixed delay; env {@code ORDER_WATCHDOG_INTERVAL}). One run:
 * <ol>
 *   <li><b>Resolve</b> open flags whose order has left the flagged status (normally the status change itself already
 *       did, see {@link OrderEventService} and {@link OrderCancellationService}).</li>
 *   <li><b>Detect</b>, per {@link StuckRule}: a plain read of the orders past their threshold without a flag for their
 *       status (batches of {@code batch-size}, longest overdue first); then each one in its own short transaction:
 *       lock the order ({@link OrderLocks}), re-check the rule on the locked row, insert the {@code stuck_order} flag
 *       and — only when the flag is new — publish {@code OrderStuckDetected} through the outbox.</li>
 * </ol>
 * Safe with replicas: the flag's primary key (order, status) lets exactly one run flag an order in a status, so the
 * event is published once; the order lock keeps a flag from racing a status change. A failure on one order is logged
 * and retried by the next run.
 */
@Component
public class StuckOrderWatchdog {

    /** Upper bound of candidate batches per status and run. */
    static final int MAX_BATCHES = 10;
    private static final Logger log = LoggerFactory.getLogger(StuckOrderWatchdog.class);

    /** What one run did. */
    public record Run(int resolved, int flagged) {
    }

    private final StuckOrders stuckOrders;
    private final OrderLocks locks;
    private final OrderEventPublisher events;
    private final OrderProperties.Watchdog settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StuckOrderWatchdog(StuckOrders stuckOrders, OrderLocks locks, OrderEventPublisher events,
                              OrderProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.stuckOrders = stuckOrders;
        this.locks = locks;
        this.events = events;
        this.settings = properties.getWatchdog();
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Runs only when {@code grocery.order.watchdog.enabled} (default true): scheduling may be on for the return
     * escalation poller alone, see OrderConfig.
     */
    @Scheduled(initialDelayString = "${grocery.order.watchdog.interval:1m}",
            fixedDelayString = "${grocery.order.watchdog.interval:1m}")
    public void scheduled() {
        if (settings.isEnabled()) {
            run();
        }
    }

    /** One run at the clock's current time. */
    public Run run() {
        Instant now = clock.instant();
        int resolved = resolveMoved(now);
        int flagged = 0;
        for (StuckRule rule : StuckRule.values()) {
            Instant cutoff = now.minus(rule.threshold(settings));
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<UUID> candidates;
                try {
                    candidates = stuckOrders.candidates(rule.status(), rule.column(), cutoff,
                            settings.getBatchSize());
                } catch (RuntimeException e) {
                    log.warn("Stuck-order watchdog: looking for {} orders failed, retried in the next run: {}",
                            rule.status(), e.toString());
                    break;
                }
                for (UUID orderId : candidates) {
                    try {
                        if (flag(orderId, now)) {
                            flagged++;
                        }
                    } catch (RuntimeException e) {
                        log.warn("Stuck-order watchdog: order {} could not be checked, retried in the next run: {}",
                                orderId, e.toString());
                    }
                }
                if (candidates.size() < settings.getBatchSize()) {
                    break;
                }
            }
        }
        if (resolved > 0 || flagged > 0) {
            log.info("Stuck-order watchdog: {} order(s) flagged, {} flag(s) resolved", flagged, resolved);
        }
        return new Run(resolved, flagged);
    }

    private int resolveMoved(Instant now) {
        int resolved = 0;
        try {
            for (StuckOrders.Moved moved : stuckOrders.moved()) {
                if (stuckOrders.resolve(moved.orderId(), moved.status(), now)) {
                    resolved++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Stuck-order watchdog: resolving flags failed, retried in the next run: {}", e.toString());
        }
        return resolved;
    }

    /** Locks the order, re-checks its rule and flags it; true when this call flagged it (and published the event). */
    boolean flag(UUID orderId, Instant now) {
        Boolean flagged = transaction.execute(status -> {
            CustomerOrder order = locks.lock(orderId);
            if (order == null) {
                return false;
            }
            Optional<StuckRule> rule = StuckRule.of(order.getStatus());
            if (rule.isEmpty()) {
                return false;                                        // terminal meanwhile
            }
            Instant dueAt = rule.get().dueAt(order, settings);
            if (dueAt == null || dueAt.isAfter(now)) {
                return false;                                        // moved on (a newer status) meanwhile
            }
            Instant since = rule.get().since(order);
            if (!stuckOrders.flag(order.getId(), order.getStatus(), rule.get().reason(), since, dueAt, now)) {
                return false;                                        // flagged before (another replica / run)
            }
            events.stuckDetected(order, rule.get().reason(), since, now);
            log.warn("Order {} ({}) is stuck in {} since {}: {} (due {})", order.getOrderNumber(), order.getId(),
                    order.getStatus(), since, rule.get().reason(), dueAt);
            return true;
        });
        return Boolean.TRUE.equals(flagged);
    }
}
