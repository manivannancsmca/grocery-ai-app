package com.grocery.pricing;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.id.Ids;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011 inside the service: the real business operations (vendor price changes, the admin approval queue, and
 * the automatic cancellation when catalog.events archives a product) publish every pricing.events type, and each
 * outbox row carries, next to its JSON {@code payload}, a {@code payload_avro} in the Confluent wire format (magic
 * byte 0, schema id, Avro binary) that grocery-common's consumer-side {@link AvroEventDeserializer} turns back into
 * the same JSON. The schema id is the one registered (in the in-memory registry) under
 * {@code <topic>-<record full name>}. The products arrive through the same Avro path (catalog.events
 * ProductUpserted, where {@code unitValue} becomes {@code 500.000}).
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "pricing.events";
    private static final String NAMESPACE = "com.grocery.events.pricing.";
    private static final Set<String> EVENT_TYPES = Set.of("PriceChanged", "PriceChangeRequested",
            "PriceChangeApproved", "PriceChangeRejected", "PriceChangeCancelled");
    /** Schemas 1.8.0: admin settings saves, keyed by the setting key (PlatformSettingsIntegrationTest). */
    private static final String SETTING_CHANGED = "PlatformSettingChanged";

    @Autowired
    SchemaRegistryClient registry;
    @Autowired
    GroceryAvroProperties avroProperties;
    @Autowired
    JsonAvroConverter converter;
    @Autowired
    AvroEventEncoder encoder;

    private final UUID vendorId = UUID.randomUUID();

    private record OutboxRow(String id, String topic, String aggregateId, String eventType, String payload,
                             byte[] payloadAvro) {
    }

    @Test
    void everyEventIsStoredAsAvroThatDecodesBackToItsJsonPayload() throws Exception {
        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
        UUID tea = Ids.newId();
        UUID coffee = Ids.newId();
        for (UUID product : List.of(tea, coffee)) {          // products arrive as Avro, decoded by the consumer
            catalogEvents.on(throughAvro(productUpserted(Ids.newId().toString(), product, vendorId, "Tea " + product,
                    null, "PUBLISHED", 1), deserializer));
        }

        // PriceChanged: the first price, then changes within 20 % (amounts with 0, 1 and 2 decimals)
        putPrice(vendorId, tea, "100.00", "90.00").andExpect(status().isOk());
        putPrice(vendorId, tea, "100", "95.5").andExpect(status().isOk());
        // PriceChangeRequested + PriceChangeApproved (+ PriceChanged)
        String approved = pendingRequest(vendorId, tea, "100.00", "50.00");
        approve(approved).andExpect(status().isOk());
        // PriceChangeRequested + PriceChangeRejected
        String rejected = pendingRequest(vendorId, tea, "100.00", "10.00");
        mvc.perform(post("/api/admin/price-change-requests/{id}/reject", rejected).with(opsAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Below cost\"}"))
                .andExpect(status().isOk());
        // PriceChangeCancelled by the vendor
        String cancelled = pendingRequest(vendorId, tea, "100.00", "99.99");
        mvc.perform(post("/api/vendor/price-change-requests/{id}/cancel", cancelled).with(vendor(vendorId)))
                .andExpect(status().isOk());
        // PriceChangeCancelled automatically (cancelledBy null): catalog.events archives the product
        putPrice(vendorId, coffee, "250.00", "240.00").andExpect(status().isOk());
        pendingRequest(vendorId, coffee, "250.00", "120.00");
        catalogEvents.on(throughAvro(productUpserted(Ids.newId().toString(), coffee, vendorId, "Coffee", null,
                "ARCHIVED", 2), deserializer));

        Set<String> aggregates = new HashSet<>(List.of(tea.toString(), coffee.toString()));
        List<OutboxRow> rows = outboxRows();
        List<OutboxRow> ours = rows.stream().filter(row -> aggregates.contains(row.aggregateId())).toList();
        assertThat(ours.stream().map(OutboxRow::eventType).collect(Collectors.toSet()))
                .as("every event type pricing-service publishes").isEqualTo(EVENT_TYPES);
        assertThat(ours).filteredOn(row -> row.eventType().equals("PriceChangeCancelled"))
                .extracting(row -> converter.readExact(row.payload()).get("cancelledBy").isNull())
                .containsExactlyInAnyOrder(false, true);

        Set<String> subjects = new TreeSet<>();
        for (OutboxRow row : rows) {                       // every row of the shared database, not only ours
            String event = row.eventType() + " " + row.id();
            assertThat(row.topic()).as(event).isEqualTo(TOPIC);
            assertThat(row.payloadAvro()).as("payload_avro of " + event).isNotNull().hasSizeGreaterThan(5);
            assertThat(row.payloadAvro()[0]).as("magic byte of " + event).isZero();

            String decoded = deserializer.deserialize(row.topic(), row.payloadAvro());
            assertSameJson(converter.readExact(row.payload()), converter.readExact(decoded), event);

            int schemaId = ByteBuffer.wrap(row.payloadAvro(), 1, 4).getInt();
            ParsedSchema schema = registry.getSchemaBySubjectAndId(null, schemaId);
            String subject = TOPIC + "-" + NAMESPACE + row.eventType();
            assertThat(schema.name()).as(event).isEqualTo(NAMESPACE + row.eventType());
            assertThat(registry.getAllSubjects()).as(event).contains(subject);
            assertThat(registry.getId(subject, schema)).as("schema id of " + event + " under " + subject)
                    .isEqualTo(schemaId);
            subjects.add(subject);
        }
        // admin settings saves (PlatformSettingsIntegrationTest) are pricing.events too, checked like the rest above
        assertThat(subjects).filteredOn(subject -> !subject.equals(TOPIC + "-" + NAMESPACE + SETTING_CHANGED))
                .containsExactlyInAnyOrderElementsOf(
                        EVENT_TYPES.stream().map(type -> TOPIC + "-" + NAMESPACE + type).toList());
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** The record's JSON value as the producer's outbox encodes it and this service's consumer decodes it. */
    private ConsumerRecord<String, String> throughAvro(ConsumerRecord<String, String> record,
                                                       AvroEventDeserializer deserializer) {
        String eventType = new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8);
        byte[] wire = encoder.encode(record.topic(), eventType, 1, converter.readExact(record.value()));
        assertThat(wire[0]).isZero();
        String decoded = deserializer.deserialize(record.topic(), wire);
        assertThat(converter.readExact(decoded).get("unitValue").decimalValue()).isEqualByComparingTo("500");
        ConsumerRecord<String, String> viaAvro = new ConsumerRecord<>(record.topic(), record.partition(),
                record.offset(), record.key(), decoded);
        for (Header header : record.headers()) {
            viaAvro.headers().add(header);
        }
        return viaAvro;
    }

    private List<OutboxRow> outboxRows() {
        return jdbc()
                .sql("SELECT id, topic, aggregate_id, event_type, payload, payload_avro FROM outbox_event ORDER BY occurred_at, id")
                .query((rs, n) -> new OutboxRow(rs.getString("id"), rs.getString("topic"), rs.getString("aggregate_id"),
                        rs.getString("event_type"), rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    /**
     * Semantic JSON equality: numbers compared numerically (MySQL's JSON column and Avro decimals may render the
     * same amount as {@code 95.5} or {@code 95.50}), timestamps as instants truncated to microseconds (Avro's
     * timestamp-micros), a missing property equal to an explicit null.
     */
    static void assertSameJson(JsonNode expected, JsonNode actual, String path) {
        boolean expectedNull = expected == null || expected.isNull() || expected.isMissingNode();
        boolean actualNull = actual == null || actual.isNull() || actual.isMissingNode();
        if (expectedNull || actualNull) {
            assertThat(actualNull).as(path + ": " + expected + " vs " + actual).isEqualTo(expectedNull);
            return;
        }
        if (expected.isNumber()) {
            assertThat(actual.isNumber()).as(path + " is a number: " + actual).isTrue();
            assertThat(actual.decimalValue()).as(path).isEqualByComparingTo(expected.decimalValue());
        } else if (expected.isString()) {
            assertThat(actual.isString()).as(path + " is a string: " + actual).isTrue();
            if (!expected.asString().equals(actual.asString())) {
                Instant expectedInstant = instant(expected.asString());
                Instant actualInstant = instant(actual.asString());
                assertThat(expectedInstant != null && actualInstant != null)
                        .as(path + ": " + expected + " vs " + actual).isTrue();
                assertThat(actualInstant.truncatedTo(ChronoUnit.MICROS)).as(path)
                        .isEqualTo(expectedInstant.truncatedTo(ChronoUnit.MICROS));
            }
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path + " is an array: " + actual).isTrue();
            assertThat(actual.size()).as(path + " size").isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameJson(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        } else if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + " is an object: " + actual).isTrue();
            Set<String> names = new TreeSet<>();
            expected.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            actual.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            for (String name : names) {
                assertSameJson(expected.get(name), actual.get(name), path + "." + name);
            }
        } else {
            assertThat(actual).as(path).isEqualTo(expected);
        }
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
