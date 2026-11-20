package com.grocery.payment.service;

import com.grocery.common.id.Ids;
import com.grocery.payment.entity.AttemptStatus;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentAttempt;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.entity.RefundReason;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.repository.PaymentAttemptRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies what the provider reported about one Razorpay payment to a payment, inside a transaction that holds the
 * payment's lock ({@link PaymentLocks}). Shared by verify, webhooks, the expiry poller and the simulator, so every
 * path makes exactly the same change:
 * <ul>
 *   <li><b>captured</b> → attempt CAPTURED; the payment CAPTURED + {@code PaymentCaptured}. Idempotent per Razorpay
 *       payment id (a second report changes and publishes nothing). An EXPIRED / CANCELLED payment becomes a
 *       <b>late capture</b>: {@code PaymentCaptured} with {@code late: true} and a full LATE_CAPTURE refund, which
 *       the caller sends to the provider after commit.</li>
 *   <li><b>failed</b> → attempt FAILED + {@code PaymentFailed} (once per Razorpay payment id); the payment stays as it
 *       is (the customer may try again on the same Razorpay order). {@code retryable} = still open and in the
 *       window.</li>
 *   <li><b>authorized</b> → attempt AUTHORIZED only (the caller captures it at the provider).</li>
 * </ul>
 */
@Component
public class PaymentRecorder {

    public enum CaptureOutcome {
        /** Newly captured (PaymentCaptured late=false). */
        CAPTURED,
        /** Newly captured after EXPIRED / CANCELLED (PaymentCaptured late=true, LATE_CAPTURE refund opened). */
        LATE_CAPTURED,
        /** This Razorpay payment was recorded before: nothing changed. */
        ALREADY_CAPTURED,
        /** The payment was captured by a different Razorpay payment: only the attempt is recorded (see README). */
        CAPTURED_BY_ANOTHER_PAYMENT
    }

    /** @param lateRefundId the LATE_CAPTURE refund to send to the provider after commit (null otherwise) */
    public record Capture(CaptureOutcome outcome, UUID lateRefundId) {
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentRecorder.class);

    private final PaymentAttemptRepository attempts;
    private final RefundLedger ledger;
    private final PaymentEventPublisher events;
    private final EntityManager entityManager;

    public PaymentRecorder(PaymentAttemptRepository attempts, RefundLedger ledger, PaymentEventPublisher events,
                           EntityManager entityManager) {
        this.attempts = attempts;
        this.ledger = ledger;
        this.events = events;
        this.entityManager = entityManager;
    }

    public Capture captured(Payment payment, ProviderPayment reported, Instant now) {
        record(payment, reported, AttemptStatus.CAPTURED, now);
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            if (reported.id().equals(payment.getRazorpayPaymentId())) {
                return new Capture(CaptureOutcome.ALREADY_CAPTURED, null);
            }
            log.error("Payment {} (order {}) was captured by {} and AGAIN by {}: the second payment must be refunded "
                            + "by hand at Razorpay", payment.getId(), payment.getOrderNumber(),
                    payment.getRazorpayPaymentId(), reported.id());
            return new Capture(CaptureOutcome.CAPTURED_BY_ANOTHER_PAYMENT, null);
        }
        if (reported.amount() != null && reported.amount().compareTo(payment.getAmount()) != 0) {
            log.warn("Payment {} captured {} for an amount of {}", payment.getId(), reported.amount(),
                    payment.getAmount());
        }
        PaymentStatus before = payment.getStatus();
        boolean late = payment.capture(reported.id(), reported.method(), now);
        events.captured(payment);
        if (!late) {
            log.info("Payment {} of order {} captured ({})", payment.getId(), payment.getOrderNumber(), reported.id());
            return new Capture(CaptureOutcome.CAPTURED, null);
        }
        BigDecimal refundable = ledger.refundable(payment);
        Refund refund = ledger.open(payment, Ids.newId(), RefundReason.LATE_CAPTURE, refundable, null, null, now);
        log.warn("Late capture of payment {} (order {}, was {}): refunding {} in full", payment.getId(),
                payment.getOrderNumber(), before, reported.id());
        return new Capture(CaptureOutcome.LATE_CAPTURED, refund.getId());
    }

    /** @return true when this decline was new (and {@code PaymentFailed} was published) */
    public boolean failed(Payment payment, ProviderPayment reported, Instant now) {
        if (!record(payment, reported, AttemptStatus.FAILED, now)) {
            return false;
        }
        boolean retryable = payment.getStatus().isOpen() && now.isBefore(payment.getExpiresAt());
        events.failed(payment, reported.id(), reported.errorCode(), reported.errorDescription(), retryable);
        log.info("Payment attempt {} of order {} declined: {} {}", reported.id(), payment.getOrderNumber(),
                reported.errorCode(), reported.errorDescription());
        return true;
    }

    public void authorized(Payment payment, ProviderPayment reported, Instant now) {
        record(payment, reported, AttemptStatus.AUTHORIZED, now);
    }

    /** Creates or moves the attempt row; true when it changed. */
    private boolean record(Payment payment, ProviderPayment reported, AttemptStatus status, Instant now) {
        Optional<PaymentAttempt> existing = attempts.findByRazorpayPaymentId(reported.id());
        if (existing.isPresent()) {
            PaymentAttempt attempt = existing.get();
            if (!attempt.getPaymentId().equals(payment.getId())) {
                throw new IllegalStateException("Razorpay payment " + reported.id() + " belongs to payment "
                        + attempt.getPaymentId() + ", not " + payment.getId());
            }
            return attempt.moveTo(status, reported.method(), reported.amount(), reported.errorCode(),
                    reported.errorDescription(), now);
        }
        entityManager.persist(PaymentAttempt.of(Ids.newId(), payment.getId(), reported.id(), status,
                reported.method(), reported.amount(), reported.errorCode(), reported.errorDescription(), now));
        return true;
    }
}
