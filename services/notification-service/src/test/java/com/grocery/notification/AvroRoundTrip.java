package com.grocery.notification;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.kafka.core.ConsumerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A test record as it travels (ADR-0011): its JSON payload encoded with the REAL schema of grocery-event-schemas (an
 * unknown or missing field fails here) and decoded by this service's value deserializer, headers kept.
 */
final class AvroRoundTrip {

    private final AvroEventEncoder encoder;
    private final JsonAvroConverter converter;
    private final ConsumerFactory<?, ?> consumerFactory;

    AvroRoundTrip(AvroEventEncoder encoder, JsonAvroConverter converter, ConsumerFactory<?, ?> consumerFactory) {
        this.encoder = encoder;
        this.converter = converter;
        this.consumerFactory = consumerFactory;
    }

    ConsumerRecord<String, String> via(ConsumerRecord<String, String> record) {
        String eventType = EventEnvelope.header(record, "eventType");
        byte[] wire = encoder.encode(record.topic(), eventType, 1, converter.readExact(record.value()));
        assertThat(AvroEventDeserializer.isAvro(wire)).isTrue();
        Deserializer<?> values = consumerFactory.getValueDeserializer();
        Headers headers = new RecordHeaders();
        Object value = values.deserialize(record.topic(), headers, wire);
        assertThat(headers.lastHeader("springDeserializerExceptionValue")).as("deserialization failure").isNull();
        assertThat(value).isInstanceOf(String.class);
        ConsumerRecord<String, String> decoded = new ConsumerRecord<>(record.topic(), 0, 0L, record.key(),
                (String) value);
        record.headers().forEach(header -> decoded.headers().add(header));
        return decoded;
    }
}
