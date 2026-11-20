package com.grocery.delivery.dispatch.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.entity.OfferStatus;
import com.grocery.delivery.dispatch.entity.OrderSnapshot;
import com.grocery.delivery.dispatch.event.DeliveryEventPublisher;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The order saga's side of dispatch ({@code order.events}, consumer {@value #CONSUMER}, contract §6):
 * <ul>
 *   <li>{@code OrderConfirmed} → a PENDING delivery (snapshot of the order; a replay of an order that already has one
 *       is skipped);</li>
 *   <li>{@code OrderAccepted} → SEARCHING and the first wave of offers right away;</li>
 *   <li>{@code OrderReadyForPickup} → {@code readyForPickup} (pickup is allowed from now on);</li>
 *   <li>{@code OrderCancelled} → CANCELLED, open offers WITHDRAWN, the assigned partner released, DeliveryCancelled —
 *       unless already PICKED_UP or later (then ignored and logged: an order cannot be cancelled after pickup).</li>
 * </ul>
 * Each event is marked in {@code processed_event} as the first statement of the transaction that applies it, so a
 * redelivery changes and publishes nothing. Database failures propagate: the consumer retries the record.
 */
@Service
public class DeliveryProjection {

    public static final String CONSUMER = "delivery-service.order-events";
    private static final Logger log = LoggerFactory.getLogger(DeliveryProjection.class);

    private final DeliveryRepository deliveries;
    private final AssignmentEngine engine;
    private final DeliveryEventPublisher events;
    private final ProcessedEventGuard processedEvents;
    private final Clock clock;

    public DeliveryProjection(DeliveryRepository deliveries, AssignmentEngine engine, DeliveryEventPublisher events,
                              ProcessedEventGuard processedEvents, Clock clock) {
        this.deliveries = deliveries;
        this.engine = engine;
        this.events = events;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void orderConfirmed(String eventId, OrderSnapshot order) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderConfirmed {} already processed", eventId);
            return;
        }
        if (deliveries.existsByOrderId(order.orderId())) {
            log.debug("OrderConfirmed {}: order {} already has a delivery", eventId, order.orderId());
            return;
        }
        Delivery delivery = deliveries.saveAndFlush(Delivery.pending(Ids.newId(), order, clock.instant()));
        log.info("Delivery {} created for order {} ({}), pickup {}", delivery.getId(), order.orderNumber(),
                order.orderId(), delivery.pickupPoint() != null ? "at the store" : "unknown (search uses the drop point)");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void orderAccepted(String eventId, UUID orderId, Instant readyBy) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderAccepted {} already processed", eventId);
            return;
        }
        Delivery delivery = deliveries.lockByOrderId(orderId).orElse(null);
        if (delivery == null) {
            log.warn("OrderAccepted {} for order {} without a delivery (no OrderConfirmed seen): skipped", eventId,
                    orderId);
            return;
        }
        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            log.info("OrderAccepted for order {} ignored: the delivery is already {}", orderId, delivery.getStatus());
            return;
        }
        Instant now = clock.instant();
        delivery.startSearching(readyBy, now);
        engine.advance(delivery, now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void orderReadyForPickup(String eventId, UUID orderId, Instant readyAt) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderReadyForPickup {} already processed", eventId);
            return;
        }
        Delivery delivery = deliveries.lockByOrderId(orderId).orElse(null);
        if (delivery == null) {
            log.warn("OrderReadyForPickup {} for order {} without a delivery: skipped", eventId, orderId);
            return;
        }
        if (delivery.getStatus().isTerminal()) {
            log.info("OrderReadyForPickup for order {} ignored: the delivery is {}", orderId, delivery.getStatus());
            return;
        }
        if (delivery.markReadyForPickup(readyAt, clock.instant())) {
            log.info("Order {} is ready for pickup (delivery {} {})", delivery.getOrderNumber(), delivery.getId(),
                    delivery.getStatus());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void orderCancelled(String eventId, UUID orderId, String reason) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderCancelled {} already processed", eventId);
            return;
        }
        Delivery delivery = deliveries.lockByOrderId(orderId).orElse(null);
        if (delivery == null) {
            log.debug("OrderCancelled {}: order {} has no delivery", eventId, orderId);
            return;
        }
        if (!DeliveryStatus.CANCELLABLE.contains(delivery.getStatus())) {
            if (delivery.getStatus() != DeliveryStatus.CANCELLED) {
                log.warn("OrderCancelled ({}) for order {} ignored: the delivery is already {} (no cancellation "
                        + "after pickup)", reason, orderId, delivery.getStatus());
            }
            return;
        }
        Instant now = clock.instant();
        engine.closePendingOffers(delivery, OfferStatus.WITHDRAWN, now);
        UUID partnerId = delivery.getStatus() == DeliveryStatus.ASSIGNED ? delivery.getPartnerId() : null;
        engine.releasePartner(delivery, partnerId);
        delivery.cancel(reason, now);
        events.cancelled(delivery, partnerId);
        log.info("Delivery {} of order {} cancelled ({}){}", delivery.getId(), delivery.getOrderNumber(), reason,
                partnerId != null ? ", partner " + partnerId + " released" : "");
    }
}
