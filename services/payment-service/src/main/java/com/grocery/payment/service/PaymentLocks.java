package com.grocery.payment.service;

import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.repository.RefundRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Takes the row locks. <b>The single entry point for locking payments and refunds.</b>
 *
 * <h2>Locking rule</h2>
 * Every transaction that changes a payment, its attempts or its refunds — Razorpay order creation, capture
 * (verify, webhook, poller), decline, expiry, cancellation, refund start / finish — does this, in one transaction:
 * <ol>
 *   <li><b>Lock the payment row first</b> ({@code SELECT … FOR UPDATE} by primary key, {@link #payment}), before
 *       loading anything else of it. Callers locate the payment beforehand with an id-only query (never by loading
 *       the entity in the same transaction: a managed entity would not be refreshed by the locking read).</li>
 *   <li>Then, if needed, the refund row ({@link #refund}) — always payment, then refund, so two transactions never
 *       wait for each other in opposite order.</li>
 *   <li>Read, check, change, write the outbox event; commit releases the locks.</li>
 * </ol>
 * Connections run in READ COMMITTED, so every read after the lock sees the latest committed rows (attempts, refunds,
 * sums). Concurrent changes of one payment are thereby applied one after the other, each on top of the other's
 * result: a capture racing an {@code OrderCancelled} is either a normal capture followed by an ORDER_CANCELLED
 * refund, or a cancellation followed by a late capture and a LATE_CAPTURE refund — never neither.
 *
 * <p><b>No provider call while a lock is held</b> — except the creation of the Razorpay order, which keeps the payment
 * locked across the call so that exactly one Razorpay order exists per payment (README "One Razorpay order per
 * payment"). Everything else commits first, calls the provider, then records the result in a new transaction,
 * re-checking the state it finds. The pollers claim rows with {@code FOR UPDATE SKIP LOCKED} plus a lease column,
 * commit, and only then call the provider (README "Pollers").
 */
@Component
public class PaymentLocks {

    private final PaymentRepository payments;
    private final RefundRepository refunds;

    public PaymentLocks(PaymentRepository payments, RefundRepository refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    /** {@code SELECT … FOR UPDATE} on one payment row. */
    public Payment payment(UUID paymentId) {
        requireTransaction();
        return payments.lockById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " vanished"));
    }

    /** {@code SELECT … FOR UPDATE} on a refund of {@code payment}, which the caller has locked already. */
    public Refund refund(Payment payment, UUID refundId) {
        requireTransaction();
        Refund refund = refunds.lockById(refundId)
                .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " vanished"));
        if (!refund.getPaymentId().equals(payment.getId())) {
            throw new IllegalStateException("Refund " + refundId + " is not a refund of payment " + payment.getId());
        }
        return refund;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment rows can only be locked inside the changing transaction");
        }
    }
}
