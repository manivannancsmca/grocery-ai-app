package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.dto.PaymentDtos.ReconciliationSummary;
import com.grocery.payment.dto.PaymentDtos.RecoveredPayment;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.repository.ReconciliationLedger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Runs {@link PaymentReconciliationService} every {@code grocery.payment.reconcile-interval} (2 min, fixed delay; env
 * {@code PAYMENT_RECONCILE_INTERVAL}), in batches of {@code grocery.payment.reconcile-batch-size}, until a batch comes
 * back short; then adds the run's numbers to the durable counters ({@link ReconciliationLedger}). Safe with several
 * replicas (claims use {@code FOR UPDATE SKIP LOCKED} and a lease). A payment whose check fails — the provider
 * unreachable, an open circuit breaker — is logged, counted as a failure and asked about again in the next run; the
 * others go on. The payment-window poller ({@link PaymentExpiryPoller}) keeps its own last check before expiring.
 *
 * <p>Phase 13 metrics (the same numbers as the ledger, per instance): {@code grocery_payments_reconciliation_recovered_total}
 * (captures, authorizations and declines nobody had reported) and {@code grocery_payments_reconciliation_failures_total}
 * (payments the provider could not be asked about) — alert {@code ReconciliationFailures}.
 */
@Component
public class PaymentReconciler {

    /** Upper bound of batches per run. */
    static final int MAX_BATCHES_PER_RUN = 20;
    public static final String RECOVERED_METRIC = "grocery.payments.reconciliation.recovered";
    public static final String FAILURES_METRIC = "grocery.payments.reconciliation.failures";
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    /** What one run did: payments checked, recoveries, failed checks. */
    public record Run(int checked, int recovered, int failures) {
    }

    private final PaymentReconciliationService reconciliation;
    private final ReconciliationLedger ledger;
    private final int batchSize;
    private final Clock clock;
    private final Counter recoveredCounter;
    private final Counter failuresCounter;

    public PaymentReconciler(PaymentReconciliationService reconciliation, ReconciliationLedger ledger,
                             PaymentProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.reconciliation = reconciliation;
        this.ledger = ledger;
        this.batchSize = properties.reconcileBatchSize();
        this.clock = clock;
        // registered at start (value 0), so increase() sees the first recovery / failure
        this.recoveredCounter = Counter.builder(RECOVERED_METRIC)
                .description("Payments brought in line by reconciliation (unreported captures, authorizations, declines)")
                .register(meterRegistry);
        this.failuresCounter = Counter.builder(FAILURES_METRIC)
                .description("Pending payments reconciliation could not check at the provider")
                .register(meterRegistry);
    }

    /** Scheduled only when {@code grocery.payment.pollers-enabled} (default true), see PaymentConfig. */
    @Scheduled(initialDelayString = "${grocery.payment.reconcile-interval:2m}",
            fixedDelayString = "${grocery.payment.reconcile-interval:2m}")
    public void scheduled() {
        run();
    }

    /** One run at the clock's current time. */
    public Run run() {
        Instant runStartedAt = clock.instant();
        int checked = 0;
        int recovered = 0;
        int failures = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<UUID> claimed;
            try {
                claimed = reconciliation.claim(runStartedAt, batchSize);
            } catch (RuntimeException e) {
                log.warn("Reconciliation: claiming pending payments failed, retried in the next run: {}",
                        e.toString());
                break;
            }
            for (UUID paymentId : claimed) {
                checked++;
                try {
                    int found = reconciliation.reconcile(paymentId).size();
                    recovered += found;
                    recoveredCounter.increment(found);
                } catch (ProviderUnavailableException e) {
                    failures++;
                    failuresCounter.increment();
                    log.warn("Reconciliation: the provider could not be asked about payment {}, asked again in the "
                            + "next run: {}", paymentId, e.getMessage());
                } catch (RuntimeException e) {
                    failures++;
                    failuresCounter.increment();
                    log.warn("Reconciliation of payment {} failed, retried in the next run: {}", paymentId,
                            e.toString());
                } finally {
                    release(paymentId, runStartedAt);
                }
            }
            if (claimed.size() < batchSize) {
                break;
            }
        }
        try {
            ledger.runFinished(clock.instant(), checked, recovered, failures);
        } catch (RuntimeException e) {
            log.warn("Reconciliation: the run's counters could not be stored: {}", e.toString());
        }
        if (recovered > 0 || failures > 0) {
            log.info("Reconciliation: {} pending payment(s) checked, {} recovered, {} failed", checked, recovered,
                    failures);
        } else if (checked > 0) {
            log.debug("Reconciliation: {} pending payment(s) checked, nothing to recover", checked);
        }
        return new Run(checked, recovered, failures);
    }

    /** {@code GET /api/admin/payments/reconciliation}: the counters and the last 20 recoveries, newest first. */
    public ReconciliationSummary summary() {
        ReconciliationLedger.Stats stats = ledger.stats();
        return new ReconciliationSummary(stats.lastRunAt(), stats.checked(), stats.recovered(), stats.failures(),
                ledger.latest(PaymentReconciliationService.LAST_RECOVERED).stream()
                        .map(r -> new RecoveredPayment(r.orderId(), r.orderNumber(), r.outcome(), r.at()))
                        .toList());
    }

    private void release(UUID paymentId, Instant runStartedAt) {
        try {
            reconciliation.finish(paymentId, runStartedAt);
        } catch (RuntimeException e) {
            log.warn("Reconciliation: payment {} keeps its lease until it runs out: {}", paymentId, e.toString());
        }
    }
}
