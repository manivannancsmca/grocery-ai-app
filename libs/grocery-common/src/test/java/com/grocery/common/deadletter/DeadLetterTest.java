package com.grocery.common.deadletter;

import com.grocery.common.kafka.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetterTest {

    @Test
    void readsTheOriginAndTheEventFromTheHeaders() {
        DeadLetter deadLetter = DeadLetterRecords.record("vendor.events.DLT", 0, 12).deadLetter();

        assertThat(deadLetter.id()).isEqualTo("vendor.events.DLT:0:12");
        assertThat(deadLetter.actionKey()).isEqualTo("test-service.events|vendor.events.DLT|0|12");
        assertThat(deadLetter.originalTopic()).isEqualTo("vendor.events");
        assertThat(deadLetter.originalPartition()).isEqualTo(2);
        assertThat(deadLetter.originalOffset()).isEqualTo(845L);
        assertThat(deadLetter.originalTimestamp()).isEqualTo(1_762_855_595_000L);
        assertThat(deadLetter.originalTimestampType()).isEqualTo(TimestampType.CREATE_TIME);
        assertThat(deadLetter.consumerGroup()).isEqualTo("test-service.events");
        assertThat(deadLetter.keyText()).isEqualTo("order-1");
        assertThat(deadLetter.eventId()).isEqualTo("0193a9a4-0000-7000-8000-000000000001");
        assertThat(deadLetter.eventType()).isEqualTo("OrderCreated");
        assertThat(deadLetter.eventVersion()).isEqualTo(1);
        assertThat(deadLetter.occurredAt()).isEqualTo(Instant.parse("2025-11-11T10:05:00.250123Z"));
        assertThat(deadLetter.deadLetteredAt()).isEqualTo(Instant.ofEpochMilli(1_762_855_600_000L));
    }

    @Test
    void reportsTheListenersExceptionNotTheContainersWrapper() {
        DeadLetter deadLetter = DeadLetterRecords.record("vendor.events.DLT", 0, 1)
                .failure(new ListenerExecutionFailedException("Listener method 'on' threw exception", "g",
                        new InvalidEventException("Event e-1 has no payload")))
                .deadLetter();

        assertThat(deadLetter.exceptionClass()).isEqualTo(InvalidEventException.class.getName());
        assertThat(deadLetter.exceptionMessage()).isEqualTo("Event e-1 has no payload");
        assertThat(deadLetter.stackTrace()).startsWith(ListenerExecutionFailedException.class.getName());
    }

    @Test
    void reportsTheDeserializersFailureForAnUndecodableValue() {
        DeserializationException deserialization = new DeserializationException("failed to deserialize",
                new byte[]{0, 0, 0, 0, 11}, false,
                new InvalidEventException("Corrupt Avro value on it.events (schema id 11): Malformed data.\nLength is negative"));
        DeadLetter deadLetter = DeadLetterRecords.record("it.events.DLT", 0, 3)
                .failure(new ListenerExecutionFailedException("Listener failed", "g", deserialization))
                .deadLetter();

        assertThat(deadLetter.exceptionClass()).isEqualTo(InvalidEventException.class.getName());
        assertThat(deadLetter.exceptionMessage()).isEqualTo("Corrupt Avro value on it.events (schema id 11): "
                + "Malformed data.\nLength is negative");
    }

    @Test
    void fallsBackToTheExceptionHeadersWithoutAStackTrace() {
        String[] failure = DeadLetter.failure(null, DeadLetterRecords.record("a.DLT", 0, 0)
                .failure(new ListenerExecutionFailedException("Listener failed", "g", new IllegalStateException("x")))
                .build().headers());

        assertThat(failure).containsExactly(IllegalStateException.class.getName(), "Listener failed");
    }

    @Test
    void keepsTheOriginalHeadersWithoutTheDeadLetterOnes() {
        DeadLetter deadLetter = DeadLetterRecords.record("vendor.events.DLT", 0, 12).deadLetter();

        List<String> names = new ArrayList<>();
        for (Header header : deadLetter.headersCopy()) {
            names.add(header.key());
        }
        assertThat(names).containsExactly("eventId", "eventType", "eventVersion", "occurredAt", "correlationId");
        deadLetter.headersCopy().add("x", new byte[0]);                       // a copy every time
        assertThat(deadLetter.headersCopy().lastHeader("x")).isNull();
    }

    @Test
    void truncatesTheStackTraceToFourKilobytes() {
        RuntimeException deep = new RuntimeException("deep");
        for (int i = 0; i < 50; i++) {
            deep = new RuntimeException("level " + i, deep);
        }
        DeadLetter deadLetter = DeadLetterRecords.record("a.DLT", 0, 0).failure(deep).deadLetter();

        assertThat(deadLetter.stackTrace()).hasSize(DeadLetter.STACK_TRACE_LIMIT);
        assertThat(deadLetter.exceptionClass()).isEqualTo(RuntimeException.class.getName());
        assertThat(deadLetter.exceptionMessage()).startsWith("level 49");
    }

    @Test
    void aRecordWithoutConsumerGroupBelongsToNoService() {
        assertThat(DeadLetterRecords.record("a.DLT", 0, 0).noGroup().deadLetter()).isNull();
    }

    @Test
    void parsesIds() {
        assertThat(DeadLetter.partitionOf("vendor.events.DLT:0:12")).isEqualTo(new TopicPartition("vendor.events.DLT", 0));
        assertThat(DeadLetter.partitionOf("a:b:c")).isNull();
        assertThat(DeadLetter.partitionOf("vendor.events.DLT:12")).isNull();
        assertThat(DeadLetter.partitionOf(null)).isNull();
    }

    @Test
    void parsesOccurredAtAsMicrosOrIso() {
        assertThat(DeadLetter.occurredAt("1762855500250123")).isEqualTo(Instant.parse("2025-11-11T10:05:00.250123Z"));
        assertThat(DeadLetter.occurredAt("2026-11-11T10:05:00Z")).isEqualTo(Instant.parse("2026-11-11T10:05:00Z"));
        assertThat(DeadLetter.occurredAt("yesterday")).isNull();
    }

    @Test
    void actionsRoundTripAsJsonAndTolerateUnknownFields() {
        DeadLetterAction action = new DeadLetterAction(DeadLetterStatus.REPLAY_FAILED, 2, "boom", "u-1",
                Instant.parse("2026-11-13T10:00:00.123Z"), null);

        String json = new String(action.toJson(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"status\":\"REPLAY_FAILED\"", "\"attempts\":2", "\"lastError\":\"boom\"",
                "\"actor\":\"u-1\"", "\"at\":\"2026-11-13T10:00:00.123Z\"");
        assertThat(DeadLetterAction.fromJson(action.toJson())).isEqualTo(action);
        assertThat(DeadLetterAction.fromJson("{\"status\":\"DISCARDED\",\"future\":1}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new DeadLetterAction(DeadLetterStatus.DISCARDED, 0, null, null, null, null));
        assertThatThrownBy(() -> DeadLetterAction.fromJson("{\"status\":\"LOST\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theIndexKeepsTheNewestStateAndForgetsExpiredRecords() {
        DeadLetterIndex index = new DeadLetterIndex(100);
        DeadLetter first = DeadLetterRecords.record("a.DLT", 0, 1).deadLetter();
        DeadLetter second = DeadLetterRecords.record("a.DLT", 0, 2).timestamp(1_762_855_700_000L).deadLetter();
        index.add(first);
        index.add(second);
        DeadLetterAction replayed = new DeadLetterAction(DeadLetterStatus.REPLAYED, 1, null, "u", Instant.now(), null);
        DeadLetterAction failed = new DeadLetterAction(DeadLetterStatus.REPLAY_FAILED, 1, "x", "u", Instant.now(), null);

        index.applyAction(first.actionKey(), replayed, 7);
        index.applyAction(first.actionKey(), failed, 6);                       // older record read late: ignored
        assertThat(index.state(first)).isEqualTo(replayed);
        assertThat(index.state(second)).isEqualTo(DeadLetterAction.PENDING);
        assertThat(index.newestFirst(d -> true)).extracting(DeadLetter::id).containsExactly("a.DLT:0:2", "a.DLT:0:1");

        index.evictBelow("a.DLT", 0, 2);
        assertThat(index.find("a.DLT:0:1")).isEmpty();
        assertThat(index.state(first)).isEqualTo(DeadLetterAction.PENDING);
        assertThat(index.find("a.DLT:0:2")).isPresent();
    }

    @Test
    void theIndexIsBoundedAndDropsClosedRecordsFirst() {
        DeadLetterIndex index = new DeadLetterIndex(5);
        List<DeadLetter> all = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            DeadLetter deadLetter = DeadLetterRecords.record("a.DLT", 0, i).timestamp(1_000_000L + i).deadLetter();
            all.add(deadLetter);
            if (i == 15) {
                index.applyAction(all.get(14).actionKey(),
                        new DeadLetterAction(DeadLetterStatus.DISCARDED, 0, null, "u", Instant.now(), "r"), 1);
            }
            index.add(deadLetter);
        }
        assertThat(index.size()).isEqualTo(5);
        assertThat(index.newestFirst(d -> true)).extracting(DeadLetter::dltOffset).containsExactly(15L, 13L, 12L, 11L, 10L);
    }
}
