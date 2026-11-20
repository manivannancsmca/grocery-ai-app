package com.grocery.delivery;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.common.id.Ids;
import com.grocery.delivery.dispatch.entity.FailureReason;
import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import com.grocery.delivery.partner.event.PartnerEvents;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
 * ADR-0011: every event delivery-service publishes is also written to the outbox as Avro ({@code payload_avro}, the
 * column Debezium publishes): Confluent wire format, written with the central schema under the subject
 * {@code <topic>-com.grocery.events.<domain>.<EventType>}, and decoded the way every consumer reads it (grocery-common's
 * {@link AvroEventDeserializer}) it is the JSON stored in {@code payload}. The same holds for DeliveryLocationUpdated,
 * which is published directly to {@code delivery.location}.
 */
class AvroOutboxRoundTripIntegrationTest extends DispatchTestSupport {

    private static final List<String> EVENT_TYPES = List.of(PartnerEvents.REGISTERED, PartnerEvents.SUBMITTED,
            PartnerEvents.APPROVED, PartnerEvents.REJECTED, PartnerEvents.SUSPENDED, PartnerEvents.REINSTATED);
    private static final List<String> DELIVERY_EVENT_TYPES = List.of(DeliveryEvents.OFFER_CREATED,
            DeliveryEvents.OFFER_CLOSED, DeliveryEvents.PARTNER_ASSIGNED, DeliveryEvents.PARTNER_UNASSIGNED,
            DeliveryEvents.PICKED_UP, DeliveryEvents.COMPLETED, DeliveryEvents.FAILED,
            DeliveryEvents.ASSIGNMENT_FAILED, DeliveryEvents.CANCELLED);
    private static final String COMPLETE_PROFILE = """
            {"fullName":"Avro Rider","phone":"+919811122233","city":"Bengaluru","vehicleType":"MOTORCYCLE",
             "vehicleNumber":"ka-01-ab 1234","drivingLicenseNumber":"KA01 2020-1234567"}""";

    @Autowired
    GroceryAvroProperties avroProperties;

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final AvroEventSchemas schemas = new AvroEventSchemas();

    private record OutboxRow(String aggregateId, String aggregateType, String topic, String eventType,
                             int eventVersion, String payload, byte[] payloadAvro) {
    }

