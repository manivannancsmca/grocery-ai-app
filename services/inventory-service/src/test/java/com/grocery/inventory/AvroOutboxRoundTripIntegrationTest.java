package com.grocery.inventory;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.id.Ids;
import com.grocery.inventory.config.InventoryProperties;
import com.grocery.inventory.service.ReservationExpiryPoller;
import com.grocery.inventory.service.ReservationService;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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

/**
 * ADR-0011 inside the service: the real business operations (vendor adjustments, and the Phase 6 reservation saga
 * driven by order.events and the expiry poller) publish every inventory.events type, and each outbox row carries,
 * next to its JSON {@code payload}, a {@code payload_avro} in the Confluent wire format (magic byte 0, schema id,
 * Avro binary) that grocery-common's consumer-side {@link AvroEventDeserializer} turns back into the same JSON. The
 * schema id is the one registered (in the in-memory registry) under {@code <topic>-<record full name>}. The products
 * arrive through the same Avro path (catalog.events ProductUpserted, where {@code unitValue} becomes {@code 500.000}).
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = "inventory.events";
    private static final String NAMESPACE = "com.grocery.events.inventory.";
    private static final Set<String> EVENT_TYPES = Set.of("StockAdjusted", "ProductAvailabilityChanged",
            "LowStockDetected", "StockReserved", "StockReservationFailed", "StockReservationExpired", "StockReleased",
            "StockCommitted");

    @Autowired
    SchemaRegistryClient registry;
    @Autowired
    GroceryAvroProperties avroProperties;
    @Autowired
    JsonAvroConverter converter;
    @Autowired
    AvroEventEncoder encoder;
    @Autowired
    ReservationService reservations;
    @Autowired
    InventoryProperties properties;
    @Autowired
    Clock clock;

    private record OutboxRow(String id, String topic, String aggregateId, String eventType, String payload,
                             byte[] payloadAvro) {
    }

    @Test
    void everyEventIsStoredAsAvroThatDecodesBackToItsJsonPayload() throws Exception {
        AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avroProperties, converter);
        Set<String> aggregates = new HashSet<>();

        // Products arrive as Avro (catalog.events), decoded to JSON by the consumer's deserializer
        UUID rice = Ids.newId();
        UUID dal = Ids.newId();
        for (UUID product : List.of(rice, dal)) {
            ConsumerRecord<String, String> json = productUpserted(newEventId(), product, vendorId, "Rice " + product,
                    "SKU-" + product.toString().substring(0, 8), "PUBLISHED", 1);
            ConsumerRecord<String, String> viaAvro = throughAvro(json, deserializer);
            assertThat(converter.readExact(viaAvro.value()).get("unitValue").decimalValue()).isEqualByComparingTo("500");
            catalogListener.on(viaAvro);
        }
        assertThat(stock(vendorId, rice).get("onHand").asInt()).isZero();
        aggregates.addAll(List.of(rice.toString(), dal.toString()));

        // StockAdjusted, ProductAvailabilityChanged (out -> in -> out -> in), LowStockDetected (threshold 5)
        adjust(vendorId, rice, "INCREASE", 10, "RESTOCK");
        adjust(vendorId, rice, "DECREASE", 6, "DAMAGED");                                   // 10 -> 4: low
        adjust(vendorId, rice, "SET", 0, "STOCK_COUNT");                                     // out of stock
        adjust(vendorId, rice, "SET", 12, "CORRECTION");
        adjust(vendorId, dal, "INCREASE", 3, "RESTOCK");

        // StockReserved, StockReservationFailed, StockReleased (ORDER_CANCELLED)
        UUID cancelled = Ids.newId();
        orderListener.on(orderCreated(newEventId(), cancelled, "GROCERY", List.of(Map.entry(rice, 2), Map.entry(dal, 1),
                Map.entry(rice, 1))));
        orderListener.on(orderCancelled(newEventId(), cancelled, "GROCERY"));
        UUID failed = Ids.newId();
        orderListener.on(orderCreated(newEventId(), failed, "GROCERY", List.of(Map.entry(dal, 50), Map.entry(Ids.newId(), 1))));
        // StockReservationExpired + StockReleased (RESERVATION_EXPIRED): unpaid past the 20-minute TTL
        UUID expired = Ids.newId();
        orderListener.on(orderCreated(newEventId(), expired, "GROCERY", List.of(Map.entry(rice, 4))));
        assertThat(new ReservationExpiryPoller(reservations, properties, Clock.offset(clock, Duration.ofMinutes(21)))
                .releaseExpired()).isPositive();
        // Phase 9: StockCommitted at pickup (OrderOutForDelivery), two products
        UUID picked = Ids.newId();
        orderListener.on(orderCreated(newEventId(), picked, "GROCERY", List.of(Map.entry(rice, 1), Map.entry(dal, 1))));
        orderListener.on(orderConfirmed(newEventId(), picked, "GROCERY"));
        orderListener.on(orderOutForDelivery(newEventId(), picked, "GROCERY"));
        aggregates.addAll(List.of(cancelled.toString(), failed.toString(), expired.toString(), picked.toString()));
        assertThat(reservationEventTypes(picked)).containsExactly("StockReserved", "StockCommitted");
        assertThat(reservationEventTypes(cancelled)).containsExactly("StockReserved", "StockReleased");
        assertThat(reservationEventTypes(failed)).containsExactly("StockReservationFailed");
        assertThat(reservationEventTypes(expired))
                .containsExactly("StockReserved", "StockReleased", "StockReservationExpired");

        List<OutboxRow> rows = outboxRows();
        List<OutboxRow> ours = rows.stream().filter(row -> aggregates.contains(row.aggregateId())).toList();
        assertThat(ours.stream().map(OutboxRow::eventType).collect(Collectors.toSet()))
                .as("every event type inventory-service publishes").isEqualTo(EVENT_TYPES);

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
        assertThat(subjects).containsAll(EVENT_TYPES.stream().map(type -> TOPIC + "-" + NAMESPACE + type).toList());
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** The record's JSON value as the producer's outbox encodes it and this service's consumer decodes it. */
    private ConsumerRecord<String, String> throughAvro(ConsumerRecord<String, String> record,
                                                       AvroEventDeserializer deserializer) {
        String eventType = new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8);
        byte[] wire = encoder.encode(record.topic(), eventType, 1, converter.readExact(record.value()));
        assertThat(wire[0]).isZero();
        ConsumerRecord<String, String> decoded = new ConsumerRecord<>(record.topic(), record.partition(),
                record.offset(), record.key(), deserializer.deserialize(record.topic(), wire));
        for (Header header : record.headers()) {
            decoded.headers().add(header);
        }
        return decoded;
    }

    private List<OutboxRow> outboxRows() {
        return JdbcClient.create(dataSource)
                .sql("SELECT id, topic, aggregate_id, event_type, payload, payload_avro FROM outbox_event ORDER BY occurred_at, id")
                .query((rs, n) -> new OutboxRow(rs.getString("id"), rs.getString("topic"), rs.getString("aggregate_id"),
                        rs.getString("event_type"), rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    /**
     * Semantic JSON equality: numbers compared numerically (MySQL's JSON column and Avro decimals may render the
     * same number differently), timestamps as instants truncated to microseconds (Avro's timestamp-micros), a
     * missing property equal to an explicit null.
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
