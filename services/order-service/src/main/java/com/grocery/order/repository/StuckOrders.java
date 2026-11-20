package com.grocery.order.repository;

import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.StuckReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The watchdog's flags ({@code stuck_order}, docs/api/phase-10-event-driven-hardening.md §4). Plain JDBC. A flag is
 * keyed by (order, status): flagging the same order in the same status again is a no-op, which is what makes the first
 * detection the only one — whichever replica or run gets there first.
 */
@Repository
public class StuckOrders {

    /** An unresolved flag with what the admin list shows of its order. */
    public record OpenFlag(UUID orderId, String orderNumber, OrderType type, OrderStatus status, StuckReason reason,
                           Instant since, Instant dueAt, Instant detectedAt, UUID storeId, String storeName,
                           UUID customerId) {
    }

    /** A flag whose order has left the flagged status. */
    public record Moved(UUID orderId, OrderStatus status) {
    }

    private final JdbcClient jdbc;

    public StuckOrders(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Orders in {@code status} whose {@code column} (the rule's reference time) is at or before {@code cutoff} and that
     * have no flag for that status yet, longest overdue first. A plain (non-locking) read: the caller locks each one
     * and re-checks it.
     *
     * @param column a {@code customer_order} column name from {@code StuckRule} (a constant, never user input)
     */
    public List<UUID> candidates(OrderStatus status, String column, Instant cutoff, int limit) {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(o.id) FROM customer_order o
                        WHERE o.status = :status AND o.%1$s <= :cutoff
                          AND NOT EXISTS (SELECT 1 FROM stuck_order s WHERE s.order_id = o.id AND s.status = o.status)
                        ORDER BY o.%1$s, o.id
                        LIMIT :limit""".formatted(column))
                .param("status", status.name())
                .param("cutoff", utc(cutoff))
                .param("limit", limit)
                .query(String.class).list().stream()
                .map(UUID::fromString)
                .toList();
    }

    /** Flags the (locked) order; false when it was flagged in this status before (nothing written). */
    public boolean flag(UUID orderId, OrderStatus status, StuckReason reason, Instant since, Instant dueAt,
                        Instant detectedAt) {
        requireTransaction();
        return jdbc.sql("""
                        INSERT IGNORE INTO stuck_order (order_id, status, reason, since, due_at, detected_at)
                        VALUES (UUID_TO_BIN(:orderId), :status, :reason, :since, :dueAt, :detectedAt)""")
                .param("orderId", orderId.toString())
                .param("status", status.name())
                .param("reason", reason.name())
                .param("since", utc(since))
                .param("dueAt", utc(dueAt))
                .param("detectedAt", utc(detectedAt))
                .update() == 1;
    }

    /**
     * The order is {@code current} now: its open flags of any other status resolve (called in the transaction of
     * every status change). Returns how many.
     */
    public int resolveOthers(UUID orderId, OrderStatus current, Instant now) {
        return jdbc.sql("""
                        UPDATE stuck_order SET resolved_at = :now
                        WHERE order_id = UUID_TO_BIN(:orderId) AND resolved_at IS NULL AND status <> :current""")
                .param("now", utc(now))
                .param("orderId", orderId.toString())
                .param("current", current.name())
                .update();
    }

    /** Open flags whose order is no longer in the flagged status (a plain read; statuses never come back). */
    public List<Moved> moved() {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(s.order_id) AS order_id, s.status FROM stuck_order s
                        JOIN customer_order o ON o.id = s.order_id
                        WHERE s.resolved_at IS NULL AND o.status <> s.status""")
                .query((rs, row) -> new Moved(UUID.fromString(rs.getString("order_id")),
                        OrderStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /** Resolves one flag (if still open); true when it did. */
    public boolean resolve(UUID orderId, OrderStatus status, Instant now) {
        return jdbc.sql("""
                        UPDATE stuck_order SET resolved_at = :now
                        WHERE order_id = UUID_TO_BIN(:orderId) AND status = :status AND resolved_at IS NULL""")
                .param("now", utc(now))
                .param("orderId", orderId.toString())
                .param("status", status.name())
                .update() == 1;
    }

    /** Every unresolved flag whose order is still in the flagged status, longest overdue first. */
    public List<OpenFlag> open() {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(s.order_id) AS order_id, o.order_number, o.type, s.status, s.reason,
                               s.since, s.due_at, s.detected_at, BIN_TO_UUID(o.store_id) AS store_id, o.store_name,
                               BIN_TO_UUID(o.customer_id) AS customer_id
                        FROM stuck_order s JOIN customer_order o ON o.id = s.order_id
                        WHERE s.resolved_at IS NULL AND o.status = s.status
                        ORDER BY s.due_at, s.detected_at, s.order_id""")
                .query((rs, row) -> new OpenFlag(UUID.fromString(rs.getString("order_id")),
                        rs.getString("order_number"), OrderType.valueOf(rs.getString("type")),
                        OrderStatus.valueOf(rs.getString("status")), StuckReason.valueOf(rs.getString("reason")),
                        OrderEventLog.instant(rs, "since"), OrderEventLog.instant(rs, "due_at"),
                        OrderEventLog.instant(rs, "detected_at"), UUID.fromString(rs.getString("store_id")),
                        rs.getString("store_name"), UUID.fromString(rs.getString("customer_id"))))
                .list();
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An order is flagged only inside the transaction that locks it");
        }
    }
}
