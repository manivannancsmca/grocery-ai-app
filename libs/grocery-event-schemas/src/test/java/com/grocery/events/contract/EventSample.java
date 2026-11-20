package com.grocery.events.contract;

import com.grocery.events.contract.EventSampleGenerator.Variant;
import com.grocery.events.contract.EventSchemaCatalog.EventSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.subject.TopicRecordNameStrategy;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One generated event: its schema, the record (raw Avro values) and how it travels, as Debezium's outbox router delivers
 * it: value in the Confluent wire format (magic byte 0, 4-byte schema id from the Schema Registry, Avro binary), key =
 * the documented key field, headers {@code eventId}, {@code eventType}, {@code eventVersion}, {@code aggregateType},
 * {@code correlationId}, {@code causationId}, {@code occurredAt} (epoch microseconds, as the router writes the
 * {@code DATETIME(6)} column) and {@code traceparent} (W3C).
 */
public final class EventSample {

    private final EventSchema schema;
    private final Variant variant;
    private final GenericRecord record;
    private final String eventId = UUID.randomUUID().toString();
    private final String correlationId = UUID.randomUUID().toString();
    private final Instant occurredAt = Instant.now();

    private EventSample(EventSchema schema, Variant variant, GenericRecord record) {
        this.schema = schema;
        this.variant = variant;
        this.record = record;
    }

    public static EventSample of(EventSchema schema, Variant variant, Map<String, ?> overrides) {
        return new EventSample(schema, variant, EventSampleGenerator.generate(schema.schema(), variant, overrides));
    }

    public static EventSample of(EventSchema schema, Variant variant) {
        return of(schema, variant, Map.of());
    }

    /**
     * A sample of another event type (v1, every optional field set), e.g. to seed the related state a consumer needs
     * through its own listener: {@code EventSample.of("catalog.events", "ProductUpserted", Map.of("productId", id))}.
     */
    public static EventSample of(String topic, String eventType, Map<String, ?> values) {
        return of(EventSchemaCatalog.fromClasspath().get(topic, eventType), Variant.FULL, values);
    }

    public EventSchema schema() {
        return schema;
    }

    public Variant variant() {
        return variant;
    }

    /** The generated record (raw Avro representation). */
    public GenericRecord record() {
        return record;
    }

    public String eventId() {
        return eventId;
    }

    /** A top-level field's value as text (a uuid field gives its UUID string), or null. */
    public String text(String field) {
        Object value = record.getSchema().getField(field) == null ? null : record.get(field);
        return value == null ? null : value.toString();
    }

    /** A top-level uuid field. */
    public Optional<UUID> uuid(String field) {
        return Optional.ofNullable(text(field)).map(UUID::fromString);
    }

    /** Every non-null value of fields with this name at any depth (e.g. {@code lines[].productId}), as text. */
    public List<String> texts(String field) {
        List<String> values = new ArrayList<>();
        collect(record, record.getSchema(), field, values);
        return values;
    }

    private static void collect(Object value, Schema schema, String field, List<String> values) {
        if (value == null) {
            return;
        }
        switch (schema.getType()) {
            case UNION -> collect(value, schema.getTypes().get(GenericData.get().resolveUnion(schema, value)), field,
                    values);
            case RECORD -> {
                GenericRecord nested = (GenericRecord) value;
                for (Schema.Field f : schema.getFields()) {
                    Object fieldValue = nested.get(f.pos());
                    if (f.name().equals(field) && fieldValue != null && f.schema().getType() != Schema.Type.RECORD) {
                        values.add(fieldValue.toString());
                    }
                    collect(fieldValue, f.schema(), field, values);
                }
            }
            case ARRAY -> ((Collection<?>) value).forEach(element -> collect(element, schema.getElementType(), field,
                    values));
            case MAP -> ((Map<?, ?>) value).values().forEach(element -> collect(element, schema.getValueType(), field,
                    values));
            default -> {
            }
        }
    }

    /** The message key: the documented key field's value, else the first top-level uuid, else the event id. */
    public String key() {
        if (schema.keyField() != null && record.get(schema.keyField()) != null) {
            return record.get(schema.keyField()).toString();
        }
        return record.getSchema().getFields().stream()
                .filter(field -> field.schema().getLogicalType() != null
                        && "uuid".equals(field.schema().getLogicalType().getName()))
                .map(field -> record.get(field.pos()).toString())
                .findFirst().orElse(eventId);
    }

    /** The record as JSON text with decimals, timestamps and dates readable (for failure messages). */
    public String describe() {
        return render(record, record.getSchema());
    }

    private static String render(Object value, Schema schema) {
        if (value == null) {
            return "null";
        }
        LogicalType logical = schema.getLogicalType();
        String logicalName = logical == null ? "" : logical.getName();
        return switch (schema.getType()) {
            case UNION -> render(value, schema.getTypes().get(GenericData.get().resolveUnion(schema, value)));
            case RECORD -> {
                GenericRecord nested = (GenericRecord) value;
                StringJoiner fields = new StringJoiner(", ", "{", "}");
                for (Schema.Field field : schema.getFields()) {
                    fields.add("\"" + field.name() + "\": " + render(nested.get(field.pos()), field.schema()));
                }
                yield fields.toString();
            }
            case ARRAY -> {
                StringJoiner elements = new StringJoiner(", ", "[", "]");
                ((Collection<?>) value).forEach(element -> elements.add(render(element, schema.getElementType())));
                yield elements.toString();
            }
            case MAP -> {
                StringJoiner entries = new StringJoiner(", ", "{", "}");
                ((Map<?, ?>) value).forEach((k, v) -> entries.add("\"" + k + "\": " + render(v, schema.getValueType())));
                yield entries.toString();
            }
            case BYTES -> logical instanceof LogicalTypes.Decimal
                    ? EventSampleGenerator.decimalOf((ByteBuffer) value, schema).toPlainString()
                    : "\"" + Base64.getEncoder().encodeToString(bytes((ByteBuffer) value)) + "\"";
            case FIXED -> "\"" + HexFormat.of().formatHex(((GenericFixed) value).bytes()) + "\"";
            case LONG -> logicalName.startsWith("timestamp-") ? "\"" + instant((Long) value, logicalName) + "\""
                    : value.toString();
            case INT -> logical instanceof LogicalTypes.Date
                    ? "\"" + LocalDate.ofEpochDay((Integer) value) + "\"" : value.toString();
            case STRING, ENUM -> "\"" + value + "\"";
            case BOOLEAN, FLOAT, DOUBLE, NULL -> value.toString();
        };
    }

