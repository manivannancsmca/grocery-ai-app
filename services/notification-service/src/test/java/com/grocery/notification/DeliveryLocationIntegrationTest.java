package com.grocery.notification;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.event.LocationListener;
import com.grocery.notification.service.LocationService;
import com.grocery.notification.service.LocationService.Outcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 contract §7, the live location: DeliveryLocationUpdated (group {@code notification-service.location}, its
 * own listener container) → signal DELIVERY_LOCATION to the order's customer only, resolved through the order
 * projection; older than 30 s → dropped; at most one per order per 2 s; unknown or ended orders → dropped; nothing is
 * stored; a position that travelled as Avro from the direct producer is read the same way.
 */
class DeliveryLocationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LocationListener locationListener;
    @Autowired
    LocationService locations;
    @Autowired
    KafkaListenerEndpointRegistry kafkaListeners;
    @Autowired
    AvroEventEncoder avroEncoder;
    @Autowired
    JsonAvroConverter avroConverter;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    private Order orderOf(UUID customer) {
        Order order = TestEvents.restaurantOrder(customer, UUID.randomUUID());
        knownOrder(order);
        return order;
    }

    @Test
    void theLocationConsumerIsASeparateGroupAndContainer() {
        MessageListenerContainer location = kafkaListeners.getListenerContainer("notification-location");
        MessageListenerContainer dispatch = kafkaListeners.getListenerContainer("notification-dispatch");
        assertThat(location).isNotNull().isNotSameAs(dispatch);
        assertThat(location.getGroupId()).isEqualTo("notification-service.location");
        assertThat(location.getContainerProperties().getTopics()).containsExactly("delivery.location");
        assertThat(dispatch.getGroupId()).isEqualTo("notification-service.dispatch");
        assertThat(dispatch.getContainerProperties().getTopics()).doesNotContain("delivery.location")
                .contains("delivery.events");
        assertThat(location.isRunning()).isFalse();                   // tests: spring.kafka.listener.auto-startup=false
    }

    @Test
    void onlyTheOrdersCustomerReceivesTheLocation() {
        UUID customer = UUID.randomUUID();
        UUID partner = UUID.randomUUID();
        Order order = orderOf(customer);
        SseClient customerStream = openStream(customer, "CUSTOMER");
        List<SseClient> others = List.of(
                openStream(partner, "DELIVERY_PARTNER"),
                openStream(UUID.randomUUID(), "CUSTOMER"),
                openStream(UUID.randomUUID(), "VENDOR"),
                openStream(UUID.randomUUID(), "SUPER_ADMIN", "OPS_ADMIN", "SUPPORT", "DELIVERY_MANAGER"));
        Instant recordedAt = Instant.now().minusSeconds(1);

        locationListener.on(TestEvents.location(TestEvents.locationPayload(order, UUID.randomUUID(), partner,
                12.9716, 77.5946, 135.5, recordedAt), true));

        SseClient.Block block = customerStream.await(b -> "signal".equals(b.event())
                && b.data().contains("DELIVERY_LOCATION"), WAIT);
        assertThat(block).as("the customer's stream").isNotNull();
        assertThat(block.id()).isNull();                                // a signal: never replayed
        JsonNode signal = data(block);
        assertThat(signal.get("type").asString()).isEqualTo("DELIVERY_LOCATION");
        JsonNode data = signal.get("data");
        assertThat(fieldNames(data)).containsExactly("orderId", "latitude", "longitude", "heading", "at");
        assertThat(data.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(data.get("latitude").asDouble()).isEqualTo(12.9716);
        assertThat(data.get("longitude").asDouble()).isEqualTo(77.5946);
        assertThat(data.get("heading").asDouble()).isEqualTo(135.5);
        assertThat(Instant.parse(data.get("at").asString())).isEqualTo(recordedAt);

        JsonNode published = recorder.await(signal("DELIVERY_LOCATION").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(published)).containsExactly("user:" + customer);

        // a public marker published after it reaches every stream: nobody else got the location before it
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        for (SseClient other : others) {
            assertThat(other.await(b -> b.data() != null && b.data().contains(marker.toString()), WAIT))
                    .as("the marker").isNotNull();
            assertThat(other.history()).noneMatch(b -> b.data() != null && b.data().contains("DELIVERY_LOCATION"));
        }
        assertThat(recorder.matching(signal("DELIVERY_LOCATION").and(mentioning(order.orderId())))).hasSize(1);

        // nothing is stored: no notification, no processed_event row
        assertThat(jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE consumer = 'notification-service.location'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE type = 'DELIVERY_LOCATION'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void positionsOlderThanThirtySecondsAreDropped() {
        UUID customer = UUID.randomUUID();
        Order order = orderOf(customer);
        UUID partner = UUID.randomUUID();

        assertThat(locations.handle(TestEvents.location(order, partner, 12.97, 77.59,
                Instant.now().minusSeconds(31)))).isEqualTo(Outcome.TOO_OLD);
        assertThat(locations.handle(TestEvents.location(order, partner, 12.97, 77.59,
                Instant.now().minusSeconds(25)))).isEqualTo(Outcome.SENT);

        awaitMarker();
        assertThat(recorder.matching(signal("DELIVERY_LOCATION").and(mentioning(order.orderId())))).hasSize(1);
    }

    @Test
    void atMostOnePositionPerOrderEveryTwoSeconds() {
        UUID customer = UUID.randomUUID();
        Order order = orderOf(customer);
        Order otherOrder = orderOf(customer);
        UUID partner = UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(locations.handle(TestEvents.location(order, partner, 12.9701, 77.5901, now)))
                .isEqualTo(Outcome.SENT);
        assertThat(locations.handle(TestEvents.location(order, partner, 12.9702, 77.5902, now.plusMillis(500))))
                .isEqualTo(Outcome.THROTTLED);
        assertThat(locations.handle(TestEvents.location(order, partner, 12.9703, 77.5903, now.plusMillis(900))))
                .isEqualTo(Outcome.THROTTLED);
        // another order is throttled on its own
        assertThat(locations.handle(TestEvents.location(otherOrder, partner, 12.98, 77.60, now)))
                .isEqualTo(Outcome.SENT);

        awaitMarker();
        List<JsonNode> sent = recorder.matching(signal("DELIVERY_LOCATION").and(mentioning(order.orderId())));
        assertThat(sent).singleElement()
                .satisfies(m -> assertThat(m.at("/payload/data/latitude").asDouble()).isEqualTo(12.9701));
    }

    @Test
    void unknownAndEndedOrdersAndForeignOrBrokenRecordsAreDropped() {
        UUID partner = UUID.randomUUID();
        Order unknown = TestEvents.restaurantOrder(UUID.randomUUID(), UUID.randomUUID());
        assertThat(locations.handle(TestEvents.location(unknown, partner, 12.97, 77.59, Instant.now())))
                .isEqualTo(Outcome.UNKNOWN_ORDER);

        Order delivered = orderOf(UUID.randomUUID());
        dispatch(TestEvents.old(TestEvents.orderDelivered(delivered, partner)));
        assertThat(locations.handle(TestEvents.location(delivered, partner, 12.97, 77.59, Instant.now())))
                .isEqualTo(Outcome.UNKNOWN_ORDER);

        Order order = orderOf(UUID.randomUUID());
        Map<String, Object> payload = TestEvents.locationPayload(order, UUID.randomUUID(), partner, 12.97, 77.59,
                null, Instant.now());
        ConsumerRecord<String, String> otherType = TestEvents.location(payload, false);
        otherType.headers().add("eventType", "DeliveryPartnerAssigned".getBytes());
        assertThat(locations.handle(otherType)).isEqualTo(Outcome.IGNORED);
        Map<String, Object> noCoordinates = TestEvents.locationPayload(order, UUID.randomUUID(), partner, 1, 1, null,
                Instant.now());
        noCoordinates.remove("latitude");
        assertThat(locations.handle(TestEvents.location(noCoordinates, true))).isEqualTo(Outcome.IGNORED);
        ConsumerRecord<String, String> unreadable = new ConsumerRecord<>("delivery.location", 0, 0L,
                order.orderId().toString(), "not json");
        assertThat(locations.handle(unreadable)).isEqualTo(Outcome.IGNORED);
        locationListener.on(unreadable);                              // the listener never throws: best effort

        awaitMarker();
        for (Order dropped : List.of(unknown, delivered, order)) {
            assertThat(recorder.matching(signal("DELIVERY_LOCATION").and(mentioning(dropped.orderId())))).isEmpty();
        }
    }

    @Test
    void aPositionWithoutHeadersAndWithoutHeadingIsSentToo() {
        UUID customer = UUID.randomUUID();
        Order order = orderOf(customer);
        Map<String, Object> payload = TestEvents.locationPayload(order, UUID.randomUUID(), UUID.randomUUID(), 12.9,
                77.6, null, Instant.now());

        assertThat(locations.handle(TestEvents.location(payload, false))).isEqualTo(Outcome.SENT);

        JsonNode published = recorder.await(signal("DELIVERY_LOCATION").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(published)).containsExactly("user:" + customer);
        assertThat(published.at("/payload/data/heading").isNull()).isTrue();
        assertThat(fieldNames(published.at("/payload/data")))
                .containsExactly("orderId", "latitude", "longitude", "heading", "at");
    }

    /**
     * The position as delivery-service's direct producer writes it: the JSON encoded to Avro with the real
     * DeliveryLocationUpdated schema (grocery-event-schemas 1.2.0) and decoded by this service's value deserializer.
     */
    @Test
    void aPositionThatTravelledAsAvroIsReadTheSameWay() {
        UUID customer = UUID.randomUUID();
        Order order = orderOf(customer);
        Instant recordedAt = Instant.now().minusMillis(300).truncatedTo(ChronoUnit.MICROS);
        String json = TestEvents.JSON.writeValueAsString(TestEvents.locationPayload(order, UUID.randomUUID(),
                UUID.randomUUID(), 12.93, 77.62, 270.0, recordedAt));
        byte[] wire = avroEncoder.encode("delivery.location", "DeliveryLocationUpdated", 1,
                avroConverter.readExact(json));
        assertThat(AvroEventDeserializer.isAvro(wire)).isTrue();
        Deserializer<?> values = consumerFactory.getValueDeserializer();
        Headers headers = new RecordHeaders();
        Object value = values.deserialize("delivery.location", headers, wire);
        assertThat(headers.lastHeader("springDeserializerExceptionValue")).as("deserialization failure").isNull();
        assertThat(value).isInstanceOf(String.class);
        ConsumerRecord<String, String> decoded = new ConsumerRecord<>("delivery.location", 0, 0L,
                order.orderId().toString(), (String) value);

        assertThat(locations.handle(decoded)).isEqualTo(Outcome.SENT);

        JsonNode published = recorder.await(signal("DELIVERY_LOCATION").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(published.at("/payload/data/latitude").asDouble()).isEqualTo(12.93);
        assertThat(published.at("/payload/data/heading").asDouble()).isEqualTo(270.0);
        assertThat(Instant.parse(published.at("/payload/data/at").asString())).isEqualTo(recordedAt);
    }

    // ---------------------------------------------------------------- helpers

    /** A marker after the events of a test: once it arrived, anything published before it has arrived too. */
    private void awaitMarker() {
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        assertThat(recorder.await(kind("signal").and(mentioning(marker)), 1, WAIT)).isNotEmpty();
    }

    private static List<String> audiences(JsonNode message) {
        List<String> audiences = new ArrayList<>();
        message.get("audiences").forEach(a -> audiences.add(a.asString()));
        return audiences;
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
