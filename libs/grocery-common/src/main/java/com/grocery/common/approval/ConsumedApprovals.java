package com.grocery.common.approval;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The single-use record of approvals ({@code consumed_approval}, contract Phase 11 §5 step 6). The {@code INSERT} runs
 * on its own pooled connection in auto-commit — its own transaction, committed before the handler starts — so a
 * consumed approval stays consumed even when the business call then fails, and of two concurrent requests with the
 * same approval exactly one gets the row (the other hits the primary key).
 *
 * <p>Required table (in the service's Flyway migration; MySQL 8.4):
 * <pre>
 * CREATE TABLE consumed_approval (
 *     approval_id BINARY(16)  NOT NULL,
 *     action      VARCHAR(40) NOT NULL,
 *     approver_id BINARY(16)  NOT NULL,
 *     consumed_at DATETIME(6) NOT NULL,
 *     CONSTRAINT pk_consumed_approval PRIMARY KEY (approval_id),
 *     INDEX idx_consumed_approval_consumed_at (consumed_at)
 * ) ENGINE = InnoDB;
 * </pre>
 * UUIDs are stored as their 16 big-endian bytes ({@code UUID_TO_BIN(id)} without the swap flag; Hibernate's
 * {@code BINARY(16)} mapping), {@code consumed_at} in UTC.
 */
public class ConsumedApprovals {

    static final String INSERT_SQL =
            "INSERT INTO consumed_approval (approval_id, action, approver_id, consumed_at) VALUES (?, ?, ?, ?)";
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;
    private final Clock clock;

    public ConsumedApprovals(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /**
     * Records the approval as used, committed at once.
     *
     * @return {@code true} if it was recorded now; {@code false} if it had been used before
     * @throws SQLException when the database fails (the request must then not run)
     */
    public boolean consume(ApprovalContext approval) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(true);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.setBytes(1, bytes(approval.approvalId()));
                    statement.setString(2, approval.action());
                    statement.setBytes(3, bytes(approval.approverId()));
                    statement.setObject(4, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                    statement.executeUpdate();
                    return true;
                } catch (SQLException e) {
                    if (isDuplicate(e)) {
                        return false;
                    }
                    throw e;
                }
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** The 16 big-endian bytes of {@code id} (MySQL {@code UUID_TO_BIN(id)}). */
    public static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }

    private static boolean isDuplicate(SQLException e) {
        String state = e.getSQLState();
        return e instanceof SQLIntegrityConstraintViolationException || e.getErrorCode() == 1062
                || (state != null && state.startsWith("23"));
    }
}
