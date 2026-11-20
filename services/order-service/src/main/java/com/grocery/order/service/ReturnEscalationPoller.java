package com.grocery.order.service;

import com.grocery.order.config.OrderProperties;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.repository.OrderReturnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Escalates returns the store did not decide in time (docs/api/phase-12-post-mvp-features.md §3): every
 * {@code grocery.order.returns.escalation-interval} (1 min, fixed delay; env {@code RETURN_ESCALATION_INTERVAL}),
 * REQUESTED returns past their {@code decideBy} ({@code RETURN_DECISION_TIMEOUT} after the request) become ESCALATED
 * and {@code ReturnEscalated} is published (notification tells the admins, who decide them now).
 *
 * <p>One short transaction per batch ({@code batch-size}): claim the overdue rows with {@code FOR UPDATE SKIP LOCKED}
 * — safe with several replicas, which claim disjoint batches, and never waiting for a store that is deciding one of
 * them right now — escalate them and write the events (and the orders' event timelines). Only return rows are locked,
 * never order rows, so it cannot deadlock with a decision (order, then return). Returns do not affect the stuck-order
 * watchdog: a delivered order is terminal.
 */
@Component
public class ReturnEscalationPoller {

    static final int MAX_BATCHES = 10;
    private static final Logger log = LoggerFactory.getLogger(ReturnEscalationPoller.class);

    private final OrderReturnRepository returns;
    private final OrderEventPublisher events;
    private final OrderProperties.Returns settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReturnEscalationPoller(OrderReturnRepository returns, OrderEventPublisher events,
                                  OrderProperties properties, PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.returns = returns;
        this.events = events;
        this.settings = properties.getReturns();
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Runs only when {@code grocery.order.returns.escalation-enabled} (default true), see OrderConfig. */
    @Scheduled(initialDelayString = "${grocery.order.returns.escalation-interval:1m}",
            fixedDelayString = "${grocery.order.returns.escalation-interval:1m}")
    public void scheduled() {
        if (settings.isEscalationEnabled()) {
            escalateDue();
        }
    }

    /** One run at the clock's current time; returns how many returns it escalated. */
    public int escalateDue() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Integer escalated;
            try {
                escalated = transaction.execute(status -> escalateBatch(clock.instant()));
            } catch (RuntimeException e) {
                log.warn("Return escalation failed, retried in the next run: {}", e.toString());
                break;
            }
            int count = escalated == null ? 0 : escalated;
            total += count;
            if (count < settings.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("Escalated {} undecided return(s) to the admins", total);
        }
        return total;
    }

    private int escalateBatch(Instant now) {
        List<UUID> claimed = returns.claimOverdue(now, settings.getBatchSize()).stream()
                .map(UUID::fromString).toList();
        for (OrderReturn request : returns.findAllById(claimed)) {
            request.escalate(now);
            events.returnEscalated(request);
            log.warn("Return {} of order {} escalated: the store did not decide by {}", request.getReturnNumber(),
                    request.getOrderNumber(), request.getDecideBy());
        }
        return claimed.size();
    }
}
