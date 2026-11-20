package com.grocery.dbtests;

import com.grocery.dbtests.ServiceMigrations.Service;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The schema lint proven on a deliberately bad database, and on a good one. */
class SchemaLintRulesTest {

    private static final Service SCRATCH = new Service("lint-scratch", Path.of("."), List.of(), true);

    @Test
    void aBadSchemaBreaksEveryRule() throws Exception {
        String database = MySql.createDatabase(SCRATCH);
        try (Connection connection = MySql.connect(database); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE chat_memory (conversation_id VARCHAR(36) NOT NULL, content TEXT NOT NULL,"
                    + " INDEX idx_chat_memory (conversation_id))");
            sql.execute("CREATE TABLE legacy (id INT NOT NULL PRIMARY KEY, name VARCHAR(20)) CHARACTER SET latin1");
            sql.execute("CREATE TABLE mixed (id INT NOT NULL PRIMARY KEY, code CHAR(3) CHARACTER SET ascii NOT NULL)");
            sql.execute("""
                    CREATE TABLE outbox_event (id CHAR(36) NOT NULL PRIMARY KEY, topic VARCHAR(100) NOT NULL,
                      aggregate_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
                      event_type VARCHAR(150) NOT NULL, event_version INT NOT NULL, payload JSON NOT NULL,
                      correlation_id VARCHAR(64) NULL, causation_id VARCHAR(64) NULL, traceparent VARCHAR(32) NULL,
                      occurred_at DATETIME(3) NOT NULL)""");

            List<SchemaLint.Finding> findings = SchemaLint.check(connection, database, true);

            assertThat(findings).extracting(SchemaLint.Finding::toString).containsExactlyInAnyOrder(
                    "[primary-key] chat_memory: has no primary key",
                    "[utf8mb4] legacy: table collation latin1_swedish_ci is not utf8mb4",
                    "[utf8mb4] legacy.name: character set latin1 (varchar(20)) is not utf8mb4",
                    "[utf8mb4] mixed.code: character set ascii (char(3)) is not utf8mb4",
                    "[outbox-table] outbox_event.payload_avro: missing column (expected longblob NULL)",
                    "[outbox-table] outbox_event.traceparent: length 32, expected at least 55",
                    "[outbox-table] outbox_event.occurred_at: fractional seconds 3, expected 6",
                    "[processed-event-table] processed_event: the service consumes events but has no processed_event"
                            + " table");
            assertThat(findings.getFirst().id("ai-orchestrator-service"))
                    .isEqualTo("ai-orchestrator-service/primary-key/chat_memory");
        }
    }

    @Test
    void theSharedPlatformTablesPass() throws Exception {
        String database = MySql.createDatabase(SCRATCH);
        try (Connection connection = MySql.connect(database); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE outbox_event (id CHAR(36) NOT NULL, topic VARCHAR(100) NOT NULL,
                      aggregate_type VARCHAR(100) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
                      event_type VARCHAR(150) NOT NULL, event_version INT NOT NULL DEFAULT 1, payload JSON NOT NULL,
                      payload_avro LONGBLOB NULL, correlation_id VARCHAR(64) NULL, causation_id VARCHAR(64) NULL,
                      traceparent VARCHAR(55) NULL, occurred_at DATETIME(6) NOT NULL,
                      CONSTRAINT pk_outbox_event PRIMARY KEY (id))""");
            sql.execute("""
                    CREATE TABLE processed_event (event_id CHAR(36) NOT NULL, consumer VARCHAR(100) NOT NULL,
                      processed_at DATETIME(6) NOT NULL, CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer))""");
            sql.execute("CREATE TABLE parent (id BINARY(16) NOT NULL PRIMARY KEY)");
            sql.execute("CREATE TABLE child (id BINARY(16) NOT NULL PRIMARY KEY, parent_id BINARY(16) NOT NULL,"
                    + " CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent (id))");

            assertThat(SchemaLint.check(connection, database, true)).isEmpty();
            // InnoDB adds an index for a foreign key that has none, so on MySQL this rule guards future engines /
            // hand-made schemas; the check itself sees the automatic index:
            assertThat(SchemaLint.foreignKeysWithoutIndex(connection, database)).isEmpty();
        }
    }
}
