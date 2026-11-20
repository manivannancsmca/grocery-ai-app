package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.entity.RefundStatus;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.NewRefund;
import com.grocery.payment.gateway.PaymentGateway.ProviderRefund;
import com.grocery.payment.gateway.PaymentGateway.ProviderRefundStatus;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Sends refunds to the provider and records the outcome. A refund is always opened as PENDING first, in the
 * transaction that decides it ({@link RefundLedger#open}: OrderCancelled, late capture, admin refund), and only
 * after that commit does {@link #process} call the provider — never while a row lock is held:
 * <ul>
 *   <li>processed → PROCESSED + {@code RefundCompleted} ({@code totalRefunded}, {@code fullyRefunded});</li>
 *   <li>rejected (4xx) or failed → FAILED + {@code RefundFailed} (the amount becomes refundable again; an admin can
 *       try again with a new Idempotency-Key);</li>
 *   <li>accepted but still pending (normal outside test mode) → stays PENDING with the provider's refund id; the
 *       {@code refund.processed} / {@code refund.failed} webhook finishes it ({@link #applyWebhook}), and the refund
 *       poller re-checks it every {@code grocery.payment.refund-retry-interval} in case the webhook is lost;</li>
 *   <li>provider unreachable → stays PENDING without a provider id, retried by the refund poller with exponential
 *       backoff (refund-retry-interval × 2^n, at most {@link #MAX_BACKOFF}). The retry sends the same refund id as
 *       {@code receipt}, {@code notes.refundId} and Razorpay's {@code X-Refund-Idempotency} header, so a call that
 *       did reach Razorpay before timing out does not refund twice.</li>
 * </ul>
 */
@Service
public class RefundService {

    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    static final String FAILED_AT_PROVIDER = "REFUND_FAILED";
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentLocks locks;
    private final RefundLedger ledger;
    private final PaymentGateway gateway;
    private final PaymentProperties properties;
    private final TransactionTemplate transaction;
    private final java.time.Clock clock;

    public RefundService(RefundRepository refunds, PaymentRepository payments, PaymentLocks locks,
                         RefundLedger ledger, PaymentGateway gateway, PaymentProperties properties,
                         PlatformTransactionManager transactionManager, java.time.Clock clock) {
        this.refunds = refunds;
        this.payments = payments;
        this.locks = locks;
        this.ledger = ledger;
        this.gateway = gateway;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Calls the provider for a PENDING refund — creates it, or re-checks it when it already has a provider id — and
     * records the result. Must not run inside a transaction. A temporary failure only schedules a retry, so callers
     * (a webhook, the Kafka consumer, an admin request) never fail because of it.
     */
    public void process(UUID refundId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refunds are sent to the provider after the opening transaction commits");
        }
        Refund refund = refunds.findById(refundId).orElse(null);
        if (refund == null || refund.getStatus() != RefundStatus.PENDING) {
            return;
        }
        Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
        boolean create = refund.getRazorpayRefundId() == null;
        ProviderRefund result;
        try {
            result = create
                    ? gateway.createRefund(new NewRefund(payment.getRazorpayPaymentId(), refund.getAmount(),
                    refundId.toString(), notes(refund)))
                    : gateway.fetchRefund(refund.getRazorpayRefundId());
        } catch (ProviderRejectedException e) {
            if (create) {
                log.warn("Refund {} of order {} rejected by the provider: {}", refundId, payment.getOrderNumber(),
                        e.getMessage());
                inLockedRefund(refundId, (p, r) -> ledger.failed(p, r, null, e.code(), e.description(), clock.instant()));
            } else {
                log.warn("Re-checking refund {} at the provider failed: {}", refundId, e.getMessage());
                retryLater(refundId);
            }
            return;
        } catch (ProviderUnavailableException e) {
            log.warn("Refund {} of order {}: provider unavailable, retried later ({})", refundId,
                    payment.getOrderNumber(), e.getMessage());
            retryLater(refundId);
            return;
        }
        record(refundId, result);
    }

    /**
     * {@link #process} as the follow-up of a change that has already committed (a late capture, OrderCancelled, an
     * admin refund): whatever goes wrong here must not fail that change. The refund is PENDING in the database, so
     * the refund poller takes it over once its lease ends.
     */
    public void processAfterCommit(UUID refundId) {
        try {
            process(refundId);
        } catch (RuntimeException e) {
            log.warn("Sending refund {} to the provider failed; the refund poller retries it: {}", refundId,
                    e.toString());
        }
    }

    /**
     * The refund poller's claim: PENDING refunds due for a (re)try, leased for {@code grocery.payment.poll-lease} so
     * no other poller takes them while this one calls the provider ({@code FOR UPDATE SKIP LOCKED}).
     */
    public List<UUID> claimDue(int limit) {
        Instant now = clock.instant();
        List<UUID> ids = transaction.execute(status -> {
            List<UUID> claimed = refunds.claimDue(now, limit).stream().map(UUID::fromString).toList();
            refunds.findAllById(claimed).forEach(refund -> refund.lease(now.plus(properties.pollLease())));
            return claimed;
        });
        return ids == null ? List.of() : ids;
    }

    /**
     * {@code refund.processed} / {@code refund.failed} webhook, inside the webhook's transaction. The refund is found
     * by the provider's refund id or by our id in {@code notes.refundId} (when our create call timed out).
     *
     * @return what happened, for {@code webhook_event.outcome}; empty when the refund is not ours
     */
    public Optional<String> applyWebhook(ProviderRefund reported, boolean processed) {
        Optional<UUID> refundId = Optional.ofNullable(reported.id()).flatMap(refunds::findIdByRazorpayRefundId);
        if (refundId.isEmpty() && reported.refundId() != null) {
            refundId = parse(reported.refundId()).filter(refunds::existsById);
        }
        if (refundId.isEmpty()) {
            return Optional.empty();
        }
        UUID paymentId = refunds.findPaymentIdById(refundId.get()).orElseThrow();
        Payment payment = locks.payment(paymentId);
        Refund refund = locks.refund(payment, refundId.get());
        if (refund.getStatus() != RefundStatus.PENDING) {
            return Optional.of("refund " + refund.getId() + " already " + refund.getStatus());
        }
        Instant now = clock.instant();
        if (processed) {
            ledger.processed(payment, refund, reported.id(), now);
        } else {
            ledger.failed(payment, refund, reported.id(), FAILED_AT_PROVIDER, "The refund failed at the provider", now);
        }
        log.info("Refund {} of order {} {} (webhook)", refund.getId(), payment.getOrderNumber(), refund.getStatus());
        return Optional.of("refund " + refund.getId() + " " + refund.getStatus());
    }

    private void record(UUID refundId, ProviderRefund result) {
        inLockedRefund(refundId, (payment, refund) -> {
            Instant now = clock.instant();
            if (result.status() == ProviderRefundStatus.PROCESSED) {
                ledger.processed(payment, refund, result.id(), now);
                log.info("Refund {} of order {} processed ({} {})", refundId, payment.getOrderNumber(),
                        refund.getAmount(), refund.getReason());
            } else if (result.status() == ProviderRefundStatus.FAILED) {
                ledger.failed(payment, refund, result.id(), FAILED_AT_PROVIDER, "The refund failed at the provider",
                        now);
                log.warn("Refund {} of order {} failed at the provider", refundId, payment.getOrderNumber());
            } else {
                refund.acceptedByProvider(result.id(), now.plus(properties.refundRetryInterval()), now);
                log.info("Refund {} of order {} accepted by the provider ({}), waiting for it to be processed",
                        refundId, payment.getOrderNumber(), result.id());
            }
        });
    }

    private void retryLater(UUID refundId) {
        inLockedRefund(refundId, (payment, refund) -> {
            Instant now = clock.instant();
            refund.retryAt(now.plus(backoff(refund.getProviderAttempts())), now);
        });
    }

    /** refund-retry-interval × 2^attempts, capped at {@link #MAX_BACKOFF}. */
    Duration backoff(int attemptsSoFar) {
        Duration base = properties.refundRetryInterval();
        Duration delay = base.multipliedBy(1L << Math.min(attemptsSoFar, 16));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** Locks the refund's payment, then the refund, and applies {@code change} if the refund is still PENDING. */
    private void inLockedRefund(UUID refundId, BiConsumer<Payment, Refund> change) {
        transaction.executeWithoutResult(status -> {
            UUID paymentId = refunds.findPaymentIdById(refundId).orElseThrow();
            Payment payment = locks.payment(paymentId);
            Refund refund = locks.refund(payment, refundId);
            if (refund.getStatus() == RefundStatus.PENDING) {
                change.accept(payment, refund);
            }
        });
    }

    private static Map<String, String> notes(Refund refund) {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("refundId", refund.getId().toString());
        notes.put("orderId", refund.getOrderId().toString());
        notes.put("reason", refund.getReason().name());
        if (refund.getReturnId() != null) {
            notes.put("returnId", refund.getReturnId().toString());
        }
        return notes;
    }

    private static Optional<UUID> parse(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
