package com.grocery.review.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.review.entity.TargetType;
import com.grocery.review.repository.OrderProjection.OrderItem;
import com.grocery.review.repository.OrderProjection.ProjectedOrder;
import com.grocery.review.service.OrderEventsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * order.events → what customers may review (group {@value OrderEventsService#CONSUMER}): {@code OrderConfirmed}
 * (lines, store, type) and {@code OrderDelivered} (partner, time). Every other event type is ignored.
 *
 * <p>A payload without its ids, order number, type or time is an {@link InvalidEventException} (straight to
 * {@code order.events.DLT}); database outages propagate as they are, so grocery-common's error handler retries the
 * same record. Lines without an item id or with an unknown type are skipped (tolerant reader).
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order.events";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_DELIVERED = "OrderDelivered";

    static final int MAX_ORDER_NUMBER = 40;
    static final int MAX_NAME = 200;
    static final String UNNAMED_ITEM = "Item";

    /** Our own view of the OrderConfirmed payload (tolerant reader). */
    record OrderConfirmed(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                          List<Line> items, Instant confirmedAt, StoreLocation storeLocation) {
    }

    record Line(UUID itemId, String type, String name) {
    }

    record StoreLocation(String name) {
    }

    /** Our own view of the OrderDelivered payload. */
    record OrderDelivered(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                          UUID partnerId, Instant deliveredAt) {
    }

    private final OrderEventsService service;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(OrderEventsService service, JsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "review-order-events", groupId = OrderEventsService.CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case ORDER_CONFIRMED -> confirmed(event);
            case ORDER_DELIVERED -> delivered(event);
            default -> {
                // OrderCreated, OrderCancelled, returns, …: nothing to review
            }
        }
    }

    private void confirmed(EventEnvelope event) {
        OrderConfirmed p = event.payloadAs(jsonMapper, OrderConfirmed.class);
        if (p.orderId() == null || p.customerId() == null || p.storeId() == null || p.confirmedAt() == null
                || blank(p.orderNumber()) || blank(p.orderType())) {
            throw invalid(event, "lacks orderId, orderNumber, orderType, customerId, storeId or confirmedAt");
        }
        String storeName = p.storeLocation() == null || blank(p.storeLocation().name()) ? null
                : cut(p.storeLocation().name().strip(), MAX_NAME);
        ProjectedOrder order = new ProjectedOrder(p.orderId(), orderNumber(event, p.orderNumber()), p.orderType().strip(),
                p.customerId(), p.storeId(), storeName, p.confirmedAt(), null, null);
        service.orderConfirmed(event.eventId(), order, items(p));
    }

    private void delivered(EventEnvelope event) {
        OrderDelivered p = event.payloadAs(jsonMapper, OrderDelivered.class);
        if (p.orderId() == null || p.customerId() == null || p.storeId() == null || p.partnerId() == null
                || p.deliveredAt() == null || blank(p.orderNumber()) || blank(p.orderType())) {
            throw invalid(event, "lacks orderId, orderNumber, orderType, customerId, storeId, partnerId or deliveredAt");
        }
        service.orderDelivered(event.eventId(), new ProjectedOrder(p.orderId(), orderNumber(event, p.orderNumber()),
                p.orderType().strip(), p.customerId(), p.storeId(), null, null, p.partnerId(), p.deliveredAt()));
    }

    /** One entry per distinct (type, item), in line order; unknown types and lines without an id are skipped. */
    private static List<OrderItem> items(OrderConfirmed p) {
        List<OrderItem> items = new ArrayList<>();
        if (p.items() == null) {
            return items;
        }
        Set<String> seen = new HashSet<>();
        int lineNo = 0;
        for (Line line : p.items()) {
            if (line == null || line.itemId() == null || TargetType.itemOf(line.type()) == null) {
                continue;
            }
            if (seen.add(line.type() + ":" + line.itemId())) {
                String name = blank(line.name()) ? UNNAMED_ITEM : cut(line.name().strip(), MAX_NAME);
                items.add(new OrderItem(p.orderId(), line.type(), line.itemId(), name, lineNo++));
            }
        }
        return items;
    }

    private static String orderNumber(EventEnvelope event, String orderNumber) {
        String value = orderNumber.strip();
        if (value.length() > MAX_ORDER_NUMBER) {
            throw invalid(event, "has an orderNumber longer than " + MAX_ORDER_NUMBER);
        }
        return value;
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static InvalidEventException invalid(EventEnvelope event, String problem) {
        return new InvalidEventException(event.eventType() + " " + event.eventId() + " " + problem);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
