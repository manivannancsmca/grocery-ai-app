package com.grocery.common.deadletter;

import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.OriginalValueDeadLetterPublishingRecoverer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One record of a {@code <topic>.DLT}, as written by grocery-common's dead-letter recoverer: the ORIGINAL value bytes
 * (Avro wire format or legacy JSON), the original headers ({@code eventId}, {@code eventType}, …) and Spring Kafka's
 * {@code kafka_dlt-*} headers (original topic / partition / offset / timestamp / consumer group, exception).
 * Immutable except for the cached payload preview.
 */
final class DeadLetter {

    static final String DLT_HEADER_PREFIX = "kafka_dlt-";
    /** ErrorHandlingDeserializer's failure headers (springDeserializerExceptionKey/Value); never part of the event. */
    static final String DESERIALIZER_EXCEPTION_HEADER_PREFIX = "springDeserializerException";
    static final int STACK_TRACE_LIMIT = 4096;
    static final int EXCEPTION_MESSAGE_LIMIT = 2000;

    /** Exceptions that only wrap the real failure: the first other one of the cause chain is reported. */
    private static final Set<String> WRAPPERS = Set.of(
            "org.springframework.kafka.listener.ListenerExecutionFailedException",
            "org.springframework.kafka.listener.TimestampedException",
            "org.springframework.kafka.support.serializer.DeserializationException",
            "java.lang.reflect.InvocationTargetException",
            "java.lang.reflect.UndeclaredThrowableException");

    private final String dltTopic;
    private final int dltPartition;
    private final long dltOffset;
    private final String originalTopic;
    private final Integer originalPartition;
    private final Long originalOffset;
    private final long originalTimestamp;
    private final TimestampType originalTimestampType;
    private final String consumerGroup;
    private final byte[] key;
    private final byte[] value;
    private final List<Header> headers;
    private final String eventId;
    private final String eventType;
    private final Integer eventVersion;
    private final Instant occurredAt;
    private final Instant deadLetteredAt;
    private final String exceptionClass;
    private final String exceptionMessage;
    private final String stackTrace;

    private volatile String payloadPreview;
    private volatile boolean previewDecoded;
    private volatile long previewFailedAtNanos;
    private volatile boolean previewFailed;

