package com.grocery.common.avro;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.kafka.TransientFailures;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static com.grocery.common.avro.TestEvents.JSON;
import static com.grocery.common.avro.TestEvents.PAYMENT_CAPTURED;
import static com.grocery.common.avro.TestEvents.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvroEventDeserializerTest {

    private static final String SCOPE = "deserializer-test";

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final AvroEventSchemas schemas = new AvroEventSchemas();
    private final Schema schema = schemas.require(TOPIC, PAYMENT_CAPTURED, 1);
    private final GroceryAvroProperties mock = TestEvents.properties("mock://" + SCOPE, true);

    @AfterEach
    void dropScope() {
        MockSchemaRegistry.dropScope(SCOPE);
    }

    private AvroEventDeserializer deserializer(GroceryAvroProperties properties) {
        return new AvroEventDeserializer(SchemaRegistrySupport.newClient(properties), properties, converter);
    }

    /** What the outbox does: JSON as stored, then Avro from exactly that JSON. */
    private byte[] produce(Object payload) {
        SchemaRegistryAvroEventEncoder encoder = new SchemaRegistryAvroEventEncoder(
                SchemaRegistrySupport.newClient(mock), mock, schemas, converter);
        return encoder.encode(TOPIC, PAYMENT_CAPTURED, 1, converter.readExact(JSON.writeValueAsString(payload)));
    }

    private GenericRecord record(Object payload) {
        return converter.toRecord(converter.readExact(JSON.writeValueAsString(payload)), schema);
    }

    @Test
    void avroBecomesTheJsonTheProducerWrote() {
        for (TestEvents.PaymentCaptured event : new TestEvents.PaymentCaptured[]{
                TestEvents.paymentCaptured(), TestEvents.paymentCapturedWithNulls()}) {
            byte[] wire = produce(event);
            Headers headers = new RecordHeaders();

            String value = deserializer(mock).deserialize(TOPIC, headers, wire);

            assertThat(converter.readExact(value)).isEqualTo(converter.readExact(JSON.writeValueAsString(event)));
            assertThat(headers.lastHeader(AvroEventDeserializer.ORIGINAL_VALUE_HEADER).value()).isSameAs(wire);
        }
    }

    @Test
    void legacyJsonPassesThroughUnchanged() {
        String json = JSON.writeValueAsString(TestEvents.paymentCaptured());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = new RecordHeaders();

        assertThat(deserializer(mock).deserialize(TOPIC, headers, bytes)).isEqualTo(json);
        assertThat(headers.lastHeader(AvroEventDeserializer.ORIGINAL_VALUE_HEADER).value()).isSameAs(bytes);
        assertThat(deserializer(mock).deserialize(TOPIC, "  {\"a\":1}".getBytes(StandardCharsets.UTF_8))).isEqualTo("  {\"a\":1}");
        assertThat(deserializer(mock).deserialize(TOPIC, new byte[]{0, 1})).isEqualTo(new String(new byte[]{0, 1}, StandardCharsets.UTF_8));
    }

    @Test
    void nullStaysNull() {
        Headers headers = new RecordHeaders();
        assertThat(deserializer(mock).deserialize(TOPIC, headers, (byte[]) null)).isNull();
        assertThat(headers.toArray()).isEmpty();
    }

    @Test
    void corruptAvroIsAPoisonMessage() {
        byte[] wire = produce(TestEvents.paymentCaptured());
        AvroEventDeserializer deserializer = deserializer(mock);

        byte[] truncated = Arrays.copyOf(wire, 12);
        byte[] trailing = Arrays.copyOf(wire, wire.length + 3);
        byte[] garbage = Arrays.copyOf(wire, wire.length);
        Arrays.fill(garbage, 5, garbage.length, (byte) 0xFF);

        for (byte[] bad : new byte[][]{truncated, trailing, garbage}) {
            assertThatThrownBy(() -> deserializer.deserialize(TOPIC, new RecordHeaders(), bad))
                    .isInstanceOf(InvalidEventException.class)
                    .hasMessageContaining("Corrupt Avro value on test.events")
                    .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isFalse());
        }
    }

    @Test
    void anUnknownSchemaIdIsAPoisonMessage() {
        byte[] unknownId = TestEvents.wireFormat(424_242, schema, record(TestEvents.paymentCaptured()));

        assertThatThrownBy(() -> deserializer(mock).deserialize(TOPIC, new RecordHeaders(), unknownId))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("Schema id 424242")
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void anUnreachableRegistryIsAnOutage() {
        AvroEventDeserializer deserializer = deserializer(TestEvents.properties("http://127.0.0.1:1", false));
        byte[] wire = TestEvents.wireFormat(7, schema, record(TestEvents.paymentCaptured()));

        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, new RecordHeaders(), wire))
                .isInstanceOf(TransientEventProcessingException.class)
                .hasMessageContaining("Schema Registry unavailable")
                .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isTrue());
        // further records of the same poll fail fast instead of waiting for another timeout each
        long start = System.nanoTime();
        assertThatThrownBy(() -> deserializer.deserialize(TOPIC, new RecordHeaders(), wire))
                .isInstanceOf(TransientEventProcessingException.class)
                .hasMessageContaining("failed less than");
        assertThat(System.nanoTime() - start).isLessThan(200_000_000L);
    }

    @Test
    void registryAnswersAreClassifiedByHttpStatus() throws Exception {
        try (StubSchemaRegistry registry = StubSchemaRegistry.start()) {
            registry.register(7, schema);
            GroceryAvroProperties properties = TestEvents.properties(registry.url(), false);
            byte[] wire = TestEvents.wireFormat(7, schema, record(TestEvents.paymentCaptured()));

            for (int status : new int[]{500, 503, 408, 429}) {
                registry.failNext(1, status);
                assertThatThrownBy(() -> deserializer(properties).deserialize(TOPIC, new RecordHeaders(), wire))
                        .as("HTTP %d", status)
                        .isInstanceOf(TransientEventProcessingException.class);
            }
            for (int status : new int[]{401, 403, 422}) {
                registry.failNext(1, status);
                assertThatThrownBy(() -> deserializer(properties).deserialize(TOPIC, new RecordHeaders(), wire))
                        .as("HTTP %d", status)
                        .isInstanceOf(InvalidEventException.class);
            }
            registry.failNext(0, 503);
            AvroEventDeserializer deserializer = deserializer(properties);
            String json = deserializer.deserialize(TOPIC, new RecordHeaders(), wire);
            assertThat(converter.readExact(json).get("amount").decimalValue()).isEqualByComparingTo("245.00");

            int requests = registry.requests();
            deserializer.deserialize(TOPIC, new RecordHeaders(), wire);
            assertThat(registry.requests()).as("writer schema cached").isEqualTo(requests);

            byte[] unknown = TestEvents.wireFormat(8, schema, record(TestEvents.paymentCaptured()));
            assertThatThrownBy(() -> deserializer.deserialize(TOPIC, new RecordHeaders(), unknown))
                    .isInstanceOf(InvalidEventException.class);
        }
    }

    @Test
    void theClassificationSurvivesTheErrorHandlingDeserializer() {
        byte[] wire = TestEvents.wireFormat(7, schema, record(TestEvents.paymentCaptured()));
        ErrorHandlingDeserializer<String> safe = new ErrorHandlingDeserializer<>(
                deserializer(TestEvents.properties("http://127.0.0.1:1", false)));
        Headers headers = new RecordHeaders();

        assertThat(safe.deserialize(TOPIC, headers, wire)).isNull();             // the consumer keeps running

        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 3L, 0L, TimestampType.CREATE_TIME,
                0, wire.length, "order-1", null, headers, Optional.empty());
        DeserializationException failure = SerializationUtils.getExceptionFromHeader(record,
                "springDeserializerExceptionValue", new LogAccessor(getClass()));
        assertThat(failure).isNotNull();
        assertThat(failure.getData()).isEqualTo(wire);                           // the original bytes, for the DLT
        assertThat(failure.getCause()).isInstanceOf(TransientEventProcessingException.class);
        assertThat(TransientFailures.isTransient(failure)).isTrue();
    }

    @Test
    void roundTripFromPayloadRecordToTheListenersDto() {
        TestEvents.PaymentCaptured sent = TestEvents.paymentCaptured();
        byte[] wire = produce(sent);
        Headers headers = new RecordHeaders();
        headers.add("eventId", "e-1".getBytes(StandardCharsets.UTF_8));
        headers.add("eventType", PAYMENT_CAPTURED.getBytes(StandardCharsets.UTF_8));
        headers.add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        String value = deserializer(mock).deserialize(TOPIC, headers, wire);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 9L, 0L, TimestampType.CREATE_TIME,
                0, wire.length, sent.orderId().toString(), value, headers, Optional.empty());

        EventEnvelope envelope = EventEnvelope.from(record);

        assertThat(envelope.eventType()).isEqualTo(PAYMENT_CAPTURED);
        assertThat(envelope.payloadAs(JSON, TestEvents.PaymentCaptured.class)).isEqualTo(sent);
        TestEvents.PaymentCaptured withNulls = TestEvents.paymentCapturedWithNulls();
        String nullsValue = deserializer(mock).deserialize(TOPIC, new RecordHeaders(), produce(withNulls));
        assertThat(JSON.readValue(nullsValue, TestEvents.PaymentCaptured.class)).isEqualTo(withNulls);
    }

    @Test
    void wireFormatDetection() {
        assertThat(AvroEventDeserializer.isAvro(new byte[]{0, 0, 0, 0, 1})).isTrue();
        assertThat(AvroEventDeserializer.isAvro(new byte[]{0, 0, 0, 1})).isFalse();
        assertThat(AvroEventDeserializer.isAvro("{\"a\":1}".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(AvroEventDeserializer.isAvro(null)).isFalse();
        assertThat(ByteBuffer.wrap(produce(TestEvents.paymentCaptured()), 1, 4).getInt()).isPositive();
    }
}
