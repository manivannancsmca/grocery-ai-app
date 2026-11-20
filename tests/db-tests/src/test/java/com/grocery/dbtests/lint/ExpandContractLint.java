package com.grocery.dbtests.lint;

import com.grocery.dbtests.lint.SqlScript.Statement;
import com.grocery.dbtests.lint.SqlScript.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The expand/contract lint of migrations that run against a RELEASED schema (docs/api/phase-13-observability-and-testing.md
 * §4): while old and new versions of a service run side by side (rolling deploy, rollback), a migration may only expand
 * the schema. Rules, each failure naming file, line and rule:
 * <ul>
 *   <li>{@code no-drop-table} - {@code DROP TABLE} of a table that existed before;</li>
 *   <li>{@code no-drop-column} - {@code ALTER TABLE ... DROP [COLUMN] c};</li>
 *   <li>{@code no-rename-table} - {@code RENAME TABLE}, {@code ALTER TABLE ... RENAME [TO|AS]};</li>
 *   <li>{@code no-rename-column} - {@code RENAME COLUMN a TO b}, {@code CHANGE [COLUMN] a b ...} with {@code a != b};</li>
 *   <li>{@code no-narrowing-modify} - {@code MODIFY}/{@code CHANGE} to a type that cannot hold every existing value
 *       (shorter, smaller, fewer decimals, fewer ENUM values, signedness change, another type family) or from NULL to
 *       NOT NULL;</li>
 *   <li>{@code no-not-null-without-default} - {@code ADD [COLUMN] c ... NOT NULL} without {@code DEFAULT} (and not
 *       {@code AUTO_INCREMENT} or generated) on an existing table: the old version's INSERTs would fail.</li>
 * </ul>
 * Tables created by the linted migrations themselves are new: none of the rules applies to them. The SQL is read like
 * the server reads it ({@link SqlScript}): case-insensitive, comments ignored, any number of statements per file.
 */
public final class ExpandContractLint {

    /** The rules. */
    public enum Rule {
        DROP_TABLE("no-drop-table"),
        DROP_COLUMN("no-drop-column"),
        RENAME_TABLE("no-rename-table"),
        RENAME_COLUMN("no-rename-column"),
        NARROWING_MODIFY("no-narrowing-modify"),
        NOT_NULL_WITHOUT_DEFAULT("no-not-null-without-default");

        private final String id;

