package com.grocery.catalog;

import com.grocery.catalog.event.VendorEventsListener;
import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.id.Ids;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011 inside the service: the real business operations publish every catalog.events type, and each outbox row
 * carries, next to its JSON {@code payload}, a {@code payload_avro} in the Confluent wire format (magic byte 0, schema
 * id, Avro binary) that grocery-common's consumer-side {@link AvroEventDeserializer} turns back into the same JSON.
 * The schema id is the one registered (in the in-memory registry) under {@code <topic>-<record full name>}.
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "catalog.events";
    private static final String NAMESPACE = "com.grocery.events.catalog.";
    private static final Set<String> EVENT_TYPES = Set.of("ProductUpserted", "CategoryChanged");

    @Autowired
    SchemaRegistryClient registry;
    @Autowired
    GroceryAvroProperties avroProperties;
    @Autowired
    JsonAvroConverter converter;
    @Autowired
    VendorEventsListener vendorEvents;

    private record OutboxRow(String id, String topic, String aggregateId, String eventType, String payload,
                             byte[] payloadAvro) {
    }

    @Test
    void everyEventIsStoredAsAvroThatDecodesBackToItsJsonPayload() throws Exception {
        UUID vendorId = UUID.randomUUID();
        Set<String> aggregates = new HashSet<>();

        // CategoryChanged: a root, a child with every optional field, an edit, (de)activation and a deletion
        String rootId = createCategory(unique("Staples"), null);
        String leafId = categoryRequest(post("/api/admin/categories"), unique("Rice"), rootId, 3);
        String emptyId = createCategory(unique("Seasonal"), rootId);
        categoryRequest(put("/api/admin/categories/{id}", leafId), unique("Rice & Grains"), rootId, 4);
        aggregates.addAll(List.of(rootId, leafId, emptyId));

        // ProductUpserted: a complete product (brand, attributes, 3-decimal unit value, published) and a minimal one
        String full = createProduct(vendorId, """
                {"name":"Basmati Rice","description":"Aged long grain","categoryId":"%s","brandName":"%s",
                 "unitValue":1.5,"unitOfMeasure":"KG","sku":"BAS-1500","barcode":"8901234567890","foodType":"VEG",
                 "tags":["Rice","premium"],"attributes":[{"name":"Origin","value":"Dehradun"}],
                 "imageUrls":["https://img.example.com/basmati.jpg"]}""".formatted(leafId, unique("Royal")));
        String minimal = createProduct(vendorId, """
                {"name":"Loose Onions","categoryId":"%s","unitValue":6,"unitOfMeasure":"PCS"}""".formatted(leafId));
        aggregates.addAll(List.of(full, minimal));
        mvc.perform(post("/api/vendor/products/{id}/publish", full).with(vendor(vendorId))).andExpect(status().isOk());
        mvc.perform(put("/api/vendor/products/{id}", full).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Basmati Rice","categoryId":"%s","unitValue":0.125,"unitOfMeasure":"KG",
                                 "foodType":"","sku":""}""".formatted(leafId)))
                .andExpect(status().isOk());

        // Re-snapshots: vendor name and suspension (vendor.events), category deactivation and reactivation
        vendorEvents.on(vendorEvent(vendorId, "VendorApproved", "Green Basket"));
        vendorEvents.on(vendorEvent(vendorId, "VendorSuspended", "Green Basket"));
        mvc.perform(post("/api/admin/categories/{id}/deactivate", rootId).with(opsAdmin())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/categories/{id}/activate", rootId).with(opsAdmin())).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/categories/{id}", emptyId).with(opsAdmin())).andExpect(status().isNoContent());
        // Unpublish and archive
        mvc.perform(post("/api/vendor/products/{id}/unpublish", full).with(vendor(vendorId))).andExpect(status().isOk());
        mvc.perform(delete("/api/vendor/products/{id}", full).with(vendor(vendorId))).andExpect(status().isNoContent());

        List<OutboxRow> rows = outboxRows();
        List<OutboxRow> ours = rows.stream().filter(row -> aggregates.contains(row.aggregateId())).toList();
        assertThat(ours.stream().map(OutboxRow::eventType).collect(Collectors.toSet()))
                .as("every event type catalog-command-service publishes").isEqualTo(EVENT_TYPES);
        assertThat(ours).filteredOn(row -> row.aggregateId().equals(full)).hasSizeGreaterThanOrEqualTo(8);
        assertThat(ours).filteredOn(row -> row.aggregateId().equals(emptyId))
                .extracting(row -> converter.readExact(row.payload()).get("deleted").asBoolean())
                .contains(false).endsWith(true);

        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
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
        assertThat(subjects).containsExactly(
                "catalog.events-com.grocery.events.catalog.CategoryChanged",
                "catalog.events-com.grocery.events.catalog.ProductUpserted");
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private String categoryRequest(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                   String name, String parentId, int sortOrder) throws Exception {
        return body(mvc.perform(request.with(opsAdmin()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"%s","parentId":"%s","description":"Everyday %s","imageUrl":"https://img.example.com/c.jpg",
                         "sortOrder":%d}""".formatted(name, parentId, name, sortOrder)))
                .andExpect(status().is2xxSuccessful())).get("id").asString();
    }

    private String createProduct(UUID vendorId, String json) throws Exception {
        return body(mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())).get("id").asString();
    }

    private static ConsumerRecord<String, String> vendorEvent(UUID vendorId, String eventType, String businessName) {
        String payload = """
                {"vendorId":"%s","ownerUserId":"%s","businessName":"%s","reason":null,"actorUserId":null}"""
                .formatted(vendorId, UUID.randomUUID(), businessName);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("vendor.events", 0, 0L, vendorId.toString(), payload);
        record.headers().add("eventId", Ids.newId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Vendor".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private List<OutboxRow> outboxRows() {
        return JdbcClient.create(dataSource)
                .sql("SELECT id, topic, aggregate_id, event_type, payload, payload_avro FROM outbox_event ORDER BY occurred_at, id")
                .query((rs, n) -> new OutboxRow(rs.getString("id"), rs.getString("topic"), rs.getString("aggregate_id"),
                        rs.getString("event_type"), rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    /**
     * Semantic JSON equality: numbers compared numerically (MySQL's JSON column and Avro decimals may render
     * {@code 1.5} as {@code 1.500}), timestamps as instants truncated to microseconds (Avro's timestamp-micros),
     * a missing property equal to an explicit null.
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
