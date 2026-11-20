package com.grocery.dbtests.lint;

import com.grocery.dbtests.lint.ExpandContractLint.Column;
import com.grocery.dbtests.lint.ExpandContractLint.Rule;
import com.grocery.dbtests.lint.ExpandContractLint.Schema;
import com.grocery.dbtests.lint.ExpandContractLint.Script;
import com.grocery.dbtests.lint.ExpandContractLint.Violation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The expand/contract lint proven on bad (and good) SQL: every rule fires with file, line and rule, case and comments
 * do not matter, several statements per file are read, and allowed changes pass.
 */
class ExpandContractLintTest {

    /** The released schema the samples run against: orders(id, status VARCHAR(20) NOT NULL, note VARCHAR(300) NULL,
     *  total DECIMAL(12,2) NOT NULL, quantity INT NOT NULL, kind ENUM('A','B') NOT NULL, at DATETIME(6) NULL). */
    static final Schema RELEASED = new Schema() {
        private final Map<String, Column> columns = Map.of(
                "id", new Column(ColumnType.of("binary(16)"), false),
                "status", new Column(ColumnType.of("varchar(20)"), false),
                "note", new Column(ColumnType.of("varchar(300)"), true),
                "total", new Column(ColumnType.of("decimal(12,2)"), false),
                "quantity", new Column(ColumnType.of("int"), false),
                "kind", new Column(ColumnType.of("enum('A','B')"), false),
                "at", new Column(ColumnType.of("datetime(6)"), true));

        @Override
        public boolean hasTable(String table) {
            return table.equalsIgnoreCase("orders");
        }

        @Override
        public Optional<Column> column(String table, String column) {
            return hasTable(table) ? Optional.ofNullable(columns.get(column.toLowerCase())) : Optional.empty();
        }
    };

    static List<Violation> lint(String sql) {
        return ExpandContractLint.lint(RELEASED, new Script("V9__sample.sql", sql));
    }

    static void assertSingle(String sql, Rule rule, int line, String messagePart) {
        List<Violation> violations = lint(sql);
        assertThat(violations).as(sql).singleElement().satisfies(violation -> {
            assertThat(violation.rule()).isEqualTo(rule);
            assertThat(violation.line()).isEqualTo(line);
            assertThat(violation.file()).isEqualTo("V9__sample.sql");
            assertThat(violation.toString()).startsWith("V9__sample.sql:" + line + ": [" + rule.id() + "] ")
                    .contains(messagePart);
        });
    }

    @Nested
    class BadSqlIsRejected {

