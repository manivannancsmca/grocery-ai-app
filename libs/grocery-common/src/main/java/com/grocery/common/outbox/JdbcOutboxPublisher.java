package com.grocery.common.outbox;

import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.id.Ids;
import com.grocery.common.observability.GroceryEventMetrics;
import com.grocery.common.observability.TraceParent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Plain-JDBC outbox writer. It shares the caller's transaction (JPA or JDBC) because Spring binds
 * the same connection to the thread for the duration of the transaction.
 *
 * <p>Required table (each service creates it in its own Flyway migrations; {@code payload_avro} since grocery-common
 * 0.6.0, {@code traceparent} since 0.9.0):
 * <pre>
 * CREATE TABLE outbox_event (
 *   id CHAR(36) PRIMARY KEY, topic VARCHAR(100) NOT NULL, aggregate_type VARCHAR(100) NOT NULL,
 *   aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(150) NOT NULL, event_version INT NOT NULL,
 *   payload JSON NOT NULL, payload_avro LONGBLOB NULL, correlation_id VARCHAR(64), causation_id VARCHAR(64),
 *   traceparent VARCHAR(55) NULL, occurred_at DATETIME(6) NOT NULL);
 * -- existing tables: ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload;
 * --                  ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55) NULL AFTER causation_id;
 * </pre>
 *
 * <p>{@code payload} always holds the payload as JSON (audit, debugging, the rollback path). With an
 * {@link AvroEventEncoder} (Avro enabled, the default) {@code payload_avro} holds the same payload in the Confluent
 * wire format, converted from exactly that JSON; it is what Debezium publishes. Without one (grocery.events.avro.enabled=false)
 * the INSERT does not mention {@code payload_avro} at all, exactly as before 0.6.0.
 *
 * <p><b>Trace context (0.9.0):</b> when a span is current, its W3C {@code traceparent} is written to the
 * {@code traceparent} column, which Debezium places in the Kafka header {@code traceparent}; the consumer continues the
 * trace. Whether the table has that column is looked up once (on the first event published inside a span): until a
 * service's Flyway migration adds it, the INSERT does not mention the column, exactly as before 0.9.0. Without a
 * current span (tracing off, a scheduled job …) the column is not written either.
 *
 * <p><b>Metrics (0.9.0):</b> {@code grocery_events_published_total{topic,event_type}} is incremented when the
 * business transaction commits (a rolled-back event is not counted).
 */