    private DeadLetter(ConsumerRecord<byte[], byte[]> record, String consumerGroup) {
        Headers all = record.headers();
        this.dltTopic = record.topic();
        this.dltPartition = record.partition();
        this.dltOffset = record.offset();
        this.consumerGroup = consumerGroup;
        String topic = text(all, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        this.originalTopic = topic != null ? topic
                : dltTopic.endsWith(".DLT") ? dltTopic.substring(0, dltTopic.length() - 4) : dltTopic;
        this.originalPartition = intHeader(all, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        this.originalOffset = longHeader(all, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        Long timestamp = longHeader(all, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);
        this.originalTimestamp = timestamp != null ? timestamp : record.timestamp();
        this.originalTimestampType = timestampType(text(all, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE));
        this.key = record.key();
        this.value = record.value();
        this.headers = originalHeaders(all);
        this.eventId = text(all, EventHeaders.EVENT_ID);
        this.eventType = text(all, EventHeaders.EVENT_TYPE);
        this.eventVersion = parseInt(text(all, EventHeaders.EVENT_VERSION));
        this.occurredAt = occurredAt(text(all, EventHeaders.OCCURRED_AT));
        this.deadLetteredAt = record.timestamp() >= 0 ? Instant.ofEpochMilli(record.timestamp()) : null;
        String fullStackTrace = text(all, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        String[] failure = failure(fullStackTrace, all);
        this.exceptionClass = failure[0];
        this.exceptionMessage = truncate(failure[1], EXCEPTION_MESSAGE_LIMIT);
        this.stackTrace = truncate(fullStackTrace, STACK_TRACE_LIMIT);
    }

    /**
     * @return the dead letter, or {@code null} when the record carries no original consumer group (not written by a
     *         listener container's recoverer, so it belongs to no consumer group)
     */
    static DeadLetter from(ConsumerRecord<byte[], byte[]> record) {
        String group = text(record.headers(), KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        return group == null || group.isBlank() ? null : new DeadLetter(record, group);
    }

    /** {@code <dltTopic>:<partition>:<offset>}. */
    static String id(String dltTopic, int partition, long offset) {
        return dltTopic + ":" + partition + ":" + offset;
    }

    /** The DLT partition of an id, or {@code null} if the id is malformed. */
    static TopicPartition partitionOf(String id) {
        if (id == null) {
            return null;
        }
        int last = id.lastIndexOf(':');
        int middle = last > 0 ? id.lastIndexOf(':', last - 1) : -1;
        if (middle <= 0) {
            return null;
        }
        try {
            Long.parseLong(id.substring(last + 1));
            return new TopicPartition(id.substring(0, middle), Integer.parseInt(id.substring(middle + 1, last)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String id() {
        return id(dltTopic, dltPartition, dltOffset);
    }

    /** Key of this dead letter's state in {@code _grocery.dead-letter-actions}. */
    String actionKey() {
        return actionKey(consumerGroup, dltTopic, dltPartition, dltOffset);
    }

    static String actionKey(String consumerGroup, String dltTopic, int partition, long offset) {
        return consumerGroup + "|" + dltTopic + "|" + partition + "|" + offset;
    }

    TopicPartition dltTopicPartition() {
        return new TopicPartition(dltTopic, dltPartition);
    }

    /** A fresh, mutable copy of the original headers (without {@code kafka_dlt-*}). */
    Headers headersCopy() {
        RecordHeaders copy = new RecordHeaders();
        headers.forEach(header -> copy.add(new RecordHeader(header.key(), header.value())));
        return copy;
    }

    String keyText() {
        return key == null ? null : new String(key, StandardCharsets.UTF_8);
    }

    String dltTopic() {
        return dltTopic;
    }

    int dltPartition() {
        return dltPartition;
    }

    long dltOffset() {
        return dltOffset;
    }

    String originalTopic() {
        return originalTopic;
    }

    Integer originalPartition() {
        return originalPartition;
    }

    Long originalOffset() {
        return originalOffset;
    }

    long originalTimestamp() {
        return originalTimestamp;
    }

    TimestampType originalTimestampType() {
        return originalTimestampType;
    }

    String consumerGroup() {
        return consumerGroup;
    }

    byte[] key() {
        return key;
    }

    byte[] value() {
        return value;
    }

    String eventId() {
        return eventId;
    }

    String eventType() {
        return eventType;
    }

    Integer eventVersion() {
        return eventVersion;
    }

    Instant occurredAt() {
        return occurredAt;
    }

    Instant deadLetteredAt() {
        return deadLetteredAt;
    }

    String exceptionClass() {
        return exceptionClass;
    }

    String exceptionMessage() {
        return exceptionMessage;
    }

    String stackTrace() {
        return stackTrace;
    }

    /** Orders newest first: by the DLT record's timestamp, then by id. */
    long sortTime() {
        return deadLetteredAt == null ? Long.MIN_VALUE : deadLetteredAt.toEpochMilli();
    }

    // ---- cached payload preview (decoded lazily: decoding Avro may need the Schema Registry) ----

    boolean previewDecoded() {
        return previewDecoded;
    }

    String payloadPreview() {
        return payloadPreview;
    }

    void previewDecoded(String preview) {
        this.payloadPreview = preview;
        this.previewDecoded = true;
    }

    /** A failed decoding is retried only after {@code retryAfterNanos} (the registry may be down). */
    boolean previewFailedRecently(long retryAfterNanos) {
        return previewFailed && System.nanoTime() - previewFailedAtNanos < retryAfterNanos;
    }

    void previewFailed() {
        this.previewFailedAtNanos = System.nanoTime();
        this.previewFailed = true;
    }

    // ---- header parsing ----

    private static List<Header> originalHeaders(Headers all) {
        List<Header> kept = new ArrayList<>();
        for (Header header : all) {
            String name = header.key();
            if (name.startsWith(DLT_HEADER_PREFIX)
                    || name.startsWith(DESERIALIZER_EXCEPTION_HEADER_PREFIX)
                    || name.equals(OriginalValueDeadLetterPublishingRecoverer.ORIGINAL_VALUE_HEADER)) {
                continue;
            }
            kept.add(new RecordHeader(name, header.value()));
        }
        return List.copyOf(kept);
    }

    /**
     * Class and message of the failure worth showing: the first exception of the cause chain that is not a mere
     * wrapper (the listener container wraps every failure in a ListenerExecutionFailedException, a value that could not
     * be decoded in a DeserializationException). Read from the stack trace header; falls back to the
     * {@code kafka_dlt-exception-*} headers.
     */
    static String[] failure(String stackTrace, Headers headers) {
        if (stackTrace != null && !stackTrace.isBlank()) {
            String[] fallback = null;
            for (String block : stackTrace.split("\n(?=Caused by: )")) {
                String head = block.startsWith("Caused by: ") ? block.substring("Caused by: ".length()) : block;
                int frames = head.indexOf("\n\tat ");
                if (frames < 0) {
                    frames = head.indexOf("\n\t");
                }
                head = (frames < 0 ? head : head.substring(0, frames)).strip();
                int colon = head.indexOf(": ");
                String type = (colon < 0 ? head : head.substring(0, colon)).strip();
                String message = colon < 0 ? null : head.substring(colon + 2).strip();
                if (type.isEmpty() || type.contains(" ")) {
                    continue;
                }
                if (!WRAPPERS.contains(type)) {
                    return new String[]{type, message};
                }
                if (fallback == null) {
                    fallback = new String[]{type, message};
                }
            }
            if (fallback != null && text(headers, KafkaHeaders.DLT_EXCEPTION_FQCN) == null) {
                return fallback;               // only wrappers and no exception headers: the outermost one
            }
        }
        String cause = text(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        String type = cause != null ? cause : text(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
        return new String[]{type, text(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE)};
    }

    static String text(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    /** Spring Kafka writes the original partition as a 4-byte int. */
    private static Integer intHeader(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        byte[] bytes = header.value();
        if (bytes.length == Integer.BYTES) {
            return ByteBuffer.wrap(bytes).getInt();
        }
        return parseInt(new String(bytes, StandardCharsets.UTF_8));      // null when unreadable
    }

    /** Spring Kafka writes the original offset and timestamp as 8-byte longs. */
    private static Long longHeader(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        byte[] bytes = header.value();
        if (bytes.length == Long.BYTES) {
            return ByteBuffer.wrap(bytes).getLong();
        }
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TimestampType timestampType(String name) {
        if (name == null) {
            return TimestampType.CREATE_TIME;
        }
        for (TimestampType type : TimestampType.values()) {
            if (type.name().equalsIgnoreCase(name.trim()) || type.toString().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return TimestampType.CREATE_TIME;
    }

    /** {@code occurredAt}: epoch microseconds (Debezium's DATETIME(6)); ISO-8601 accepted too. */
    static Instant occurredAt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return Instant.EPOCH.plus(Long.parseLong(trimmed), ChronoUnit.MICROS);
        } catch (NumberFormatException | ArithmeticException e) {
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
