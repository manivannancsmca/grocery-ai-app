package com.grocery.dbtests;

import com.grocery.dbtests.lint.ColumnType;
import com.grocery.dbtests.lint.ExpandContractLint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** A database's tables and columns from information_schema (the state a migration runs against). */
final class DatabaseSchema implements ExpandContractLint.Schema {

    /** One column of information_schema.COLUMNS. */
    record ColumnInfo(String table, String name, int position, String dataType, String columnType, boolean nullable,
                      String defaultValue, String extra, Long maxLength, Long precision, Long scale,
                      Long datetimePrecision, String characterSet) {

        boolean generated() {
            return extra != null && extra.toUpperCase(Locale.ROOT).contains("GENERATED")
                    && !extra.toUpperCase(Locale.ROOT).contains("DEFAULT_GENERATED");
        }

        boolean autoIncrement() {
            return extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
        }
    }

    private final Map<String, List<ColumnInfo>> tables;

    private DatabaseSchema(Map<String, List<ColumnInfo>> tables) {
        this.tables = tables;
    }

    static DatabaseSchema read(Connection connection, String schema) throws SQLException {
        Map<String, List<ColumnInfo>> tables = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.TABLE_NAME, c.COLUMN_NAME, c.ORDINAL_POSITION, c.DATA_TYPE, c.COLUMN_TYPE, c.IS_NULLABLE,
                       c.COLUMN_DEFAULT, c.EXTRA, c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, c.NUMERIC_SCALE,
                       c.DATETIME_PRECISION, c.CHARACTER_SET_NAME
                FROM information_schema.COLUMNS c
                JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
                WHERE c.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE'
                ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION""")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ColumnInfo column = new ColumnInfo(rows.getString(1), rows.getString(2), rows.getInt(3),
                            rows.getString(4).toLowerCase(Locale.ROOT), rows.getString(5),
                            "YES".equals(rows.getString(6)), rows.getString(7), rows.getString(8),
                            (Long) rows.getObject(9, Long.class), (Long) rows.getObject(10, Long.class),
                            (Long) rows.getObject(11, Long.class), (Long) rows.getObject(12, Long.class),
                            rows.getString(13));
                    tables.computeIfAbsent(column.table(), t -> new ArrayList<>()).add(column);
                }
            }
        }
        return new DatabaseSchema(tables);
    }

    /** Table names as stored (case kept). */
    List<String> tableNames() {
        return List.copyOf(tables.keySet());
    }

    List<ColumnInfo> columns(String table) {
        return tables.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(table)).findFirst()
                .map(Map.Entry::getValue).orElse(List.of());
    }

    Optional<ColumnInfo> columnInfo(String table, String column) {
        return columns(table).stream().filter(c -> c.name().equalsIgnoreCase(column)).findFirst();
    }

    @Override
    public boolean hasTable(String table) {
        return tables.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(table));
    }

    @Override
    public Optional<ExpandContractLint.Column> column(String table, String column) {
        return columnInfo(table, column)
                .map(c -> new ExpandContractLint.Column(ColumnType.of(c.columnType()), c.nullable()));
    }
}
