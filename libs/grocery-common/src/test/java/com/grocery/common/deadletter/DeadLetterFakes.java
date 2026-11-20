package com.grocery.common.deadletter;

import com.grocery.common.kafka.InvalidEventException;
import org.apache.kafka.common.header.Headers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A {@link DeadLetterService} without Kafka: in-memory action log, scripted listener, UTF-8 "deserializer". */
final class DeadLetterFakes {

    static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    final DeadLetterIndex index = new DeadLetterIndex(1000);
    final FakeActionLog actionLog = new FakeActionLog();
    final FakeReplayer replayer = new FakeReplayer();
    final DeadLetterService service;

    DeadLetterFakes() {
        DeadLetterProperties properties = new DeadLetterProperties();
        DeadLetterReader reader = new DeadLetterReader(Map.of("bootstrap.servers", "localhost:1"), null, index,
                properties, false);                                              // never started: no catch-up
        service = new DeadLetterService(index, reader, actionLog, replayer, new FakeDecoder(),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    DeadLetter add(DeadLetterRecords.Builder record) {
        DeadLetter deadLetter = record.deadLetter();
        index.add(deadLetter);
        return deadLetter;
    }

    static final class FakeActionLog implements DeadLetterActionLog {
        record Appended(String key, DeadLetterAction action) {
        }

        final List<Appended> appended = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public long append(String key, DeadLetterAction action) {
            if (failing) {
                throw new IllegalStateException("Cannot write to _grocery.dead-letter-actions: broker down");
            }
            appended.add(new Appended(key, action));
            return appended.size() - 1;
        }
    }

    /** The listener: succeeds unless {@link #behaviour} returns an error text. */
    static final class FakeReplayer extends DeadLetterReplayer {
        final List<String> replayed = new CopyOnWriteArrayList<>();
        volatile Function<DeadLetter, String> behaviour = d -> null;

        FakeReplayer() {
            super(null, null);
        }

        @Override
        Outcome replay(DeadLetter deadLetter) {
            replayed.add(deadLetter.id());
            return new Outcome(behaviour.apply(deadLetter));
        }
    }

    /** UTF-8 text; a value starting with byte 0 is "corrupt Avro". */
    static final class FakeDecoder extends DeadLetterPayloadDecoder {
        FakeDecoder() {
            super(null);
        }

        @Override
        public synchronized Object decode(String topic, Headers headers, byte[] value) {
            if (value == null) {
                return null;
            }
            if (value.length > 0 && value[0] == 0) {
                throw new DecodingException(new InvalidEventException("Corrupt Avro value on " + topic));
            }
            return new String(value, StandardCharsets.UTF_8);
        }
    }
}
