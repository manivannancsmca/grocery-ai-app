package com.grocery.common.idempotency;

import com.grocery.common.observability.ConsumedEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC implementation backed by the service's {@code processed_event} table:
 * <pre>
 * CREATE TABLE processed_event (
 *   event_id CHAR(36) NOT NULL, consumer VARCHAR(100) NOT NULL, processed_at DATETIME(6) NOT NULL,
 *   PRIMARY KEY (event_id, consumer));
 * </pre>
 * Uses {@code INSERT IGNORE}, so a duplicate does not raise an error that would mark the
 * surrounding transaction rollback-only.
 *
 * <p>Since 0.9.0 the answer is also reported to {@link ConsumedEvents}, so the record being handled on this thread is
 * counted as {@code APPLIED} or {@code DUPLICATE} in {@code grocery_events_consumed_total} (call it on the listener
 * thread, as every service does).
 */
public class JdbcProcessedEventGuard implements ProcessedEventGuard {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO processed_event (event_id, consumer, processed_at)
            VALUES (:eventId, :consumer, :processedAt)
            """;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public JdbcProcessedEventGuard(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Override
    public boolean markProcessed(String eventId, String consumer) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "ProcessedEventGuard.markProcessed must run inside the consumer's transaction");
        }
        int inserted = jdbcClient.sql(INSERT_SQL)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .param("processedAt", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        boolean first = inserted == 1;
        ConsumedEvents.report(first);       // grocery_events_consumed_total: APPLIED or DUPLICATE for the current record
        return first;
    }
}
