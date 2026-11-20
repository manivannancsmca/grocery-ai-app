package com.grocery.payment.service;

import com.grocery.payment.entity.Payment;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPaymentStatus;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.service.PaymentRecorder.Capture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Brings a payment in line with what the provider says about one Razorpay payment of its order — the path shared by
 * verify, {@code payment.authorized} webhooks, the expiry poller, the simulator and (Phase 10) reconciliation:
 * <ol>
 *   <li>{@code authorized} → captured at the provider first ({@code POST /payments/{id}/capture}, no lock held). If
 *       the provider refuses because it was captured meanwhile (auto-capture, a webhook, a parallel verify), the
 *       payment is fetched again and its current state used.</li>
 *   <li>The result is recorded in one transaction with the payment locked ({@link PaymentRecorder}).</li>
 *   <li>After commit, a late capture's LATE_CAPTURE refund is sent to the provider ({@link RefundService#process}).</li>
 * </ol>
 */
@Service
public class CaptureService {

    public enum Result {
        /** The payment is captured (now or before). */
        CAPTURED,
        /** This Razorpay payment failed (a declined attempt was recorded). */
        FAILED,
        /** Not paid (yet): created, or still authorized because the capture did not go through. */
        NOT_PAID
    }

    /** What a settlement newly recorded on our side (reconciliation reports it as recovered). */
    public enum Recorded {
        /** The payment was captured now ({@code PaymentCaptured}). */
        CAPTURED,
        /** Captured now after EXPIRED / CANCELLED ({@code PaymentCaptured} late, refunded). */
        LATE_CAPTURED,
        /** A declined attempt we had not seen ({@code PaymentFailed}). */
        DECLINED
    }

    /** @param recorded what this call newly recorded; null = nothing new (already known, or not paid yet) */
    public record Settlement(Result result, ProviderPayment payment, Recorded recorded) {

        public Settlement(Result result, ProviderPayment payment) {
            this(result, payment, null);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final PaymentLocks locks;
    private final PaymentRecorder recorder;
    private final RefundService refunds;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CaptureService(PaymentGateway gateway, PaymentRepository payments, PaymentLocks locks,
                          PaymentRecorder recorder, RefundService refunds,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.gateway = gateway;
        this.payments = payments;
        this.locks = locks;
        this.recorder = recorder;
        this.refunds = refunds;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param reported a Razorpay payment of the payment's Razorpay order, as the provider reported it
     * @throws ProviderUnavailableException when capturing an authorized payment failed temporarily (nothing recorded)
     */
    public Settlement settle(UUID paymentId, ProviderPayment reported) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("settle() calls the provider; it must not run inside a transaction");
        }
        ProviderPayment current = reported.status() == ProviderPaymentStatus.AUTHORIZED
                ? captureAtProvider(paymentId, reported)
                : reported;
        return switch (current.status()) {
            case CAPTURED, REFUNDED -> {
                Capture capture = transaction.execute(status ->
                        recorder.captured(locks.payment(paymentId), current, clock.instant()));
                if (capture != null && capture.lateRefundId() != null) {
                    refunds.processAfterCommit(capture.lateRefundId());
                }
                yield new Settlement(Result.CAPTURED, current, capture == null ? null : switch (capture.outcome()) {
                    case CAPTURED -> Recorded.CAPTURED;
                    case LATE_CAPTURED -> Recorded.LATE_CAPTURED;
                    case ALREADY_CAPTURED, CAPTURED_BY_ANOTHER_PAYMENT -> null;
                });
            }
            case FAILED -> {
                Boolean declined = transaction.execute(status ->
                        recorder.failed(locks.payment(paymentId), current, clock.instant()));
                yield new Settlement(Result.FAILED, current, Boolean.TRUE.equals(declined) ? Recorded.DECLINED : null);
            }
            case AUTHORIZED -> {
                transaction.executeWithoutResult(status ->
                        recorder.authorized(locks.payment(paymentId), current, clock.instant()));
                yield new Settlement(Result.NOT_PAID, current);
            }
            case CREATED -> new Settlement(Result.NOT_PAID, current);
        };
    }

    private ProviderPayment captureAtProvider(UUID paymentId, ProviderPayment authorized) {
        Payment payment = payments.findById(paymentId).orElseThrow();
        BigDecimal amount = authorized.amount() != null ? authorized.amount() : payment.getAmount();
        try {
            ProviderPayment captured = gateway.capturePayment(authorized.id(), amount, payment.getCurrency());
            log.info("Captured authorized payment {} of order {}", authorized.id(), payment.getOrderNumber());
            return captured;
        } catch (ProviderRejectedException e) {
            log.info("Capture of {} refused ({}); asking the provider for its current state", authorized.id(),
                    e.getMessage());
            return gateway.fetchPayment(authorized.id());
        }
    }
}
