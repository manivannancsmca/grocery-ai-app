package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.entity.RefundReason;
import com.grocery.payment.entity.RefundStatus;
import com.grocery.payment.repository.RefundRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * The refund bookkeeping inside a transaction that holds the payment's lock ({@link PaymentLocks}): what can still
 * be refunded, opening a PENDING refund, and finishing one as PROCESSED ({@code RefundCompleted}) or FAILED
 * ({@code RefundFailed}). Provider calls happen elsewhere ({@link RefundService}), after commit.
 */
@Component
public class RefundLedger {

    /** The failure code of a RETURN refund when nothing of the payment is refundable any more. */
    public static final String NOTHING_REFUNDABLE = "NOTHING_REFUNDABLE";

    private final RefundRepository refunds;
    private final PaymentEventPublisher events;
    private final PaymentProperties properties;

    public RefundLedger(RefundRepository refunds, PaymentEventPublisher events, PaymentProperties properties) {
        this.refunds = refunds;
        this.events = events;
        this.properties = properties;
    }

    /** Captured amount − processed refunds − PENDING refunds (never negative). 0 unless CAPTURED. */
    public BigDecimal refundable(Payment payment) {
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal pending = refunds.sumAmount(payment.getId(), RefundStatus.PENDING);
        return payment.getAmount().subtract(payment.getRefundedAmount()).subtract(pending)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * A new PENDING refund of {@code amount} (checked against {@link #refundable} by the caller). The caller calls
     * {@link RefundService#process} right after commit; until then the refund is leased so the refund poller
     * leaves it alone.
     */
    public Refund open(Payment payment, UUID refundId, RefundReason reason, BigDecimal amount, String note,
                       UUID requestedBy, Instant now) {
        if (amount.compareTo(refundable(payment)) > 0) {
            throw new IllegalStateException("Refund of " + amount + " exceeds what payment " + payment.getId()
                    + " can still refund");
        }
        Refund refund = Refund.pending(refundId, payment, amount, reason, note, requestedBy, now,
                now.plus(properties.pollLease()));
        refunds.save(refund);
        payment.refreshRefundStatus(true, now);
        return refund;
    }

    /**
     * Phase 12: the refund of an approved return (reason RETURN, {@code returnId}), never more than the payment can
     * still refund: {@code requested} is capped at {@link #refundable} and both are recorded ({@code amount},
     * {@code requested_amount}). When nothing is refundable any more (refunded already, not captured) the refund is
     * recorded as FAILED ({@value #NOTHING_REFUNDABLE}) with {@code RefundFailed} at once; otherwise it is PENDING and
     * the caller sends it to the provider after commit.
     *
     * @param requested positive, 2 decimals
     */
    public Refund openForReturn(Payment payment, UUID refundId, UUID returnId, String returnNumber,
                                BigDecimal requested, Instant now) {
        BigDecimal refundable = refundable(payment);
        BigDecimal amount = requested.min(refundable);
        if (amount.signum() <= 0) {
            Refund refund = Refund.pendingForReturn(refundId, payment, requested, requested, returnId,
                    "Return " + returnNumber + ": nothing left to refund", now, null);
            refunds.save(refund);
            failed(payment, refund, null, NOTHING_REFUNDABLE, "Nothing of the payment is left to refund", now);
            return refund;
        }
        String note = amount.compareTo(requested) < 0
                ? "Return " + returnNumber + " (capped at the refundable " + amount + " of " + requested + ")"
                : "Return " + returnNumber;
        Refund refund = Refund.pendingForReturn(refundId, payment, amount, requested, returnId, note, now,
                now.plus(properties.pollLease()));
        refunds.save(refund);
        payment.refreshRefundStatus(true, now);
        return refund;
    }

    /** The provider processed the refund: refunded amount + amount, {@code RefundCompleted}. */
    public void processed(Payment payment, Refund refund, String razorpayRefundId, Instant now) {
        refund.processed(razorpayRefundId, now);
        refunds.flush();
        payment.refunded(refund.getAmount(), anyPending(payment), now);
        events.refundCompleted(payment, refund);
    }

    /** The provider refused or failed the refund for good: {@code RefundFailed}; the amount is refundable again. */
    public void failed(Payment payment, Refund refund, String razorpayRefundId, String code, String reason,
                       Instant now) {
        refund.failed(razorpayRefundId, code, reason, now);
        refunds.flush();
        payment.refreshRefundStatus(anyPending(payment), now);
        events.refundFailed(payment, refund);
    }

    private boolean anyPending(Payment payment) {
        return refunds.existsByPaymentIdAndStatus(payment.getId(), RefundStatus.PENDING);
    }
}
