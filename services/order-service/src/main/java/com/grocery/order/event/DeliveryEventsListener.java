package com.grocery.order.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.order.service.EventSource;
import com.grocery.order.service.OrderEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * delivery.events → orders (consumer group {@code order-service.delivery-events}, docs/api/phase-9-delivery.md §7):
 * {@code DeliveryPartnerAssigned}, {@code DeliveryPartnerUnassigned}, {@code DeliveryPickedUp},
 * {@code DeliveryCompleted}, {@code DeliveryFailed}; the state guards are applied by {@link OrderEventService}.
 * Every other type on the topic — the partner lifecycle (DeliveryPartnerRegistered, Submitted, Approved, …, keyed by
 * partnerId), the offers (DeliveryOfferCreated / Closed), DeliveryAssignmentFailed and DeliveryCancelled (the order
 * already knows it was cancelled) — is skipped without touching the database.
 *
 * <p>A broken event of a handled type (no {@code orderId}, no {@code partnerId}, a failure without {@code reason}, an
 * unreadable payload) is {@link InvalidEventException} → the DLT.
 */
@Component
public class DeliveryEventsListener {

    static final String TOPIC = "delivery.events";
    static final String PARTNER_ASSIGNED = "DeliveryPartnerAssigned";
    static final String PARTNER_UNASSIGNED = "DeliveryPartnerUnassigned";
    static final String PICKED_UP = "DeliveryPickedUp";
    static final String COMPLETED = "DeliveryCompleted";
    static final String FAILED = "DeliveryFailed";

    /**
     * Our own view of the five payloads: only the fields order-service uses (tolerant reader; a field an event type
     * does not have stays null).
     */
    record DeliveryEvent(UUID orderId, UUID partnerId, String partnerName, Instant assignedAt, Instant pickedUpAt,
                         Instant deliveredAt, String reason, String note, Instant failedAt) {
    }

    private final OrderEventService orderEvents;
    private final JsonMapper jsonMapper;

    public DeliveryEventsListener(OrderEventService orderEvents, JsonMapper jsonMapper) {
        this.orderEvents = orderEvents;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "order-delivery-events", groupId = OrderEventService.DELIVERY_CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        EventSource source = EventSource.of(record, event);
        switch (event.eventType()) {
            case PARTNER_ASSIGNED -> {
                DeliveryEvent payload = read(event);
                orderEvents.partnerAssigned(source, payload.orderId(), payload.partnerId(),
                        payload.partnerName(), payload.assignedAt());
            }
            case PARTNER_UNASSIGNED -> {
                DeliveryEvent payload = read(event);
                orderEvents.partnerUnassigned(source, payload.orderId(), payload.partnerId());
            }
            case PICKED_UP -> {
                DeliveryEvent payload = read(event);
                orderEvents.deliveryPickedUp(source, payload.orderId(), payload.partnerId(),
                        payload.pickedUpAt());
            }
            case COMPLETED -> {
                DeliveryEvent payload = read(event);
                orderEvents.deliveryCompleted(source, payload.orderId(), payload.partnerId(),
                        payload.deliveredAt());
            }
            case FAILED -> {
                DeliveryEvent payload = read(event);
                if (payload.reason() == null || payload.reason().isBlank()) {
                    throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks reason");
                }
                orderEvents.deliveryFailed(source, payload.orderId(), payload.partnerId(),
                        payload.reason().strip(), payload.note(), payload.failedAt());
            }
            default -> {
                // partner lifecycle, offers, DeliveryAssignmentFailed, DeliveryCancelled: not for orders
            }
        }
    }

    private DeliveryEvent read(EventEnvelope event) {
        DeliveryEvent payload = event.payloadAs(jsonMapper, DeliveryEvent.class);
        if (payload.orderId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        if (payload.partnerId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks partnerId");
        }
        return payload;
    }
}
