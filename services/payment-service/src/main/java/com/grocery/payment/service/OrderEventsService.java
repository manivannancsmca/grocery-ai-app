package com.grocery.payment.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.Refund;
import com.grocery.payment.entity.RefundReason;
import com.grocery.payment.entity.RefundStatus;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment side of the order saga ({@code order.events}, consumer {@code payment-service.order-events},
 * docs/api/phase-6-orders-and-payments.md §3.1). Each event is marked in {@code processed_event} inside the
 * transaction that applies it, so a redelivered event changes and publishes nothing.
 * <ul>
 *   <li>{@code OrderAwaitingPayment} → a CREATED payment (unique per order; amount, window and contact phone from
 *       the event), committed; then its Razorpay order ({@link ProviderOrderService#ensure}) → PENDING +
 *       {@code PaymentInitiated}. Razorpay unreachable → {@link ProviderUnavailableException} reaches the listener,
 *       which retries the record (the processed mark is already committed, so the retry skips the insert and only
 *       creates the Razorpay order — as does the checkout endpoint on demand meanwhile).</li>
 *   <li>{@code OrderCancelled} → CREATED / PENDING: CANCELLED (a capture arriving later is a late capture, refunded);
 *       CAPTURED: a full ORDER_CANCELLED refund of whatever is still refundable, sent to the provider after commit;
 *       anything else (no payment, EXPIRED, CANCELLED): nothing.</li>
 *   <li>(Phase 12) {@code ReturnApproved} → a RETURN refund of the return's {@code refundAmount}, once per return and
 *       capped at what is still refundable ({@link #returnApproved}).</li>
 * </ul>
 */
@Service
public class OrderEventsService {

    public static final String CONSUMER = "payment-service.order-events";
    private static final Logger log = LoggerFactory.getLogger(OrderEventsService.class);

    /** The fields of {@code OrderAwaitingPayment} this service keeps (validated by the listener). */
    public record AwaitingPayment(UUID orderId, String orderNumber, UUID customerId, BigDecimal amount,
                                  String currency, Instant expiresAt, String contactPhone) {
    }

    /** Phase 12: the fields of {@code ReturnApproved} this service uses (validated by the listener). */
    public record ApprovedReturn(UUID returnId, String returnNumber, UUID orderId, BigDecimal refundAmount) {
    }

    private final PaymentRepository payments;
    private final RefundRepository refundRows;
    private final PaymentLocks locks;
    private final ProcessedEventGuard processedEvents;
    private final ProviderOrderService providerOrders;
    private final RefundLedger ledger;
    private final RefundService refunds;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderEventsService(PaymentRepository payments, RefundRepository refundRows, PaymentLocks locks,
                              ProcessedEventGuard processedEvents, ProviderOrderService providerOrders,
                              RefundLedger ledger, RefundService refunds,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.payments = payments;
        this.refundRows = refundRows;
        this.locks = locks;
        this.processedEvents = processedEvents;
        this.providerOrders = providerOrders;
        this.ledger = ledger;
        this.refunds = refunds;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws ProviderUnavailableException Razorpay unreachable while creating the order: retry the event
     */
    public void orderAwaitingPayment(String eventId, AwaitingPayment event) {
        UUID paymentId;
        try {
            paymentId = transaction.execute(status -> {
                boolean first = processedEvents.markProcessed(eventId, CONSUMER);
                Optional<UUID> existing = payments.findIdByOrderId(event.orderId());
                if (existing.isPresent() || !first) {
                    return existing.orElse(null);
                }
                Payment payment = Payment.create(Ids.newId(), event.orderId(), event.orderNumber(),
                        event.customerId(), event.amount(), event.currency(), event.contactPhone(),
                        event.expiresAt(), clock.instant());
                payments.saveAndFlush(payment);
                log.info("Payment {} created for order {} ({} {}, due {})", payment.getId(), event.orderNumber(),
                        event.amount(), event.currency(), event.expiresAt());
                return payment.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // Another OrderAwaitingPayment of the same order created the payment at the same moment: use that one
            paymentId = payments.findIdByOrderId(event.orderId()).orElseThrow(() -> e);
        }
        if (paymentId == null) {
            log.debug("OrderAwaitingPayment {} already processed", eventId);
            return;
        }
        try {
            providerOrders.ensure(paymentId);
        } catch (ProviderRejectedException e) {
            // Not temporary (e.g. wrong keys): retrying the event would not help. The payment stays CREATED; the
            // checkout endpoint tries again on demand and the expiry poller ends it with the window.
            log.error("Razorpay refused to create the order of payment {} (order {}): {}", paymentId,
                    event.orderNumber(), e.getMessage());
        }
    }

    /**
     * Phase 12 §3: {@code ReturnApproved} → a RETURN refund of {@code refundAmount}, once per return (the
     * {@code processed_event} mark per event and the unique {@code refund.return_id} per return), capped at what the
     * payment can still refund ({@link RefundLedger#openForReturn}); sent to the provider after commit →
     * {@code RefundCompleted} / {@code RefundFailed} with {@code returnId}. No payment for the order → logged, nothing
     * done.
     */
    public void returnApproved(String eventId, ApprovedReturn event) {
        UUID refundId;
        try {
            refundId = transaction.execute(status -> {
                if (!processedEvents.markProcessed(eventId, CONSUMER)) {
                    log.debug("ReturnApproved {} already processed", eventId);
                    return null;
                }
                Optional<UUID> paymentId = payments.findIdByOrderId(event.orderId());
                if (paymentId.isEmpty()) {
                    log.error("Return {} of order {} approved, but the order has no payment: nothing to refund",
                            event.returnNumber(), event.orderId());
                    return null;
                }
                Payment payment = locks.payment(paymentId.get());
                if (refundRows.existsByReturnId(event.returnId())) {
                    log.info("Return {} of order {} is refunded already", event.returnNumber(),
                            payment.getOrderNumber());
                    return null;                                // another event of the same return
                }
                if (event.refundAmount().signum() <= 0) {
                    log.warn("Return {} of order {} approved with nothing to refund", event.returnNumber(),
                            payment.getOrderNumber());
                    return null;
                }
                Refund refund = ledger.openForReturn(payment, Ids.newId(), event.returnId(), event.returnNumber(),
                        event.refundAmount(), clock.instant());
                log.info("Return {} of order {} approved: refunding {} (asked {}), {}", event.returnNumber(),
                        payment.getOrderNumber(), refund.getAmount(), event.refundAmount(), refund.getStatus());
                return refund.getStatus() == RefundStatus.PENDING ? refund.getId() : null;
            });
        } catch (DataIntegrityViolationException e) {
            // two events of the same return at the same moment: the other one's refund is the refund
            if (refundRows.existsByReturnId(event.returnId())) {
                log.info("Return {} is refunded by a concurrent event", event.returnNumber());
                return;
            }
            throw e;
        }
        if (refundId != null) {
            refunds.processAfterCommit(refundId);       // never fails the (committed) event
        }
    }

    public void orderCancelled(String eventId, UUID orderId) {
        UUID refundId = transaction.execute(status -> {
            if (!processedEvents.markProcessed(eventId, CONSUMER)) {
                log.debug("OrderCancelled {} already processed", eventId);
                return null;
            }
            Optional<UUID> paymentId = payments.findIdByOrderId(orderId);
            if (paymentId.isEmpty()) {
                return null;                                    // cancelled before any payment was created
            }
            Payment payment = locks.payment(paymentId.get());
            Instant now = clock.instant();
            if (payment.getStatus().isOpen()) {
                payment.cancel(now);
                log.info("Payment {} of cancelled order {} cancelled", payment.getId(), payment.getOrderNumber());
                return null;
            }
            if (payment.getStatus() != PaymentStatus.CAPTURED) {
                return null;                                    // EXPIRED / CANCELLED already
            }
            BigDecimal refundable = ledger.refundable(payment);
            if (refundable.signum() == 0) {
                return null;                                    // refunded (or being refunded) already
            }
            UUID id = ledger.open(payment, Ids.newId(), RefundReason.ORDER_CANCELLED, refundable, null, null, now)
                    .getId();
            log.info("Order {} cancelled after payment: refunding {}", payment.getOrderNumber(), refundable);
            return id;
        });
        if (refundId != null) {
            refunds.processAfterCommit(refundId);       // never fails the (committed) event
        }
    }
}
