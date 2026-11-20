package com.grocery.reporting;

import com.grocery.reporting.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.grocery.reporting.TestEvents.grocery;
import static com.grocery.reporting.TestEvents.ist;
import static com.grocery.reporting.TestEvents.orderConfirmed;
import static com.grocery.reporting.TestEvents.orderCreated;
import static com.grocery.reporting.TestEvents.product;
import static com.grocery.reporting.TestEvents.refundCompleted;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path through a real broker: Avro values in the Confluent wire format with the outbox router's headers on
 * the real topics → the listener container (group reporting-service.facts, grocery-common's deserializer and error
 * handler) → facts → the report. The refund is published BEFORE its order, on another topic; a record is published
 * twice.
 */
class KafkaEndToEndIntegrationTest extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Test
    void eventsPublishedToKafkaReachTheReports() throws Exception {
        Order o = grocery(UUID.randomUUID(), UUID.randomUUID(), "Green Basket", "30.00",
                product(UUID.randomUUID(), "Apples", 3, "50.00"));          // total 180.00
        ConsumerRecord<String, String> created = orderCreated(o, ist("2026-09-10T10:00"));
        ConsumerRecord<String, String> confirmed = orderConfirmed(o, ist("2026-09-10T10:02"));
        ConsumerRecord<String, String> refund = refundCompleted(o.orderId(), UUID.randomUUID(), "30.00", "ADMIN",
                null, ist("2026-09-10T18:00"));

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            for (ConsumerRecord<String, String> record : List.of(refund, created, confirmed, confirmed)) {
                ProducerRecord<String, byte[]> message = new ProducerRecord<>(record.topic(), record.key(),
                        wire(record));
                record.headers().forEach(h -> message.headers().add(h.key(), h.value()));
                producer.send(message).get(30, TimeUnit.SECONDS);
            }
        }

        // the facts: three events (the duplicate is not a new row)
        await(() -> count("SELECT COUNT(*) FROM processed_event WHERE consumer = 'reporting-service.facts'") == 3);
        // the audit trail's own group read the same topics: the refund is an entry, the orders are not
        await(() -> count("SELECT COUNT(*) FROM audit_entry WHERE action = 'REFUND_COMPLETED'") == 1);
        assertThat(count("SELECT COUNT(*) FROM audit_entry")).isEqualTo(1);
        await(() -> count("SELECT COUNT(*) FROM order_fact WHERE confirmed_at IS NOT NULL") == 1);

        JsonNode overview = report("/api/admin/reports/overview", "from", "2026-09-10", "to", "2026-09-10");
        assertThat(overview.get("orders").asLong()).isEqualTo(1);
        assertThat(overview.get("grossSales").toString()).isEqualTo("180.00");
        assertThat(overview.get("refunds").toString()).isEqualTo("30.00");
        assertThat(overview.get("netSales").toString()).isEqualTo("150.00");
        assertThat(overview.get("newCustomers").asLong()).isEqualTo(1);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waited %s for the listener", WAIT).isBefore(deadline);
            Thread.sleep(200);
        }
    }
}
