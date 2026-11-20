package com.grocery.order.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.order.entity.Actor;
import com.grocery.order.entity.CancellationReason;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.EventOutcome;
import com.grocery.order.entity.OrderItem;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.RejectedItem;
import com.grocery.order.repository.OrderEventLog;
import com.grocery.order.repository.StuckOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The order side of the saga: {@code inventory.events}, {@code payment.events}, (Phase 8) the stores' decisions
 * from {@code vendor.events} / {@code restaurant.events} and (Phase 9) the delivery from {@code delivery.events}
 * applied to orders, following the contracts' state-guard tables (docs/api/phase-6-orders-and-payments.md §6,
 * phase-8-vendor-and-restaurant-fulfillment.md §4, phase-9-delivery.md §7):
 *
 * <table>
 *   <tr><th>Event</th><th>Applied when the order is</th><th>Result</th></tr>
 *   <tr><td>StockReserved</td><td>CREATED</td><td>AWAITING_PAYMENT + OrderAwaitingPayment</td></tr>
 *   <tr><td>StockReservationFailed</td><td>CREATED</td><td>REJECTED (OUT_OF_STOCK, with the items) + OrderRejected</td></tr>
 *   <tr><td>StockReservationExpired</td><td>AWAITING_PAYMENT, CONFIRMED</td><td>CANCELLED (RESERVATION_EXPIRED) + OrderCancelled</td></tr>
 *   <tr><td>PaymentInitiated</td><td>any</td><td>payment PENDING (while nothing is paid)</td></tr>
 *   <tr><td>PaymentCaptured</td><td>AWAITING_PAYMENT</td><td>CONFIRMED + OrderConfirmed, payment PAID</td></tr>
 *   <tr><td></td><td>CANCELLED</td><td>payment REFUND_PENDING only (payment-service refunds)</td></tr>
 *   <tr><td>PaymentFailed</td><td>AWAITING_PAYMENT</td><td>lastFailure recorded, status unchanged</td></tr>
 *   <tr><td>PaymentExpired</td><td>AWAITING_PAYMENT</td><td>CANCELLED (PAYMENT_TIMEOUT) + OrderCancelled, payment EXPIRED</td></tr>
 *   <tr><td>RefundCompleted</td><td>any</td><td>refundedAmount = totalRefunded; REFUNDED / PARTIALLY_REFUNDED; with
 *       {@code returnId} (Phase 12) the APPROVED return becomes REFUNDED</td></tr>
 *   <tr><td>…OrderAccepted</td><td>CONFIRMED</td><td>ACCEPTED + OrderAccepted (readyInMinutes, readyBy)</td></tr>
 *   <tr><td>…OrderPreparing</td><td>ACCEPTED</td><td>PREPARING + OrderPreparing</td></tr>
 *   <tr><td>…OrderReadyForPickup</td><td>ACCEPTED, PREPARING</td><td>READY_FOR_PICKUP + OrderReadyForPickup</td></tr>
 *   <tr><td>…OrderRejected</td><td>CONFIRMED</td><td>CANCELLED (STORE_REJECTED by STORE, or STORE_NO_RESPONSE by
 *       SYSTEM when automatic) + OrderCancelled</td></tr>
 *   <tr><td>DeliveryPartnerAssigned</td><td>ACCEPTED, PREPARING, READY_FOR_PICKUP</td><td>the partner is recorded
 *       (no status change, no event)</td></tr>
 *   <tr><td>DeliveryPartnerUnassigned</td><td>same</td><td>the partner is cleared (if it is the one recorded)</td></tr>
 *   <tr><td>DeliveryPickedUp</td><td>READY_FOR_PICKUP</td><td>OUT_FOR_DELIVERY + OrderOutForDelivery</td></tr>
 *   <tr><td>DeliveryCompleted</td><td>OUT_FOR_DELIVERY</td><td>DELIVERED + OrderDelivered</td></tr>
 *   <tr><td>DeliveryFailed</td><td>OUT_FOR_DELIVERY</td><td>DELIVERY_FAILED + OrderDeliveryFailed</td></tr>
 * </table>
 * A store event for an order of the other store type, or of another store, is marked processed, ignored and logged.
 *
 * <p>Every event runs in one transaction that (1) marks it in {@code processed_event} — a duplicate stops there,
 * (2) locks the order row ({@link OrderLocks}), (3) checks the guard on the locked row and applies the change, and
 * (4) writes the history entry and the outbox event; all of it commits or rolls back together, so an event is applied
 * exactly once. An event in any other state, or for an unknown order, is marked processed, changes nothing and is
 * logged. Infrastructure failures (lock wait timeout, deadlock, database down) propagate unchanged: grocery-common's
 * error handler recognises them ({@code TransientFailures}) and redelivers the same record instead of dead-lettering it.
 *
 * <p>Phase 10 (docs/api/phase-10-event-driven-hardening.md §4, §5), in the same transaction: every event of a known
 * order becomes a CONSUMED entry of its event timeline ({@link OrderEventLog}) — APPLIED, IGNORED with the guard's
 * reason as detail, or DUPLICATE for a redelivery — written before the change, so the events the change publishes
 * follow it; and a status change resolves the order's open stuck flags ({@link StuckOrders}).
 */