    @Test
    void everyPartnerEventIsAvroThatDecodesToTheJsonPayload() throws Exception {
        // The whole lifecycle: registered (on the rider's first save), submitted, rejected, resubmitted, approved,
        // suspended, reinstated
        UUID user = UUID.randomUUID();
        String partnerId = user.toString();
        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER))
                .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE)).andExpect(status().isOk());
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/delivery-partners/{id}/reject", partnerId).with(as(admin, "DELIVERY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Licence photo is unreadable\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/delivery-partners/{id}/approve", partnerId).with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/admin/delivery-partners/{id}/suspend", partnerId).with(as(admin, "DELIVERY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Three no-shows\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/delivery-partners/{id}/reinstate", partnerId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // A draft from the registration event without a name: fullName is null in both formats
        UUID nameless = UUID.randomUUID();
        onboarding.onDeliveryPartnerRegistered(Ids.newId().toString(), nameless, null, "nameless@example.com", null);

        List<OutboxRow> rows = outbox();
        assertThat(rows).filteredOn(row -> row.aggregateId().equals(partnerId)).extracting(OutboxRow::eventType)
                .containsExactly(PartnerEvents.REGISTERED, PartnerEvents.SUBMITTED, PartnerEvents.REJECTED,
                        PartnerEvents.SUBMITTED, PartnerEvents.APPROVED, PartnerEvents.SUSPENDED,
                        PartnerEvents.REINSTATED);
        List<OutboxRow> namelessRows = rows.stream()
                .filter(row -> row.aggregateId().equals(nameless.toString())).toList();
        assertThat(namelessRows).extracting(OutboxRow::eventType).containsExactly(PartnerEvents.REGISTERED);
        assertThat(converter.readExact(namelessRows.getFirst().payload()).get("fullName").isNull()).isTrue();
        assertThat(rows).extracting(OutboxRow::eventType).containsAll(EVENT_TYPES);

        SchemaRegistryClient registry = assertEveryRowDecodesToItsPayload(rows);
        assertThat(registry.getAllSubjects()).containsAll(EVENT_TYPES.stream()
                .map(type -> "delivery.events-com.grocery.events.delivery." + type).toList());
    }

    @Test
    void everyDeliveryEventIsAvroThatDecodesToTheJsonPayload() throws Exception {
        UUID one = onlinePartnerAt("Avro One", at(1.0, 0));
        UUID two = onlinePartnerAt("Avro Two", at(2.0, 0));
        // Offers (created, rejected, accepted), an admin's re-search (unassigned), pickup, delivery
        Order delivered = searchingOrder();
        offers.reject(one, offerOf(delivered, one));
        offers.accept(two, offerOf(delivered, two));
        deliveryAdmin.reassign(deliveryId(delivered), null, "Avro test", adminUser(admin));
        offers.accept(one, offerOf(delivered, one));
        readyForPickup(delivered);
        partnerDeliveries.pickUp(one, deliveryId(delivered));
        partnerDeliveries.deliver(one, deliveryId(delivered));
        // A failed delivery, with and without a note
        Order failed = searchingOrder();
        offers.accept(one, offerOf(failed, one));
        readyForPickup(failed);
        partnerDeliveries.pickUp(one, deliveryId(failed));
        partnerDeliveries.fail(one, deliveryId(failed), FailureReason.CUSTOMER_UNREACHABLE, null);
        // Nobody anywhere near: assignment failed (no store location, searched around the drop point)
        GeoPoint home = base;
        base = new GeoPoint(-base.latitude() / 2, base.longitude() > 0 ? base.longitude() - 90 : base.longitude() + 90);
        Order unassignable = newOrder();
        confirm(unassignable, null, at(0, 3));
        storeAccepted(unassignable);
        base = home;
        // Cancelled with a partner assigned, and without
        Order cancelled = searchingOrder();
        offers.accept(two, offerOf(cancelled, two));
        orderCancelled(cancelled, "ACCEPTED");
        Order cancelledEarly = confirmedOrder();
        orderCancelled(cancelledEarly, "CONFIRMED");

        List<OutboxRow> rows = outbox();
        List<OutboxRow> deliveryRows = rows.stream().filter(row -> row.aggregateType().equals("Delivery")).toList();
        assertThat(deliveryRows).extracting(OutboxRow::eventType).containsAll(DELIVERY_EVENT_TYPES);
        assertThat(deliveryRows).allSatisfy(row -> {
            assertThat(row.topic()).isEqualTo("delivery.events");
            assertThat(converter.readExact(row.payload()).get("orderId").asString()).isEqualTo(row.aggregateId());
        });
        assertThat(eventTypes(delivered)).containsExactly(DeliveryEvents.OFFER_CREATED, DeliveryEvents.OFFER_CREATED,
                DeliveryEvents.OFFER_CLOSED, DeliveryEvents.PARTNER_ASSIGNED, DeliveryEvents.PARTNER_UNASSIGNED,
                DeliveryEvents.OFFER_CREATED, DeliveryEvents.PARTNER_ASSIGNED, DeliveryEvents.PICKED_UP,
                DeliveryEvents.COMPLETED);
        assertThat(event(cancelled, DeliveryEvents.CANCELLED).get("partnerId").asString()).isEqualTo(two.toString());
        assertThat(event(failed, DeliveryEvents.FAILED).get("note").isNull()).isTrue();
        assertThat(event(cancelledEarly, DeliveryEvents.CANCELLED).get("partnerId").isNull()).isTrue();
        assertThat(event(unassignable, DeliveryEvents.ASSIGNMENT_FAILED).get("attempts").asInt()).isZero();

        SchemaRegistryClient registry = assertEveryRowDecodesToItsPayload(rows);
        assertThat(registry.getAllSubjects()).containsAll(DELIVERY_EVENT_TYPES.stream()
                .map(type -> "delivery.events-com.grocery.events.delivery." + type).toList());
    }

    @Test
    void deliveryLocationUpdatedIsAvroThatDecodesWithTheConsumersDeserializer() throws Exception {
        UUID rider = onlinePartnerAt("Avro Rider", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        clock.set(T0.plusSeconds(3).plusNanos(123_456_000));
        report(rider, at(0.9, 0));                                     // heading, speed and accuracy set
        clock.set(T0.plusSeconds(6));
        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + at(0.8, 0).latitude() + ",\"longitude\":" + base.longitude() + "}"))
                .andExpect(status().isNoContent());                    // heading, speed and accuracy unknown

        List<ProducerRecord<String, byte[]>> records = locationSender.records();
        assertThat(records).hasSize(2);
        SchemaRegistryClient registry = SchemaRegistrySupport.newClient(avroProperties);
        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
        String subject = "delivery.location-com.grocery.events.delivery.DeliveryLocationUpdated";
        for (ProducerRecord<String, byte[]> record : records) {
            byte[] value = record.value();
            assertThat(record.topic()).isEqualTo("delivery.location");
            assertThat(record.key()).isEqualTo(order.orderId().toString());
            assertThat(value[0]).isZero();
            assertThat(AvroEventDeserializer.isAvro(value)).isTrue();
            assertThat(registry.getId(subject, new AvroSchema(schemas.require("delivery.location",
                    "DeliveryLocationUpdated", 1)))).isEqualTo(ByteBuffer.wrap(value, 1, 4).getInt());
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("DeliveryLocationUpdated");
        }
        JsonNode first = converter.readExact(deserializer.deserialize("delivery.location", records.get(0).value()));
        assertThat(first.get("deliveryId").asString()).isEqualTo(deliveryId(order).toString());
        assertThat(first.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(first.get("partnerId").asString()).isEqualTo(rider.toString());
        assertThat(first.get("latitude").asDouble()).isEqualTo(at(0.9, 0).latitude());
        assertThat(first.get("longitude").asDouble()).isEqualTo(at(0.9, 0).longitude());
        assertThat(first.get("heading").asDouble()).isEqualTo(90.0);
        assertThat(first.get("speedKmh").asDouble()).isEqualTo(18.0);
        assertThat(first.get("accuracyMeters").asDouble()).isEqualTo(8.0);
        assertThat(Instant.parse(first.get("recordedAt").asString()))
                .isEqualTo(T0.plusSeconds(3).plusNanos(123_456_000));
        JsonNode second = converter.readExact(deserializer.deserialize("delivery.location", records.get(1).value()));
        assertThat(second.get("heading").isNull()).isTrue();
        assertThat(second.get("speedKmh").isNull()).isTrue();
        assertThat(second.get("accuracyMeters").isNull()).isTrue();
        assertThat(registry.getAllSubjects()).contains(subject);
    }

    private List<OutboxRow> outbox() {
        return jdbc().sql("""
                        SELECT aggregate_id, aggregate_type, topic, event_type, event_version, payload, payload_avro
                        FROM outbox_event ORDER BY occurred_at, id""")
                .query((rs, n) -> new OutboxRow(rs.getString("aggregate_id"), rs.getString("aggregate_type"),
                        rs.getString("topic"), rs.getString("event_type"), rs.getInt("event_version"),
                        rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    /**
     * Every row (also those written by the other test classes), decoded like a consumer does, equals its JSON payload,
     * and carries the schema id of its central schema under the subject {@code <topic>-<record full name>}.
     */
    private SchemaRegistryClient assertEveryRowDecodesToItsPayload(List<OutboxRow> rows) throws Exception {
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
        return registry;
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
