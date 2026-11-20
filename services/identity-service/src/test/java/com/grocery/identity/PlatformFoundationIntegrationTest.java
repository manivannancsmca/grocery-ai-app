package com.grocery.identity;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 1 foundation: Flyway, outbox, idempotency guard and public health, against real MySQL. */
class PlatformFoundationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxPublisher outboxPublisher;
    @Autowired
    ProcessedEventGuard processedEventGuard;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    DataSource dataSource;

    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void flywayCreatesAllTables() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() ORDER BY table_name""")
                .query(String.class).list();
        assertThat(tables).contains("outbox_event", "processed_event", "flyway_schema_history", "user_account",
                "user_role", "refresh_token", "business_membership", "jwt_signing_key", "auth_audit");
    }

    @Test
    void outboxRowCommitsWithTheBusinessTransaction() {
        MDC.put("correlationId", "corr-test-0001");
        try {
            String aggregateId = "user-" + UUID.randomUUID();
            UUID eventId = transactionTemplate.execute(tx -> outboxPublisher.publish(
                    OutboxEvent.of("identity.events", "User", aggregateId, "TestEvent", Map.of("role", "CUSTOMER"))));

            Map<String, Object> row = jdbc.sql("SELECT * FROM outbox_event WHERE id = ?")
                    .param(eventId.toString()).query().singleRow();
            assertThat(row).containsEntry("topic", "identity.events")
                    .containsEntry("aggregate_id", aggregateId)
                    .containsEntry("event_type", "TestEvent")
                    .containsEntry("event_version", 1)
                    .containsEntry("correlation_id", "corr-test-0001");
            assertThat(eventId.version()).isEqualTo(7);
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void outboxRowRollsBackWithTheBusinessTransaction() {
        String aggregateId = "rollback-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            outboxPublisher.publish(OutboxEvent.of("identity.events", "User", aggregateId, "TestEvent", Map.of()));
            tx.setRollbackOnly();
        });
        Long count = jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?")
                .param(aggregateId).query(Long.class).single();
        assertThat(count).isZero();
    }

    @Test
    void publishingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outboxPublisher.publish(
                OutboxEvent.of("identity.events", "User", "u", "TestEvent", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
    }

    @Test
    void processedEventGuardDetectsDuplicates() {
        String eventId = Ids.newId().toString();
        Boolean first = transactionTemplate.execute(tx -> processedEventGuard.markProcessed(eventId, "c1"));
        Boolean duplicate = transactionTemplate.execute(tx -> processedEventGuard.markProcessed(eventId, "c1"));
        Boolean otherConsumer = transactionTemplate.execute(tx -> processedEventGuard.markProcessed(eventId, "c2"));
        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(otherConsumer).isTrue();
    }

    @Test
    void healthIsPublicAndCorrelationIdIsEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "corr-health-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().string("X-Correlation-Id", "corr-health-0001"));
    }
}
