package com.grocery.order.repository;

import com.grocery.order.entity.EventDirection;
import com.grocery.order.entity.EventOutcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The order event timeline ({@code order_event_log}, docs/api/phase-10-event-driven-hardening.md §5): one row per event
 * order-service published for an order and per event it consumed for an order. Plain JDBC, always inside the
 * transaction that publishes or processes the event, so a row exists exactly when the processing committed.
 * Read back oldest first ({@code recorded_at}, then insertion order).
 */
@Repository
public class OrderEventLog {

    /** Longer details are cut to the column. */
    public static final int DETAIL_MAX = 500;

    /** One timeline entry. */
    public record Entry(String eventId, String eventType, String sourceTopic, EventDirection direction,
                        EventOutcome outcome, String detail, Instant occurredAt, Instant recordedAt,
                        String correlationId) {
    }

    private static final String INSERT = """
            INSERT INTO order_event_log
                (order_id, event_id, event_type, source_topic, direction, outcome, detail, occurred_at, recorded_at,
                 correlation_id)
            VALUES (UUID_TO_BIN(:orderId), :eventId, :eventType, :topic, :direction, :outcome, :detail, :occurredAt,
                    :recordedAt, :correlationId)""";

    private final JdbcClient jdbc;

    public OrderEventLog(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** An event order-service published for the order (outcome APPLIED), in the publishing transaction. */
    public void published(UUID orderId, String eventId, String eventType, String topic, Instant occurredAt,
                          String correlationId, Instant recordedAt) {
        requireTransaction();
        statement(INSERT, orderId, eventId, eventType, topic, EventDirection.PUBLISHED, EventOutcome.APPLIED, null,
                occurredAt, recordedAt, correlationId).update();
    }

    /**
     * A consumed event of an existing (locked) order, recorded BEFORE it is applied — so that the events its
     * processing publishes come after it in the timeline — with outcome APPLIED; {@link #outcome} corrects it when
     * the guard rejected the event. Returns the row's id.
     */
    public long consumed(UUID orderId, String eventId, String eventType, String topic, Instant occurredAt,
                         String correlationId, Instant recordedAt) {
        requireTransaction();
        KeyHolder key = new GeneratedKeyHolder();
        statement(INSERT, orderId, eventId, eventType, topic, EventDirection.CONSUMED, EventOutcome.APPLIED, null,
                occurredAt, recordedAt, correlationId).update(key, "id");
        return key.getKeyAs(Number.class).longValue();
    }

    /** Sets the outcome and detail of a consumed entry written in this transaction. */
    public void outcome(long id, EventOutcome outcome, String detail) {
        jdbc.sql("UPDATE order_event_log SET outcome = :outcome, detail = :detail WHERE id = :id")
                .param("outcome", outcome.name())
                .param("detail", cut(detail))
                .param("id", id)
                .update();
    }

    /**
     * A redelivered event (already processed): a DUPLICATE entry — only when the order exists (an event for an
     * unknown order is not part of any timeline).
     */
    public void duplicate(UUID orderId, String eventId, String eventType, String topic, Instant occurredAt,
                          String correlationId, String detail, Instant recordedAt) {
        requireTransaction();
        statement("""
                        INSERT INTO order_event_log
                            (order_id, event_id, event_type, source_topic, direction, outcome, detail, occurred_at,
                             recorded_at, correlation_id)
                        SELECT id, :eventId, :eventType, :topic, :direction, :outcome, :detail, :occurredAt,
                               :recordedAt, :correlationId
                        FROM customer_order WHERE id = UUID_TO_BIN(:orderId)""",
                orderId, eventId, eventType, topic, EventDirection.CONSUMED, EventOutcome.DUPLICATE, detail,
                occurredAt, recordedAt, correlationId).update();
    }

    /** The order's timeline, oldest first. */
    public List<Entry> list(UUID orderId) {
        return jdbc.sql("""
                        SELECT event_id, event_type, source_topic, direction, outcome, detail, occurred_at,
                               recorded_at, correlation_id
                        FROM order_event_log WHERE order_id = UUID_TO_BIN(:orderId)
                        ORDER BY recorded_at, id""")
                .param("orderId", orderId.toString())
                .query((rs, row) -> new Entry(rs.getString("event_id"), rs.getString("event_type"),
                        rs.getString("source_topic"), EventDirection.valueOf(rs.getString("direction")),
                        EventOutcome.valueOf(rs.getString("outcome")), rs.getString("detail"),
                        instant(rs, "occurred_at"), instant(rs, "recorded_at"), rs.getString("correlation_id")))
                .list();
    }

    private JdbcClient.StatementSpec statement(String sql, UUID orderId, String eventId, String eventType,
                                               String topic, EventDirection direction, EventOutcome outcome,
                                               String detail, Instant occurredAt, Instant recordedAt,
                                               String correlationId) {
        return jdbc.sql(sql)
                .param("orderId", orderId.toString())
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("topic", topic)
                .param("direction", direction.name())
                .param("outcome", outcome.name())
                .param("detail", cut(detail))
                .param("occurredAt", occurredAt == null ? null : LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("recordedAt", LocalDateTime.ofInstant(recordedAt, ZoneOffset.UTC))
                .param("correlationId", correlationId == null || correlationId.length() <= 64
                        ? correlationId : correlationId.substring(0, 64));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String cut(String detail) {
        return detail == null || detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The order event log is written in the processing transaction only");
        }
    }
}
