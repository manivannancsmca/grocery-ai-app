package com.grocery.common.outbox;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistryAvroEventEncoder;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.common.avro.TestEvents;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Clock;
import java.util.UUID;

import static com.grocery.common.avro.TestEvents.JSON;
import static com.grocery.common.avro.TestEvents.PAYMENT_CAPTURED;
import static com.grocery.common.avro.TestEvents.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbox INSERT against real MySQL 8.4: JSON + Avro columns, or the pre-0.6.0 JSON-only table. */
@Testcontainers
class JdbcOutboxPublisherTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    private static final String SCOPE = "outbox-test";
    private static final String TABLE_0_5 = """
            CREATE TABLE outbox_event (
              id CHAR(36) PRIMARY KEY, topic VARCHAR(100) NOT NULL, aggregate_type VARCHAR(100) NOT NULL,
              aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(150) NOT NULL, event_version INT NOT NULL,
              payload JSON NOT NULL, correlation_id VARCHAR(64), causation_id VARCHAR(64),
              occurred_at DATETIME(6) NOT NULL)""";
    private static final String ADD_AVRO_COLUMN = "ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload";

    private final JsonAvroConverter converter = new JsonAvroConverter();
    private final GroceryAvroProperties avro = TestEvents.properties("mock://" + SCOPE, true);
    private JdbcClient jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void freshTable() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("DROP TABLE IF EXISTS outbox_event").update();
        jdbc.sql(TABLE_0_5).update();
    }

    @AfterEach
    void dropScope() {
        MockSchemaRegistry.dropScope(SCOPE);
    }

    private JdbcOutboxPublisher avroPublisher() {
        return new JdbcOutboxPublisher(jdbc, JSON, Clock.systemUTC(), new SchemaRegistryAvroEventEncoder(
                SchemaRegistrySupport.newClient(avro), avro, new AvroEventSchemas(), converter));
    }

    private UUID publish(OutboxPublisher publisher, OutboxEvent event) {
        return transaction.execute(status -> publisher.publish(event));
    }

    private static OutboxEvent paymentCaptured(TestEvents.PaymentCaptured payload) {
        return new OutboxEvent(TOPIC, "Payment", payload.orderId().toString(), PAYMENT_CAPTURED, 1, payload);
    }

    @Test
    void writesTheJsonPayloadAndTheSamePayloadAsAvro() {
        jdbc.sql(ADD_AVRO_COLUMN).update();
        TestEvents.PaymentCaptured payload = TestEvents.paymentCaptured();

        UUID id = publish(avroPublisher(), paymentCaptured(payload));

        record Row(String payload, byte[] payloadAvro) {
        }
        Row row = jdbc.sql("SELECT payload, payload_avro FROM outbox_event WHERE id = ?").param(id.toString())
                .query((rs, n) -> new Row(rs.getString(1), rs.getBytes(2))).single();
        // the JSON column as before (MySQL normalises JSON, so compare the meaning)
        TestEvents.PaymentCaptured stored = JSON.readValue(row.payload(), TestEvents.PaymentCaptured.class);
        assertThat(stored.orderId()).isEqualTo(payload.orderId());
        assertThat(stored.amount()).isEqualByComparingTo(payload.amount());
        // payload_avro: Confluent wire format, decoding to exactly the JSON the producer wrote
        assertThat(AvroEventDeserializer.isAvro(row.payloadAvro())).isTrue();
        String decoded = new AvroEventDeserializer(SchemaRegistrySupport.newClient(avro), avro, converter)
                .deserialize(TOPIC, row.payloadAvro());
        assertThat(converter.readExact(decoded)).isEqualTo(converter.readExact(JSON.writeValueAsString(payload)));
        assertThat(JSON.readValue(decoded, TestEvents.PaymentCaptured.class)).isEqualTo(payload);
    }

    @Test
    void withAvroOffTheInsertIsTheJsonOnlyOneOfBefore() {
        // table WITHOUT payload_avro: the 0.5.0 INSERT must not mention the column
        UUID id = publish(new JdbcOutboxPublisher(jdbc, JSON, Clock.systemUTC()), paymentCaptured(TestEvents.paymentCaptured()));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE id = ?").param(id.toString())
                .query(Long.class).single()).isOne();
    }

    @Test
    void anEventWithoutSchemaRollsTheBusinessTransactionBack() {
        jdbc.sql(ADD_AVRO_COLUMN).update();
        JdbcOutboxPublisher publisher = avroPublisher();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO outbox_event (id, topic, aggregate_type, aggregate_id, event_type, event_version,"
                    + " payload, occurred_at) VALUES ('business-change', 't', 'X', 'x', 'X', 1, '{}', NOW(6))").update();
            publisher.publish(OutboxEvent.of("order.events", "Order", "o-1", "OrderCreated", TestEvents.paymentCaptured()));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("avro/order.events/OrderCreated.avsc");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event").query(Long.class).single()).isZero();
    }

    @Test
    void publishingStillRequiresATransaction() {
        assertThatThrownBy(() -> avroPublisher().publish(paymentCaptured(TestEvents.paymentCaptured())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
    }
}
