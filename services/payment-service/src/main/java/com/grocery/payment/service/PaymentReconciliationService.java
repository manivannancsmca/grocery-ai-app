package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.entity.AttemptStatus;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPaymentStatus;
import com.grocery.payment.repository.PaymentAttemptRepository;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.repository.ReconciliationLedger;
import com.grocery.payment.service.CaptureService.Recorded;
import com.grocery.payment.service.CaptureService.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment reconciliation (docs/api/phase-10-event-driven-hardening.md §3): a payment still PENDING whose Razorpay order
 * is older than {@code grocery.payment.reconcile-after} and whose window is still open may have been paid with both the
 * verify call and the webhook lost (browser closed, webhook not delivered). For each such payment:
 * <ol>
 *   <li><b>Claim</b> (one short transaction per batch): {@code SELECT … FOR UPDATE SKIP LOCKED}, then
 *       {@code reconcile_lease_until = now + grocery.payment.poll-lease}, commit — another replica skips locked rows
 *       during the claim and leased rows afterwards; no lock is held during the provider call.</li>
 *   <li><b>Ask the provider</b> for the Razorpay order's payments ({@link PaymentGateway#listOrderPayments}) and apply
 *       them through the same code as verify and the webhook ({@link CaptureService#settle}): a captured one →
 *       captured + {@code PaymentCaptured}; else an authorized one → captured at the provider first; then every
 *       declined one we have not recorded → a failed attempt + {@code PaymentFailed} (after a capture it is no longer
 *       retryable, so nobody is told about it).</li>
 *   <li><b>Finish</b>: the lease is released and the payment marked as checked by this run, so a run checks a
 *       payment once and the next run checks it again (until it is captured, expired or cancelled). A failure (the
 *       provider unreachable) is left to the next run.</li>
 * </ol>
 * Every new capture or decline is written to the durable recovery list ({@link ReconciliationLedger}) with what the
 * provider had: {@code CAPTURED}, {@code AUTHORIZED} (captured by us) or {@code FAILED}.
 */
@Service
public class PaymentReconciliationService {

    /** {@code lastRecovered} of the admin summary. */
    public static final int LAST_RECOVERED = 20;
    /** {@code outcome} of a recovery: the provider had captured it; the unreported capture was recorded. */
    public static final String OUTCOME_CAPTURED = "CAPTURED";
    /** {@code outcome}: the provider held it authorized; we captured it at the provider and recorded the capture. */
    public static final String OUTCOME_AUTHORIZED = "AUTHORIZED";
    /** {@code outcome}: a declined attempt nobody reported was recorded (a failed attempt). */
    public static final String OUTCOME_FAILED = "FAILED";
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentLocks locks;
    private final PaymentGateway gateway;
    private final CaptureService captures;
    private final ReconciliationLedger ledger;
    private final PaymentProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PaymentReconciliationService(PaymentRepository payments, PaymentAttemptRepository attempts,
                                        PaymentLocks locks, PaymentGateway gateway, CaptureService captures,
                                        ReconciliationLedger ledger, PaymentProperties properties,
                                        PlatformTransactionManager transactionManager, Clock clock) {
        this.payments = payments;
        this.attempts = attempts;
        this.locks = locks;
        this.gateway = gateway;
        this.captures = captures;
        this.ledger = ledger;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Claims up to {@code limit} payments due for a check that the run started at {@code runStartedAt} has not checked. */
    public List<UUID> claim(Instant runStartedAt, int limit) {
        Instant now = clock.instant();
        List<UUID> ids = transaction.execute(status -> {
            List<UUID> claimed = payments.claimForReconciliation(now, now.minus(properties.reconcileAfter()),
                    runStartedAt, limit).stream().map(UUID::fromString).toList();
            payments.findAllById(claimed).forEach(p -> p.claimForReconciliation(now.plus(properties.pollLease())));
            return claimed;
        });
        return ids == null ? List.of() : ids;
    }

    /**
     * Checks one claimed payment at the provider and applies what it finds. Never call it inside a transaction (it
     * calls the provider).
     *
     * @return what was recovered (empty: nothing new)
     * @throws com.grocery.payment.gateway.ProviderUnavailableException the provider could not be asked (nothing
     *                                                                  changed; the next run asks again)
     */
    public List<ReconciliationLedger.Recovery> reconcile(UUID paymentId) {
        Payment snapshot = payments.findById(paymentId).orElse(null);
        if (snapshot == null || snapshot.getStatus() != PaymentStatus.PENDING
                || snapshot.getRazorpayOrderId() == null) {
            return List.of();                                           // captured / expired / cancelled meanwhile
        }
        List<ProviderPayment> atProvider = gateway.listOrderPayments(snapshot.getRazorpayOrderId());
        List<ReconciliationLedger.Recovery> recovered = new ArrayList<>();
        Optional<ProviderPayment> captured = atProvider.stream().filter(p -> p.status().wasCaptured()).findFirst();
        if (captured.isPresent()) {
            note(snapshot, OUTCOME_CAPTURED, captures.settle(paymentId, captured.get()), recovered);
        } else {
            for (ProviderPayment authorized : atProvider) {
                if (authorized.status() == ProviderPaymentStatus.AUTHORIZED) {
                    Settlement settlement = captures.settle(paymentId, authorized);
                    note(snapshot, OUTCOME_AUTHORIZED, settlement, recovered);
                    if (settlement.result() == CaptureService.Result.CAPTURED) {
                        break;
                    }
                }
            }
        }
        for (ProviderPayment declined : atProvider) {
            if (declined.status() == ProviderPaymentStatus.FAILED && !knownDecline(declined)) {
                note(snapshot, OUTCOME_FAILED, captures.settle(paymentId, declined), recovered);
            }
        }
        return recovered;
    }

    /** The run is done with the payment: lease released, checked by this run. */
    public void finish(UUID paymentId, Instant runStartedAt) {
        transaction.executeWithoutResult(status -> locks.payment(paymentId).finishReconciliation(runStartedAt));
    }

    /** A decline already recorded (by the webhook, the simulator or an earlier run) changes nothing: not asked again. */
    private boolean knownDecline(ProviderPayment declined) {
        return attempts.findByRazorpayPaymentId(declined.id())
                .map(attempt -> attempt.getStatus() == AttemptStatus.FAILED)
                .orElse(false);
    }

    /**
     * Records a recovery when the settlement changed something on our side. {@code outcome} = what the provider had:
     * CAPTURED, AUTHORIZED (we captured it) or FAILED — a capture that turned into a late capture (the order was
     * cancelled meanwhile; refunded at once) keeps the outcome of what was found.
     */
    private void note(Payment payment, String outcome, Settlement settlement,
                      List<ReconciliationLedger.Recovery> recovered) {
        Recorded what = settlement.recorded();
        if (what == null) {
            return;                                                     // nothing new (already known / not paid)
        }
        Instant now = clock.instant();
        ledger.recovered(payment.getId(), payment.getOrderId(), payment.getOrderNumber(), outcome,
                settlement.payment().id(), now);
        recovered.add(new ReconciliationLedger.Recovery(payment.getOrderId(), payment.getOrderNumber(), outcome,
                settlement.payment().id(), now));
        if (what == Recorded.DECLINED) {
            log.info("Reconciliation: payment {} of order {} had a declined attempt {} nobody reported",
                    payment.getId(), payment.getOrderNumber(), settlement.payment().id());
        } else {
            log.warn("Reconciliation: payment {} of order {} was paid at the provider ({}, {}) but never reported: {}",
                    payment.getId(), payment.getOrderNumber(), settlement.payment().id(), outcome, what);
        }
    }
}
