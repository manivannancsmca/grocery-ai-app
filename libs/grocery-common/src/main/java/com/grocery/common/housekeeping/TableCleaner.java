package com.grocery.common.housekeeping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Deletes old rows of one platform table in small batches:
 * {@code DELETE FROM <table> WHERE <time column> < :cutoff ORDER BY <order> LIMIT <batch-size>}, repeated until a
 * batch deletes fewer rows than its size. Each statement is its own short auto-commit transaction in READ COMMITTED
 * (no gap locks; rows that do not match are released at once), so business transactions are never blocked for more
 * than one batch of very old rows. Several replicas may run it at the same time: they only delete the same old rows.
 *
 * <p>Only tables that exist (with their time column) are cleaned, and only on MySQL / MariaDB (the statement form is
 * MySQL's). Times are compared as UTC {@code DATETIME(6)} values, the way grocery-common writes them.
 */
public class TableCleaner {

    private static final Logger log = LoggerFactory.getLogger(TableCleaner.class);
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final long PAUSE_BETWEEN_BATCHES_MILLIS = 20;

    /**
     * A platform table to clean. {@code orderBy} keeps each batch cheap: the indexed time column for
     * {@code outbox_event}, the primary key elsewhere (UUIDv7 event ids are time-ordered, so the oldest rows come first
     * even without an index on the time column).
     */
    public record Target(String table, String timeColumn, String orderBy, Duration retention) {
    }

    public record Result(String table, boolean skipped, long deleted, int batches, Instant cutoff) {
    }

    public static Target outboxEvents(Duration retention) {
        return new Target("outbox_event", "occurred_at", "occurred_at", retention);
    }

    public static Target processedEvents(Duration retention) {
        return new Target("processed_event", "processed_at", "event_id, consumer", retention);
    }

    public static Target idempotencyKeys(Duration retention) {
        return new Target("idempotency_key", "created_at", "scope, idem_key", retention);
    }

    /** grocery-common 0.8.0: random approval ids are not time-ordered, so the batches follow the indexed time column. */
    public static Target consumedApprovals(Duration retention) {
        return new Target("consumed_approval", "consumed_at", "consumed_at", retention);
    }

    private final DataSource dataSource;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;
    private volatile Boolean mysql;

    public TableCleaner(DataSource dataSource, Clock clock, int batchSize, int maxBatches) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    /**
     * @param stop checked between batches (shutdown)
     * @throws SQLException when the database fails (the next scheduled run tries again)
     */
    public Result clean(Target target, BooleanSupplier stop) throws SQLException {
        Instant cutoff = clock.instant().minus(target.retention());
        if (!isMysql()) {
            return new Result(target.table(), true, 0, 0, cutoff);
        }
        if (!exists(target)) {
            log.debug("Housekeeping: no table {} (with column {}) in this service, nothing to clean", target.table(),
                    target.timeColumn());
            return new Result(target.table(), true, 0, 0, cutoff);
        }
        String sql = "DELETE FROM " + target.table() + " WHERE " + target.timeColumn() + " < ? ORDER BY "
                + target.orderBy() + " LIMIT " + batchSize;
        LocalDateTime cutoffUtc = LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        long started = System.nanoTime();
        long deleted = 0;
        int batches = 0;
        while (batches < maxBatches && !stop.getAsBoolean()) {
            int rows = deleteBatch(sql, cutoffUtc);
            batches++;
            deleted += rows;
            if (rows < batchSize) {
                break;
            }
            try {
                Thread.sleep(PAUSE_BETWEEN_BATCHES_MILLIS);       // let other work through between batches
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (deleted > 0) {
            log.info("Housekeeping: deleted {} rows from {} with {} before {} (retention {}) in {} batch(es), {} ms{}",
                    deleted, target.table(), target.timeColumn(), cutoff, target.retention(), batches, millis,
                    batches >= maxBatches ? "; more left for the next run" : "");
        } else {
            log.debug("Housekeeping: nothing to delete from {} before {}", target.table(), cutoff);
        }
        return new Result(target.table(), false, deleted, batches, cutoff);
    }

    private int deleteBatch(String sql, LocalDateTime cutoff) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            try {
                connection.setAutoCommit(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.setObject(1, cutoff);
                    return statement.executeUpdate();
                }
            } finally {
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private boolean isMysql() throws SQLException {
        Boolean known = mysql;
        if (known == null) {
            try (Connection connection = dataSource.getConnection()) {
                String product = connection.getMetaData().getDatabaseProductName();
                known = product != null && (product.toLowerCase(Locale.ROOT).contains("mysql")
                        || product.toLowerCase(Locale.ROOT).contains("mariadb"));
                if (!known) {
                    log.warn("Housekeeping disabled: the database is {} (only MySQL / MariaDB are supported)", product);
                }
            }
            mysql = known;
        }
        return known;
    }

    private boolean exists(Target target) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();
            String escape = metaData.getSearchStringEscape();
            try (ResultSet columns = metaData.getColumns(catalog, catalog == null ? connection.getSchema() : null,
                    pattern(target.table(), escape), pattern(target.timeColumn(), escape))) {
                return columns.next();
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