        @ParameterizedTest
        @ValueSource(strings = {
                "DROP TABLE orders;",
                "drop table if exists `orders`;",
                "DROP TEMPORARY TABLE orders",
                "DROP TABLE shop.orders, other;"})
        void dropTable(String sql) {
            assertThat(lint(sql)).extracting(Violation::rule).contains(Rule.DROP_TABLE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ALTER TABLE orders DROP COLUMN note;",
                "alter table `orders` drop `note`;",
                "ALTER TABLE orders ADD COLUMN x INT NULL, DROP note, ALGORITHM=INPLACE;"})
        void dropColumn(String sql) {
            assertThat(lint(sql)).extracting(Violation::rule).containsExactly(Rule.DROP_COLUMN);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "RENAME TABLE orders TO customer_order;",
                "ALTER TABLE orders RENAME TO customer_order;",
                "ALTER TABLE orders RENAME AS customer_order;",
                "alter table orders rename customer_order"})
        void renameTable(String sql) {
            assertThat(lint(sql)).extracting(Violation::rule).containsExactly(Rule.RENAME_TABLE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ALTER TABLE orders RENAME COLUMN note TO comment;",
                "ALTER TABLE orders CHANGE note comment VARCHAR(300) NULL;",
                "ALTER TABLE orders CHANGE COLUMN `note` `comment` VARCHAR(500) NULL;"})
        void renameColumn(String sql) {
            assertThat(lint(sql)).extracting(Violation::rule).containsExactly(Rule.RENAME_COLUMN);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
                "ALTER TABLE orders MODIFY status VARCHAR(10) NOT NULL;                 | VARCHAR(20) to VARCHAR(10)",
                "ALTER TABLE orders MODIFY COLUMN total DECIMAL(12,1) NOT NULL;         | DECIMAL(12,2) to DECIMAL(12,1)",
                "ALTER TABLE orders MODIFY total DECIMAL(10,2) NOT NULL;                | DECIMAL(12,2) to DECIMAL(10,2)",
                "ALTER TABLE orders MODIFY quantity SMALLINT NOT NULL;                  | INT to SMALLINT",
                "ALTER TABLE orders MODIFY quantity INT UNSIGNED NOT NULL;              | INT to INT UNSIGNED",
                "ALTER TABLE orders MODIFY kind ENUM('A') NOT NULL;                     | ENUM(A,B) to ENUM(A)",
                "ALTER TABLE orders MODIFY at DATETIME NULL;                            | DATETIME(6) to DATETIME",
                "ALTER TABLE orders MODIFY note INT NULL;                               | VARCHAR(300) to INT",
                "ALTER TABLE orders MODIFY note TINYTEXT NULL;                          | VARCHAR(300) to TINYTEXT",
                "ALTER TABLE orders CHANGE status status CHAR(5) NOT NULL;              | VARCHAR(20) to CHAR(5)",
                "ALTER TABLE orders MODIFY note VARCHAR(300) NOT NULL DEFAULT '';       | makes nullable `orders`.`note` NOT NULL"})
        void narrowingModify(String sql, String message) {
            assertSingle(sql, Rule.NARROWING_MODIFY, 1, message);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ALTER TABLE orders ADD COLUMN currency CHAR(3) NOT NULL;",
                "alter table orders add currency char(3) not null after total;",
                "ALTER TABLE orders ADD (currency CHAR(3) NOT NULL, rate DECIMAL(5,2) NULL);",
                "ALTER TABLE orders ADD COLUMN currency CHAR(3) NOT NULL COMMENT 'DEFAULT is INR';",
                "ALTER TABLE orders ADD COLUMN currency CHAR(3) NOT NULL CHECK (currency <> 'DEFAULT');"})
        void notNullWithoutDefaultOnAnExistingTable(String sql) {
            assertThat(lint(sql)).extracting(Violation::rule).containsExactly(Rule.NOT_NULL_WITHOUT_DEFAULT);
        }

        @Test
        void everyFailureNamesFileLineAndRuleInMultiStatementFilesWithComments() {
            String sql = """
                    -- Phase 14: a careless clean-up
                    /* a block comment ; with a semicolon
                       DROP TABLE orders; (commented out: no finding) */
                    # a hash comment: ALTER TABLE orders DROP COLUMN status;
                    ALTER TABLE orders ADD COLUMN source VARCHAR(20) NULL;
                    UPDATE orders SET note = 'DROP TABLE orders; -- not a statement';

                    ALTER TABLE orders
                        ADD COLUMN channel VARCHAR(20) NOT NULL,
                        DROP COLUMN note;
                    RENAME TABLE orders TO customer_order;""";

            List<Violation> violations = lint(sql);

            assertThat(violations).extracting(Violation::toString).containsExactly(
                    "V9__sample.sql:9: [no-not-null-without-default] adds `orders`.`channel` VARCHAR(20) NOT NULL without"
                            + " DEFAULT to an existing table: the running version's INSERTs would fail",
                    "V9__sample.sql:10: [no-drop-column] drops column `orders`.`note`",
                    "V9__sample.sql:11: [no-rename-table] renames table `orders`");
        }

        @Test
        void aVersionedCommentIsCodeAsMySqlExecutesIt() {
            assertSingle("/*!80000 ALTER TABLE orders DROP COLUMN note */;", Rule.DROP_COLUMN, 1, "`orders`.`note`");
        }

        @Test
        void aLaterScriptIsCheckedAgainstWhatAnEarlierOneAdded() {
            List<Violation> violations = ExpandContractLint.lint(RELEASED,
                    new Script("V9__add.sql", "ALTER TABLE orders ADD COLUMN source VARCHAR(40) NULL;"),
                    new Script("V10__shrink.sql", "ALTER TABLE orders MODIFY source VARCHAR(20) NULL;"));

            assertThat(violations).extracting(Violation::toString).containsExactly(
                    "V10__shrink.sql:1: [no-narrowing-modify] changes `orders`.`source` from VARCHAR(40) to VARCHAR(20),"
                            + " which cannot hold every existing value");
        }

        @Test
        void storedProgramsAreNotSilentlyMisread() {
            assertThatThrownBy(() -> lint("DELIMITER //\nCREATE PROCEDURE p() BEGIN SELECT 1; END //"))
                    .hasMessageContaining("DELIMITER");
        }
    }

    @Nested
    class ExpandOnlySqlPasses {

        @ParameterizedTest
        @ValueSource(strings = {
                "ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55) NULL AFTER causation_id;",
                "ALTER TABLE orders ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'INR';",
                "ALTER TABLE orders ADD COLUMN seq BIGINT UNSIGNED NOT NULL AUTO_INCREMENT INVISIBLE, ADD PRIMARY KEY (seq);",
                "ALTER TABLE orders ADD COLUMN open_id BINARY(16) AS (CASE WHEN status = 'OPEN' THEN id END) STORED NOT NULL;",
                "ALTER TABLE orders ADD COLUMN k VARCHAR(10) GENERATED ALWAYS AS (status) VIRTUAL NOT NULL;",
                "ALTER TABLE orders ADD INDEX idx_orders_status (status), ADD CONSTRAINT fk_x FOREIGN KEY (id) REFERENCES t (id);",
                "ALTER TABLE orders DROP INDEX idx_orders_status, DROP FOREIGN KEY fk_x, DROP CHECK ck_x;",
                "ALTER TABLE orders ALTER COLUMN note DROP DEFAULT, ALTER COLUMN status SET DEFAULT 'NEW';",
                "ALTER TABLE orders RENAME INDEX idx_a TO idx_b, RENAME KEY idx_c TO idx_d;",
                "ALTER TABLE orders MODIFY status VARCHAR(40) NOT NULL;",
                "ALTER TABLE orders MODIFY note TEXT NULL;",
                "ALTER TABLE orders MODIFY total DECIMAL(14,3) NOT NULL;",
                "ALTER TABLE orders MODIFY quantity BIGINT NOT NULL;",
                "ALTER TABLE orders MODIFY kind ENUM('A','B','C') NOT NULL;",
                "ALTER TABLE orders MODIFY status VARCHAR(20) NULL;",
                "ALTER TABLE orders CHANGE COLUMN status status VARCHAR(30) NOT NULL;",
                "CREATE TABLE refunds (id BINARY(16) NOT NULL, amount DECIMAL(12,2) NOT NULL, PRIMARY KEY (id));"
                        + " ALTER TABLE refunds ADD COLUMN reason VARCHAR(20) NOT NULL; ALTER TABLE refunds DROP COLUMN reason;"
                        + " RENAME TABLE refunds TO refund; DROP TABLE refund;",
                "CREATE TABLE IF NOT EXISTS tmp_copy LIKE orders; DROP TABLE IF EXISTS tmp_copy;",
                "INSERT INTO orders (id, status) VALUES (UNHEX('00'), 'DROP TABLE orders');",
                "UPDATE orders SET note = NULL WHERE status = 'ALTER TABLE orders DROP note';"})
        void allowedChanges(String sql) {
            assertThat(lint(sql)).isEmpty();
        }
    }

    @Nested
    class Parsing {

        @Test
        void statementsLinesQuotesAndComments() {
            SqlScript script = SqlScript.parse("""
                    SELECT 'a;b', "c""d", `e;f` FROM t; -- trailing ; comment
                    /* x ; y */ SELECT 1
                    ;
                    SELECT 'it\\'s', 'it''s';""");

            assertThat(script.statements()).hasSize(3);
            assertThat(script.statements().get(0).tokens()).extracting(SqlScript.Token::text)
                    .containsExactly("SELECT", "a;b", ",", "c\"d", ",", "e;f", "FROM", "t");
            assertThat(script.statements().get(1).line()).isEqualTo(2);
            assertThat(script.statements().get(2).line()).isEqualTo(4);
            assertThat(script.statements().get(2).tokens()).extracting(SqlScript.Token::text)
                    .containsExactly("SELECT", "it's", ",", "it's");
        }

        @Test
        void aDoubleDashWithoutSpaceIsAnOperatorNotAComment() {
            assertThat(SqlScript.parse("SELECT 1--1;").statements().getFirst().tokens()).hasSize(5);
        }

        @Test
        void columnTypesFromInformationSchemaAndFromDdl() {
            assertThat(ColumnType.of("decimal(12,2) unsigned")).hasToString("DECIMAL(12,2) UNSIGNED");
            assertThat(ColumnType.of("enum('USER','ASSISTANT')").arguments()).containsExactly("USER", "ASSISTANT");
            assertThat(ColumnType.of("INTEGER")).isEqualTo(ColumnType.of("int"));
            assertThat(ColumnType.of("BOOLEAN")).isEqualTo(ColumnType.of("tinyint(1)"));
            assertThat(ColumnType.of("varchar(55)").narrowedBy(ColumnType.of("VARCHAR(55)"))).isFalse();
            assertThat(ColumnType.of("int").narrowedBy(ColumnType.of("DECIMAL(12,2)"))).isFalse();
            assertThat(ColumnType.of("int").narrowedBy(ColumnType.of("DECIMAL(10,2)"))).isTrue();
            assertThat(ColumnType.of("timestamp(6)").narrowedBy(ColumnType.of("datetime(6)"))).isFalse();
            assertThat(ColumnType.of("datetime(6)").narrowedBy(ColumnType.of("timestamp(6)"))).isTrue();
        }
    }
}
