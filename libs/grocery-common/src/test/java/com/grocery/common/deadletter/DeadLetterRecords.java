package com.grocery.common.deadletter;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** DLT records shaped like the ones Spring Kafka's DeadLetterPublishingRecoverer writes. */
final class DeadLetterRecords {

    static final long OCCURRED_AT_MICROS = 1_762_855_500_250_123L;   // 2025-11-11T10:05:00.250123Z

    private DeadLetterRecords() {
    }

    static Builder record(String dltTopic, int partition, long offset) {
        return new Builder(dltTopic, partition, offset);
    }

    static String stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text, true));
        return text.toString();
    }

    static final class Builder {
        private final String dltTopic;
        private final int partition;
        private final long offset;
        private String group = "test-service.events";
        private String originalTopic;
        private int originalPartition = 2;
        private long originalOffset = 845;
        private long timestamp = 1_762_855_600_000L;
        private String key = "order-1";
        private byte[] value = "{\"orderId\":\"o-1\",\"amount\":245.00}".getBytes(StandardCharsets.UTF_8);
        private String eventId = "0193a9a4-0000-7000-8000-000000000001";
        private String eventType = "OrderCreated";
        private Throwable failure = new ListenerExecutionFailedException("Listener method 'on' threw exception",
                "test-service.events", new IllegalStateException("boom"));
        private boolean withGroup = true;

        private Builder(String dltTopic, int partition, long offset) {
            this.dltTopic = dltTopic;
            this.partition = partition;
            this.offset = offset;
            this.originalTopic = dltTopic.replace(".DLT", "");
        }

        Builder group(String group) {
            this.group = group;
            return this;
        }

        Builder noGroup() {
            this.withGroup = false;
            return this;
        }

        Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        Builder value(byte[] value) {
            this.value = value;
            return this;
        }

        Builder value(String value) {
            return value(value.getBytes(StandardCharsets.UTF_8));
        }

        Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        Builder failure(Throwable failure) {
            this.failure = failure;
            return this;
        }

        ConsumerRecord<byte[], byte[]> build() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
            headers.add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
            headers.add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
            headers.add("occurredAt", Long.toString(OCCURRED_AT_MICROS).getBytes(StandardCharsets.UTF_8));
            headers.add("correlationId", "corr-12345678".getBytes(StandardCharsets.UTF_8));
            headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, originalTopic.getBytes(StandardCharsets.UTF_8));
            headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(originalPartition).array());
            headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(originalOffset).array());
            headers.add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, ByteBuffer.allocate(8).putLong(timestamp - 5_000).array());
            headers.add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE, "CreateTime".getBytes(StandardCharsets.UTF_8));
            if (withGroup) {
                headers.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, group.getBytes(StandardCharsets.UTF_8));
            }
            headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN, failure.getClass().getName().getBytes(StandardCharsets.UTF_8));
            if (failure.getCause() != null) {
                headers.add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                        failure.getCause().getClass().getName().getBytes(StandardCharsets.UTF_8));
            }
            headers.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, String.valueOf(failure.getMessage()).getBytes(StandardCharsets.UTF_8));
            headers.add(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, stackTrace(failure).getBytes(StandardCharsets.UTF_8));
            byte[] keyBytes = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
            return new ConsumerRecord<>(dltTopic, partition, offset, timestamp, TimestampType.CREATE_TIME,
                    keyBytes == null ? -1 : keyBytes.length, value == null ? -1 : value.length, keyBytes, value, headers,
                    Optional.empty());
        }

        DeadLetter deadLetter() {
            return DeadLetter.from(build());
        }
    }
}
