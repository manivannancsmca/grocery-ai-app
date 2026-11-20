package com.grocery.dbtests.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A MySQL column type ({@code VARCHAR(55)}, {@code INT UNSIGNED}, {@code DECIMAL(12,2)}, {@code ENUM('A','B')},
 * {@code DATETIME(6)}, ...), parsed from a migration's column definition or from {@code COLUMN_TYPE} of
 * information_schema, and the one question the expand/contract lint asks: can the new type hold every value of the old
 * one? A change that cannot be proven to keep every value counts as narrowing.
 */
public record ColumnType(String base, List<String> arguments, boolean unsigned) {

    private static final List<String> INTEGERS = List.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "BIGINT");
    private static final Map<String, Long> CHARACTER_CAPACITY = Map.of(
            "TINYTEXT", 255L, "TEXT", 65_535L, "MEDIUMTEXT", 16_777_215L, "LONGTEXT", 4_294_967_295L);
    private static final Map<String, Long> BINARY_CAPACITY = Map.of(
            "TINYBLOB", 255L, "BLOB", 65_535L, "MEDIUMBLOB", 16_777_215L, "LONGBLOB", 4_294_967_295L);
    private static final Map<String, String> SYNONYMS = Map.of("INTEGER", "INT", "BOOL", "TINYINT",
            "BOOLEAN", "TINYINT", "DEC", "DECIMAL", "NUMERIC", "DECIMAL", "FIXED", "DECIMAL", "REAL", "DOUBLE",
            "CHARACTER", "CHAR", "NCHAR", "CHAR", "NVARCHAR", "VARCHAR");
    private static final Set<String> TEMPORAL_WITH_FRACTION = Set.of("DATETIME", "TIMESTAMP", "TIME");

    /**
     * Parses a type from its tokens: the type word, an optional argument list and the modifiers
     * {@code UNSIGNED}/{@code SIGNED}/{@code ZEROFILL}/{@code PRECISION}/{@code VARYING}; returns the type and how many
     * tokens it used.
     */
    static Parsed parse(List<SqlScript.Token> tokens, int start) {
        int i = start;
        String base = tokens.get(i++).text().toUpperCase(Locale.ROOT);
        if (base.equals("DOUBLE") && i < tokens.size() && tokens.get(i).is("PRECISION")) {
            i++;
        }
        if ((base.equals("CHARACTER") || base.equals("CHAR")) && i < tokens.size() && tokens.get(i).is("VARYING")) {
            base = "VARCHAR";
            i++;
        }
        boolean bool = base.equals("BOOL") || base.equals("BOOLEAN");
        base = SYNONYMS.getOrDefault(base, base);
        List<String> arguments = new ArrayList<>();
        if (i < tokens.size() && tokens.get(i).isSymbol("(")) {
            i++;
            StringBuilder argument = new StringBuilder();
            while (i < tokens.size() && !tokens.get(i).isSymbol(")")) {
                SqlScript.Token token = tokens.get(i++);
                if (token.isSymbol(",")) {
                    arguments.add(argument.toString());
                    argument.setLength(0);
                } else {
                    argument.append(token.text());
                }
            }
            arguments.add(argument.toString());
            i++;                                                    // ')'
        } else if (bool) {
            arguments.add("1");
        }
        boolean unsigned = false;
        while (i < tokens.size() && (tokens.get(i).is("UNSIGNED") || tokens.get(i).is("SIGNED")
                || tokens.get(i).is("ZEROFILL"))) {
            unsigned |= tokens.get(i).is("UNSIGNED") || tokens.get(i).is("ZEROFILL");
            i++;
        }
        return new Parsed(new ColumnType(base, List.copyOf(arguments), unsigned), i - start);
    }

    /** A type and the number of tokens it took. */
    record Parsed(ColumnType type, int length) {
    }

    /** Parses information_schema's {@code COLUMN_TYPE}, e.g. {@code decimal(12,2)}, {@code int unsigned}. */
    public static ColumnType of(String columnType) {
        List<SqlScript.Statement> statements = SqlScript.parse(columnType).statements();
        return parse(statements.getFirst().tokens(), 0).type();
    }

    private long argument(int index, long otherwise) {
        if (arguments.size() <= index || arguments.get(index).isBlank()) {
            return otherwise;
        }
        return Long.parseLong(arguments.get(index).trim());
    }

    /** Whether a column of this type, changed to {@code to}, can lose or reject a value it holds today. */
    public boolean narrowedBy(ColumnType to) {
        if (equals(to)) {
            return false;
        }
        String from = base;
        String target = to.base;
        if (INTEGERS.contains(from) && INTEGERS.contains(target)) {
            int a = INTEGERS.indexOf(from);
            int b = INTEGERS.indexOf(target);
            if (to.unsigned && !unsigned) {
                return true;                                         // negative values are lost
            }
            return b < a || (b == a && unsigned && !to.unsigned);    // a smaller range, or half the range
        }
        if (INTEGERS.contains(from) && target.equals("DECIMAL")) {
            long digits = INTEGERS.indexOf(from) == 4 ? 20 : List.of(3L, 5L, 8L, 10L).get(INTEGERS.indexOf(from));
            return to.argument(0, 10) - to.argument(1, 0) < digits || (to.unsigned && !unsigned);
        }
        if (from.equals("DECIMAL") && target.equals("DECIMAL")) {
            long precision = argument(0, 10);
            long scale = argument(1, 0);
            long toPrecision = to.argument(0, 10);
            long toScale = to.argument(1, 0);
            return toScale < scale || toPrecision - toScale < precision - scale || (to.unsigned && !unsigned);
        }
        if ((from.equals("FLOAT") || from.equals("DOUBLE")) && (target.equals("FLOAT") || target.equals("DOUBLE"))) {
            return from.equals("DOUBLE") && target.equals("FLOAT");
        }
        Long fromChars = characterCapacity();
        Long toChars = to.characterCapacity();
        if (fromChars != null && toChars != null) {
            return toChars < fromChars;
        }
        Long fromBytes = binaryCapacity();
        Long toBytes = to.binaryCapacity();
        if (fromBytes != null && toBytes != null) {
            return toBytes < fromBytes;
        }
        if (TEMPORAL_WITH_FRACTION.contains(from) && from.equals(target)) {
            return to.argument(0, 0) < argument(0, 0);
        }
        if (from.equals("DATE") && (target.equals("DATETIME"))) {
            return false;
        }
        if (from.equals("TIMESTAMP") && target.equals("DATETIME")) {
            return to.argument(0, 0) < argument(0, 0);
        }
        if ((from.equals("ENUM") || from.equals("SET")) && from.equals(target)) {
            return !to.arguments.containsAll(arguments);
        }
        if (from.equals("BIT") && target.equals("BIT")) {
            return to.argument(0, 1) < argument(0, 1);
        }
        return true;                                                   // another family: not provably compatible
    }

    /** Characters a character type holds; null for other types. */
    private Long characterCapacity() {
        if (base.equals("CHAR")) {
            return argument(0, 1);
        }
        if (base.equals("VARCHAR")) {
            return argument(0, 0);
        }
        return CHARACTER_CAPACITY.get(base);
    }

    /** Bytes a binary type holds; null for other types. */
    private Long binaryCapacity() {
        if (base.equals("BINARY")) {
            return argument(0, 1);
        }
        if (base.equals("VARBINARY")) {
            return argument(0, 0);
        }
        return BINARY_CAPACITY.get(base);
    }

    @Override
    public String toString() {
        String text = base + (arguments.isEmpty() ? "" : "(" + String.join(",", arguments) + ")");
        return unsigned ? text + " UNSIGNED" : text;
    }
}
