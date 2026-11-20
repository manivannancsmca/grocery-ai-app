package com.grocery.review;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every review event the real operations write to the outbox is also stored as Avro in {@code payload_avro}
 * (Confluent wire format, schema 1.5.0 from grocery-event-schemas, subject
 * {@code review.events-com.grocery.events.review.<EventType>}), and decoding it the way every consumer does
 * (grocery-common's {@link AvroEventDeserializer}) gives back the JSON in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> REVIEW_EVENTS = List.of("ReviewSubmitted", "ReviewModerated", "ReviewReplied",
            "RatingSummaryUpdated");
    /** Schemas 1.8.0: admin settings saves (written here by PlatformSettingsIntegrationTest), key = the setting key. */
    private static final String SETTING_CHANGED = "PlatformSettingChanged";
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z");

    private final JsonAvroConverter converter = new JsonAvroConverter();
    /** The application's own in-memory registry (same mock:// scope), as a consumer would reach it. */
    private final GroceryAvroProperties avro = avroProperties();
    private final SchemaRegistryClient registry = SchemaRegistrySupport.newClient(avro);
    private final AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avro, converter);

    private record OutboxRow(String id, String topic, String aggregateType, String aggregateId, String eventType,
                             int eventVersion, String payload, byte[] payloadAvro) {
    }

    private static GroceryAvroProperties avroProperties() {
        GroceryAvroProperties properties = new GroceryAvroProperties();
        properties.setSchemaRegistryUrl(SCHEMA_REGISTRY_URL);
        return properties;
    }

    @Test
    void everyReviewEventIsPublishedAsAvroThatDecodesToItsJsonPayload() throws Exception {
        Order order = deliveredGroceryOrder(customer, "Tomato", "Onion", "Milk");
        // published at once (+ summary), replied by the store, edited, removed (+ summary)
        JsonNode tomato = review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 4, "Fresh", null);
        mvc.perform(put("/api/store/reviews/{id}/reply", id(tomato))
                        .with(vendor(UUID.randomUUID(), order.storeId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Thank you!\"}"))
                .andExpect(status().isOk());
        edit(customer, id(tomato), 5, "Very fresh", "Lasted a week").andExpect(status().isOk());
        moderate(id(tomato), "remove", "Duplicate", "SUPPORT").andExpect(status().isOk());
        // flagged, then published by an admin (+ summary)
        JsonNode onion = review(customer, order.orderId(), "PRODUCT", order.item(1).itemId(), 2, null,
                "call 9876543210");
        moderate(id(onion), "publish", null, "OPS_ADMIN").andExpect(status().isOk());
        // flagged, then rejected
        JsonNode milk = review(customer, order.orderId(), "PRODUCT", order.item(2).itemId(), 1, null,
                "absolute bullshit");
        moderate(id(milk), "reject", "Abusive", "SUPER_ADMIN").andExpect(status().isOk());

        assertThat(eventTypes(id(tomato))).containsExactly("ReviewSubmitted", "ReviewReplied", "ReviewSubmitted",
                "ReviewModerated");
        assertThat(eventTypes(id(onion))).containsExactly("ReviewSubmitted", "ReviewModerated");
        assertThat(eventTypes(id(milk))).containsExactly("ReviewSubmitted", "ReviewModerated");
        assertThat(eventTypes(order.item(0).itemId())).containsExactly("RatingSummaryUpdated",
                "RatingSummaryUpdated", "RatingSummaryUpdated");
        assertThat(eventTypes(order.item(1).itemId())).containsExactly("RatingSummaryUpdated");
        assertThat(eventTypes(order.item(2).itemId())).isEmpty();

        // every row in the table: these events, and whatever the other test classes wrote
        List<OutboxRow> all = outboxRows();
        assertThat(all).extracting(OutboxRow::eventType).containsAll(REVIEW_EVENTS);
        all.forEach(this::assertAvroIsTheJsonPayload);
    }

    private List<OutboxRow> outboxRows() {
        return jdbc().sql("SELECT id, topic, aggregate_type, aggregate_id, event_type, event_version, payload, "
                        + "payload_avro FROM outbox_event ORDER BY occurred_at, id")
                .query((rs, n) -> new OutboxRow(rs.getString("id"), rs.getString("topic"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                        rs.getInt("event_version"), rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    private void assertAvroIsTheJsonPayload(OutboxRow row) {
        String what = row.eventType() + " " + row.id();
        assertThat(row.topic()).isEqualTo("review.events");
        if (!SETTING_CHANGED.equals(row.eventType())) {
            assertThat(row.eventType()).isIn(REVIEW_EVENTS);
        }
        assertThat(row.aggregateType()).isEqualTo(switch (row.eventType()) {
            case "RatingSummaryUpdated" -> "RatingSummary";
            case SETTING_CHANGED -> "PlatformSetting";
            default -> "Review";
        });
        assertThat(row.eventVersion()).isEqualTo(1);
        byte[] avro = row.payloadAvro();
        assertThat(avro).as("payload_avro of %s", what).isNotNull();
        assertThat(avro.length).as("length of %s", what).isGreaterThan(5);
        assertThat(avro[0]).as("magic byte of %s", what).isZero();

        // the schema id in the wire format is the one registered under <topic>-<record full name>
        int schemaId = ByteBuffer.wrap(avro, 1, 4).getInt();
        String recordName = "com.grocery.events.review." + row.eventType();
        SchemaMetadata registered;
        try {
            registered = registry.getLatestSchemaMetadata("review.events-" + recordName);
        } catch (Exception e) {
            throw new AssertionError("No subject review.events-" + recordName + " in the registry", e);
        }
        assertThat(registered.getId()).as("schema id of %s", what).isEqualTo(schemaId);
        assertThat(new Schema.Parser().parse(registered.getSchema()).getFullName()).isEqualTo(recordName);

        String decoded = deserializer.deserialize(row.topic(), avro);
        assertThat(canonical(converter.readExact(decoded)))
                .as("decoded payload_avro of %s:%n%s%nvs payload:%n%s", what, decoded, row.payload())
                .isEqualTo(canonical(converter.readExact(row.payload())));
        // the key is the review (Review), the target (RatingSummary) or the setting key (PlatformSetting)
        JsonNode payload = converter.readExact(row.payload());
        String key = switch (row.eventType()) {
            case "RatingSummaryUpdated" -> payload.get("targetId").asString();
            case SETTING_CHANGED -> payload.get("settingKey").asString();
            default -> payload.get("reviewId").asString();
        };
        assertThat(row.aggregateId()).isEqualTo(key);
    }

    /**
     * Comparable form of a JSON tree: objects as sorted maps (MySQL's JSON column reorders keys), numbers by value
     * (MySQL and Avro decimals may differ in trailing zeros), ISO instants truncated to microseconds (Avro's
     * timestamp-micros), everything else as is.
     */
    private static Object canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> fields = new TreeMap<>();
            node.properties().forEach(field -> fields.put(field.getKey(), canonical(field.getValue())));
            return fields;
        }
        if (node.isArray()) {
            return node.valueStream().map(AvroOutboxRoundTripIntegrationTest::canonical).toList();
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asString();
        if (ISO_INSTANT.matcher(text).matches()) {
            try {
                return Instant.parse(text).truncatedTo(ChronoUnit.MICROS);
            } catch (DateTimeParseException e) {
                return text;
            }
        }
        return text;
    }
}
