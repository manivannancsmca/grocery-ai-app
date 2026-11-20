package com.grocery.payment.repository;

import com.grocery.common.id.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * What payment reconciliation did (Phase 10 §3), durable and shared by every replica: the counters in
 * {@code reconciliation_stats} (one row) and every recovery in {@code reconciliation_recovery}. Plain JDBC; each call is
 * its own short statement (no business row is locked).
 */
@Repository
public class ReconciliationLedger {

    /** The counters since the job first ran; {@code lastRunAt} null = it never ran. */
    public record Stats(Instant lastRunAt, long checked, long recovered, long failures) {
    }

    /** One recovery: the order, what the provider had ({@code CAPTURED}, {@code AUTHORIZED}, {@code FAILED}), when. */
    public record Recovery(UUID orderId, String orderNumber, String outcome, String razorpayPaymentId, Instant at) {
    }

    private final JdbcClient jdbc;

    public ReconciliationLedger(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void recovered(UUID paymentId, UUID orderId, String orderNumber, String outcome, String razorpayPaymentId,
                          Instant at) {
        jdbc.sql("""
                        INSERT INTO reconciliation_recovery
                            (id, payment_id, order_id, order_number, outcome, razorpay_payment_id, recovered_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:paymentId), UUID_TO_BIN(:orderId), :orderNumber, :outcome,
                                :razorpayPaymentId, :at)""")
                .param("id", Ids.newId().toString())
                .param("paymentId", paymentId.toString())
                .param("orderId", orderId.toString())
                .param("orderNumber", orderNumber)
                .param("outcome", outcome)
                .param("razorpayPaymentId", razorpayPaymentId)
                .param("at", utc(at))
                .update();
    }

    /** Adds one run's numbers (any replica); {@code lastRunAt} only moves forward. */
    public void runFinished(Instant at, int checked, int recovered, int failures) {
        jdbc.sql("""
                        UPDATE reconciliation_stats
                        SET last_run_at = GREATEST(COALESCE(last_run_at, :at), :at),
                            checked = checked + :checked, recovered = recovered + :recovered,
                            failures = failures + :failures
                        WHERE id = 1""")
                .param("at", utc(at))
                .param("checked", checked)
                .param("recovered", recovered)
                .param("failures", failures)
                .update();
    }

    public Stats stats() {
        return jdbc.sql("SELECT last_run_at, checked, recovered, failures FROM reconciliation_stats WHERE id = 1")
                .query((rs, row) -> new Stats(instant(rs, "last_run_at"), rs.getLong("checked"),
                        rs.getLong("recovered"), rs.getLong("failures")))
                .optional()
                .orElse(new Stats(null, 0, 0, 0));
    }

    /** The newest {@code limit} recoveries, newest first. */
    public List<Recovery> latest(int limit) {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(order_id) AS order_id, order_number, outcome, razorpay_payment_id,
                               recovered_at
                        FROM reconciliation_recovery ORDER BY recovered_at DESC, id DESC LIMIT :limit""")
                .param("limit", limit)
                .query((rs, row) -> new Recovery(UUID.fromString(rs.getString("order_id")),
                        rs.getString("order_number"), rs.getString("outcome"), rs.getString("razorpay_payment_id"),
                        instant(rs, "recovered_at")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
