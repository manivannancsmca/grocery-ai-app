package com.grocery.order.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.order.service.EventSource;
import com.grocery.order.service.OrderEventService;
import com.grocery.order.service.OrderEventService.Unavailable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * inventory.events → orders (consumer group {@code order-service.inventory-events}). Only the reservation events of
 * the order saga are used: {@code StockReserved}, {@code StockReservationFailed}, {@code StockReservationExpired}.
 * Everything else on the topic — {@code StockReleased} (audit only) and the Phase 4 stock events
 * ({@code StockAdjusted}, {@code ProductAvailabilityChanged}, {@code LowStockDetected}) — is ignored without
 * touching the database. The state guards are applied by {@link OrderEventService}.
 */
@Component
public class InventoryEventsListener {

    static final String TOPIC = "inventory.events";
    static final String STOCK_RESERVED = "StockReserved";
    static final String STOCK_RESERVATION_FAILED = "StockReservationFailed";
    static final String STOCK_RESERVATION_EXPIRED = "StockReservationExpired";

    /** StockReserved / StockReservationExpired: only which order (tolerant reader). */
    record OrderRef(UUID orderId) {
    }

    record StockReservationFailed(UUID orderId, List<UnavailableItem> unavailableItems) {
    }

    record UnavailableItem(UUID productId, Integer requested, Integer available) {
    }

    private final OrderEventService orderEvents;
    private final JsonMapper jsonMapper;

    public InventoryEventsListener(OrderEventService orderEvents, JsonMapper jsonMapper) {
        this.orderEvents = orderEvents;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "order-inventory-events", groupId = OrderEventService.INVENTORY_CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case STOCK_RESERVED -> orderEvents.stockReserved(EventSource.of(record, event), orderId(event));
            case STOCK_RESERVATION_FAILED -> reservationFailed(record, event);
            case STOCK_RESERVATION_EXPIRED ->
                    orderEvents.stockReservationExpired(EventSource.of(record, event), orderId(event));
            default -> {
                // StockReleased, StockAdjusted, ProductAvailabilityChanged, LowStockDetected, …: not for orders
            }
        }
    }

    private void reservationFailed(ConsumerRecord<String, String> record, EventEnvelope event) {
        StockReservationFailed failed = event.payloadAs(jsonMapper, StockReservationFailed.class);
        if (failed.orderId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        List<Unavailable> unavailable = failed.unavailableItems() == null ? List.of() : failed.unavailableItems()
                .stream()
                .map(item -> {
                    if (item == null || item.productId() == null) {
                        throw new InvalidEventException(event.eventType() + " " + event.eventId()
                                + " has an unavailable item without productId");
                    }
                    return new Unavailable(item.productId(), item.requested() == null ? 0 : item.requested(),
                            item.available() == null ? 0 : item.available());
                })
                .toList();
        orderEvents.stockReservationFailed(EventSource.of(record, event), failed.orderId(), unavailable);
    }

    private UUID orderId(EventEnvelope event) {
        OrderRef ref = event.payloadAs(jsonMapper, OrderRef.class);
        if (ref.orderId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        return ref.orderId();
    }
}
