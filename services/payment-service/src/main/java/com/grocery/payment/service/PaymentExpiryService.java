package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPaymentStatus;
import com.grocery.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The end of the payment window (contract §3.1 "Window over"). For each CREATED / PENDING payment past
 * {@code expires_at}:
 * <ol>
 *   <li><b>Claim</b> (one short transaction for the batch): {@code SELECT … FOR UPDATE SKIP LOCKED}, then
 *       {@code poll_lease_until = now + grocery.payment.poll-lease}, commit. Another poller (another replica, or a
 *       second run) skips locked rows during the claim and leased rows afterwards, so no payment is handled twice
 *       while a provider call is in flight — and no lock is held during that call.</li>
 *   <li><b>Ask the provider</b> for the Razorpay order's payments (only if it has a Razorpay order): a captured one is
 *       a lost capture (webhook and verify never arrived) → captured exactly like verify would; an authorized one is
 *       captured first.</li>
 *   <li>Otherwise <b>expire</b> (payment locked, state re-checked: a capture or cancel may have happened meanwhile)
 *       → EXPIRED + {@code PaymentExpired}.</li>
 * </ol>
 * Provider unreachable → the payment keeps its lease and is retried when it runs out. Time comes from the injected
 * {@link Clock}.
 */
@Service
public class PaymentExpiryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryService.class);

    private final PaymentRepository payments;
    private final PaymentLocks locks;
    private final PaymentGateway gateway;
    private final CaptureService captures;
    private final PaymentEventPublisher events;
    private final PaymentProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PaymentExpiryService(PaymentRepository payments, PaymentLocks locks, PaymentGateway gateway,
                                CaptureService captures, PaymentEventPublisher events, PaymentProperties properties,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.payments = payments;
        this.locks = locks;
        this.gateway = gateway;
        this.captures = captures;
        this.events = events;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What happened to a claimed payment. */
    public enum Outcome { EXPIRED, CAPTURED, SKIPPED, RETRY_LATER }

    /** Claims up to {@code limit} payments past their window (see the class comment). */
    public List<UUID> claim(int limit) {
        Instant now = clock.instant();
        List<UUID> ids = transaction.execute(status -> {
            List<UUID> claimed = payments.claimExpired(now, limit).stream().map(UUID::fromString).toList();
            payments.findAllById(claimed).forEach(p -> p.claimForExpiry(now.plus(properties.pollLease())));
            return claimed;
        });
        return ids == null ? List.of() : ids;
    }

    /** Handles one claimed payment. Never call it inside a transaction (it calls the provider). */
    public Outcome handle(UUID paymentId) {
        Payment snapshot = payments.findById(paymentId).orElse(null);
        if (snapshot == null || !snapshot.getStatus().isOpen()) {
            return Outcome.SKIPPED;                                  // captured / cancelled meanwhile
        }
        if (snapshot.getRazorpayOrderId() != null) {
            List<ProviderPayment> atProvider = gateway.listOrderPayments(snapshot.getRazorpayOrderId());
            Optional<ProviderPayment> captured = atProvider.stream()
                    .filter(p -> p.status().wasCaptured()).findFirst();
            if (captured.isPresent()) {
                log.warn("Payment {} (order {}) was captured at the provider ({}) but never reported: capturing it "
                        + "instead of expiring it", paymentId, snapshot.getOrderNumber(), captured.get().id());
                captures.settle(paymentId, captured.get());
                return Outcome.CAPTURED;
            }
            boolean authorizedLeft = false;
            for (ProviderPayment authorized : atProvider) {
                if (authorized.status() != ProviderPaymentStatus.AUTHORIZED) {
                    continue;
                }
                if (captures.settle(paymentId, authorized).result() == CaptureService.Result.CAPTURED) {
                    return Outcome.CAPTURED;
                }
                authorizedLeft = true;
            }
            if (authorizedLeft) {
                return Outcome.RETRY_LATER;                         // money is authorized: never expire it unseen
            }
        }
        Boolean expired = transaction.execute(status -> {
            Payment payment = locks.payment(paymentId);
            Instant now = clock.instant();
            payment.releaseExpiryClaim();
            if (!payment.isPastWindow(now)) {
                return false;
            }
            payment.expire(now);
            events.expired(payment);
            log.info("Payment {} of order {} expired unpaid", payment.getId(), payment.getOrderNumber());
            return true;
        });
        return Boolean.TRUE.equals(expired) ? Outcome.EXPIRED : Outcome.SKIPPED;
    }
}
