package com.grocery.vendor;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.common.id.Ids;
import com.grocery.vendor.entity.RejectionReason;
import com.grocery.vendor.event.PurchasingEvents;
import com.grocery.vendor.event.VendorEvents;
import com.grocery.vendor.event.VendorOrderEvents;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every event vendor-service publishes is also written to the outbox as Avro ({@code payload_avro}, the
 * column Debezium publishes): Confluent wire format, written with the central schema under the subject
 * {@code <topic>-com.grocery.events.<domain>.<EventType>}, and decoded the way every consumer reads it (grocery-common's
 * {@link AvroEventDeserializer}) it is the JSON stored in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends PurchasingTestSupport {

    private static final List<String> EVENT_TYPES = List.of(VendorEvents.REGISTERED, VendorEvents.SUBMITTED,
            VendorEvents.APPROVED, VendorEvents.REJECTED, VendorEvents.SUSPENDED, VendorEvents.REINSTATED,
            // Phase 8: the store order events (aggregate VendorOrder)
            VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED, VendorOrderEvents.REJECTED,
            VendorOrderEvents.PREPARING, VendorOrderEvents.READY_FOR_PICKUP,
            // Phase 12: goods receipts (aggregate PurchaseOrder, key vendorId; schemas 1.5.0)
            PurchasingEvents.GOODS_RECEIVED);
    private static final String COMPLETE_PROFILE = """
            {"businessName":"Avro Mart","legalName":"Avro Mart Pvt Ltd","gstin":"29ABCDE1234F1Z5",
             "fssaiLicense":"12345678901234","contactEmail":"ops@avromart.in","contactPhone":"+919876543210",
             "address":{"line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                        "country":"IN","latitude":12.9716,"longitude":77.5946}}""";

    @Autowired
    GroceryAvroProperties avroProperties;

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final AvroEventSchemas schemas = new AvroEventSchemas();
    private final UUID admin = UUID.randomUUID();

    private record OutboxRow(String aggregateId, String topic, String eventType, int eventVersion, String payload,
                             byte[] payloadAvro) {
    }

    @Test
    void everyVendorEventIsAvroThatDecodesToTheJsonPayload() throws Exception {
        // The whole lifecycle: registered (on the owner's first save), submitted, rejected, resubmitted, approved,
        // suspended, reinstated
        UUID owner = UUID.randomUUID();
        String vendorId = json.readTree(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/vendors/{id}/reject", vendorId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"GSTIN does not match\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/vendors/{id}/approve", vendorId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/admin/vendors/{id}/suspend", vendorId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Late deliveries\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/vendors/{id}/reinstate", vendorId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // A draft from the registration event without a name: businessName is null in both formats
        UUID nameless = UUID.randomUUID();
        onboarding.onVendorUserRegistered(Ids.newId().toString(), nameless, null, "nameless@example.com", null);

        // Phase 8 store orders of the approved vendor: received → accepted (20 min) → packing → ready; received →
        // rejected by the store with a note; received → auto-rejected (no note, no actor); fractional rupees
        Store store = new Store(UUID.fromString(vendorId), owner);
        UUID packed = confirm(store, List.of(line(Ids.newId(), "Basmati Rice", 2, "52.50"),
                line(Ids.newId(), "Toor Dal", 1, "119.99")));
        storeOrders.accept(vendorUser(store), packed, 20);
        storeOrders.preparing(vendorUser(store), packed);
        storeOrders.ready(vendorUser(store), packed);
        UUID declined = confirm(store);
        storeOrders.reject(vendorUser(store), declined, RejectionReason.OTHER, "Freezer broke down");
        UUID unanswered = confirm(store);
        pollerAhead(Duration.ofMinutes(6)).rejectExpired();
        assertThat(storeEventTypes(packed)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.ACCEPTED,
                VendorOrderEvents.PREPARING, VendorOrderEvents.READY_FOR_PICKUP);
        assertThat(storeEventTypes(declined)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
        assertThat(storeEventTypes(unanswered)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
        assertThat(storeEvent(unanswered, VendorOrderEvents.REJECTED).get("actorUserId").isNull()).isTrue();

        // Phase 12: a goods receipt of another store (GoodsReceived is keyed by the vendor id), fractional unit costs
        // and a whole-rupee one
        Store buyer = newStore();
        UUID oil = product(buyer, "Sunflower Oil");
        UUID sugar = product(buyer, "Sugar");
        UUID purchaseOrder = sentPo(buyer, supplier(buyer, "Avro Supplier"),
                List.of(poLine(oil, 12, "132.75"), poLine(sugar, 50, "41")));
        receiveRequest(buyer, purchaseOrder, newKey(),
                receiveBody(List.of(Map.entry(oil, 12), Map.entry(sugar, 20)), "Avro"))
                .andExpect(status().isOk());
        assertThat(goodsReceived(purchaseOrder)).hasSize(1);

        List<OutboxRow> rows = JdbcClient.create(dataSource).sql("""
                        SELECT aggregate_id, topic, event_type, event_version, payload, payload_avro
                        FROM outbox_event ORDER BY occurred_at, id""")
                .query((rs, n) -> new OutboxRow(rs.getString("aggregate_id"), rs.getString("topic"),
                        rs.getString("event_type"), rs.getInt("event_version"), rs.getString("payload"),
                        rs.getBytes("payload_avro")))
                .list();
        assertThat(rows).filteredOn(row -> row.aggregateId().equals(vendorId)).extracting(OutboxRow::eventType)
                .containsExactly(VendorEvents.REGISTERED, VendorEvents.SUBMITTED, VendorEvents.REJECTED,
                        VendorEvents.SUBMITTED, VendorEvents.APPROVED, VendorEvents.SUSPENDED, VendorEvents.REINSTATED);
        List<String> namelessPayloads = rows.stream()
                .filter(row -> !row.aggregateId().equals(vendorId) && row.payload().contains(nameless.toString()))
                .map(OutboxRow::payload).toList();
        assertThat(namelessPayloads).hasSize(1);
        assertThat(converter.readExact(namelessPayloads.getFirst()).get("businessName").isNull()).isTrue();
        assertThat(rows).extracting(OutboxRow::eventType).containsAll(EVENT_TYPES);

        // Every row in the table (also those written by the other test classes), decoded like a consumer does
        SchemaRegistryClient registry = SchemaRegistrySupport.newClient(avroProperties);
        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
        for (OutboxRow row : rows) {
            String what = row.topic() + " " + row.eventType() + " of " + row.aggregateId();
            byte[] avro = row.payloadAvro();
            assertThat(avro).as("payload_avro of %s", what).isNotNull();
            assertThat(avro[0]).as("magic byte of %s", what).isZero();
            assertThat(AvroEventDeserializer.isAvro(avro)).as("wire format of %s", what).isTrue();

            String decoded = deserializer.deserialize(row.topic(), avro);
            assertSameJson(what, converter.readExact(decoded), converter.readExact(row.payload()));

            String domain = row.topic().substring(0, row.topic().indexOf('.'));
            String subject = row.topic() + "-com.grocery.events." + domain + "." + row.eventType();
            int schemaId = ByteBuffer.wrap(avro, 1, 4).getInt();
            assertThat(registry.getAllSubjects()).as("subjects").contains(subject);
            assertThat(registry.getId(subject, new AvroSchema(
                    schemas.require(row.topic(), row.eventType(), row.eventVersion()))))
                    .as("schema id of %s under subject %s", what, subject).isEqualTo(schemaId);
        }
        assertThat(registry.getAllSubjects()).containsAll(EVENT_TYPES.stream()
                .map(type -> "vendor.events-com.grocery.events.vendor." + type).toList());
    }

    /**
     * The decoded JSON equals the stored JSON: numbers compared by value (500 vs 500.000), timestamps as instants
     * truncated to microseconds (timestamp-micros), a missing property equal to an explicit null.
     */
    private static void assertSameJson(String path, JsonNode decoded, JsonNode stored) {
        if (isNull(decoded) || isNull(stored)) {
            assertThat(isNull(decoded)).as("%s: null in both", path).isEqualTo(isNull(stored));
        } else if (decoded.isNumber() && stored.isNumber()) {
            assertThat(decoded.decimalValue()).as(path).isEqualByComparingTo(stored.decimalValue());
        } else if (decoded.isString() && stored.isString()) {
            Instant decodedInstant = instant(decoded.asString());
            Instant storedInstant = instant(stored.asString());
            if (decodedInstant != null && storedInstant != null) {
                assertThat(decodedInstant.truncatedTo(ChronoUnit.MICROS)).as(path)
                        .isEqualTo(storedInstant.truncatedTo(ChronoUnit.MICROS));
            } else {
                assertThat(decoded.asString()).as(path).isEqualTo(stored.asString());
            }
        } else if (decoded.isObject() && stored.isObject()) {
            Set<String> names = new TreeSet<>();
            decoded.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            stored.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            for (String name : names) {
                assertSameJson(path + "." + name, decoded.path(name), stored.path(name));
            }
        } else if (decoded.isArray() && stored.isArray()) {
            assertThat(decoded.size()).as("%s: array size", path).isEqualTo(stored.size());
            for (int i = 0; i < decoded.size(); i++) {
                assertSameJson(path + "[" + i + "]", decoded.get(i), stored.get(i));
            }
        } else {
            assertThat(decoded).as(path).isEqualTo(stored);
        }
    }

    private static boolean isNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
