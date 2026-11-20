package com.grocery.order.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.order.entity.OrderType;
import com.grocery.order.service.EventSource;
import com.grocery.order.service.OrderEventService;
import com.grocery.order.service.OrderEventService.StoreEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * The stores' decisions on an order (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §4, §5), shared by
 * {@link VendorEventsListener} and {@link RestaurantEventsListener}: the same four event types with the store's
 * prefix ({@code VendorOrder*} / {@code RestaurantOrder*}) and the store id in {@code vendorId} / {@code restaurantId}.
 * <ul>
 *   <li>{@code …OrderAccepted}, {@code …OrderPreparing}, {@code …OrderReadyForPickup}, {@code …OrderRejected} → the
 *       state guards of {@link OrderEventService};</li>
 *   <li>{@code …OrderReceived} (order-service already knows the order) and every other event type on the topic (the
 *       store lifecycle: Registered, Submitted, Approved, …, ListingUpdated) → skipped without touching the
 *       database.</li>
 * </ul>
 * A broken store order event (no {@code orderId}, no store id, an accepted event without {@code readyInMinutes}, a
 * rejected event without {@code reason}, an unreadable payload) is {@link InvalidEventException} → the DLT.
 */
@Component
public class StoreOrderEvents {

    static final String ORDER_ACCEPTED = "OrderAccepted";
    static final String ORDER_PREPARING = "OrderPreparing";
    static final String ORDER_READY_FOR_PICKUP = "OrderReadyForPickup";
    static final String ORDER_REJECTED = "OrderRejected";

    /** The two kinds of store: event-type prefix, store-id field, the order type they serve and the consumer group. */
    public enum Store {
        VENDOR("Vendor", OrderType.GROCERY, OrderEventService.VENDOR_CONSUMER),
        RESTAURANT("Restaurant", OrderType.RESTAURANT, OrderEventService.RESTAURANT_CONSUMER);

        private final String prefix;
        private final OrderType orderType;
        private final String consumer;

        Store(String prefix, OrderType orderType, String consumer) {
            this.prefix = prefix;
            this.orderType = orderType;
            this.consumer = consumer;
        }

        UUID storeId(StoreOrderEvent payload) {
            return this == VENDOR ? payload.vendorId() : payload.restaurantId();
        }

        String storeIdField() {
            return this == VENDOR ? "vendorId" : "restaurantId";
        }
    }

    /**
     * Our own view of every {@code VendorOrder*} / {@code RestaurantOrder*} payload: only the fields order-service uses
     * (tolerant reader; a field an event type does not have stays null).
     */
    record StoreOrderEvent(UUID orderId, UUID vendorId, UUID restaurantId, Integer readyInMinutes, Instant readyBy,
                           Instant acceptedAt, Instant preparingAt, Instant readyAt, String reason, String note,
                           Boolean auto, UUID actorUserId) {
    }

    private final OrderEventService orderEvents;
    private final JsonMapper jsonMapper;

    public StoreOrderEvents(OrderEventService orderEvents, JsonMapper jsonMapper) {
        this.orderEvents = orderEvents;
        this.jsonMapper = jsonMapper;
    }

    public void on(ConsumerRecord<String, String> record, Store store) {
        EventEnvelope event = EventEnvelope.from(record);
        String type = event.eventType();
        if (!type.startsWith(store.prefix)) {
            return;                                     // not this store's kind of event
        }
        switch (type.substring(store.prefix.length())) {
            case ORDER_ACCEPTED -> {
                StoreOrderEvent payload = read(event, store);
                if (payload.readyInMinutes() == null) {
                    throw new InvalidEventException(type + " " + event.eventId() + " lacks readyInMinutes");
                }
                orderEvents.storeAccepted(storeEvent(record, event, store, payload), payload.readyInMinutes(),
                        payload.readyBy(), payload.acceptedAt());
            }
            case ORDER_PREPARING -> {
                StoreOrderEvent payload = read(event, store);
                orderEvents.storePreparing(storeEvent(record, event, store, payload), payload.preparingAt());
            }
            case ORDER_READY_FOR_PICKUP -> {
                StoreOrderEvent payload = read(event, store);
                orderEvents.storeReadyForPickup(storeEvent(record, event, store, payload), payload.readyAt());
            }
            case ORDER_REJECTED -> {
                StoreOrderEvent payload = read(event, store);
                if (payload.reason() == null || payload.reason().isBlank()) {
                    throw new InvalidEventException(type + " " + event.eventId() + " lacks reason");
                }
                orderEvents.storeRejected(storeEvent(record, event, store, payload), payload.reason().strip(),
                        payload.note(), Boolean.TRUE.equals(payload.auto()), payload.actorUserId());
            }
            default -> {
                // …OrderReceived (order-service already knows the order) and the store lifecycle events
            }
        }
    }

    private StoreOrderEvent read(EventEnvelope event, Store store) {
        StoreOrderEvent payload = event.payloadAs(jsonMapper, StoreOrderEvent.class);
        if (payload.orderId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        if (store.storeId(payload) == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks "
                    + store.storeIdField());
        }
        return payload;
    }

    private static StoreEvent storeEvent(ConsumerRecord<String, String> record, EventEnvelope event, Store store,
                                         StoreOrderEvent payload) {
        return new StoreEvent(store.consumer, EventSource.of(record, event), payload.orderId(), store.orderType,
                store.storeId(payload));
    }
}