    private static Instant instant(long epoch, String logicalType) {
        if (logicalType.endsWith("millis")) {
            return Instant.ofEpochMilli(epoch);
        }
        if (logicalType.endsWith("nanos")) {
            return Instant.ofEpochSecond(Math.floorDiv(epoch, 1_000_000_000L), Math.floorMod(epoch, 1_000_000_000L));
        }
        return Instant.ofEpochSecond(Math.floorDiv(epoch, 1_000_000L), Math.floorMod(epoch, 1_000_000L) * 1_000L);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] copy = new byte[buffer.remaining()];
        buffer.duplicate().get(copy);
        return copy;
    }

    /**
     * The value in the Confluent wire format, written by Confluent's own KafkaAvroSerializer with the platform's
     * settings (TopicRecordNameStrategy, raw values, never the latest version). The schema is registered in
     * {@code registry} (a {@code mock://} client in tests) if it is not there yet.
     */
    public byte[] serialize(SchemaRegistryClient registry) {
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", "mock://event-contract");          // required; the client is passed in
        config.put("value.subject.name.strategy", TopicRecordNameStrategy.class.getName());
        config.put("auto.register.schemas", true);
        config.put("use.latest.version", false);
        config.put("normalize.schemas", false);
        config.put("avro.use.logical.type.converters", false);
        // not closed: closing a serde closes the registry client passed in, which is the service's shared bean
        @SuppressWarnings("resource")
        KafkaAvroSerializer serializer = new KafkaAvroSerializer(registry, config);
        return serializer.serialize(schema.topic(), record);
    }

    /** The headers Debezium's outbox router (or the direct location publisher) puts on the record. */
    public Headers headers() {
        RecordHeaders headers = new RecordHeaders();
        header(headers, "eventId", eventId);
        header(headers, "eventType", schema.eventType());
        header(headers, "eventVersion", Integer.toString(schema.version()));
        header(headers, "aggregateType", schema.aggregateType());
        header(headers, "correlationId", correlationId);
        header(headers, "causationId", UUID.randomUUID().toString());
        header(headers, "occurredAt", Long.toString(occurredAt.getEpochSecond() * 1_000_000L
                + occurredAt.getNano() / 1_000));
        HexFormat hex = HexFormat.of();
        byte[] trace = new byte[16];
        byte[] span = new byte[8];
        ThreadLocalRandom.current().nextBytes(trace);
        ThreadLocalRandom.current().nextBytes(span);
        header(headers, "traceparent", "00-" + hex.formatHex(trace) + "-" + hex.formatHex(span) + "-01");
        return headers;
    }

    private static void header(Headers headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** The record as a consumer's poll returns it before value deserialization. */
    public ConsumerRecord<String, byte[]> rawRecord(SchemaRegistryClient registry) {
        byte[] value = serialize(registry);
        Headers headers = headers();
        return new ConsumerRecord<>(schema.topic(), 0, 0L, occurredAt.toEpochMilli(), TimestampType.CREATE_TIME,
                ConsumerRecord.NULL_SIZE, value.length, key(), value, headers, Optional.empty());
    }

    /**
     * The record as the service's listener receives it: the wire-format bytes run through the service's own value
     * deserializer (grocery-common's {@code AvroEventDeserializer}: Avro → the producer's JSON).
     *
     * @throws AssertionError when the deserializer returns null or reports a failure in a header (Spring Kafka's
     *                        {@code ErrorHandlingDeserializer}), i.e. the record would go to the DLT unread
     */
    public ConsumerRecord<String, String> consumerRecord(SchemaRegistryClient registry,
                                                         Deserializer<String> valueDeserializer) {
        ConsumerRecord<String, byte[]> raw = rawRecord(registry);
        String value = valueDeserializer.deserialize(raw.topic(), raw.headers(), raw.value());
        for (Header header : raw.headers()) {
            if (header.key().startsWith("springDeserializerException")) {
                throw new AssertionError(id() + ": the value deserializer failed (header " + header.key()
                        + "); the record would be dead-lettered unread. Payload: " + describe());
            }
        }
        if (value == null) {
            throw new AssertionError(id() + ": the value deserializer returned null. Payload: " + describe());
        }
        return new ConsumerRecord<>(raw.topic(), raw.partition(), raw.offset(), raw.timestamp(), raw.timestampType(),
                raw.serializedKeySize(), raw.serializedValueSize(), raw.key(), value, raw.headers(),
                raw.leaderEpoch());
    }

    /** {@code <topic>/<EventType>[.v<n>] (<variant>)}. */
    public String id() {
        return schema.id() + " (" + variant.label() + ")";
    }

    @Override
    public String toString() {
        return id();
    }
}
