package com.grocery.common.outbox;

import java.util.Objects;

/**
 * A domain event to be published reliably through the transactional outbox.
 *
 * @param topic         destination Kafka topic, e.g. {@code order.events}
 * @param aggregateType aggregate name, e.g. {@code Order}
 * @param aggregateId   aggregate ID; becomes the Kafka message key (per-aggregate ordering)
 * @param eventType     event name in past tense, e.g. {@code OrderCreated}
 * @param eventVersion  payload schema version (additive changes keep the version)
 * @param payload       payload object, serialised to JSON
 */
public record OutboxEvent(String topic, String aggregateType, String aggregateId,
                          String eventType, int eventVersion, Object payload) {

    public OutboxEvent {
        requireText(topic, "topic");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }

    /** Convenience factory for version 1 events. */
    public static OutboxEvent of(String topic, String aggregateType, String aggregateId,
                                 String eventType, Object payload) {
        return new OutboxEvent(topic, aggregateType, aggregateId, eventType, 1, payload);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
