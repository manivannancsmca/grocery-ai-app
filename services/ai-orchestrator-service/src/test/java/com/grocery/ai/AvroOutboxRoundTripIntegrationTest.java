package com.grocery.ai;

import com.grocery.ai.approval.ApprovalView;
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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every ai.events event the real operations write to the outbox is also stored as Avro in
 * {@code payload_avro} (Confluent wire format, schema from grocery-event-schemas 1.4.0, subject
 * {@code ai.events-com.grocery.events.ai.<EventType>}), and decoding it the way every consumer does (grocery-common's
 * {@link AvroEventDeserializer}) gives back the JSON in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> AI_EVENTS = List.of("ApprovalRequested", "ApprovalDecided", "ApprovalExecuted",
            "ApprovalFailed");
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z");

    private final JsonAvroConverter converter = new JsonAvroConverter();
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
    void everyApprovalEventIsPublishedAsAvroThatDecodesToItsJsonPayload() throws Exception {
        UUID customer = UUID.randomUUID();
        // requested -> approved -> executed (money, customer)
        ApprovalView executed = proposeOrder(customer, "285.00");
        GATEWAY.json("POST", "/api/orders", 201, Fixtures.json(Fixtures.order(UUID.randomUUID(), "GA-AVRO001",
                "CREATED", true, "285.00")));
        mvc.perform(post("/api/approvals/{id}/approve", executed.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk());
        // requested -> approved -> failed (admin, four-eyes, no obo)
        ApprovalView failed = proposeRefund(UUID.randomUUID(), "FINANCE_ADMIN", "2500.00");
        GATEWAY.on("POST", "/api/admin/refunds", request -> FakeGateway.Response.problem(422,
                "REFUND_EXCEEDS_CAPTURED", "Too much"));
        mvc.perform(post("/api/approvals/{id}/approve", failed.id()).with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isOk());
        // requested -> rejected (comment); requested -> cancelled; requested -> expired (no money, no decider)
        ApprovalView rejected = proposeOrder(customer, "190.00");
        mvc.perform(post("/api/approvals/{id}/reject", rejected.id()).with(as(customer, "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Too expensive\"}"))
                .andExpect(status().isOk());
        UUID opsRequester = UUID.randomUUID();
        ApprovalView cancelled = proposeSuspension(opsRequester, UUID.randomUUID());
        mvc.perform(post("/api/approvals/{id}/cancel", cancelled.id()).with(as(opsRequester, "OPS_ADMIN")))
                .andExpect(status().isOk());
        ApprovalView expired = proposeSuspension(UUID.randomUUID(), UUID.randomUUID());
        clock.advance(Duration.ofHours(25));
        maintenance.expireOverdue();
        clock.reset();

        assertThat(eventTypes(executed.id())).containsExactly("ApprovalRequested", "ApprovalDecided",
                "ApprovalExecuted");
        assertThat(eventTypes(failed.id())).containsExactly("ApprovalRequested", "ApprovalDecided",
                "ApprovalFailed");
        assertThat(eventTypes(rejected.id())).containsExactly("ApprovalRequested", "ApprovalDecided");
        assertThat(eventTypes(expired.id())).containsExactly("ApprovalRequested", "ApprovalDecided");
        assertThat(event(cancelled.id(), "ApprovalDecided").get("decision").asString()).isEqualTo("CANCELLED");
        assertThat(event(expired.id(), "ApprovalDecided").get("decision").asString()).isEqualTo("EXPIRED");

        List<OutboxRow> all = outboxRows();
        List<String> mine = Stream.of(executed, failed, rejected, cancelled, expired).map(a -> a.id().toString()).toList();
        assertThat(all).filteredOn(row -> mine.contains(row.aggregateId()))
                .hasSize(12)
                .extracting(OutboxRow::eventType).containsAll(AI_EVENTS);
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
        assertThat(row.topic()).isEqualTo("ai.events");
        assertThat(row.aggregateType()).isEqualTo("Approval");
        assertThat(row.eventType()).isIn(AI_EVENTS);
        assertThat(row.eventVersion()).isEqualTo(1);
        byte[] avroBytes = row.payloadAvro();
        assertThat(avroBytes).as("payload_avro of %s", what).isNotNull();
        assertThat(avroBytes[0]).as("magic byte of %s", what).isZero();

        int schemaId = ByteBuffer.wrap(avroBytes, 1, 4).getInt();
        String recordName = "com.grocery.events.ai." + row.eventType();
        SchemaMetadata registered;
        try {
            registered = registry.getLatestSchemaMetadata("ai.events-" + recordName);
        } catch (Exception e) {
            throw new AssertionError("No subject ai.events-" + recordName + " in the registry", e);
        }
        assertThat(registered.getId()).as("schema id of %s", what).isEqualTo(schemaId);
        assertThat(new Schema.Parser().parse(registered.getSchema()).getFullName()).isEqualTo(recordName);

        String decoded = deserializer.deserialize(row.topic(), avroBytes);
        assertThat(canonical(converter.readExact(decoded)))
                .as("decoded payload_avro of %s:%n%s%nvs payload:%n%s", what, decoded, row.payload())
                .isEqualTo(canonical(converter.readExact(row.payload())));
    }

    /** Objects as sorted maps, numbers by value, ISO instants truncated to microseconds. */
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
