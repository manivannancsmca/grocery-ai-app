package com.grocery.apitests.kafka;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Json;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The end-to-end event-flow check (Phase 13 §4, "Kafka integration"): placing an order publishes OrderCreated and —
 * after inventory's StockReserved — OrderAwaitingPayment on {@code order.events}, read here by a plain consumer:
 * key = orderId, the outbox headers ({@code eventId}, {@code eventType}, {@code eventVersion}, {@code aggregateType},
 * {@code correlationId} = the caller's X-Correlation-Id, {@code occurredAt}), and an Avro value in the Confluent wire
 * format (magic byte 0, a schema id the Schema Registry knows) that decodes to the order.
 */
@Tag("kafka")
@Order(30)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Kafka: order event flow")
class OrderEventFlowTest {

    static final String TOPIC = "order.events";

    KafkaConsumer<String, byte[]> consumer;
    KafkaAvroDeserializer avro;
    World.Order order;
    String correlationId;
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();

    @BeforeAll
    void placeAnOrderWhileListening() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Config.KAFKA_BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "apit-" + Config.RUN);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000");
        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer());
        List<PartitionInfo> partitions;
        try {
            partitions = consumer.partitionsFor(TOPIC, Duration.ofSeconds(15));
        } catch (RuntimeException e) {
            partitions = List.of();
        }
        assumeTrue(partitions != null && !partitions.isEmpty(), "Kafka is not reachable at " + Config.KAFKA_BOOTSTRAP
                + " (from a container use the compose network: -DKAFKA_BOOTSTRAP=kafka:29092, see README)");
        List<TopicPartition> assigned = partitions.stream().map(p -> new TopicPartition(TOPIC, p.partition())).toList();
        consumer.assign(assigned);
        consumer.seekToEnd(assigned);
        assigned.forEach(consumer::position);               // fix the start offsets before the order is placed

        avro = new KafkaAvroDeserializer();
        avro.configure(Map.of("schema.registry.url", Config.SCHEMA_REGISTRY_URL), false);

        World.Customer customer = World.newCustomer("Kafka");
        World.fillCart(customer, World.storeA().rice(), 1);
        correlationId = "apit-" + Config.RUN + "-kafka";
        Res placed = Api.as(customer.session()).idempotencyKey(World.key("kafka")).correlationId(correlationId)
                .body(Json.obj("addressId", customer.addressId(), "expectedTotal", World.previewTotal(customer)))
                .post("/api/orders").created();
        World.track(placed.str("id"));
        order = new World.Order(placed.str("id"), placed.str("orderNumber"), placed.dec("total"), placed);

        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline && !(seen("OrderCreated") && seen("OrderAwaitingPayment"))) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                if (order.id().equals(record.key())) {
                    records.add(record);
                }
            }
        }
    }

    @AfterAll
    void close() {
        if (consumer != null) {
            consumer.close(Duration.ofSeconds(5));
        }
        if (avro != null) {
            avro.close();
        }
    }

    private boolean seen(String type) {
        return records.stream().anyMatch(r -> type.equals(header(r, "eventType")));
    }

    private ConsumerRecord<String, byte[]> event(String type) {
        return records.stream().filter(r -> type.equals(header(r, "eventType"))).findFirst()
                .orElseThrow(() -> new AssertionError(type + " not seen for order " + order.id() + "; got "
                        + records.stream().map(r -> header(r, "eventType")).collect(Collectors.toList())));
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @Order(1)
    void the_order_publishes_OrderCreated_then_OrderAwaitingPayment_keyed_by_the_order() {
        ConsumerRecord<String, byte[]> created = event("OrderCreated");
        ConsumerRecord<String, byte[]> awaiting = event("OrderAwaitingPayment");
        assertThat(created.key()).isEqualTo(order.id());
        assertThat(awaiting.partition()).as("one order, one partition").isEqualTo(created.partition());
        assertThat(awaiting.offset()).as("in order").isGreaterThan(created.offset());
    }

    @Test
    @Order(2)
    void every_event_carries_the_outbox_headers_and_the_callers_correlation_id() {
        for (String type : List.of("OrderCreated", "OrderAwaitingPayment")) {
            ConsumerRecord<String, byte[]> record = event(type);
            assertThat(UUID.fromString(header(record, "eventId"))).as("eventId of %s", type).isNotNull();
            assertThat(header(record, "eventVersion")).as("eventVersion of %s", type).matches("[1-9][0-9]*");
            assertThat(header(record, "aggregateType")).as("aggregateType of %s", type).isEqualTo("Order");
            assertThat(header(record, "correlationId")).as("correlationId of %s", type).isEqualTo(correlationId);
            assertThat(Long.parseLong(header(record, "occurredAt"))).as("occurredAt of %s", type).isPositive();
        }
        assertThat(header(event("OrderCreated"), "eventId")).isNotEqualTo(header(event("OrderAwaitingPayment"), "eventId"));
    }

    @Test
    @Order(3)
    void the_value_is_confluent_avro_with_a_registered_schema_and_decodes_to_the_order() {
        for (String type : List.of("OrderCreated", "OrderAwaitingPayment")) {
            byte[] value = event(type).value();
            assertThat(value.length).isGreaterThan(5);
            assertThat(value[0]).as("Confluent magic byte of %s", type).isEqualTo((byte) 0);
            int schemaId = ByteBuffer.wrap(value, 1, 4).getInt();
            assertThat(schemaId).isPositive();
            String registered = io.restassured.RestAssured.given().baseUri(Config.SCHEMA_REGISTRY_URL)
                    .get("/schemas/ids/" + schemaId).then().statusCode(200).extract().asString();
            assertThat(registered).as("schema %s of %s", schemaId, type).contains(type);

            GenericRecord decoded = (GenericRecord) avro.deserialize(TOPIC, value);
            assertThat(decoded.getSchema().getName()).isEqualTo(type);
            assertThat(String.valueOf(decoded.get("orderId"))).isEqualTo(order.id());
            assertThat(String.valueOf(decoded.get("orderNumber"))).isEqualTo(order.number());
        }
        GenericRecord created = (GenericRecord) avro.deserialize(TOPIC, event("OrderCreated").value());
        assertThat(String.valueOf(created.get("orderType"))).isEqualTo("GROCERY");
        assertThat(String.valueOf(created.get("storeId"))).isEqualTo(World.storeA().vendorId());
        assertThat((List<?>) created.get("items")).hasSize(1);
    }

    @Test
    @Order(4)
    void the_published_events_are_in_the_admin_order_timeline_with_the_same_ids() {
        Res timeline = Api.as(World.adminWithRole("SUPPORT")).get("/api/admin/orders/" + order.id() + "/events").ok();
        List<Map<String, Object>> events = timeline.list("$");
        for (String type : List.of("OrderCreated", "OrderAwaitingPayment")) {
            String eventId = header(event(type), "eventId");
            assertThat(events).as("timeline of %s", order.number()).anySatisfy(e -> assertThat(e)
                    .containsEntry("eventId", eventId).containsEntry("eventType", type).containsEntry("direction", "PUBLISHED"));
        }
    }

    @Test
    @Order(5)
    void the_events_carry_a_w3c_traceparent_when_tracing_is_deployed() {
        String traceparent = header(event("OrderCreated"), "traceparent");
        assumeTrue(traceparent != null, "no traceparent header on order.events yet: the Phase 13 outbox trace propagation "
                + "(outbox column traceparent + Debezium header placement) is not deployed in this stack");
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[0-1]");
    }
}
