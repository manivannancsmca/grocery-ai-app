package com.grocery.common.avro;

import tools.jackson.databind.JsonNode;

/**
 * Encodes an event payload for the outbox's {@code payload_avro} column: the payload as Avro, in the Confluent wire
 * format (magic byte 0, 4-byte Schema Registry id, Avro binary) that Debezium publishes unchanged.
 *
 * <p>An interface without Confluent types, so the outbox works even where the Avro libraries are absent.
 */
public interface AvroEventEncoder {

    /**
     * @param payload the payload as JSON (exactly what the outbox writes to its {@code payload} column)
     * @return the Kafka message value in the Confluent wire format
     * @throws IllegalStateException   if no schema for (topic, eventType, eventVersion) is on the classpath, or the
     *                                 Schema Registry does not know it / cannot be reached; the caller's transaction
     *                                 then rolls back instead of publishing an unvalidated event
     * @throws AvroConversionException if the payload does not match the schema (names the event and field path)
     */
    byte[] encode(String topic, String eventType, int eventVersion, JsonNode payload);
}