@Service
public class OrderEventService {

    public static final String INVENTORY_CONSUMER = "order-service.inventory-events";
    public static final String PAYMENT_CONSUMER = "order-service.payment-events";
    public static final String VENDOR_CONSUMER = "order-service.vendor-events";
    public static final String RESTAURANT_CONSUMER = "order-service.restaurant-events";
    public static final String DELIVERY_CONSUMER = "order-service.delivery-events";
    static final String ALREADY_PROCESSED = "already processed";
    private static final Logger log = LoggerFactory.getLogger(OrderEventService.class);

    /** What one event did. */
    public enum Result {
        /** The guard matched and the order changed. */
        APPLIED,
        /** Not applicable in the order's current state (or nothing left to change): marked processed, no change. */
        IGNORED,
        /** A store event for an order of another store type or another store: marked processed, no change. */
        WRONG_STORE,
        /** Already processed (redelivery). */
        DUPLICATE,
        /** No such order: marked processed, no change. */
        UNKNOWN_ORDER
    }

    /** A line inventory could not reserve; {@code productId} = the order line's {@code itemId}. */
    public record Unavailable(UUID productId, int requested, int available) {
    }

    /**
     * Where a store event ({@code vendor.events} / {@code restaurant.events}) comes from: its consumer group, the event
     * itself, the order it is about, and the store that sent it — {@code storeType} GROCERY for a vendor, RESTAURANT
     * for a restaurant, {@code storeId} = the payload's {@code vendorId} / {@code restaurantId}.
     */
    public record StoreEvent(String consumer, EventSource source, UUID orderId, OrderType storeType, UUID storeId) {
    }

    /** One guarded change on the locked order. */
    @FunctionalInterface
    private interface Change {
        /**
         * Checks the guard on the locked order and applies the change.
         *
         * @return null when applied; otherwise why the event does not apply (the guard's reason), nothing changed
         */
        String apply(CustomerOrder order, Instant now);
    }

    /** What a detailed change did: applied (with an optional timeline detail) or ignored (with the guard's reason). */
    private record Verdict(String ignored, String detail) {

        static Verdict applied(String detail) {
            return new Verdict(null, detail);
        }

        static Verdict ignored(String reason) {
            return new Verdict(reason, null);
        }
    }

    /** A guarded change that can also give the timeline entry of an applied event a detail. */
    @FunctionalInterface
    private interface DetailedChange {
        Verdict apply(CustomerOrder order, Instant now);
    }

    private record Applied(Result result, OrderStatus before, OrderStatus after, String reason) {
    }

