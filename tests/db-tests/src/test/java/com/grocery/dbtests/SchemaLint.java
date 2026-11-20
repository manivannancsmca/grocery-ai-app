package com.grocery.dbtests;

import com.grocery.dbtests.DatabaseSchema.ColumnInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The schema lint of a fully migrated database (docs/api/phase-13-observability-and-testing.md §4), from
 * information_schema:
 * <ul>
 *   <li>{@code primary-key} - every table has a primary key, Flyway's history table included;</li>
 *   <li>{@code utf8mb4} - every table's collation and every character column are utf8mb4;</li>
 *   <li>{@code foreign-key-index} - every foreign key's columns are the leftmost columns of some index;</li>
 *   <li>{@code outbox-table} - an {@code outbox_event} table with grocery-common 0.9.0's columns (the INSERT of
 *       {@code JdbcOutboxPublisher} and the Debezium outbox router's fields) and its primary key;</li>
 *   <li>{@code processed-event-table} - a service with a {@code @KafkaListener} has {@code processed_event} with
 *       grocery-common's columns and primary key (event_id, consumer).</li>
 * </ul>
 * A finding is identified as {@code <service>/<rule>/<object>}; documented exceptions are in
 * {@code schema-lint-exceptions.properties} (key = finding id, value = the reason).
 */
final class SchemaLint {

    /** A finding: its id ({@code <rule>/<object>}, prefixed with the service in exceptions) and what is wrong. */
    record Finding(String rule, String object, String message) {

        String id(String service) {
            return service + "/" + rule + "/" + object;
        }

        @Override
        public String toString() {
            return "[" + rule + "] " + object + ": " + message;
        }
    }

    /** An expected column: information_schema DATA_TYPE, nullability, and a minimal length / exact precision. */
    private record Expected(String dataType, boolean nullable, Long minLength, Long datetimePrecision) {
    }

    /** grocery-common 0.9.0's outbox table (JdbcOutboxPublisher's javadoc; Debezium's EventRouter fields). */
    static final Map<String, Expected> OUTBOX_COLUMNS = ordered(
            "id", new Expected("char", false, 36L, null),
            "topic", new Expected("varchar", false, null, null),
            "aggregate_type", new Expected("varchar", false, null, null),
            "aggregate_id", new Expected("varchar", false, null, null),
            "event_type", new Expected("varchar", false, null, null),
            "event_version", new Expected("int", false, null, null),
            "payload", new Expected("json", false, null, null),
            "payload_avro", new Expected("longblob", true, null, null),
            "correlation_id", new Expected("varchar", true, null, null),
            "causation_id", new Expected("varchar", true, null, null),
            "traceparent", new Expected("varchar", true, 55L, null),
            "occurred_at", new Expected("datetime", false, null, 6L));

    /** grocery-common's idempotent-consumer table (JdbcProcessedEventGuard). */
    static final Map<String, Expected> PROCESSED_EVENT_COLUMNS = ordered(
            "event_id", new Expected("char", false, 36L, null),
            "consumer", new Expected("varchar", false, null, null),
            "processed_at", new Expected("datetime", false, null, 6L));

    private SchemaLint() {
    }

    private static Map<String, Expected> ordered(Object... namesAndColumns) {
        Map<String, Expected> columns = new LinkedHashMap<>();
        for (int i = 0; i < namesAndColumns.length; i += 2) {
            columns.put((String) namesAndColumns[i], (Expected) namesAndColumns[i + 1]);
        }
        return columns;
    }

    static List<Finding> check(Connection connection, String schema, boolean consumesEvents) throws SQLException {
        List<Finding> findings = new ArrayList<>();
        DatabaseSchema tables = DatabaseSchema.read(connection, schema);

        for (String table : strings(connection, """
                SELECT t.TABLE_NAME FROM information_schema.TABLES t
                WHERE t.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE'
                  AND NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS c
                                  WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME
                                    AND c.CONSTRAINT_TYPE = 'PRIMARY KEY')
                ORDER BY t.TABLE_NAME""", schema)) {
            findings.add(new Finding("primary-key", table, "has no primary key"));
        }

        for (List<String> row : rows(connection, """
                SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                  AND (TABLE_COLLATION IS NULL OR TABLE_COLLATION NOT LIKE 'utf8mb4%')
                ORDER BY TABLE_NAME""", schema)) {
            findings.add(new Finding("utf8mb4", row.get(0), "table collation " + row.get(1) + " is not utf8mb4"));
        }
        for (String table : tables.tableNames()) {
            for (ColumnInfo column : tables.columns(table)) {
                if (column.characterSet() != null && !column.characterSet().equals("utf8mb4")) {
                    findings.add(new Finding("utf8mb4", table + "." + column.name(), "character set "
                            + column.characterSet() + " (" + column.columnType() + ") is not utf8mb4"));
                }
            }
        }

        findings.addAll(foreignKeysWithoutIndex(connection, schema));

        if (!tables.hasTable("outbox_event")) {
            findings.add(new Finding("outbox-table", "outbox_event", "the database has no outbox_event table"));
        } else {
            findings.addAll(columns(tables, "outbox_event", OUTBOX_COLUMNS, "outbox-table"));
            findings.addAll(primaryKey(connection, schema, "outbox_event", List.of("id"), "outbox-table"));
        }
        if (consumesEvents) {
            if (!tables.hasTable("processed_event")) {
                findings.add(new Finding("processed-event-table", "processed_event",
                        "the service consumes events but has no processed_event table"));
            } else {
                findings.addAll(columns(tables, "processed_event", PROCESSED_EVENT_COLUMNS, "processed-event-table"));
                findings.addAll(primaryKey(connection, schema, "processed_event", List.of("event_id", "consumer"),
                        "processed-event-table"));
            }
        }
        return findings;
    }

