package com.grocery.pricing.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.pricing.entity.ReleaseReason;
import com.grocery.pricing.service.CouponRedemptionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * order.events → the coupon redemption lifecycle (Phase 12 §2), consumer group {@code pricing-service.order-events}:
 * {@code OrderCreated} confirms the order's redemption, {@code OrderCancelled} and {@code OrderRejected} release it.
 * Every other event type, and orders without a redemption, are ignored. Values arrive as JSON (grocery-common's Avro
 * deserializer renders the Avro records); a record without {@code orderId} is poison (dead-letter topic).
 */
@Component
public class OrderEventsListener {

    public static final String TOPIC = "order.events";
    static final String ORDER_CREATED = "OrderCreated";
    static final String ORDER_CANCELLED = "OrderCancelled";
    static final String ORDER_REJECTED = "OrderRejected";

    /** Our own view of the payloads: the order id and number (tolerant reader). */
    record OrderEvent(UUID orderId, String orderNumber) {
    }

    private final CouponRedemptionService redemptions;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(CouponRedemptionService redemptions, JsonMapper jsonMapper) {
        this.redemptions = redemptions;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "pricing-order-events", groupId = CouponRedemptionService.CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case ORDER_CREATED -> {
                OrderEvent order = order(event);
                redemptions.confirm(event.eventId(), order.orderId(), order.orderNumber());
            }
            case ORDER_CANCELLED -> {
                OrderEvent order = order(event);
                redemptions.release(event.eventId(), order.orderId(), order.orderNumber(),
                        ReleaseReason.ORDER_CANCELLED);
            }
            case ORDER_REJECTED -> {
                OrderEvent order = order(event);
                redemptions.release(event.eventId(), order.orderId(), order.orderNumber(),
                        ReleaseReason.ORDER_REJECTED);
            }
            default -> {
                // OrderConfirmed, OrderDelivered, Return…: nothing to do
            }
        }
    }

    private OrderEvent order(EventEnvelope event) {
        OrderEvent order = event.payloadAs(jsonMapper, OrderEvent.class);
        if (order.orderId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        return order;
    }
}
