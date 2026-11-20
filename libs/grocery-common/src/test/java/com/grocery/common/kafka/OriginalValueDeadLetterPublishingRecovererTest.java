package com.grocery.common.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.mock.MockProducerFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OriginalValueDeadLetterPublishingRecovererTest {

    private final MockProducer<Object, Object> producer = new MockProducer<>(true, null,
            OriginalValueDeadLetterPublishingRecoverer.bytesOrStringSerializer(),
            OriginalValueDeadLetterPublishingRecoverer.bytesOrStringSerializer());
    private final OriginalValueDeadLetterPublishingRecoverer recoverer = new OriginalValueDeadLetterPublishingRecoverer(
            new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)),
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

    private static ConsumerRecord<String, String> record(String value, byte[] original) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.events", 2, 41L, "order-1", value);
        record.headers().add("eventId", "e-1".getBytes(StandardCharsets.UTF_8));
        if (original != null) {
            record.headers().add(OriginalValueDeadLetterPublishingRecoverer.ORIGINAL_VALUE_HEADER, original);
        }
        return record;
    }

    private ProducerRecord<Object, Object> deadLetter() {
        assertThat(producer.history()).hasSize(1);
        return producer.history().getFirst();
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void listenerFailuresAreDeadLetteredWithTheOriginalBytesNotTheDecodedJson() {
        byte[] avro = {0, 0, 0, 0, 7, 2, 4, 6};

        recoverer.accept(record("{\"orderId\":\"o-1\"}", avro), null,
                new ListenerExecutionFailedException("listener failed", new InvalidEventException("lacks orderId")));

        ProducerRecord<Object, Object> dead = deadLetter();
        assertThat(dead.topic()).isEqualTo("payment.events.DLT");
        assertThat(dead.key()).isEqualTo("order-1");
        assertThat(dead.value()).isEqualTo(avro);
        assertThat(dead.headers().lastHeader(OriginalValueDeadLetterPublishingRecoverer.ORIGINAL_VALUE_HEADER)).isNull();
        assertThat(header(dead, "eventId")).isEqualTo("e-1");                     // original headers kept
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo("payment.events");
        assertThat(header(dead, "kafka_dlt-original-offset")).isNotNull();
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).isNotNull();
        assertThat(header(dead, "kafka_dlt-exception-stacktrace"))
                .contains(InvalidEventException.class.getName() + ": lacks orderId");
    }

    @Test
    void withoutTheHelperHeaderTheValueIsPublishedAsItIs() {
        recoverer.accept(record("{\"legacy\":true}", null), null, new IllegalStateException("bug"));

        assertThat(deadLetter().value()).isEqualTo("{\"legacy\":true}");
    }

    @Test
    void tombstonesStayTombstones() {
        recoverer.accept(record(null, null), null, new InvalidEventException("Event e-1 has no payload"));

        assertThat(deadLetter().value()).isNull();
    }

    @Test
    void theSerializerAcceptsBytesStringsAndNull() {
        var serializer = OriginalValueDeadLetterPublishingRecoverer.bytesOrStringSerializer();
        assertThat(serializer.serialize("t", new byte[]{1, 2})).containsExactly(1, 2);
        assertThat(serializer.serialize("t", "é")).isEqualTo("é".getBytes(StandardCharsets.UTF_8));
        assertThat(serializer.serialize("t", null)).isNull();
    }
}
