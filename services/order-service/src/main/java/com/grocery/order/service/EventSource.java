package com.grocery.order.service;

import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * A consumed event as the order event timeline records it (Phase 10 §5): its id and type, the topic it came from, its
 * own time and its correlation id — taken from the record's outbox headers.
 *
 * @param occurredAt    header {@code occurredAt} (Debezium writes the outbox's DATETIME(6) as epoch microseconds; epoch
 *                      milliseconds and ISO-8601 are read too); null when missing or unreadable
 * @param correlationId header {@code correlationId} when it is safe to keep, else the event id (the same rule as
 *                      grocery-common's {@code CorrelationRecordInterceptor})
 */
public record EventSource(String eventId, String eventType, String topic, Instant occurredAt, String correlationId) {

    public static EventSource of(ConsumerRecord<?, ?> record, EventEnvelope event) {
        String correlationId = CorrelationId.sanitize(event.correlationId());
        return new EventSource(event.eventId(), event.eventType(), event.topic(),
                occurredAt(EventEnvelope.header(record, EventHeaders.OCCURRED_AT)),
                correlationId != null ? correlationId : event.eventId());
    }

    /** Epoch micro-, milli- or seconds (told apart by magnitude) or ISO-8601; null when unreadable. */
    static Instant occurredAt(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        try {
            long number = Long.parseLong(value);
            long abs = Math.abs(number);
            if (abs >= 100_000_000_000_000L) {
                return Instant.ofEpochSecond(Math.floorDiv(number, 1_000_000L),
                        Math.floorMod(number, 1_000_000L) * 1_000L);               // microseconds
            }
            return abs >= 100_000_000_000L ? Instant.ofEpochMilli(number) : Instant.ofEpochSecond(number);
        } catch (NumberFormatException notANumber) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }
}
