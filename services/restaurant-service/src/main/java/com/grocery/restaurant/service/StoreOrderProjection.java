package com.grocery.restaurant.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.entity.RestaurantOrder;
import com.grocery.restaurant.entity.StoreOrderItem;
import com.grocery.restaurant.entity.StoreOrderStatus;
import com.grocery.restaurant.repository.RestaurantOrderRepository;
import com.grocery.restaurant.repository.RestaurantRepository;
import com.grocery.restaurant.settings.RestaurantSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The order saga's side of the restaurant order board ({@code order.events}, consumer
 * {@code restaurant-service.order-events}, contract §2 "Created from"). RESTAURANT orders only (the listener drops the
 * rest).
 * <ul>
 *   <li>{@code OrderConfirmed} → a store order NEW with {@code acceptBy = now + the acceptance time in force}
 *       and {@code RestaurantOrderReceived} (ownerUserId = the restaurant's owner). An unknown restaurant is logged and
 *       skipped; an order that already has a store order (a replay) is skipped.</li>
 *   <li>{@code OrderCancelled} → a non-terminal store order becomes CANCELLED, under the row lock (no event:
 *       order-service already knows). REJECTED / CANCELLED / PICKED_UP ones, and orders never confirmed, are left
 *       alone.</li>
 *   <li>{@code OrderOutForDelivery} (Phase 9) → the store order becomes PICKED_UP, under the row lock (no event).</li>
 * </ul>
 * Each event is marked in {@code processed_event} as the first statement of the transaction that applies it, so a
 * redelivery changes and publishes nothing. Database failures propagate: the consumer retries the record.
 */
@Service
public class StoreOrderProjection {

    public static final String CONSUMER = "restaurant-service.order-events";
    private static final Logger log = LoggerFactory.getLogger(StoreOrderProjection.class);
    private static final int MAX_NAME = 100;
    private static final int MAX_AREA = 300;
    private static final int MAX_NOTE = 300;

    /** The confirmed order as this service needs it. {@code subtotal} / {@code note} may be absent (older events). */
    public record ConfirmedOrder(UUID orderId, String orderNumber, UUID customerId, UUID storeId, List<Line> lines,
                                 BigDecimal subtotal, BigDecimal total, Recipient recipient, String note,
                                 Instant confirmedAt) {
    }

    public record Line(UUID itemId, String type, String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** What of the delivery address the restaurant may see; null when the order has no address. */
    public record Recipient(String name, String line2, String landmark, String city) {
    }

    private final RestaurantOrderRepository orders;
    private final RestaurantRepository restaurants;
    private final StoreOrderEventPublisher events;
    private final ProcessedEventGuard processedEvents;
    private final RestaurantSettingsService settings;
    private final Clock clock;

    public StoreOrderProjection(RestaurantOrderRepository orders, RestaurantRepository restaurants,
                                StoreOrderEventPublisher events, ProcessedEventGuard processedEvents,
                                RestaurantSettingsService settings, Clock clock) {
        this.orders = orders;
        this.restaurants = restaurants;
        this.events = events;
        this.processedEvents = processedEvents;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public void orderConfirmed(String eventId, ConfirmedOrder confirmed) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderConfirmed {} already processed", eventId);
            return;
        }
        if (orders.existsById(confirmed.orderId())) {
            log.debug("OrderConfirmed {}: order {} is already on the board", eventId, confirmed.orderId());
            return;
        }
        Optional<Restaurant> restaurant = restaurants.findById(confirmed.storeId());
        if (restaurant.isEmpty()) {
            log.warn("OrderConfirmed {} for order {} names unknown restaurant {}: skipped", eventId,
                    confirmed.orderId(), confirmed.storeId());
            return;
        }
        List<StoreOrderItem> items = confirmed.lines().stream()
                .map(line -> new StoreOrderItem(line.itemId(), line.type(), line.name(), line.quantity(),
                        money(line.unitPrice()), money(line.lineTotal())))
                .toList();
        BigDecimal subtotal = confirmed.subtotal() != null
                ? confirmed.subtotal()
                : items.stream().map(StoreOrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Recipient recipient = confirmed.recipient();
        RestaurantOrder order = RestaurantOrder.received(confirmed.orderId(), confirmed.orderNumber(),
                confirmed.storeId(), confirmed.customerId(), items, money(subtotal), money(confirmed.total()),
                recipient == null ? null : truncate(clean(recipient.name()), MAX_NAME), customerArea(recipient),
                truncate(clean(confirmed.note()), MAX_NOTE), confirmed.confirmedAt(), clock.instant(),
                settings.current().acceptanceTime());     // the admin setting in force now
        orders.saveAndFlush(order);
        events.received(order, restaurant.get().getOwnerUserId());
        log.info("Order {} ({}) received by restaurant {}, accept by {}", order.getOrderNumber(), order.getOrderId(),
                order.getRestaurantId(), order.getAcceptBy());
    }

    @Transactional
    public void orderCancelled(String eventId, UUID orderId, String reason, String cancelledBy, Instant cancelledAt) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderCancelled {} already processed", eventId);
            return;
        }
        RestaurantOrder order = orders.lockById(orderId).orElse(null);
        if (order == null) {
            log.debug("OrderCancelled {}: order {} never reached the board", eventId, orderId);
            return;
        }
        if (order.getStatus().isTerminal()) {
            log.info("OrderCancelled ({}) for order {} ignored: the store order is already {}", reason, orderId,
                    order.getStatus());
            return;
        }
        Instant now = clock.instant();
        order.cancel(reason, cancelledBy, cancelledAt != null ? cancelledAt : now, now);
        log.info("Store order {} cancelled ({} by {})", orderId, reason, cancelledBy);
    }

    /**
     * Phase 9: the partner picked the order up → the store order becomes PICKED_UP (terminal success, off the board)
     * under the row lock; no event (order-service already knows). Normally from READY_FOR_PICKUP; from ACCEPTED or
     * PREPARING too (logged), since the food has left the kitchen either way. A NEW / terminal store order, or an
     * order never on the board, is left alone (logged).
     */
    @Transactional
    public void orderOutForDelivery(String eventId, UUID orderId, Instant pickedUpAt) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderOutForDelivery {} already processed", eventId);
            return;
        }
        RestaurantOrder order = orders.lockById(orderId).orElse(null);
        if (order == null) {
            log.warn("OrderOutForDelivery {}: order {} is not on any board", eventId, orderId);
            return;
        }
        if (!StoreOrderStatus.PICKUP_PENDING.contains(order.getStatus())) {
            log.info("OrderOutForDelivery for order {} ignored: the store order is {}", orderId, order.getStatus());
            return;
        }
        if (order.getStatus() != StoreOrderStatus.READY_FOR_PICKUP) {
            log.warn("Order {} picked up while the store order was still {}", orderId, order.getStatus());
        }
        Instant now = clock.instant();
        order.pickedUp(pickedUpAt != null ? pickedUpAt : now, now);
        log.info("Store order {} picked up at restaurant {}", orderId, order.getRestaurantId());
    }

    /**
     * The area the restaurant may see: the landmark (else line 2) and the city, e.g. "Indiranagar, Bengaluru"; just
     * the city when neither is set; null without an address.
     */
    static String customerArea(Recipient recipient) {
        if (recipient == null) {
            return null;
        }
        String locality = clean(recipient.landmark()) != null ? clean(recipient.landmark()) : clean(recipient.line2());
        String area = Stream.of(locality, clean(recipient.city())).filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return area.isEmpty() ? null : truncate(area, MAX_AREA);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
