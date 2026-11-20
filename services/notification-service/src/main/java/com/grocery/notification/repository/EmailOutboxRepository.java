package com.grocery.notification.repository;

import com.grocery.common.export.ExportSort.Key;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 12 §5: {@code email_outbox}. Rows are inserted in the dispatch transaction of their notification (one per
 * notification and recipient: a second insert changes nothing) and taken by the sender job with
 * {@code FOR UPDATE SKIP LOCKED}, so replicas never take the same row. Every status change is guarded by
 * {@code status = 'PENDING'}: a sent or failed email never changes again.
 */
@Repository
public class EmailOutboxRepository {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    /** {@code last_error} is VARCHAR(500). */
    static final int ERROR_WIDTH = 500;

    /** A row to insert. */
    public record NewEmail(UUID id, UUID notificationId, UUID userId, String toAddress, String toName, String type,
                           String subject, String body, String link, Instant createdAt) {
    }

    /** A row as the sender and the admin log see it. */
    public record OutboxEmail(UUID id, UUID notificationId, UUID userId, String toAddress, String toName, String type,
                              String subject, String body, String link, String status, int attempts,
                              Instant nextAttemptAt, String lastError, Instant createdAt, Instant sentAt) {
    }

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO email_outbox (id, notification_id, user_id, to_address, to_name, type, subject, body,
                                             link, status, attempts, next_attempt_at, created_at)
            VALUES (:id, :notificationId, :userId, :toAddress, :toName, :type, :subject, :body, :link, 'PENDING', 0,
                    :createdAt, :createdAt)
            """;
    private static final String COLUMNS = """
            SELECT id, notification_id, user_id, to_address, to_name, type, subject, body, link, status, attempts,
                   next_attempt_at, last_error, created_at, sent_at
            FROM email_outbox
            """;
    private static final String DUE_SQL = """
            SELECT id FROM email_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcClient jdbc;
    private final RowMapper<OutboxEmail> mapper = (rs, rowNum) -> new OutboxEmail(
            UuidBytes.uuid(rs.getBytes("id")),
            UuidBytes.uuid(rs.getBytes("notification_id")),
            UuidBytes.uuid(rs.getBytes("user_id")),
            rs.getString("to_address"),
            rs.getString("to_name"),
            rs.getString("type"),
            rs.getString("subject"),
            rs.getString("body"),
            rs.getString("link"),
            rs.getString("status"),
            rs.getInt("attempts"),
            instant(rs.getObject("next_attempt_at", LocalDateTime.class)),
            rs.getString("last_error"),
            instant(rs.getObject("created_at", LocalDateTime.class)),
            instant(rs.getObject("sent_at", LocalDateTime.class)));

