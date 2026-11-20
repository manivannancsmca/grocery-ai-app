package com.grocery.payment.service;

import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.NewOrder;
import com.grocery.payment.gateway.PaymentGateway.ProviderOrder;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Creates the Razorpay order of a payment — <b>exactly one per payment</b>, although two paths ask for it: the
 * {@code OrderAwaitingPayment} consumer (right after inserting the payment) and the checkout endpoint (on demand,
 * when the consumer could not reach Razorpay or has not got that far yet).
 *
 * <p>Both call {@link #ensure}, which locks the payment row ({@code SELECT … FOR UPDATE}), re-checks that it still
 * has no Razorpay order, calls Razorpay's {@code POST /orders} <em>while holding the lock</em>, stores the id
 * (CREATED → PENDING, {@code PaymentInitiated}) and commits. A second caller waits on the lock and then finds the id.
 * This is the one place where a provider call happens under a row lock (bounded by the HTTP timeouts and the circuit
 * breaker); a "creating" marker committed before the call would instead need a timeout after which a second order
 * is created anyway — see README "One Razorpay order per payment".
 */
@Service
public class ProviderOrderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderOrderService.class);

    private final PaymentRepository payments;
    private final PaymentLocks locks;
    private final PaymentGateway gateway;
    private final PaymentEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProviderOrderService(PaymentRepository payments, PaymentLocks locks, PaymentGateway gateway,
                                PaymentEventPublisher events, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.payments = payments;
        this.locks = locks;
        this.gateway = gateway;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Makes sure a CREATED payment has its Razorpay order. Does nothing for a payment that has one, is no longer
     * CREATED (cancelled before it got one) or whose window is over (the expiry poller expires it).
     *
     * @throws ProviderUnavailableException Razorpay unreachable (nothing stored; safe to retry)
     * @throws ProviderRejectedException    Razorpay refused the order (e.g. wrong keys; nothing stored)
     */
    public void ensure(UUID paymentId) {
        Payment snapshot = payments.findById(paymentId).orElse(null);           // fast path, no lock
        if (snapshot == null || snapshot.getRazorpayOrderId() != null
                || snapshot.getStatus() != PaymentStatus.CREATED) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            Payment payment = locks.payment(paymentId);
            Instant now = clock.instant();
            if (payment.getRazorpayOrderId() != null || payment.getStatus() != PaymentStatus.CREATED
                    || payment.isPastWindow(now)) {
                return;                                                          // someone else was first
            }
            ProviderOrder order = gateway.createOrder(new NewOrder(payment.getAmount(), payment.getCurrency(),
                    payment.getOrderNumber(), notes(payment)));
            payment.providerOrderCreated(order.id(), clock.instant());
            events.initiated(payment);
            log.info("Razorpay order {} created for payment {} (order {})", order.id(), payment.getId(),
                    payment.getOrderNumber());
        });
    }

    private static Map<String, String> notes(Payment payment) {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("orderId", payment.getOrderId().toString());
        notes.put("paymentId", payment.getId().toString());
        return notes;
    }
}
