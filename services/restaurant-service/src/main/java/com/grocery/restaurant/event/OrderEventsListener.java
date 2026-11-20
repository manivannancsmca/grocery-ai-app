package com.grocery.restaurant.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.restaurant.service.StoreOrderProjection;
import com.grocery.restaurant.service.StoreOrderProjection.ConfirmedOrder;
import com.grocery.restaurant.service.StoreOrderProjection.Line;
import com.grocery.restaurant.service.StoreOrderProjection.Recipient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * order.events → the restaurant order board (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §2 "Created
 * from"). RESTAURANT orders only: {@code OrderConfirmed} (a store order NEW + RestaurantOrderReceived),
 * {@code OrderCancelled} (the store order leaves the board) and (Phase 9) {@code OrderOutForDelivery} (the store order
 * is PICKED_UP). Grocery orders and every other event type are ignored.
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order.events";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_CANCELLED = "OrderCancelled";
    static final String ORDER_OUT_FOR_DELIVERY = "OrderOutForDelivery";
    static final String RESTAURANT = "RESTAURANT";

    /** Our own view of OrderConfirmed (tolerant reader); {@code subtotal} and {@code note} arrive from Phase 8 on. */
    record OrderConfirmed(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                          List<Item> items, BigDecimal total, Address deliveryAddress, Instant confirmedAt,
                          BigDecimal subtotal, String note) {
    }

    record Item(UUID itemId, String type, String name, Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** Only the parts of the address the store may see (no phone, no street). */
    record Address(String recipientName, String line2, String landmark, String city) {
    }

    record OrderCancelled(UUID orderId, String orderType, String reason, String cancelledBy, Instant cancelledAt) {
    }

    /** Phase 9: the partner picked the order up. */
    record OrderOutForDelivery(UUID orderId, String orderType, Instant pickedUpAt) {
    }

    private final StoreOrderProjection projection;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(StoreOrderProjection projection, JsonMapper jsonMapper) {
        this.projection = projection;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "restaurant-order-events", groupId = StoreOrderProjection.CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case ORDER_CONFIRMED -> confirmed(event);
            case ORDER_CANCELLED -> cancelled(event);
            case ORDER_OUT_FOR_DELIVERY -> outForDelivery(event);
            default -> {
                // OrderCreated, OrderAwaitingPayment, OrderAccepted, …: nothing to do here
            }
        }
    }

    private void confirmed(EventEnvelope event) {
        OrderConfirmed order = event.payloadAs(jsonMapper, OrderConfirmed.class);
        if (order.orderId() == null || order.orderType() == null) {
            throw invalid(event, "lacks orderId or orderType");
        }
        if (!RESTAURANT.equals(order.orderType())) {
            return;                             // a grocery order: vendor-service's board
        }
        if (order.orderNumber() == null || order.customerId() == null || order.storeId() == null
                || order.total() == null) {
            throw invalid(event, "lacks orderNumber, customerId, storeId or total");
        }
        if (order.items() == null || order.items().isEmpty()) {
            throw invalid(event, "has no items");
        }
        List<Line> lines = order.items().stream()
                .map(item -> {
                    if (item == null || item.itemId() == null || item.name() == null || item.quantity() == null
                            || item.quantity() <= 0 || item.unitPrice() == null || item.lineTotal() == null) {
                        throw invalid(event, "has an item without itemId, name, a positive quantity or prices");
                    }
                    return new Line(item.itemId(), item.type(), item.name(), item.quantity(), item.unitPrice(),
                            item.lineTotal());
                })
                .toList();
        Address address = order.deliveryAddress();
        Recipient recipient = address == null ? null
                : new Recipient(address.recipientName(), address.line2(), address.landmark(), address.city());
        projection.orderConfirmed(event.eventId(), new ConfirmedOrder(order.orderId(), order.orderNumber(),
                order.customerId(), order.storeId(), lines, order.subtotal(), order.total(), recipient, order.note(),
                order.confirmedAt()));
    }

    private void cancelled(EventEnvelope event) {
        OrderCancelled order = event.payloadAs(jsonMapper, OrderCancelled.class);
        if (order.orderId() == null) {
            throw invalid(event, "lacks orderId");
        }
        if (order.orderType() != null && !RESTAURANT.equals(order.orderType())) {
            return;
        }
        projection.orderCancelled(event.eventId(), order.orderId(), order.reason(), order.cancelledBy(),
                order.cancelledAt());
    }

    private void outForDelivery(EventEnvelope event) {
        OrderOutForDelivery order = event.payloadAs(jsonMapper, OrderOutForDelivery.class);
        if (order.orderId() == null) {
            throw invalid(event, "lacks orderId");
        }
        if (order.orderType() != null && !RESTAURANT.equals(order.orderType())) {
            return;
        }
        projection.orderOutForDelivery(event.eventId(), order.orderId(), order.pickedUpAt());
    }

    private static InvalidEventException invalid(EventEnvelope event, String problem) {
        return new InvalidEventException(event.eventType() + " " + event.eventId() + " " + problem);
    }
}