        Rule(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** One finding. */
    public record Violation(String file, int line, Rule rule, String message) {
        @Override
        public String toString() {
            return file + ":" + line + ": [" + rule.id() + "] " + message;
        }
    }

    /** A column as it is before the linted migrations. */
    public record Column(ColumnType type, boolean nullable) {
    }

    /** The schema before the linted migrations (the previous release). Names are compared case-insensitively. */
    public interface Schema {
        boolean hasTable(String table);

        Optional<Column> column(String table, String column);

        /** No tables at all (every table is created by the linted migrations). */
        Schema EMPTY = new Schema() {
            @Override
            public boolean hasTable(String table) {
                return false;
            }

            @Override
            public Optional<Column> column(String table, String column) {
                return Optional.empty();
            }
        };
    }

    /** A migration file: its name (for messages) and its SQL. */
    public record Script(String file, String sql) {
    }

    private static final Set<String> ADD_NOT_A_COLUMN = Set.of("INDEX", "KEY", "FULLTEXT", "SPATIAL", "PRIMARY",
            "UNIQUE", "FOREIGN", "CONSTRAINT", "CHECK", "PARTITION");
    private static final Set<String> DROP_NOT_A_COLUMN = Set.of("INDEX", "KEY", "PRIMARY", "FOREIGN", "CHECK",
            "CONSTRAINT", "PARTITION", "DEFAULT");

    private final Schema before;
    private final Set<String> createdTables = new HashSet<>();
    private final Map<String, Column> changedColumns = new HashMap<>();
    private final List<Violation> violations = new ArrayList<>();
    private String file;

    private ExpandContractLint(Schema before) {
        this.before = before;
    }

    /** Lints the scripts in order (a later script sees the tables and columns an earlier one created). */
    public static List<Violation> lint(Schema before, List<Script> scripts) {
        ExpandContractLint lint = new ExpandContractLint(before);
        for (Script script : scripts) {
            lint.file = script.file();
            for (Statement statement : SqlScript.parse(script.sql()).statements()) {
                lint.statement(statement);
            }
        }
        return List.copyOf(lint.violations);
    }

    public static List<Violation> lint(Schema before, Script... scripts) {
        return lint(before, List.of(scripts));
    }

    // ------------------------------------------------------------------------------------------------ statements

    private void statement(Statement statement) {
        List<Token> tokens = statement.tokens();
        int i = 0;
        if (statement.startsWith("CREATE")) {
            i = 1;
            while (i < tokens.size() && (tokens.get(i).is("TEMPORARY") || tokens.get(i).is("OR")
                    || tokens.get(i).is("REPLACE"))) {
                i++;
            }
            if (i < tokens.size() && tokens.get(i).is("TABLE")) {
                i = skipIfExists(tokens, i + 1, "NOT");
                createdTables.add(lastName(tokens, i).name);
            }
        } else if (statement.startsWith("DROP")) {
            i = 1;
            if (i < tokens.size() && tokens.get(i).is("TEMPORARY")) {
                i++;
            }
            if (i < tokens.size() && tokens.get(i).is("TABLE")) {
                for (List<Token> name : split(tokens.subList(skipIfExists(tokens, i + 1, null), tokens.size()))) {
                    Name table = lastName(name, 0);
                    if (table != null && !createdTables.contains(table.name)) {
                        add(tokens.getFirst(), Rule.DROP_TABLE, "drops table `" + table.name + "`");
                    }
                }
            }
        } else if (statement.startsWith("RENAME", "TABLE")) {
            for (List<Token> pair : split(tokens.subList(2, tokens.size()))) {
                Name table = lastName(pair, 0);
                if (table == null) {
                    continue;
                }
                if (createdTables.contains(table.name)) {
                    Name renamed = table.end + 1 < pair.size() ? lastName(pair, table.end + 1) : null;
                    if (renamed != null) {
                        createdTables.add(renamed.name);             // still a table of this release
                    }
                } else {
                    add(pair.getFirst(), Rule.RENAME_TABLE, "renames table `" + table.name + "`");
                }
            }
        } else if (statement.startsWith("ALTER")) {
            i = 1;
            while (i < tokens.size() && (tokens.get(i).is("ONLINE") || tokens.get(i).is("OFFLINE")
                    || tokens.get(i).is("IGNORE"))) {
                i++;
            }
            if (i < tokens.size() && tokens.get(i).is("TABLE")) {
                Name table = lastName(tokens, i + 1);
                if (table != null) {
                    for (List<Token> spec : split(tokens.subList(table.end, tokens.size()))) {
                        alterSpec(table.name, spec);
                    }
                }
            }
        }
    }

    private void alterSpec(String table, List<Token> spec) {
        if (spec.isEmpty()) {
            return;
        }
        boolean existing = !createdTables.contains(table);
        Token first = spec.getFirst();
        if (first.is("ADD")) {
            int i = 1;
            if (i < spec.size() && spec.get(i).is("COLUMN")) {
                i++;
            }
            if (i >= spec.size() || ADD_NOT_A_COLUMN.contains(spec.get(i).text().toUpperCase(Locale.ROOT))
                    && spec.get(i).kind() == SqlScript.Kind.WORD) {
                return;
            }
            if (spec.get(i).isSymbol("(")) {
                for (List<Token> definition : split(inside(spec, i))) {
                    addColumn(table, definition, existing);
                }
            } else {
                addColumn(table, spec.subList(i, spec.size()), existing);
            }
        } else if (first.is("DROP")) {
            if (spec.size() < 2 || DROP_NOT_A_COLUMN.contains(spec.get(1).text().toUpperCase(Locale.ROOT))
                    && spec.get(1).kind() == SqlScript.Kind.WORD) {
                return;
            }
            int i = spec.get(1).is("COLUMN") ? 2 : 1;
            if (existing && i < spec.size()) {
                add(first, Rule.DROP_COLUMN, "drops column `" + table + "`.`" + spec.get(i).name() + "`");
            }
        } else if (first.is("RENAME")) {
            if (spec.size() > 1 && (spec.get(1).is("INDEX") || spec.get(1).is("KEY"))) {
                return;
            }
            if (spec.size() > 1 && spec.get(1).is("COLUMN")) {
                if (existing) {
                    add(first, Rule.RENAME_COLUMN, "renames column `" + table + "`.`"
                            + (spec.size() > 2 ? spec.get(2).name() : "?") + "` to `"
                            + (spec.size() > 4 ? spec.get(4).name() : "?") + "`");
                }
            } else if (existing) {
                add(first, Rule.RENAME_TABLE, "renames table `" + table + "`");
            }
        } else if (first.is("CHANGE")) {
            int i = spec.size() > 1 && spec.get(1).is("COLUMN") ? 2 : 1;
            if (i + 2 >= spec.size()) {
                return;
            }
            String old = spec.get(i).name();
            String renamed = spec.get(i + 1).name();
            if (existing && !old.equals(renamed)) {
                add(first, Rule.RENAME_COLUMN, "renames column `" + table + "`.`" + old + "` to `" + renamed
                        + "` (CHANGE COLUMN)");
            }
            modifyColumn(table, old, spec.subList(i + 2, spec.size()), first, existing);
        } else if (first.is("MODIFY")) {
            int i = spec.size() > 1 && spec.get(1).is("COLUMN") ? 2 : 1;
            if (i + 1 >= spec.size()) {
                return;
            }
            modifyColumn(table, spec.get(i).name(), spec.subList(i + 1, spec.size()), first, existing);
        }
    }

    /** {@code name type attributes...}: a NOT NULL column without a default breaks the old version's INSERTs. */
    private void addColumn(String table, List<Token> definition, boolean existing) {
        if (definition.size() < 2 || !definition.getFirst().isIdentifier()) {
            return;
        }
        Token name = definition.getFirst();
        Definition column = Definition.of(definition.subList(1, definition.size()));
        changedColumns.put(table + "." + name.name(), new Column(column.type, !column.notNull));
        if (existing && column.notNull && !column.hasDefault && !column.autoIncrement && !column.generated) {
            add(name, Rule.NOT_NULL_WITHOUT_DEFAULT, "adds `" + table + "`.`" + name.name() + "` " + column.type
                    + " NOT NULL without DEFAULT to an existing table: the running version's INSERTs would fail");
        }
    }

    private void modifyColumn(String table, String column, List<Token> definition, Token at, boolean existing) {
        Definition changed = Definition.of(definition);
        String key = table + "." + column;
        Column old = changedColumns.containsKey(key) ? changedColumns.get(key)
                : before.column(table, column).orElse(null);
        changedColumns.put(key, new Column(changed.type, !changed.notNull));
        if (!existing || old == null) {
            return;
        }
        if (old.type().narrowedBy(changed.type)) {
            add(at, Rule.NARROWING_MODIFY, "changes `" + table + "`.`" + column + "` from " + old.type() + " to "
                    + changed.type + ", which cannot hold every existing value");
        } else if (old.nullable() && changed.notNull) {
            add(at, Rule.NARROWING_MODIFY, "makes nullable `" + table + "`.`" + column + "` NOT NULL");
        }
    }

    private void add(Token at, Rule rule, String message) {
        violations.add(new Violation(file, at.line(), rule, message));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A column definition after the column name: its type and the attributes at the top level. */
    private record Definition(ColumnType type, boolean notNull, boolean hasDefault, boolean autoIncrement,
                              boolean generated) {

        static Definition of(List<Token> tokens) {
            ColumnType.Parsed parsed = ColumnType.parse(tokens, 0);
            boolean notNull = false;
            boolean hasDefault = false;
            boolean autoIncrement = false;
            boolean generated = false;
            boolean primaryKey = false;
            int depth = 0;
            for (int i = parsed.length(); i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.isSymbol("(")) {
                    depth++;
                } else if (token.isSymbol(")")) {
                    depth--;
                } else if (depth == 0) {
                    if (token.is("NOT") && i + 1 < tokens.size() && tokens.get(i + 1).is("NULL")) {
                        notNull = true;
                    } else if (token.is("DEFAULT")) {
                        hasDefault = true;
                    } else if (token.is("AUTO_INCREMENT")) {
                        autoIncrement = true;
                    } else if (token.is("GENERATED")
                            || (token.is("AS") && i + 1 < tokens.size() && tokens.get(i + 1).isSymbol("("))) {
                        generated = true;
                    } else if (token.is("PRIMARY")) {
                        primaryKey = true;
                    } else if (token.is("COMMENT") || token.is("REFERENCES") || token.is("CHECK")) {
                        break;                               // the rest is not about nullability or defaults
                    }
                }
            }
            return new Definition(parsed.type(), notNull || primaryKey, hasDefault, autoIncrement, generated);
        }
    }

    /** A (possibly qualified, possibly quoted) name starting at {@code start}: its last part and where it ends. */
    private record Name(String name, int end) {
    }

    private static Name lastName(List<Token> tokens, int start) {
        if (start >= tokens.size() || !tokens.get(start).isIdentifier()) {
            return null;
        }
        int i = start;
        String name = tokens.get(i++).name();
        while (i + 1 < tokens.size() && tokens.get(i).isSymbol(".") && tokens.get(i + 1).isIdentifier()) {
            name = tokens.get(i + 1).name();
            i += 2;
        }
        return new Name(name, i);
    }

    /** Skips {@code IF [NOT] EXISTS}. */
    private static int skipIfExists(List<Token> tokens, int i, String not) {
        if (i < tokens.size() && tokens.get(i).is("IF")) {
            i++;
            if (not != null && i < tokens.size() && tokens.get(i).is(not)) {
                i++;
            }
            if (i < tokens.size() && tokens.get(i).is("EXISTS")) {
                i++;
            }
        }
        return i;
    }

    /** The tokens between the parenthesis at {@code open} and its match. */
    private static List<Token> inside(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).isSymbol("(")) {
                depth++;
            } else if (tokens.get(i).isSymbol(")") && --depth == 0) {
                return tokens.subList(open + 1, i);
            }
        }
        return tokens.subList(open + 1, tokens.size());
    }

    /** Splits at top-level commas. */
    static List<List<Token>> split(List<Token> tokens) {
        List<List<Token>> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isSymbol("(")) {
                depth++;
            } else if (token.isSymbol(")")) {
                depth--;
            } else if (token.isSymbol(",") && depth == 0) {
                parts.add(tokens.subList(start, i));
                start = i + 1;
            }
        }
        parts.add(tokens.subList(start, tokens.size()));
        return parts.stream().filter(part -> !part.isEmpty()).toList();
    }
}