public class JdbcOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(JdbcOutboxPublisher.class);

    static final String TABLE = "outbox_event";
    static final String TRACEPARENT_COLUMN = "traceparent";

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final AvroEventEncoder avroEncoder;
    /** Reads the stored JSON back without losing precision (floats as BigDecimal, trailing zeros kept). */
    private final ObjectReader exactJsonReader;
    private final DataSource dataSource;
    private final Supplier<String> traceparent;
    private final GroceryEventMetrics metrics;
    /** Whether outbox_event has the traceparent column; null = not looked up yet. */
    private volatile Boolean traceparentColumn;

    public JdbcOutboxPublisher(JdbcClient jdbcClient, JsonMapper jsonMapper, Clock clock) {
        this(jdbcClient, jsonMapper, clock, null);
    }

    /**
     * @param avroEncoder encodes {@code payload_avro}; {@code null} = JSON only (no {@code payload_avro} column needed)
     */
    public JdbcOutboxPublisher(JdbcClient jdbcClient, JsonMapper jsonMapper, Clock clock, AvroEventEncoder avroEncoder) {
        this(jdbcClient, jsonMapper, clock, avroEncoder, null, null, null);
    }

    /**
     * @param avroEncoder encodes {@code payload_avro}; {@code null} = JSON only (no {@code payload_avro} column needed)
     * @param dataSource  the outbox table's database, to look up the {@code traceparent} column once; {@code null} =
     *                    never write it
     * @param traceparent the current span's W3C {@code traceparent} ({@link TraceParent#supplier}), {@code null}
     *                    without a span; {@code null} supplier = never write it
     * @param metrics     {@code grocery_events_published_total}; {@code null} = not counted
     */
    public JdbcOutboxPublisher(JdbcClient jdbcClient, JsonMapper jsonMapper, Clock clock, AvroEventEncoder avroEncoder,
                               DataSource dataSource, Supplier<String> traceparent, GroceryEventMetrics metrics) {
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.avroEncoder = avroEncoder;
        this.exactJsonReader = jsonMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
        this.dataSource = dataSource;
        this.traceparent = traceparent == null ? () -> null : traceparent;
        this.metrics = metrics == null ? GroceryEventMetrics.noop() : metrics;
    }

    @Override
    public UUID publish(OutboxEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxPublisher.publish must run inside a transaction so the event commits "
                            + "atomically with the business change (event " + event.eventType() + ")");
        }
        String payload = jsonMapper.writeValueAsString(event.payload());
        // Avro from exactly the JSON stored (and formerly published): one serialization defines the event's content.
        // Failures (no schema, payload does not match it, registry rejects it) roll the business transaction back.
        byte[] payloadAvro = avroEncoder == null ? null : avroEncoder.encode(event.topic(), event.eventType(),
                event.eventVersion(), exactJsonReader.readTree(payload));
        String trace = currentTraceparent();
        boolean withTrace = trace != null && hasTraceparentColumn();

        UUID eventId = Ids.newId();
        String correlationId = CorrelationId.current() != null ? CorrelationId.current() : eventId.toString();

        JdbcClient.StatementSpec statement = jdbcClient.sql(insertSql(payloadAvro != null, withTrace))
                .param("id", eventId.toString())
                .param("topic", event.topic())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("eventVersion", event.eventVersion())
                .param("payload", payload)
                .param("correlationId", correlationId)
                .param("causationId", CorrelationId.currentCausation())
                .param("occurredAt", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (payloadAvro != null) {
            statement = statement.param("payloadAvro", payloadAvro);
        }
        if (withTrace) {
            statement = statement.param("traceparent", trace);
        }
        statement.update();
        metrics.publishedOnCommit(event.topic(), event.eventType());
        return eventId;
    }

    /** The INSERT, mentioning {@code payload_avro} / {@code traceparent} only when they are written. */
    static String insertSql(boolean avro, boolean traceparent) {
        return "INSERT INTO " + TABLE + " (id, topic, aggregate_type, aggregate_id, event_type, event_version, payload, "
                + (avro ? "payload_avro, " : "") + "correlation_id, causation_id, "
                + (traceparent ? TRACEPARENT_COLUMN + ", " : "") + "occurred_at) VALUES (:id, :topic, :aggregateType, "
                + ":aggregateId, :eventType, :eventVersion, :payload, " + (avro ? ":payloadAvro, " : "")
                + ":correlationId, :causationId, " + (traceparent ? ":traceparent, " : "") + ":occurredAt)";
    }

    private String currentTraceparent() {
        try {
            String value = traceparent.get();
            return value != null && value.length() == TraceParent.LENGTH ? value : null;
        } catch (RuntimeException e) {
            log.debug("No traceparent for the outbox event: {}", e.toString());
            return null;                                    // tracing must never fail the business transaction
        }
    }

    /**
     * Looks the column up once, on the transaction's own connection. A failed lookup is not remembered (tried again on
     * the next event) and means "no column" for this event.
     */
    private boolean hasTraceparentColumn() {
        Boolean known = traceparentColumn;
        if (known != null) {
            return known;
        }
        if (dataSource == null) {
            traceparentColumn = false;
            return false;
        }
        Connection connection = null;
        try {
            connection = DataSourceUtils.getConnection(dataSource);
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String escape = metaData.getSearchStringEscape();
            boolean found;
            try (ResultSet columns = metaData.getColumns(catalog, catalog == null ? connection.getSchema() : null,
                    pattern(TABLE, escape), pattern(TRACEPARENT_COLUMN, escape))) {
                found = columns.next();
            }
            traceparentColumn = found;
            if (found) {
                log.info("Outbox events carry the W3C traceparent of the current span ({}.{})", TABLE, TRACEPARENT_COLUMN);
            } else {
                log.info("{} has no {} column yet: outbox events are published without trace context until the "
                        + "service's migration adds it (grocery-common 0.9.0)", TABLE, TRACEPARENT_COLUMN);
            }
            return found;
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not look up {}.{}; this event is published without trace context: {}", TABLE,
                    TRACEPARENT_COLUMN, e.toString());
            return false;
        } finally {
            if (connection != null) {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    /** A metadata name pattern matching exactly {@code name} ('_' and '%' are wildcards there). */
    private static String pattern(String name, String escape) {
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
