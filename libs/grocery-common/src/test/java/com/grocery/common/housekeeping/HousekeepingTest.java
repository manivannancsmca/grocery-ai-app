package com.grocery.common.housekeeping;

import com.grocery.common.autoconfigure.GroceryHousekeepingAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The clean-up of the platform tables against real MySQL 8.4, with the services' table definitions. */
@Testcontainers
class HousekeepingTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    private DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void freshTables() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = JdbcClient.create(dataSource);
        for (String table : List.of("outbox_event", "processed_event", "idempotency_key", "consumed_approval")) {
            jdbc.sql("DROP TABLE IF EXISTS " + table).update();
        }
        jdbc.sql("""
                CREATE TABLE outbox_event (
                    id CHAR(36) NOT NULL, topic VARCHAR(100) NOT NULL, aggregate_type VARCHAR(100) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(150) NOT NULL,
                    event_version INT NOT NULL DEFAULT 1, payload JSON NOT NULL, payload_avro LONGBLOB NULL,
                    correlation_id VARCHAR(64) NULL, causation_id VARCHAR(64) NULL, occurred_at DATETIME(6) NOT NULL,
                    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
                    INDEX idx_outbox_event_occurred_at (occurred_at)) ENGINE = InnoDB""").update();
        jdbc.sql("""
                CREATE TABLE processed_event (
                    event_id CHAR(36) NOT NULL, consumer VARCHAR(100) NOT NULL, processed_at DATETIME(6) NOT NULL,
                    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer)) ENGINE = InnoDB""").update();
        jdbc.sql("""
                CREATE TABLE idempotency_key (
                    scope VARCHAR(100) NOT NULL, idem_key VARCHAR(100) NOT NULL, request_hash CHAR(64) NOT NULL,
                    result_ref VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
                    CONSTRAINT pk_idempotency_key PRIMARY KEY (scope, idem_key)) ENGINE = InnoDB""").update();
        jdbc.sql("""
                CREATE TABLE consumed_approval (
                    approval_id BINARY(16)  NOT NULL,
                    action      VARCHAR(40) NOT NULL,
                    approver_id BINARY(16)  NOT NULL,
                    consumed_at DATETIME(6) NOT NULL,
                    CONSTRAINT pk_consumed_approval PRIMARY KEY (approval_id),
                    INDEX idx_consumed_approval_consumed_at (consumed_at)
                ) ENGINE = InnoDB""").update();
    }

    private static LocalDateTime daysAgo(int days) {
        return LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
    }

    private void consumedApprovalRows(int count, LocalDateTime consumedAt) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{UUID.randomUUID().toString(), UUID.randomUUID().toString(), consumedAt.minusSeconds(i)});
        }
        new JdbcTemplate(dataSource).batchUpdate(
                "INSERT INTO consumed_approval VALUES (UUID_TO_BIN(?), 'PLACE_ORDER', UUID_TO_BIN(?), ?)", rows);
    }

    private void outboxRows(int count, LocalDateTime occurredAt) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{UUID.randomUUID().toString(), occurredAt.minusSeconds(i)});
        }
        new JdbcTemplate(dataSource).batchUpdate("""
                INSERT INTO outbox_event (id, topic, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                VALUES (?, 'order.events', 'Order', 'o-1', 'OrderCreated', '{}', ?)""", rows);
    }

    private void processedRows(int count, LocalDateTime processedAt) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{UUID.randomUUID().toString(), "svc.order-events", processedAt});
        }
        new JdbcTemplate(dataSource).batchUpdate(
                "INSERT INTO processed_event (event_id, consumer, processed_at) VALUES (?, ?, ?)", rows);
    }

    private void idempotencyRows(int count, LocalDateTime createdAt) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("INSERT INTO idempotency_key VALUES (:scope, :key, :hash, 'r', :at)")
                    .param("scope", "order:c-1").param("key", UUID.randomUUID().toString())
                    .param("hash", "0".repeat(64)).param("at", createdAt).update();
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void deletesOldRowsInBatchesAndKeepsTheRest() throws Exception {
        outboxRows(2500, daysAgo(8));
        outboxRows(10, daysAgo(6));
        processedRows(1500, daysAgo(36));
        processedRows(5, daysAgo(34));
        idempotencyRows(3, daysAgo(8));
        idempotencyRows(2, daysAgo(1));
        TableCleaner cleaner = new TableCleaner(dataSource, Clock.systemUTC(), 1000, 1000);

        TableCleaner.Result outbox = cleaner.clean(TableCleaner.outboxEvents(Duration.ofDays(7)), () -> false);
        assertThat(outbox.skipped()).isFalse();
        assertThat(outbox.deleted()).isEqualTo(2500);
        assertThat(outbox.batches()).isEqualTo(3);
        assertThat(count("outbox_event")).isEqualTo(10);

        TableCleaner.Result processed = cleaner.clean(TableCleaner.processedEvents(Duration.ofDays(35)), () -> false);
        assertThat(processed.deleted()).isEqualTo(1500);
        assertThat(processed.batches()).isEqualTo(2);
        assertThat(count("processed_event")).isEqualTo(5);

        TableCleaner.Result idempotency = cleaner.clean(TableCleaner.idempotencyKeys(Duration.ofDays(7)), () -> false);
        assertThat(idempotency.deleted()).isEqualTo(3);
        assertThat(count("idempotency_key")).isEqualTo(2);

        // nothing left: one statement, nothing deleted
        assertThat(cleaner.clean(TableCleaner.outboxEvents(Duration.ofDays(7)), () -> false).deleted()).isZero();
    }

    @Test
    void consumedApprovalsAreKeptThirtyDays() throws Exception {
        consumedApprovalRows(1200, daysAgo(31));
        consumedApprovalRows(4, daysAgo(29));
        TableCleaner cleaner = new TableCleaner(dataSource, Clock.systemUTC(), 1000, 1000);

        TableCleaner.Result result = cleaner.clean(TableCleaner.consumedApprovals(Duration.ofDays(30)), () -> false);
        assertThat(result.skipped()).isFalse();
        assertThat(result.deleted()).isEqualTo(1200);
        assertThat(result.batches()).isEqualTo(2);
        assertThat(count("consumed_approval")).isEqualTo(4);

        jdbc.sql("DROP TABLE consumed_approval").update();          // services without approvals
        assertThat(cleaner.clean(TableCleaner.consumedApprovals(Duration.ofDays(30)), () -> false).skipped()).isTrue();
    }

    @Test
    void aRunIsBoundedAndStopsOnShutdown() throws Exception {
        outboxRows(2500, daysAgo(8));

        TableCleaner bounded = new TableCleaner(dataSource, Clock.systemUTC(), 1000, 2);
        assertThat(bounded.clean(TableCleaner.outboxEvents(Duration.ofDays(7)), () -> false).deleted()).isEqualTo(2000);
        assertThat(bounded.clean(TableCleaner.outboxEvents(Duration.ofDays(7)), () -> true).deleted()).isZero();
        assertThat(count("outbox_event")).isEqualTo(500);
    }

    @Test
    void onlyTablesThatExistAreCleaned() throws Exception {
        jdbc.sql("DROP TABLE idempotency_key").update();
        jdbc.sql("DROP TABLE outbox_event").update();
        TableCleaner cleaner = new TableCleaner(dataSource, Clock.systemUTC(), 1000, 1000);

        assertThat(cleaner.clean(TableCleaner.idempotencyKeys(Duration.ofDays(7)), () -> false).skipped()).isTrue();
        assertThat(cleaner.clean(TableCleaner.outboxEvents(Duration.ofDays(7)), () -> false).skipped()).isTrue();
        assertThat(cleaner.clean(TableCleaner.processedEvents(Duration.ofDays(35)), () -> false).skipped()).isFalse();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GroceryHousekeepingAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource);
    }

    @Test
    void theSchedulerStartsWhenTheApplicationIsReadyAndStopsWithIt() {
        outboxRows(1200, daysAgo(8));
        processedRows(10, daysAgo(40));
        consumedApprovalRows(3, daysAgo(31));
        runner().withPropertyValues("grocery.housekeeping.initial-delay=0s").run(context -> {
            assertThat(context).hasSingleBean(HousekeepingScheduler.class);
            Thread.sleep(500);
            assertThat(count("outbox_event")).isEqualTo(1200);            // not before the application is ready

            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                    context.getSourceApplicationContext(), Duration.ZERO));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((count("outbox_event") > 0 || count("processed_event") > 0 || count("consumed_approval") > 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertThat(count("outbox_event")).isZero();
            assertThat(count("processed_event")).isZero();
            assertThat(count("consumed_approval")).isZero();
        });
    }

    @Test
    void theDefaultsWaitHalfAnHourAndFollowTheContract() {
        runner().run(context -> {
            HousekeepingProperties properties = context.getBean(HousekeepingProperties.class);
            assertThat(properties.getHousekeeping().isEnabled()).isTrue();
            assertThat(properties.getHousekeeping().getInitialDelay()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.getHousekeeping().getInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.getHousekeeping().getBatchSize()).isEqualTo(1000);
            assertThat(properties.getOutbox().getCleanup().getRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.getOutbox().getCleanup().getInterval()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.getProcessedEvents().getRetention()).isEqualTo(Duration.ofDays(35));
            assertThat(properties.getIdempotency().getRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.getApprovals().getConsumedRetention()).isEqualTo(Duration.ofDays(30));
        });
    }

    @Test
    void settingsBindFromPropertiesAndEnvironmentVariables() {
        runner().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.<String, Object>of("GROCERY_OUTBOX_CLEANUP_RETENTION", "3d",
                                        "GROCERY_OUTBOX_CLEANUP_INTERVAL", "15m",
                                        "GROCERY_PROCESSEDEVENTS_RETENTION", "40d",
                                        "GROCERY_APPROVALS_CONSUMEDRETENTION", "45d"))))
                .withPropertyValues("grocery.idempotency.retention=2d", "grocery.housekeeping.batch-size=500")
                .run(context -> {
                    HousekeepingProperties properties = context.getBean(HousekeepingProperties.class);
                    assertThat(properties.getOutbox().getCleanup().getRetention()).isEqualTo(Duration.ofDays(3));
                    assertThat(properties.getOutbox().getCleanup().getInterval()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.getProcessedEvents().getRetention()).isEqualTo(Duration.ofDays(40));
                    assertThat(properties.getIdempotency().getRetention()).isEqualTo(Duration.ofDays(2));
                    assertThat(properties.getHousekeeping().getBatchSize()).isEqualTo(500);
                    assertThat(properties.getApprovals().getConsumedRetention()).isEqualTo(Duration.ofDays(45));
                });
    }

    @Test
    void canBeSwitchedOffAndNeedsADatabase() {
        runner().withPropertyValues("grocery.housekeeping.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HousekeepingScheduler.class));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GroceryHousekeepingAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(HousekeepingScheduler.class)
                        .doesNotHaveBean(TableCleaner.class));
    }
}