    public EmailOutboxRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Inside the notification's transaction; {@code false} when the (notification, user) row already existed. */
    public boolean insert(NewEmail email) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("email_outbox is written only inside the notification's transaction");
        }
        return jdbc.sql(INSERT_SQL)
                .param("id", UuidBytes.bytes(email.id()))
                .param("notificationId", UuidBytes.bytes(email.notificationId()))
                .param("userId", UuidBytes.bytes(email.userId()))
                .param("toAddress", email.toAddress())
                .param("toName", email.toName())
                .param("type", email.type())
                .param("subject", email.subject())
                .param("body", email.body())
                .param("link", email.link())
                .param("createdAt", utc(email.createdAt()))
                .update() == 1;
    }

    /**
     * Takes up to {@code limit} due PENDING rows that no other transaction holds ({@code SKIP LOCKED}) and pushes their
     * due time to {@code leaseUntil}, so nobody else takes them while they are being sent. Must run in a transaction
     * of its own (the caller's), which ends before anything is sent.
     */
    public List<OutboxEmail> claimDue(Instant now, int limit, Instant leaseUntil) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("claimDue needs a transaction (FOR UPDATE SKIP LOCKED)");
        }
        List<byte[]> ids = jdbc.sql(DUE_SQL)
                .param("now", utc(now))
                .param("limit", limit)
                .query((rs, rowNum) -> rs.getBytes("id"))
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("UPDATE email_outbox SET next_attempt_at = :leaseUntil WHERE id IN (:ids)")
                .param("leaseUntil", utc(leaseUntil))
                .param("ids", ids)
                .update();
        return jdbc.sql(COLUMNS + " WHERE id IN (:ids) ORDER BY next_attempt_at, id")
                .param("ids", ids)
                .query(mapper)
                .list();
    }

    /** Sent: {@code attempts} counts this attempt. */
    public void markSent(UUID id, int attempts, Instant sentAt) {
        jdbc.sql("""
                        UPDATE email_outbox SET status = 'SENT', attempts = :attempts, sent_at = :sentAt,
                                                last_error = NULL
                        WHERE id = :id AND status = 'PENDING'""")
                .param("attempts", attempts)
                .param("sentAt", utc(sentAt))
                .param("id", UuidBytes.bytes(id))
                .update();
    }

    /** Failed, tried again at {@code nextAttemptAt}. */
    public void markRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                        UPDATE email_outbox SET attempts = :attempts, next_attempt_at = :next, last_error = :error
                        WHERE id = :id AND status = 'PENDING'""")
                .param("attempts", attempts)
                .param("next", utc(nextAttemptAt))
                .param("error", cut(error))
                .param("id", UuidBytes.bytes(id))
                .update();
    }

    /** Failed for good. */
    public void markFailed(UUID id, int attempts, String error, Instant at) {
        jdbc.sql("""
                        UPDATE email_outbox SET status = 'FAILED', attempts = :attempts, next_attempt_at = :at,
                                                last_error = :error
                        WHERE id = :id AND status = 'PENDING'""")
                .param("attempts", attempts)
                .param("at", utc(at))
                .param("error", cut(error))
                .param("id", UuidBytes.bytes(id))
                .update();
    }

    /** A claimed row that was not tried (the run ran out of time): due again at once, no attempt counted. */
    public void release(UUID id, Instant at) {
        jdbc.sql("UPDATE email_outbox SET next_attempt_at = :at WHERE id = :id AND status = 'PENDING'")
                .param("at", utc(at))
                .param("id", UuidBytes.bytes(id))
                .update();
    }

    /** The admin email log, newest first; {@code status} and {@code to} (part of the address, any case) optional. */
    public List<OutboxEmail> page(String status, String to, int limit, long offset) {
        Filter filter = filter(status, to);
        JdbcClient.StatementSpec statement = jdbc.sql(COLUMNS + filter.where()
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        for (int i = 0; i < filter.values().size(); i++) {
            statement = statement.param(filter.names().get(i), filter.values().get(i));
        }
        return statement.param("limit", limit).param("offset", offset).query(mapper).list();
    }

    public long count(String status, String to) {
        Filter filter = filter(status, to);
        JdbcClient.StatementSpec statement = jdbc.sql("SELECT COUNT(*) FROM email_outbox" + filter.where());
        for (int i = 0; i < filter.values().size(); i++) {
            statement = statement.param(filter.names().get(i), filter.values().get(i));
        }
        return statement.query(Long.class).single();
    }

    /**
     * One batch of the admin email log's export: the log's filters ({@code status}, {@code to} as for {@link #page}),
     * then the rows after {@code after} (the key values of the previous batch's last row; null for the first batch)
     * in the order of {@code keys}, at most {@code limit}. Keyset, never OFFSET: the thousandth batch is as fast as
     * the first. The key paths are {@code email_outbox} column names from a fixed whitelist (the export's
     * {@code ExportSort}), never client text.
     */
    public List<OutboxEmail> logBatch(String status, String to, List<? extends Key<?>> keys, Object[] after,
                                      int limit) {
        Filter filter = filter(status, to);
        List<String> conditions = new ArrayList<>(filter.conditions());
        if (after != null) {
            conditions.addFirst(boundary(keys));
        }
        String orderBy = keys.stream().map(key -> key.path() + (key.descending() ? " DESC" : " ASC"))
                .collect(Collectors.joining(", "));
        JdbcClient.StatementSpec statement = jdbc.sql(COLUMNS + where(conditions) + " ORDER BY " + orderBy
                + " LIMIT :limit");
        for (int i = 0; i < filter.values().size(); i++) {
            statement = statement.param(filter.names().get(i), filter.values().get(i));
        }
        for (int i = 0; after != null && i < after.length; i++) {
            statement = statement.param("after" + i, column(after[i]));
        }
        return statement.param("limit", limit).query(mapper).list();
    }

    /**
     * {@code k0 <= :after0 AND (k0 < :after0 OR (k0 = :after0 AND k1 < :after1))} for two descending keys: the rows
     * strictly after the last one seen; the leading {@code k0 <= :after0} lets MySQL use an index range on the first
     * key.
     */
    private static String boundary(List<? extends Key<?>> keys) {
        Key<?> first = keys.getFirst();
        return first.path() + (first.descending() ? " <= " : " >= ") + ":after0 AND " + beyond(keys, 0);
    }

    private static String beyond(List<? extends Key<?>> keys, int index) {
        Key<?> key = keys.get(index);
        String strictly = key.path() + (key.descending() ? " < " : " > ") + ":after" + index;
        if (index == keys.size() - 1) {
            return strictly;
        }
        return "(" + strictly + " OR (" + key.path() + " = :after" + index + " AND " + beyond(keys, index + 1) + "))";
    }

    /** A key value as its column stores it: ids as BINARY(16), instants as UTC DATETIME(6). */
    private static Object column(Object value) {
        return switch (value) {
            case UUID id -> UuidBytes.bytes(id);
            case Instant instant -> utc(instant);
            default -> value;
        };
    }

    /** The rows of one notification (tests, support). */
    public List<OutboxEmail> forNotification(UUID notificationId) {
        return jdbc.sql(COLUMNS + " WHERE notification_id = :id ORDER BY to_address")
                .param("id", UuidBytes.bytes(notificationId))
                .query(mapper)
                .list();
    }

    private record Filter(List<String> conditions, List<String> names, List<Object> values) {

        String where() {
            return EmailOutboxRepository.where(conditions);
        }
    }

    private static String where(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static Filter filter(String status, String to) {
        List<String> conditions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (status != null) {
            conditions.add("status = :status");
            names.add("status");
            values.add(status);
        }
        if (to != null && !to.isBlank()) {
            conditions.add("LOWER(to_address) LIKE :to ESCAPE '!'");
            names.add("to");
            values.add("%" + to.strip().toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        return new Filter(conditions, names, values);
    }

    private static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > ERROR_WIDTH ? text.substring(0, ERROR_WIDTH) : text;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
