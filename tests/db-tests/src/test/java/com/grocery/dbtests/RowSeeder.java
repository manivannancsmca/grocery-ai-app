package com.grocery.dbtests;

import com.grocery.dbtests.DatabaseSchema.ColumnInfo;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts one representative row into every table of a database (the previous release's data the next migrations
 * must cope with), generically from information_schema: parents before children (foreign-key order, the child gets
 * the parent row's key), a value of the right type and size for every column (unique-safe random ids and texts, the
 * first ENUM value, a literal a CHECK constraint demands, timestamps increasing with the column position so
 * {@code valid_until > valid_from} holds), generated and auto-increment columns left to the server. A row with every
 * column set is tried first, then a minimal one (nullable columns NULL, defaults used).
 */
final class RowSeeder {

    static final String FLYWAY_TABLE = "flyway_schema_history";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 11, 14, 10, 0);

    /** What happened: the seeded row of each table and the error of each table that could not be seeded. */
    record Result(Map<String, Map<String, Object>> rows, Map<String, String> failures) {
    }

    private record ForeignKey(String table, String name, List<String> columns, String referencedTable,
                              List<String> referencedColumns) {
    }

    private RowSeeder() {
    }

    static Result seed(Connection connection, String schema) throws SQLException {
        DatabaseSchema tables = DatabaseSchema.read(connection, schema);
        List<ForeignKey> foreignKeys = foreignKeys(connection, schema);
        Map<String, String> checks = checks(connection, schema);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Map<String, String> failures = new TreeMap<>();
        for (String table : order(tables.tableNames(), foreignKeys)) {
            if (table.equalsIgnoreCase(FLYWAY_TABLE)) {
                continue;
            }
            Map<String, Object> existing = firstRow(connection, table);
            if (existing != null) {
                rows.put(table, existing);                 // a migration inserted it (e.g. a singleton stats row)
                continue;
            }
            List<ForeignKey> parents = foreignKeys.stream().filter(fk -> fk.table().equals(table)).toList();
            String error = null;
            for (boolean full : new boolean[]{true, false}) {
                Map<String, Object> row = row(tables.columns(table), parents, rows, checks.getOrDefault(table, ""),
                        full);
                try {
                    insert(connection, table, row);
                    for (ColumnInfo column : tables.columns(table)) {
                        if (column.autoIncrement()) {
                            row.put(column.name(), lastInsertId(connection));      // children reference it
                        }
                    }
                    rows.put(table, row);
                    error = null;
                    break;
                } catch (SQLException e) {
                    error = e.getMessage();
                }
            }
            if (error != null) {
                failures.put(table, error);
            }
        }
        return new Result(rows, failures);
    }

    /**
     * One row: a foreign-key column takes the parent row's value (NULL if nullable and there is no parent row, or for
     * a self reference); other columns a generated value, except - in a minimal row - nullable columns and columns
     * with a default.
     */
    private static Map<String, Object> row(List<ColumnInfo> columns, List<ForeignKey> parents,
                                           Map<String, Map<String, Object>> seeded, String checkClauses,
                                           boolean full) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnInfo column : columns) {
            if (column.generated() || column.autoIncrement()) {
                continue;
            }
            ForeignKey foreignKey = parents.stream().filter(fk -> fk.columns().contains(column.name())).findFirst()
                    .orElse(null);
            if (foreignKey != null) {
                Map<String, Object> parent = foreignKey.referencedTable().equals(column.table()) ? null
                        : seeded.get(foreignKey.referencedTable());
                Object value = parent == null ? null
                        : parent.get(foreignKey.referencedColumns().get(foreignKey.columns().indexOf(column.name())));
                if (value != null) {
                    row.put(column.name(), value);
                } else if (!column.nullable()) {
                    row.put(column.name(), value(column, checkClauses));     // no parent row: the insert fails
                }
            } else if (full || (!column.nullable() && column.defaultValue() == null)) {
                row.put(column.name(), value(column, checkClauses));
            }
        }
        return row;
    }

    /** A value of the column's type and size; a literal its CHECK constraint names wins. */
    static Object value(ColumnInfo column, String checkClauses) {
        String checked = checkedLiteral(column.name(), checkClauses);
        String random = HexFormat.of().formatHex(uuidBytes(UUID.randomUUID()));
        long length = column.maxLength() == null ? 0 : column.maxLength();
        return switch (column.dataType()) {
            case "tinyint", "smallint", "mediumint", "int", "integer", "bigint", "bit" -> 1;
            case "decimal", "numeric" -> {
                long integerDigits = (column.precision() == null ? 10 : column.precision())
                        - (column.scale() == null ? 0 : column.scale());
                yield integerDigits >= 1 ? BigDecimal.ONE : new BigDecimal("0.5");
            }
            case "float", "double", "real" -> 1.0;
            case "char" -> checked != null ? checked
                    : length == 36 ? UUID.randomUUID().toString() : fit(random + random, length);
            case "varchar" -> checked != null ? checked : fit("s" + random, Math.min(length, 24));
            case "tinytext", "text", "mediumtext", "longtext" -> checked != null ? checked : "seed " + random;
            case "enum", "set" -> checked != null ? checked : firstValue(column.columnType());
            case "json" -> "{}";
            case "binary" -> length == 16 ? uuidBytes(UUID.randomUUID()) : new byte[(int) Math.max(length, 1)];
            case "varbinary" -> uuidBytes(UUID.randomUUID());
            case "tinyblob", "blob", "mediumblob", "longblob" -> new byte[]{0, 1, 2, 3};
            case "date" -> LocalDate.of(2026, 11, 14);
            case "datetime", "timestamp" -> BASE_TIME.plusMinutes(column.position());
            case "time" -> LocalTime.of(10, 0);
            case "year" -> 2026;
            default -> null;
        };
    }

    private static String fit(String text, long length) {
        return length > 0 && text.length() > length ? text.substring(0, (int) length) : text;
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array();
    }

    private static String firstValue(String columnType) {
        Matcher value = Pattern.compile("'((?:[^']|'')*)'").matcher(columnType);
        return value.find() ? value.group(1).replace("''", "'") : null;
    }

    /**
     * {@code `col` = _utf8mb4'X'} or {@code `col` in (_utf8mb4'X', ...)} in a CHECK clause: X. information_schema
     * shows the quotes of string literals escaped ({@code _utf8mb4\'X\'}).
     */
    static String checkedLiteral(String column, String checkClauses) {
        Matcher literal = Pattern.compile("`" + Pattern.quote(column)
                        + "`\\s*(?:=|in\\s*\\()\\s*(?:_\\w+)?\\\\?'((?:[^'\\\\]|\\\\[^'])*)\\\\?'",
                Pattern.CASE_INSENSITIVE).matcher(checkClauses);
        return literal.find() ? literal.group(1) : null;
    }

    private static void insert(Connection connection, String table, Map<String, Object> row) throws SQLException {
        String sql = row.isEmpty() ? "INSERT INTO `" + table + "` () VALUES ()"
                : "INSERT INTO `" + table + "` (" + String.join(", ", row.keySet().stream().map(c -> "`" + c + "`")
                .toList()) + ") VALUES (" + String.join(", ", row.keySet().stream().map(c -> "?").toList()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : row.values()) {
                statement.setObject(i++, value);
            }
            statement.executeUpdate();
        }
    }

    /** Parents first (a table after the tables it references); cycles keep the alphabetical order. */
    private static List<String> order(List<String> tables, List<ForeignKey> foreignKeys) {
        Map<String, Set<String>> parents = new HashMap<>();
        for (ForeignKey fk : foreignKeys) {
            if (!fk.table().equals(fk.referencedTable())) {
                parents.computeIfAbsent(fk.table(), t -> new LinkedHashSet<>()).add(fk.referencedTable());
            }
        }
        List<String> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String table : tables) {
            visit(table, parents, ordered, visiting);
        }
        return ordered;
    }

    private static void visit(String table, Map<String, Set<String>> parents, List<String> ordered,
                              Set<String> visiting) {
        if (ordered.contains(table) || !visiting.add(table)) {
            return;
        }
        for (String parent : parents.getOrDefault(table, Set.of())) {
            visit(parent, parents, ordered, visiting);
        }
        ordered.add(table);
    }

    private static List<ForeignKey> foreignKeys(Connection connection, String schema) throws SQLException {
        Map<String, ForeignKey> keys = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION""")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1) + "." + rows.getString(2);
                    ForeignKey key = keys.computeIfAbsent(id, k -> {
                        try {
                            return new ForeignKey(rows.getString(1), rows.getString(2), new ArrayList<>(),
                                    rows.getString(4), new ArrayList<>());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    key.columns().add(rows.getString(3));
                    key.referencedColumns().add(rows.getString(5));
                }
            }
        }
        return List.copyOf(keys.values());
    }

    /** The CHECK clauses of each table, concatenated. */
    private static Map<String, String> checks(Connection connection, String schema) throws SQLException {
        Map<String, String> checks = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.TABLE_NAME, c.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS c
                JOIN information_schema.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA
                     AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME AND t.CONSTRAINT_TYPE = 'CHECK'
                WHERE c.CONSTRAINT_SCHEMA = ?""")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    checks.merge(rows.getString(1), rows.getString(2), (a, b) -> a + " " + b);
                }
            }
        }
        return checks;
    }

    /** Rows per table (to prove the seeded data survived the later migrations). */
    static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            result.next();
            return result.getLong(1);
        }
    }

    /** The table's first row (column → value), or null when it is empty. */
    private static Map<String, Object> firstRow(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM `" + table + "` LIMIT 1")) {
            if (!result.next()) {
                return null;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
            }
            return row;
        }
    }

    private static long lastInsertId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            result.next();
            return result.getLong(1);
        }
    }
}
