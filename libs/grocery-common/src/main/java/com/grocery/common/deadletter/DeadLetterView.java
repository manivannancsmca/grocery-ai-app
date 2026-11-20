package com.grocery.common.deadletter;

import java.time.Instant;

/**
 * One dead letter of this service (contract Phase 10 §1). {@code id} = {@code <dltTopic>:<partition>:<offset>};
 * {@code deadLetteredAt} is the DLT record's timestamp; {@code payloadPreview} is the value decoded through the
 * service's value deserializer (Avro → JSON), at most 500 characters ({@code null} if it cannot be decoded).
 */
public record DeadLetterView(String id, String dltTopic, String originalTopic, Integer originalPartition,
                             Long originalOffset, String consumerGroup, String key, String eventId, String eventType,
                             Integer eventVersion, Instant occurredAt, Instant deadLetteredAt, String exceptionClass,
                             String exceptionMessage, String payloadPreview, DeadLetterStatus status, int attempts,
                             String lastError, String lastActionBy, Instant lastActionAt) {
}