    private final ProcessedEventGuard processedEvents;
    private final OrderLocks locks;
    private final OrderEventPublisher events;
    private final OrderEventLog eventLog;
    private final StuckOrders stuckOrders;
    private final ReturnService returns;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderEventService(ProcessedEventGuard processedEvents, OrderLocks locks, OrderEventPublisher events,
                             OrderEventLog eventLog, StuckOrders stuckOrders, ReturnService returns,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.processedEvents = processedEvents;
        this.locks = locks;
        this.events = events;
        this.eventLog = eventLog;
        this.stuckOrders = stuckOrders;
        this.returns = returns;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---- inventory.events ---------------------------------------------------------------------------------------

    public Result stockReserved(EventSource source, UUID orderId) {
        return apply(INVENTORY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.CREATED);
            if (guard != null) {
                return guard;
            }
            order.awaitPayment(now);
            events.awaitingPayment(order);
            return null;
        });
    }

    public Result stockReservationFailed(EventSource source, UUID orderId, List<Unavailable> unavailable) {
        return apply(INVENTORY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.CREATED);
            if (guard != null) {
                return guard;
            }
            Map<UUID, String> names = order.getItems().stream()
                    .collect(Collectors.toMap(OrderItem::getItemId, OrderItem::getName, (a, b) -> a));
            order.rejectOutOfStock(unavailable.stream()
                    .map(item -> new RejectedItem(item.productId(), names.get(item.productId()), item.requested(),
                            item.available()))
                    .toList(), now);
            events.rejected(order);
            return null;
        });
    }

    public Result stockReservationExpired(EventSource source, UUID orderId) {
        return apply(INVENTORY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED);
            if (guard != null) {
                return guard;
            }
            OrderStatus previous = order.cancel(CancellationReason.RESERVATION_EXPIRED, Actor.SYSTEM, null, null,
                    now);
            events.cancelled(order, previous);
            return null;
        });
    }

    // ---- payment.events -----------------------------------------------------------------------------------------

    /** Any status: the payment becomes PENDING while nothing has been paid (NOT_STARTED / PENDING). */
    public Result paymentInitiated(EventSource source, UUID orderId, UUID paymentId) {
        return apply(PAYMENT_CONSUMER, source, orderId, (order, now) -> order.paymentInitiated(paymentId, now)
                ? null
                : "payment is " + order.getPaymentStatus() + ", not NOT_STARTED or PENDING");
    }

    /**
     * AWAITING_PAYMENT → CONFIRMED + OrderConfirmed. In CANCELLED (a "late capture": the order was cancelled or the
     * payment expired first) only the payment changes, to REFUND_PENDING — payment-service refunds it on its own.
     */
    public Result paymentCaptured(EventSource source, UUID orderId, UUID paymentId, Instant capturedAt) {
        return apply(PAYMENT_CONSUMER, source, orderId, (order, now) -> switch (order.getStatus()) {
            case AWAITING_PAYMENT -> {
                order.confirm(paymentId, capturedAt, now);
                events.confirmed(order);
                yield null;
            }
            case CANCELLED -> order.lateCapture(paymentId, now) ? null : "payment is already REFUNDED";
            default -> requireStatus(order, OrderStatus.AWAITING_PAYMENT, OrderStatus.CANCELLED);
        });
    }

    /** AWAITING_PAYMENT: the declined attempt becomes {@code lastFailure}; the customer may try again. */
    public Result paymentFailed(EventSource source, UUID orderId, String errorCode, String errorDescription) {
        return apply(PAYMENT_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.AWAITING_PAYMENT);
            if (guard != null) {
                return guard;
            }
            order.paymentFailed(errorCode, errorDescription, now);
            return null;
        });
    }

    /** AWAITING_PAYMENT → CANCELLED (PAYMENT_TIMEOUT, by the system) + OrderCancelled; payment EXPIRED. */
    public Result paymentExpired(EventSource source, UUID orderId) {
        return apply(PAYMENT_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.AWAITING_PAYMENT);
            if (guard != null) {
                return guard;
            }
            OrderStatus previous = order.expirePayment(now);
            events.cancelled(order, previous);
            return null;
        });
    }

    /**
     * Any status: {@code refundedAmount = totalRefunded}, payment REFUNDED or PARTIALLY_REFUNDED (the order status
     * never changes). {@code fullyRefunded} missing → derived from {@code totalRefunded ≥ total}.
     */
    public Result refundCompleted(EventSource source, UUID orderId, BigDecimal totalRefunded, Boolean fullyRefunded) {
        return refundCompleted(source, orderId, totalRefunded, fullyRefunded, null, null);
    }

    /**
     * {@link #refundCompleted(EventSource, UUID, BigDecimal, Boolean)}; Phase 12 §3: with {@code returnId} (a RETURN
     * refund) the return also becomes REFUNDED when it is APPROVED ({@link ReturnService#refunded}). Applied when
     * either changed; the timeline entry's detail names the return.
     */
    public Result refundCompleted(EventSource source, UUID orderId, BigDecimal totalRefunded, Boolean fullyRefunded,
                                  UUID returnId, UUID refundId) {
        return applyDetailed(PAYMENT_CONSUMER, source, orderId, null, null, (order, now) -> {
            boolean full = fullyRefunded != null ? fullyRefunded : totalRefunded.compareTo(order.getTotal()) >= 0;
            boolean projected = order.refundCompleted(totalRefunded, full, now);
            String stale = projected
                    ? null
                    : "totalRefunded " + totalRefunded + " is below the recorded " + order.getRefundedAmount();
            if (returnId == null) {
                return projected ? Verdict.applied(null) : Verdict.ignored(stale);
            }
            ReturnService.RefundMark mark = returns.refunded(order, returnId, refundId, now);
            if (mark.applied()) {
                return Verdict.applied(mark.detail());
            }
            return projected ? Verdict.applied(mark.detail()) : Verdict.ignored(stale + "; " + mark.detail());
        });
    }

    // ---- vendor.events / restaurant.events (Phase 8) -----------------------------------------------------------

    /**
     * CONFIRMED → ACCEPTED + OrderAccepted. {@code acceptedAt} null → now; {@code readyBy} null → acceptedAt +
     * readyInMinutes.
     */
    public Result storeAccepted(StoreEvent event, int readyInMinutes, Instant readyBy, Instant acceptedAt) {
        return apply(event, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.CONFIRMED);
            if (guard != null) {
                return guard;
            }
            Instant at = acceptedAt != null ? micros(acceptedAt) : now;
            Instant by = readyBy != null ? micros(readyBy) : at.plus(Duration.ofMinutes(readyInMinutes));
            order.accept(readyInMinutes, by, at, now);
            events.accepted(order);
            return null;
        });
    }

    /** ACCEPTED → PREPARING + OrderPreparing. {@code preparingAt} null → now. */
    public Result storePreparing(StoreEvent event, Instant preparingAt) {
        return apply(event, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.ACCEPTED);
            if (guard != null) {
                return guard;
            }
            order.startPreparing(preparingAt != null ? micros(preparingAt) : now, now);
            events.preparing(order);
            return null;
        });
    }

    /** ACCEPTED or PREPARING → READY_FOR_PICKUP + OrderReadyForPickup. {@code readyAt} null → now. */
    public Result storeReadyForPickup(StoreEvent event, Instant readyAt) {
        return apply(event, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.ACCEPTED, OrderStatus.PREPARING);
            if (guard != null) {
                return guard;
            }
            order.readyForPickup(readyAt != null ? micros(readyAt) : now, now);
            events.readyForPickup(order);
            return null;
        });
    }

    /**
     * CONFIRMED → CANCELLED + OrderCancelled (the payment becomes REFUND_PENDING; payment-service refunds in full,
     * inventory releases the stock): {@code STORE_REJECTED} by {@code STORE} with the store user as actor and note
     * "&lt;reason&gt;: &lt;note&gt;" (just the reason without a note) — or, when {@code auto} (nobody accepted before the
     * deadline), {@code STORE_NO_RESPONSE} by {@code SYSTEM}, no actor, no note.
     */
    public Result storeRejected(StoreEvent event, String reason, String note, boolean auto, UUID actorUserId) {
        return apply(event, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.CONFIRMED);
            if (guard != null) {
                return guard;
            }
            OrderStatus previous = auto
                    ? order.cancel(CancellationReason.STORE_NO_RESPONSE, Actor.SYSTEM, null, null, now)
                    : order.cancel(CancellationReason.STORE_REJECTED, Actor.STORE, actorUserId,
                    rejectionNote(reason, note), now);
            events.cancelled(order, previous);
            return null;
        });
    }

    // ---- delivery.events (Phase 9) -----------------------------------------------------------------------------

    /**
     * ACCEPTED, PREPARING, READY_FOR_PICKUP: records the partner ({@code partnerName} may be null; {@code assignedAt}
     * null → now). No status change, no event.
     */
    public Result partnerAssigned(EventSource source, UUID orderId, UUID partnerId, String partnerName,
                                  Instant assignedAt) {
        return apply(DELIVERY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requirePickupPending(order);
            if (guard != null) {
                return guard;
            }
            order.assignPartner(partnerId, partnerName, assignedAt != null ? micros(assignedAt) : now, now);
            return null;
        });
    }

    /** Same statuses: clears the partner, if it is the one recorded. No status change, no event. */
    public Result partnerUnassigned(EventSource source, UUID orderId, UUID partnerId) {
        return apply(DELIVERY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requirePickupPending(order);
            if (guard != null) {
                return guard;
            }
            return order.unassignPartner(partnerId, now) ? null : "partner " + partnerId + " is not the recorded one";
        });
    }

    /** READY_FOR_PICKUP → OUT_FOR_DELIVERY + OrderOutForDelivery. {@code pickedUpAt} null → now. */
    public Result deliveryPickedUp(EventSource source, UUID orderId, UUID partnerId, Instant pickedUpAt) {
        return apply(DELIVERY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.READY_FOR_PICKUP);
            if (guard != null) {
                return guard;
            }
            order.pickUp(partnerId, pickedUpAt != null ? micros(pickedUpAt) : now, now);
            events.outForDelivery(order);
            return null;
        });
    }

    /** OUT_FOR_DELIVERY → DELIVERED + OrderDelivered. {@code deliveredAt} null → now. */
    public Result deliveryCompleted(EventSource source, UUID orderId, UUID partnerId, Instant deliveredAt) {
        return apply(DELIVERY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.OUT_FOR_DELIVERY);
            if (guard != null) {
                return guard;
            }
            order.deliver(partnerId, deliveredAt != null ? micros(deliveredAt) : now, now);
            events.delivered(order);
            return null;
        });
    }

    /**
     * OUT_FOR_DELIVERY → DELIVERY_FAILED + OrderDeliveryFailed (reason, note). {@code failedAt} null → now. The payment
     * is unchanged: an admin decides on a refund.
     */
    public Result deliveryFailed(EventSource source, UUID orderId, UUID partnerId, String reason, String note,
                                 Instant failedAt) {
        return apply(DELIVERY_CONSUMER, source, orderId, (order, now) -> {
            String guard = requireStatus(order, OrderStatus.OUT_FOR_DELIVERY);
            if (guard != null) {
                return guard;
            }
            order.failDelivery(partnerId, reason, note, failedAt != null ? micros(failedAt) : now, now);
            events.deliveryFailed(order);
            return null;
        });
    }

    /** "&lt;reason&gt;: &lt;note&gt;", or just the reason when the store left no note. */
    static String rejectionNote(String reason, String note) {
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        return cleanNote == null ? reason : reason + ": " + cleanNote;
    }

    /** The store's times are kept at the database's precision (DATETIME(6)), like the service clock. */
    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The state guard as the timeline shows it: null when the order is in one of {@code allowed}, else
     * "order is X, not A" / "order is X, not A or B".
     */
    static String requireStatus(CustomerOrder order, OrderStatus... allowed) {
        for (OrderStatus status : allowed) {
            if (order.getStatus() == status) {
                return null;
            }
        }
        List<String> names = Arrays.stream(allowed).map(Enum::name).toList();
        String expected = names.size() == 1
                ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
        return "order is " + order.getStatus() + ", not " + expected;
    }

    private static String requirePickupPending(CustomerOrder order) {
        return requireStatus(order, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP);
    }

    // ---- the rule -----------------------------------------------------------------------------------------------

    private Result apply(String consumer, EventSource source, UUID orderId, Change change) {
        return apply(consumer, source, orderId, null, null, change);
    }

    /** A store event: also ignored (and logged) for an order of another store type or another store. */
    private Result apply(StoreEvent event, Change change) {
        return apply(event.consumer(), event.source(), event.orderId(), event.storeType(), event.storeId(), change);
    }

    /**
     * @param storeType null = no store check; otherwise the order must be of this type and belong to {@code storeId}
     */
    private Result apply(String consumer, EventSource source, UUID orderId, OrderType storeType, UUID storeId,
                         Change change) {
        return applyDetailed(consumer, source, orderId, storeType, storeId, (order, now) -> {
            String rejected = change.apply(order, now);
            return rejected == null ? Verdict.applied(null) : Verdict.ignored(rejected);
        });
    }

    private Result applyDetailed(String consumer, EventSource source, UUID orderId, OrderType storeType,
                                 UUID storeId, DetailedChange change) {
        Applied applied = transaction.execute(status -> {
            if (!processedEvents.markProcessed(source.eventId(), consumer)) {       // 1. once per event
                eventLog.duplicate(orderId, source.eventId(), source.eventType(), source.topic(),
                        source.occurredAt(), source.correlationId(), ALREADY_PROCESSED, clock.instant());
                return new Applied(Result.DUPLICATE, null, null, null);
            }
            CustomerOrder order = locks.lock(orderId);                              // 2. lock first
            if (order == null) {
                return new Applied(Result.UNKNOWN_ORDER, null, null, null);
            }
            Instant now = clock.instant();                                          // after the lock wait
            OrderStatus before = order.getStatus();
            // the timeline entry first: whatever the change publishes comes after it
            long entry = eventLog.consumed(orderId, source.eventId(), source.eventType(), source.topic(),
                    source.occurredAt(), source.correlationId(), now);
            if (storeType != null && (order.getType() != storeType || !order.getStoreId().equals(storeId))) {
                String reason = "not an order of " + storeType + " store " + storeId;
                eventLog.outcome(entry, EventOutcome.IGNORED, reason);
                return new Applied(Result.WRONG_STORE, before, before, reason);
            }
            Verdict verdict = change.apply(order, now);                             // 3. guard + change, 4. history + outbox
            if (verdict.ignored() != null) {
                eventLog.outcome(entry, EventOutcome.IGNORED, verdict.ignored());
                return new Applied(Result.IGNORED, before, before, verdict.ignored());
            }
            OrderStatus after = order.getStatus();
            if (after != before) {
                eventLog.outcome(entry, EventOutcome.APPLIED, before + " → " + after);
                stuckOrders.resolveOthers(orderId, after, now);                     // the order left a flagged status
            } else if (verdict.detail() != null) {
                eventLog.outcome(entry, EventOutcome.APPLIED, verdict.detail());
            }
            return new Applied(Result.APPLIED, before, after, null);
        });
        switch (applied.result()) {
            case APPLIED -> log.info("{} {} applied to order {} ({} → {})", source.eventType(), source.eventId(),
                    orderId, applied.before(), applied.after());
            case IGNORED -> log.info("{} {} ignored: {} (order {})", source.eventType(), source.eventId(),
                    applied.reason(), orderId);
            case WRONG_STORE -> log.warn("{} {} ignored: order {} is not an order of {} store {}",
                    source.eventType(), source.eventId(), orderId, storeType, storeId);
            case DUPLICATE -> log.debug("{} {} already processed", source.eventType(), source.eventId());
            case UNKNOWN_ORDER -> log.warn("{} {} ignored: unknown order {}", source.eventType(), source.eventId(),
                    orderId);
        }
        return applied.result();
    }
}
