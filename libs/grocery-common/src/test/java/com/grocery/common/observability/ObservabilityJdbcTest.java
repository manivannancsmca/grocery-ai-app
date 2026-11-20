package com.grocery.common.observability;

import com.grocery.common.idempotency.JdbcProcessedEventGuard;
import com.grocery.common.observability.GroceryEventMetrics.Outcome;
import com.grocery.common.outbox.JdbcOutboxPublisher;
import com.grocery.common.outbox.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * grocery-common 0.9.0 against real MySQL 8.4: the outbox row carries the active span's {@code traceparent} (and the
 * publisher keeps working on a table without that column), {@code grocery_events_published_total} counts committed
 * events only, and {@link JdbcProcessedEventGuard} reports APPLIED / DUPLICATE for the current record.
 */
@Testcontainers
class ObservabilityJdbcTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    /** The table as services have it before 0.9.0 (0.6.0 columns, JSON-only publisher here). */
    private static final String TABLE_0_8 = """
            CREATE TABLE outbox_event (
              id CHAR(36) PRIMARY KEY, topic VARCHAR(100) NOT NULL, aggregate_type VARCHAR(100) NOT NULL,
              aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(150) NOT NULL, event_version INT NOT NULL,
              payload JSON NOT NULL, payload_avro LONGBLOB NULL, correlation_id VARCHAR(64), causation_id VARCHAR(64),
              occurred_at DATETIME(6) NOT NULL)""";
    /** The rollout's Flyway migration. */
    private static final String ADD_TRACEPARENT = "ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55) NULL AFTER causation_id";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final OtelTracer tracer = TestTracing.tracer(spans);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GroceryEventMetrics metrics = new GroceryEventMetrics(registry);
    private SingleConnectionDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void freshTables() {
        // one connection for the whole test (a new TCP connection per statement is fragile on a busy Docker host)
        dataSource = new SingleConnectionDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), true);
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.sql("DROP TABLE IF EXISTS outbox_event").update();
        jdbc.sql("DROP TABLE IF EXISTS processed_event").update();
        jdbc.sql(TABLE_0_8).update();
        jdbc.sql("""
                CREATE TABLE processed_event (event_id CHAR(36) NOT NULL, consumer VARCHAR(100) NOT NULL,
                  processed_at DATETIME(6) NOT NULL, PRIMARY KEY (event_id, consumer))""").update();
    }

    @AfterEach
    void cleanUp() {
        ConsumedEvents.clear();
        dataSource.destroy();
    }

    private JdbcOutboxPublisher publisher() {
        return new JdbcOutboxPublisher(jdbc, JSON, Clock.systemUTC(), null, dataSource, TraceParent.supplier(tracer), metrics);
    }

    private static OutboxEvent orderPlaced() {
        return OutboxEvent.of("order.events", "Order", "order-1", "OrderPlaced", Map.of("orderId", "order-1"));
    }

    private UUID publishInSpan(JdbcOutboxPublisher publisher, Span span) {
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return transaction.execute(status -> publisher.publish(orderPlaced()));
        }
    }

    private String traceparentOf(UUID id) {
        return jdbc.sql("SELECT traceparent FROM outbox_event WHERE id = ?").param(id.toString())
                .query(String.class).optional().orElse(null);
    }

    private double published() {
        Counter counter = registry.find(GroceryEventMetrics.EVENTS_PUBLISHED)
                .tags("topic", "order.events", "event_type", "OrderPlaced").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theOutboxRowCarriesTheTraceparentOfTheActiveSpan() {
        jdbc.sql(ADD_TRACEPARENT).update();
        JdbcOutboxPublisher publisher = publisher();
        Span span = tracer.nextSpan().name("POST /api/orders").start();
        UUID id;
        try {
            id = publishInSpan(publisher, span);
        } finally {
            span.end();
        }

        assertThat(traceparentOf(id))
                .isEqualTo("00-" + span.context().traceId() + "-" + span.context().spanId() + "-01")
                .hasSize(55);

        // no span (a scheduled job, tracing off): NULL, the consumer starts a new trace
        UUID withoutSpan = transaction.execute(status -> publisher.publish(orderPlaced()));
        assertThat(traceparentOf(withoutSpan)).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aTableWithoutTheColumnStillTakesEveryEvent() {
        // only the library is bumped: the INSERT must not mention traceparent
        JdbcOutboxPublisher publisher = publisher();
        Span span = tracer.nextSpan().name("POST /api/orders").start();
        try {
            UUID first = publishInSpan(publisher, span);
            UUID second = publishInSpan(publisher, span);                       // the column is looked up once
            assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE id IN (?, ?)")
                    .params(first.toString(), second.toString()).query(Long.class).single()).isEqualTo(2);
        } finally {
            span.end();
        }
        assertThat(published()).isEqualTo(2);

        // the migration runs (at the service's next start): a new publisher writes the column
        jdbc.sql(ADD_TRACEPARENT).update();
        Span later = tracer.nextSpan().name("POST /api/orders").start();
        try {
            assertThat(traceparentOf(publishInSpan(publisher(), later))).contains(later.context().traceId());
        } finally {
            later.end();
        }
    }

    @Test
    void thePublishedCounterCountsCommittedEventsOnly() {
        jdbc.sql(ADD_TRACEPARENT).update();
        JdbcOutboxPublisher publisher = publisher();

        transaction.executeWithoutResult(status -> {
            publisher.publish(orderPlaced());
            publisher.publish(orderPlaced());
            assertThat(published()).isZero();                                   // not before the commit
        });
        assertThat(published()).isEqualTo(2);

        transaction.executeWithoutResult(status -> {
            publisher.publish(orderPlaced());
            status.setRollbackOnly();                                           // e.g. the business rule failed later
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            publisher.publish(orderPlaced());
            throw new IllegalStateException("payment declined");
        })).hasMessage("payment declined");

        assertThat(published()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_event").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theGuardReportsAppliedAndDuplicateForTheCurrentRecord() {
        JdbcProcessedEventGuard guard = new JdbcProcessedEventGuard(jdbc, Clock.systemUTC());
        String eventId = UUID.randomUUID().toString();
        String group = "inventory-service.order-events";

        ConsumedEvents.start(group, "OrderPlaced");
        Boolean first = transaction.execute(status -> guard.markProcessed(eventId, group));
        assertThat(first).isTrue();
        assertThat(ConsumedEvents.finish()).isEqualTo(new ConsumedEvents.Finished(group, "OrderPlaced", Outcome.APPLIED));

        ConsumedEvents.start(group, "OrderPlaced");
        Boolean again = transaction.execute(status -> guard.markProcessed(eventId, group));
        assertThat(again).isFalse();
        assertThat(ConsumedEvents.finish()).isEqualTo(new ConsumedEvents.Finished(group, "OrderPlaced", Outcome.DUPLICATE));

        // outside a record (no listener on this thread) nothing is reported
        Boolean outside = transaction.execute(status -> guard.markProcessed(UUID.randomUUID().toString(), group));
        assertThat(outside).isTrue();
        assertThat(ConsumedEvents.finish()).isNull();
    }
}