    private static List<Finding> columns(DatabaseSchema tables, String table, Map<String, Expected> expected,
                                         String rule) {
        List<Finding> findings = new ArrayList<>();
        expected.forEach((name, column) -> {
            ColumnInfo actual = tables.columnInfo(table, name).orElse(null);
            if (actual == null) {
                findings.add(new Finding(rule, table + "." + name, "missing column (expected " + column.dataType()
                        + (column.nullable() ? " NULL" : " NOT NULL") + ")"));
                return;
            }
            List<String> problems = new ArrayList<>();
            if (!actual.dataType().equals(column.dataType())) {
                problems.add("type " + actual.columnType() + ", expected " + column.dataType());
            }
            if (actual.nullable() != column.nullable()) {
                problems.add(actual.nullable() ? "nullable, expected NOT NULL" : "NOT NULL, expected nullable");
            }
            if (column.minLength() != null && (actual.maxLength() == null || actual.maxLength() < column.minLength())) {
                problems.add("length " + actual.maxLength() + ", expected at least " + column.minLength());
            }
            if (column.datetimePrecision() != null
                    && !Objects.equals(actual.datetimePrecision(), column.datetimePrecision())) {
                problems.add("fractional seconds " + actual.datetimePrecision() + ", expected "
                        + column.datetimePrecision());
            }
            if (!problems.isEmpty()) {
                findings.add(new Finding(rule, table + "." + name, String.join("; ", problems)));
            }
        });
        return findings;
    }

    private static List<Finding> primaryKey(Connection connection, String schema, String table, List<String> columns,
                                            String rule) throws SQLException {
        List<String> actual = strings(connection, """
                SELECT k.COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE k
                WHERE k.TABLE_SCHEMA = ? AND k.TABLE_NAME = ? AND k.CONSTRAINT_NAME = 'PRIMARY'
                ORDER BY k.ORDINAL_POSITION""", schema, table);
        return actual.equals(columns) ? List.of()
                : List.of(new Finding(rule, table + ".PRIMARY", "primary key " + actual + ", expected " + columns));
    }

    /** Foreign keys whose columns are not the leftmost columns of any index of their table. */
    static List<Finding> foreignKeysWithoutIndex(Connection connection, String schema) throws SQLException {
        Map<String, List<String>> foreignKeys = new TreeMap<>();
        Map<String, String> tableOf = new TreeMap<>();
        for (List<String> row : rows(connection, """
                SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION""", schema)) {
            String key = row.get(0) + "." + row.get(1);
            foreignKeys.computeIfAbsent(key, k -> new ArrayList<>()).add(row.get(2).toLowerCase());
            tableOf.put(key, row.get(0));
        }
        Map<String, List<String>> indexes = new TreeMap<>();
        for (List<String> row : rows(connection, """
                SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ? AND COLUMN_NAME IS NOT NULL
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX""", schema)) {
            indexes.computeIfAbsent(row.get(0) + "\u0000" + row.get(1), k -> new ArrayList<>())
                    .add(row.get(2).toLowerCase());
        }
        List<Finding> findings = new ArrayList<>();
        foreignKeys.forEach((key, columns) -> {
            String table = tableOf.get(key);
            boolean covered = indexes.entrySet().stream()
                    .filter(index -> index.getKey().startsWith(table + "\u0000"))
                    .anyMatch(index -> index.getValue().size() >= columns.size()
                            && index.getValue().subList(0, columns.size()).equals(columns));
            if (!covered) {
                findings.add(new Finding("foreign-key-index", key, "foreign key " + columns
                        + " is not the leftmost part of any index"));
            }
        });
        return findings;
    }

    /** {@code schema-lint-exceptions.properties}: finding id → reason. */
    static Map<String, String> exceptions() {
        Properties properties = new Properties();
        try (InputStream in = SchemaLint.class.getResourceAsStream("/schema-lint-exceptions.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> exceptions = new TreeMap<>();
        properties.forEach((key, value) -> exceptions.put(key.toString().trim(), value.toString().trim()));
        return exceptions;
    }

    static List<String> strings(Connection connection, String sql, String... parameters) throws SQLException {
        return rows(connection, sql, parameters).stream().map(List::getFirst).toList();
    }

    static List<List<String>> rows(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<List<String>> rows = new ArrayList<>();
                int count = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= count; i++) {
                        row.add(result.getString(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }
}
