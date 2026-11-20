package com.grocery.delivery;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import com.grocery.delivery.dispatch.geo.PartnerGeoIndex;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Point;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Location reports (contract §3, §6): stored in the database (throttled to one per 2 s), in the Redis GEO set, sampled
 * into the trail, and — only with an ASSIGNED / PICKED_UP delivery — published as DeliveryLocationUpdated on
 * delivery.location (key orderId, outbox-style headers, Avro); a Kafka failure never fails the call.
 */
class LocationIntegrationTest extends DispatchTestSupport {

    @Autowired
    GroceryAvroProperties avroProperties;

    private void reportAt(UUID partner, Instant at, GeoPoint point) {
        clock.set(at);
        report(partner, point);
    }

    private Point geoPosition(UUID partner) {
        List<Point> positions = redis.opsForGeo().position(PartnerGeoIndex.KEY, partner.toString());
        return positions == null || positions.isEmpty() ? null : positions.getFirst();
    }

    private long samples(UUID deliveryId) {
        return jdbc().sql("SELECT COUNT(*) FROM location_sample WHERE delivery_id = UUID_TO_BIN(?)")
                .param(deliveryId.toString()).query(Long.class).single();
    }

    private static String header(ProducerRecord<String, byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void reportsAreStoredAtMostEveryTwoSecondsAndKeptInTheGeoSet() {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        assertThat(partnerRow(rider).get("last_latitude")).isEqualTo(at(1.0, 0).latitude());
        Point stored = geoPosition(rider);
        assertThat(stored).isNotNull();
        assertThat(stored.getY()).isCloseTo(at(1.0, 0).latitude(), within(0.0001));

        reportAt(rider, T0.plusMillis(1999), at(2.0, 0));                   // closer than 2 s: accepted, not stored
        assertThat(partnerRow(rider).get("last_latitude")).isEqualTo(at(1.0, 0).latitude());
        assertThat(geoPosition(rider).getY()).isCloseTo(at(1.0, 0).latitude(), within(0.0001));

        reportAt(rider, T0.plusSeconds(2), at(2.0, 0));                     // 2 s after the last stored one
        assertThat(partnerRow(rider).get("last_latitude")).isEqualTo(at(2.0, 0).latitude());
        assertThat(geoPosition(rider).getY()).isCloseTo(at(2.0, 0).latitude(), within(0.0001));

        // Offline: out of the GEO set; online again: back at the last known position
        availability.setOnline(rider, false);
        assertThat(geoPosition(rider)).isNull();
        availability.setOnline(rider, true);
        assertThat(geoPosition(rider).getY()).isCloseTo(at(2.0, 0).latitude(), within(0.0001));
        // Nothing is published without an active delivery
        assertThat(locationSender.records()).isEmpty();
    }

    @Test
    void anActiveDeliveryPublishesThrottledLocationEventsAndSamplesTheTrail() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = searchingOrder();
        report(rider, at(1.0, 0));                                          // offered, not assigned: nothing
        assertThat(locationSender.records()).isEmpty();
        offers.accept(rider, offerOf(order, rider));
        UUID deliveryId = deliveryId(order);

        // A report every second for 18 s (the last stored one was at T0): stored and published every 2 s (T0+2, +4,
        // … +18), sampled into the trail at most every 15 s (T0+2 and T0+18)
        for (int second = 1; second <= 18; second++) {
            clock.set(T0.plusSeconds(second));
            mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"latitude\":" + at(1.0 - second * 0.01, 0).latitude() + ",\"longitude\":"
                                    + base.longitude() + ",\"accuracyMeters\":4.5,\"heading\":180,\"speedKmh\":21.5}"))
                    .andExpect(status().isNoContent());
        }
        List<ProducerRecord<String, byte[]>> records = locationSender.records();
        assertThat(records).hasSize(9);
        assertThat(samples(deliveryId)).isEqualTo(2);

        ProducerRecord<String, byte[]> record = records.getFirst();
        assertThat(record.topic()).isEqualTo("delivery.location");
        assertThat(record.key()).isEqualTo(order.orderId().toString());
        assertThat(header(record, "eventType")).isEqualTo("DeliveryLocationUpdated");
        assertThat(header(record, "eventVersion")).isEqualTo("1");
        assertThat(header(record, "aggregateType")).isEqualTo("Delivery");
        assertThat(UUID.fromString(header(record, "eventId"))).isNotNull();
        assertThat(header(record, "correlationId")).isNotBlank();
        assertThat(records).extracting(r -> header(r, "eventId")).doesNotHaveDuplicates();

        // The value decodes like any consumer reads it
        AvroEventDeserializer deserializer = new AvroEventDeserializer(SchemaRegistrySupport.newClient(avroProperties),
                avroProperties, new JsonAvroConverter());
        assertThat(AvroEventDeserializer.isAvro(record.value())).isTrue();
        JsonNode event = json.readTree(deserializer.deserialize("delivery.location", record.value()));
        assertThat(fieldNames(event)).containsExactly("deliveryId", "orderId", "partnerId", "latitude", "longitude",
                "heading", "speedKmh", "accuracyMeters", "recordedAt");
        assertThat(event.get("deliveryId").asString()).isEqualTo(deliveryId.toString());
        assertThat(event.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(event.get("partnerId").asString()).isEqualTo(rider.toString());
        assertThat(event.get("heading").asDouble()).isEqualTo(180.0);
        assertThat(event.get("speedKmh").asDouble()).isEqualTo(21.5);
        assertThat(event.get("accuracyMeters").asDouble()).isEqualTo(4.5);
        Instant recordedAt = Instant.parse(event.get("recordedAt").asString());
        assertThat(header(record, "occurredAt"))
                .isEqualTo(Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, recordedAt)));
        List<Instant> times = records.stream().map(r -> Instant.parse(json.readTree(
                deserializer.deserialize("delivery.location", r.value())).get("recordedAt").asString())).toList();
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).isAfterOrEqualTo(times.get(i - 1).plusSeconds(2));
        }
    }

    @Test
    void aKafkaFailureNeverFailsTheReportAndNothingIsPublishedAfterTheDelivery() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        UUID deliveryId = deliveryId(order);

        locationSender.failing(true);
        clock.set(T0.plusSeconds(5));
        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + at(0.5, 0).latitude() + ",\"longitude\":" + base.longitude() + "}"))
                .andExpect(status().isNoContent());
        Map<String, Object> row = partnerRow(rider);
        assertThat(row.get("last_latitude")).isEqualTo(at(0.5, 0).latitude());
        assertThat(samples(deliveryId)).isEqualTo(1);
        locationSender.failing(false);

        reportAt(rider, T0.plusSeconds(10), at(0.4, 0));
        assertThat(locationSender.records()).hasSize(1);

        readyForPickup(order);
        partnerDeliveries.pickUp(rider, deliveryId);
        reportAt(rider, T0.plusSeconds(20), at(0.2, 0));
        assertThat(locationSender.records()).hasSize(2);                    // PICKED_UP is active too
        partnerDeliveries.deliver(rider, deliveryId);
        reportAt(rider, T0.plusSeconds(30), at(0, 1));
        assertThat(locationSender.records()).hasSize(2);
    }
}
