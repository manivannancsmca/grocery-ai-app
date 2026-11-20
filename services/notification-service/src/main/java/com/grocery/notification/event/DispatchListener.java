package com.grocery.notification.event;

import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.service.DispatchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The domain-event consumer of this service: group {@value DispatchService#CONSUMER} over the eight domain topics of
 * contract §7 ({@code catalog.events} is not needed), Phase 11 {@code ai.events} (the assistants' approval
 * requests), Phase 12 {@code review.events} (reviews; the return events come on order.events) and {@code customer.events}
 * (a customer's profile name, for the email address projection). Phase 9 adds a second, separate consumer for the partners' live
 * positions ({@link LocationListener}, its own container), so location traffic never delays these events. Values arrive as JSON through grocery-common's
 * Avro deserializer. Failures follow grocery-common's error handler: {@code InvalidEventException} (unreadable
 * payload, missing headers or ids) → {@code <topic>.DLT} at once; transient failures (database down, lock timeouts)
 * → the same record is retried. A payment event before its OrderCreated is not a failure: it is parked
 * ({@code pending_event}) and the record is done, so the partition is never blocked.
 */
@Component
public class DispatchListener {

    private final DispatchService dispatch;

    public DispatchListener(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @KafkaListener(id = "notification-dispatch", groupId = DispatchService.CONSUMER, topics = {
            "identity.events", "vendor.events", "restaurant.events", "delivery.events",
            "order.events", "payment.events", "inventory.events", "pricing.events", "ai.events", "review.events",
            "customer.events"})
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        dispatch.dispatch(event, OccurredAt.parse(EventEnvelope.header(record, EventHeaders.OCCURRED_AT)));
    }
}
