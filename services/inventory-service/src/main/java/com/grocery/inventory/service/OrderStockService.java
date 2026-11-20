package com.grocery.inventory.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.inventory.config.InventoryProperties;
import com.grocery.inventory.entity.ReservationStatus;
import com.grocery.inventory.service.ReservationService.Guard;
import com.grocery.inventory.service.ReservationService.Outcome;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The inventory side of the order saga ({@code order.events}, consumer {@code inventory-service.order-events},
 * docs/api/phase-6-orders-and-payments.md §6 "Inventory"). Grocery orders only (the listener drops the rest).
 *
 * <p><b>Exactly-once effects.</b> Each event is marked in {@code processed_event} inside the transaction that
 * applies it ({@link Guard}, first statement), together with the stock change and its outbox events — they commit
 * or roll back together, so a redelivered event changes and publishes nothing. On top of that the reservation's
 * own idempotency holds: an order already reserved is never reserved again and publishes no second
 * {@code StockReserved}, a release that finds nothing held publishes no {@code StockReleased}.
 * <ul>
 *   <li>{@code OrderCreated} → reserve every line for {@code grocery.inventory.reservation-ttl} →
 *       {@code StockReserved}. Not enough stock → the reserving transaction (with its processed mark) rolls back,
 *       and a second transaction writes the processed mark and {@code StockReservationFailed} (no stock
 *       change, nothing held).</li>
 *   <li>{@code OrderConfirmed} → the reservation no longer expires (held until pickup). Publishes nothing.</li>
 *   <li>{@code OrderCancelled} → release → {@code StockReleased(ORDER_CANCELLED)} only if stock was given back
 *       (not for an order never reserved, rejected, already released by the expiry poller, or committed).</li>
 *   <li>{@code OrderOutForDelivery} (Phase 9, docs/api/phase-9-delivery.md §7) → commit → {@code StockCommitted}
 *       only if this call committed it (not for an unknown, released or already committed reservation, which is
 *       logged and ignored). A failed delivery later does not return the stock (the vendor adjusts it).</li>
 * </ul>
 */
@Service
public class OrderStockService {

    public static final String CONSUMER = "inventory-service.order-events";
    private static final Logger log = LoggerFactory.getLogger(OrderStockService.class);

    private final ReservationService reservations;
    private final ProcessedEventGuard processedEvents;
    private final StockEventPublisher events;
    private final TransactionTemplate transaction;
    private final InventoryProperties properties;

    public OrderStockService(ReservationService reservations, ProcessedEventGuard processedEvents,
                             StockEventPublisher events, PlatformTransactionManager transactionManager,
                             InventoryProperties properties) {
        this.reservations = reservations;
        this.processedEvents = processedEvents;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    public void orderCreated(String eventId, UUID orderId, List<ReservationLine> lines) {
        Outcome outcome;
        try {
            outcome = reservations.reserve(orderId, lines, properties.getReservationTtl(), guard(eventId));
        } catch (ReservationFailedException e) {
            transaction.executeWithoutResult(status -> {
                if (processedEvents.markProcessed(eventId, CONSUMER)) {
                    events.reservationFailed(orderId, e.unavailableItems());
                    log.info("Stock reservation for order {} failed: {} item(s) unavailable", orderId,
                            e.unavailableItems().size());
                } else {
                    log.debug("OrderCreated {} already processed", eventId);
                }
            });
            return;
        }
        if (outcome.changed()) {
            log.info("Stock reserved for order {} until {}", orderId, outcome.reservation().expiresAt());
        } else {
            log.debug("OrderCreated {} for order {}: already processed or reserved ({})", eventId, orderId,
                    outcome.reservation() == null ? "duplicate" : outcome.reservation().status());
        }
    }

    public void orderConfirmed(String eventId, UUID orderId) {
        Outcome outcome = reservations.holdUntilPickup(orderId, guard(eventId));
        if (outcome.changed()) {
            log.info("Stock of order {} is held until pickup", orderId);
        } else if (outcome.reservation() == null) {
            log.debug("OrderConfirmed {}: no reservation for order {} or already processed", eventId, orderId);
        } else if (outcome.reservation().status() != ReservationStatus.RESERVED) {
            log.warn("OrderConfirmed for order {} but its reservation is {}", orderId, outcome.reservation().status());
        }
    }

    public void orderCancelled(String eventId, UUID orderId) {
        Outcome outcome = reservations.release(orderId, guard(eventId));
        if (outcome.changed()) {
            log.info("Stock of cancelled order {} released", orderId);
        } else {
            log.debug("OrderCancelled {} for order {}: nothing to release ({})", eventId, orderId,
                    outcome.reservation() == null ? "no reservation / duplicate" : outcome.reservation().status());
        }
    }

    /**
     * Phase 9: the partner picked the order up → the held stock leaves the store (commit) + {@code StockCommitted},
     * in one transaction with the processed mark. A reservation that is unknown, RELEASED (e.g. expired or cancelled
     * — its stock may already be sold again) or already COMMITTED is logged and ignored.
     */
    public void orderOutForDelivery(String eventId, UUID orderId) {
        Outcome outcome = reservations.commit(orderId, guard(eventId));
        if (outcome.changed()) {
            log.info("Stock of order {} committed at pickup", orderId);
        } else if (outcome.reservation() == null) {
            log.warn("OrderOutForDelivery {}: no reservation for order {} (or already processed); nothing committed",
                    eventId, orderId);
        } else if (outcome.reservation().status() == ReservationStatus.RELEASED) {
            log.warn("OrderOutForDelivery for order {} but its reservation was already RELEASED; nothing committed",
                    orderId);
        } else {
            log.debug("OrderOutForDelivery {} for order {}: already committed", eventId, orderId);
        }
    }

    private Guard guard(String eventId) {
        return () -> processedEvents.markProcessed(eventId, CONSUMER);
    }
}
