package com.grocery.common.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test payloads for the test schemas under src/test/resources/avro/test.events. */
public final class TestEvents {

    public static final String TOPIC = "test.events";
    public static final String PAYMENT_CAPTURED = "PaymentCaptured";
    public static final String SUBJECT_V1 = "test.events-com.grocery.events.test.PaymentCaptured";
    public static final String SUBJECT_V2 = "test.events-com.grocery.events.test.PaymentCapturedV2";
    /** Like Spring Boot's mapper for these payloads (Jackson 3 defaults: ISO dates, nulls included). */
    public static final JsonMapper JSON = JsonMapper.builder().build();

    public enum Method { UPI, CARD }

    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    /** A producer's payload class: what services hand to the outbox. */
    public record PaymentCaptured(UUID orderId, UUID paymentId, BigDecimal amount, String currency, Method method,
                                  Instant capturedAt, boolean late, List<Item> items, LocalDate deliveryDate,
                                  Map<String, Object> attributes) {
    }

    private TestEvents() {
    }

    public static PaymentCaptured paymentCaptured() {
        return new PaymentCaptured(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("245.00"), "INR", Method.UPI,
                Instant.parse("2026-11-11T10:05:00.250123Z"), false,
                List.of(new Item(UUID.randomUUID(), 2, new BigDecimal("50.00")),
                        new Item(UUID.randomUUID(), 1, new BigDecimal("145.00"))),
                LocalDate.parse("2026-11-12"), Map.of("gateway", "razorpay", "attempt", 2));
    }

    /** A payload with the optional fields null. */
    public static PaymentCaptured paymentCapturedWithNulls() {
        return new PaymentCaptured(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("0.50"), "USD", null,
                Instant.parse("2026-11-11T10:05:00Z"), true, List.of(), null, null);
    }

    public static GroceryAvroProperties properties(String registryUrl, boolean autoRegister) {
        GroceryAvroProperties properties = new GroceryAvroProperties();
        properties.setSchemaRegistryUrl(registryUrl);
        properties.setAutoRegisterSchemas(autoRegister);
        properties.getClient().put("max.retries", "0");             // fail fast in tests
        return properties;
    }

    /** Confluent wire format written by hand: magic byte 0, schema id, Avro binary. */
    public static byte[] wireFormat(int schemaId, Schema schema, GenericRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0);
            out.write(ByteBuffer.allocate(4).putInt(schemaId).array());
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(schema).write(record, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
