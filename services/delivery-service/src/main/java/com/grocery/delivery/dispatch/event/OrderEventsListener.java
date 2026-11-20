package com.grocery.delivery.dispatch.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.delivery.dispatch.entity.OrderSnapshot;
import com.grocery.delivery.dispatch.service.DeliveryProjection;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * order.events → deliveries (contract §6, group {@value DeliveryProjection#CONSUMER}): {@code OrderConfirmed},
 * {@code OrderAccepted}, {@code OrderReadyForPickup}, {@code OrderCancelled}, for GROCERY and RESTAURANT orders alike.
 * Every other event type is ignored. Payloads are read with our own tolerant views (unknown fields ignored), so
 * OrderConfirmed v1/v2 (no {@code storeLocation}) and v3 are all accepted.
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order.events";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_ACCEPTED = "OrderAccepted";
    static final String ORDER_READY_FOR_PICKUP = "OrderReadyForPickup";
    static final String ORDER_CANCELLED = "OrderCancelled";

    private static final int MAX_NAME = 150;
    private static final int MAX_CITY = 100;
    private static final int MAX_LINE = 200;
    private static final int MAX_PHONE = 20;
    private static final int MAX_NOTE = 300;

    /** Our view of OrderConfirmed: {@code note} from Phase 8, {@code storeLocation} from Phase 9 (v3). */
    record OrderConfirmed(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                          List<Item> items, BigDecimal total, Address deliveryAddress, Instant confirmedAt,
                          String note, StoreLocation storeLocation) {
    }

    record Item(Integer quantity) {
    }

    record Address(String recipientName, String phone, String line1, String line2, String landmark, String city,
                   String postalCode, Double latitude, Double longitude) {
    }

    record StoreLocation(String name, String city, Double latitude, Double longitude) {
    }

    record OrderAccepted(UUID orderId, Instant readyBy, Instant acceptedAt) {
    }

    record OrderReadyForPickup(UUID orderId, Instant readyAt) {
    }

    record OrderCancelled(UUID orderId, String reason) {
    }

    private final DeliveryProjection projection;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(DeliveryProjection projection, JsonMapper jsonMapper) {
        this.projection = projection;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "delivery-order-events", groupId = DeliveryProjection.CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case ORDER_CONFIRMED -> confirmed(event);
            case ORDER_ACCEPTED -> accepted(event);
            case ORDER_READY_FOR_PICKUP -> ready(event);
            case ORDER_CANCELLED -> cancelled(event);
            default -> {
                // OrderCreated, OrderAwaitingPayment, OrderPreparing, OrderOutForDelivery, …: nothing to do here
            }
        }
    }

    private void confirmed(EventEnvelope event) {
        OrderConfirmed order = event.payloadAs(jsonMapper, OrderConfirmed.class);
        if (order.orderId() == null || order.orderNumber() == null || order.orderType() == null
                || order.customerId() == null || order.storeId() == null || order.total() == null) {
            throw invalid(event, "lacks orderId, orderNumber, orderType, customerId, storeId or total");
        }
        int itemCount = order.items() == null ? 0 : order.items().stream().filter(Objects::nonNull)
                .map(Item::quantity).filter(Objects::nonNull).mapToInt(q -> Math.max(q, 0)).sum();
        Address address = order.deliveryAddress() != null ? order.deliveryAddress()
                : new Address(null, null, null, null, null, null, null, null, null);
        StoreLocation store = order.storeLocation() != null ? order.storeLocation()
                : new StoreLocation(null, null, null, null);
        boolean storeLocated = store.latitude() != null && store.longitude() != null;
        projection.orderConfirmed(event.eventId(), new OrderSnapshot(order.orderId(),
                truncate(order.orderNumber(), 20), order.orderType(), order.customerId(), order.storeId(),
                truncate(clean(store.name()), MAX_NAME), truncate(clean(store.city()), MAX_CITY),
                storeLocated ? store.latitude() : null, storeLocated ? store.longitude() : null,
                truncate(clean(address.recipientName()), MAX_NAME), truncate(clean(address.phone()), MAX_PHONE),
                truncate(clean(address.line1()), MAX_LINE), truncate(clean(address.line2()), MAX_LINE),
                truncate(clean(address.landmark()), MAX_LINE), truncate(clean(address.city()), MAX_CITY),
                truncate(clean(address.postalCode()), MAX_PHONE),
                address.latitude() != null && address.longitude() != null ? address.latitude() : null,
                address.latitude() != null && address.longitude() != null ? address.longitude() : null,
                truncate(clean(order.note()), MAX_NOTE), itemCount, order.total().setScale(2, RoundingMode.HALF_UP),
                order.confirmedAt()));
    }

    private void accepted(EventEnvelope event) {
        OrderAccepted order = event.payloadAs(jsonMapper, OrderAccepted.class);
        if (order.orderId() == null) {
            throw invalid(event, "lacks orderId");
        }
        projection.orderAccepted(event.eventId(), order.orderId(), order.readyBy());
    }

    private void ready(EventEnvelope event) {
        OrderReadyForPickup order = event.payloadAs(jsonMapper, OrderReadyForPickup.class);
        if (order.orderId() == null) {
            throw invalid(event, "lacks orderId");
        }
        projection.orderReadyForPickup(event.eventId(), order.orderId(), order.readyAt());
    }

    private void cancelled(EventEnvelope event) {
        OrderCancelled order = event.payloadAs(jsonMapper, OrderCancelled.class);
        if (order.orderId() == null) {
            throw invalid(event, "lacks orderId");
        }
        projection.orderCancelled(event.eventId(), order.orderId(), truncate(clean(order.reason()), MAX_NOTE));
    }

    private static InvalidEventException invalid(EventEnvelope event, String problem) {
        return new InvalidEventException(event.eventType() + " " + event.eventId() + " " + problem);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
