package com.grocery.common.kafka;

import com.grocery.common.event.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * A domain event as received from Kafka: routing metadata from the headers (written by the
 * Debezium outbox router) plus the raw JSON payload.
 *
 * <p>Consumers switch on {@link #eventType()} and map the payload to their OWN DTO containing only
 * the fields they need (tolerant reader) — event classes are never shared between services.
 */
public record EventEnvelope(String eventId, String eventType, int eventVersion, String aggregateType,
                            String key, String correlationId, String topic, String payload) {

    public static EventEnvelope from(ConsumerRecord<String, String> record) {
        String eventId = header(record, EventHeaders.EVENT_ID);
        String eventType = header(record, EventHeaders.EVENT_TYPE);
        if (eventId == null || eventType == null) {
            throw new InvalidEventException("Event without eventId/eventType headers on " + record.topic()
                    + "-" + record.partition() + "@" + record.offset());
        }
        if (record.value() == null) {
            throw new InvalidEventException("Event " + eventId + " has no payload");
        }
        String version = header(record, EventHeaders.EVENT_VERSION);
        int eventVersion;
        try {
            eventVersion = version == null ? 1 : Integer.parseInt(version.trim());
        } catch (NumberFormatException e) {
            throw new InvalidEventException("Event " + eventId + " has an invalid eventVersion '" + version + "'", e);
        }
        return new EventEnvelope(eventId, eventType, eventVersion,
                header(record, EventHeaders.AGGREGATE_TYPE), record.key(),
                header(record, EventHeaders.CORRELATION_ID), record.topic(), record.value());
    }

    /** Maps the payload to the consumer's own DTO; unknown fields are ignored (Jackson 3 default). */
    public <T> T payloadAs(JsonMapper jsonMapper, Class<T> type) {
        try {
            return jsonMapper.readValue(payload, type);
        } catch (JacksonException e) {
            throw new InvalidEventException("Cannot read " + eventType + " v" + eventVersion
                    + " (" + eventId + ") as " + type.getSimpleName(), e);
        }
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return value.isEmpty() || "null".equals(value) ? null : value;
    }
}
