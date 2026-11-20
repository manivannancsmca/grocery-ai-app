package com.grocery.payment.repository;

import com.grocery.common.id.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The {@code webhook_event} table: every Razorpay webhook with a valid signature, exactly as received, at most once
 * per Razorpay event id. Plain JDBC with {@code INSERT IGNORE}: a duplicate (even one arriving at the same moment)
 * is detected without an exception that would mark the surrounding transaction rollback-only — the second insert
 * waits on the unique key until the first transaction ends and then inserts nothing.
 */
@Repository
public class WebhookEventStore {

    /** Webhook events stored, for the tests and the README: {@code processed} = applied, false = ignored. */
    public record StoredEvent(String eventId, String eventType, boolean processed, String outcome) {
    }

    static final int MAX_EVENT_ID = 100;
    static final int MAX_EVENT_TYPE = 60;
    static final int MAX_OUTCOME = 200;

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO webhook_event (id, event_id, event_type, payload, received_at, processed)
            VALUES (UUID_TO_BIN(:id), :eventId, :eventType, :payload, :receivedAt, FALSE)
            """;
    private static final String OUTCOME_SQL = """
            UPDATE webhook_event SET processed = :processed, outcome = :outcome WHERE event_id = :eventId
            """;

    private final JdbcClient jdbc;

    public WebhookEventStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Stores the event inside the caller's transaction.
     *
     * @return false when an event with this id was stored before (a redelivery: do nothing)
     */
    public boolean insertIfNew(String eventId, String eventType, String payload, Instant receivedAt) {
        requireTransaction();
        return jdbc.sql(INSERT_SQL)
                .param("id", Ids.newId().toString())
                .param("eventId", eventId)
                .param("eventType", cut(eventType, MAX_EVENT_TYPE))
                .param("payload", payload)
                .param("receivedAt", LocalDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
                .update() == 1;
    }

    /** What applying the event did ({@code processed} = it changed something / was matched). */
    public void recordOutcome(String eventId, boolean processed, String outcome) {
        requireTransaction();
        jdbc.sql(OUTCOME_SQL)
                .param("processed", processed)
                .param("outcome", cut(outcome, MAX_OUTCOME))
                .param("eventId", eventId)
                .update();
    }

    public java.util.Optional<StoredEvent> find(String eventId) {
        return jdbc.sql("SELECT event_id, event_type, processed, outcome FROM webhook_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query((rs, n) -> new StoredEvent(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)))
                .optional();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Webhook events are stored inside the transaction that applies them");
        }
    }
}
