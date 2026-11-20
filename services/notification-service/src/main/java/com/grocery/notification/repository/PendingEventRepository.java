package com.grocery.notification.repository;

import com.grocery.common.kafka.EventEnvelope;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code pending_event} table: payment events parked until their order is known. Written and drained only
 * inside the dispatch transaction of an event.
 */
@Repository
public class PendingEventRepository {

    /** A parked event, in arrival order ({@code seq}). */
    public record PendingEvent(long seq, String eventId, UUID orderId, String topic, String eventType,
                               int eventVersion, Instant occurredAt, String payload, Instant receivedAt) {

        /** The event as the listener received it (headers that matter + payload). */
        public EventEnvelope envelope() {
            return new EventEnvelope(eventId, eventType, eventVersion, null, orderId.toString(), null, topic, payload);
        }
    }

    private static final String PARK_SQL = """
            INSERT IGNORE INTO pending_event (event_id, order_id, topic, event_type, event_version, occurred_at,
                                              payload, received_at)
            VALUES (:eventId, :orderId, :topic, :eventType, :eventVersion, :occurredAt, :payload, :receivedAt)
            """;
    private static final String COLUMNS = """
            SELECT seq, event_id, order_id, topic, event_type, event_version, occurred_at, payload, received_at
            FROM pending_event
            """;

    private final JdbcClient jdbc;
    private final RowMapper<PendingEvent> mapper = (rs, rowNum) -> new PendingEvent(
            rs.getLong("seq"),
            rs.getString("event_id"),
            UuidBytes.uuid(rs.getBytes("order_id")),
            rs.getString("topic"),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            instant(rs.getObject("occurred_at", LocalDateTime.class)),
            rs.getString("payload"),
            instant(rs.getObject("received_at", LocalDateTime.class)));

    public PendingEventRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Parks the event (a second park of the same event id changes nothing). */
    public void park(EventEnvelope event, UUID orderId, Instant occurredAt, Instant receivedAt) {
        requireTransaction();
        jdbc.sql(PARK_SQL)
                .param("eventId", event.eventId())
                .param("orderId", UuidBytes.bytes(orderId))
                .param("topic", event.topic())
                .param("eventType", event.eventType())
                .param("eventVersion", event.eventVersion())
                .param("occurredAt", occurredAt == null ? null : utc(occurredAt))
                .param("payload", event.payload())
                .param("receivedAt", utc(receivedAt))
                .update();
    }

    /**
     * Removes and returns the order's parked events, oldest first. A locking read: it sees (and waits for) every
     * parked event committed by a concurrent transaction, so none is left behind.
     */
    public List<PendingEvent> takeForOrder(UUID orderId) {
        requireTransaction();
        List<PendingEvent> parked = jdbc.sql(COLUMNS + " WHERE order_id = :orderId ORDER BY seq FOR UPDATE")
                .param("orderId", UuidBytes.bytes(orderId))
                .query(mapper)
                .list();
        if (!parked.isEmpty()) {
            jdbc.sql("DELETE FROM pending_event WHERE order_id = :orderId")
                    .param("orderId", UuidBytes.bytes(orderId))
                    .update();
        }
        return parked;
    }

    /** Up to {@code limit} events parked before {@code cutoff}, oldest first. */
    public List<PendingEvent> receivedBefore(Instant cutoff, int limit) {
        return jdbc.sql(COLUMNS + " WHERE received_at < :cutoff ORDER BY received_at LIMIT :limit")
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .query(mapper)
                .list();
    }

    public int delete(Collection<Long> seqs) {
        if (seqs.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM pending_event WHERE seq IN (:seqs)").param("seqs", seqs).update();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("pending_event is written only inside the dispatch transaction");
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
