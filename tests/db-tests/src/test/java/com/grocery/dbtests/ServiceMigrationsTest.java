package com.grocery.dbtests;

import com.grocery.dbtests.ServiceMigrations.Migration;
import com.grocery.dbtests.ServiceMigrations.Service;
import com.grocery.dbtests.ServiceMigrations.Version;
import com.grocery.dbtests.lint.ExpandContractLint;
import com.grocery.dbtests.lint.ExpandContractLint.Script;
import com.grocery.dbtests.lint.ExpandContractLint.Violation;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every service's Flyway migrations (discovered under {@code services/*}/src/main/resources/db/migration) against MySQL
 * 8.4.13, as Flyway runs them in the service (docs/api/phase-13-observability-and-testing.md §4, "Database"):
 * <ol>
 *   <li>from scratch on an empty database, then the schema lint ({@link SchemaLint});</li>
 *   <li>on top of the previous release ({@code baseline.properties}) holding a representative row in every table;</li>
 *   <li>the expand/contract lint ({@link ExpandContractLint}) of the migrations newer than that release.</li>
 * </ol>
 */
class ServiceMigrationsTest {

    private static final Logger log = LoggerFactory.getLogger(ServiceMigrationsTest.class);

    static List<Service> services() {
        return ServiceMigrations.discover();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void appliesFromScratchAndPassesTheSchemaLint(Service service) throws Exception {
        String database = MySql.createDatabase(service);

        MigrateResult result = MySql.migrate(service, database, null);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(service.migrations().size());
        assertThat(result.targetSchemaVersion).isEqualTo(service.migrations().getLast().version().toString());

        List<SchemaLint.Finding> findings;
        try (Connection connection = MySql.connect(database)) {
            findings = SchemaLint.check(connection, database, service.consumesEvents());
        }
        Map<String, String> exceptions = SchemaLint.exceptions();
        List<String> unexpected = findings.stream().filter(f -> !exceptions.containsKey(f.id(service.name())))
                .map(f -> f.id(service.name()) + ": " + f.message()).toList();
        Set<String> found = findings.stream().map(f -> f.id(service.name())).collect(Collectors.toSet());
        List<String> stale = exceptions.keySet().stream()
                .filter(id -> id.startsWith(service.name() + "/") && !found.contains(id)).toList();
        findings.stream().filter(f -> exceptions.containsKey(f.id(service.name())))
                .forEach(f -> log.info("{}: accepted schema lint exception {} ({})", service, f.id(service.name()),
                        exceptions.get(f.id(service.name()))));

        assertThat(unexpected).as("schema lint findings of %s (fix them with a new expand-only migration, or add a"
                + " reasoned entry to schema-lint-exceptions.properties)", service).isEmpty();
        assertThat(stale).as("exceptions of %s in schema-lint-exceptions.properties that no longer match a finding:"
                + " remove them", service).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void appliesOnTopOfThePreviousReleaseWithData(Service service) throws Exception {
        Version baseline = ServiceMigrations.baseline(service);
        String database = MySql.createDatabase(service);
        List<Migration> released = service.upTo(baseline);
        List<Migration> next = service.after(baseline);

        MigrateResult toBaseline = MySql.migrate(service, database, baseline);
        assertThat(toBaseline.migrationsExecuted).isEqualTo(released.size());

        RowSeeder.Result seeded;
        Map<String, Long> counts = new java.util.TreeMap<>();
        try (Connection connection = MySql.connect(database)) {
            seeded = RowSeeder.seed(connection, database);
            for (String table : seeded.rows().keySet()) {
                counts.put(table, RowSeeder.count(connection, table));
            }
        }
        log.info("{}: previous release V{} ({} migrations), {} tables seeded, then {} new migration(s) {}", service,
                baseline, released.size(), seeded.rows().size(), next.size(), next);
        assertThat(seeded.failures()).as("tables of %s at V%s that could not get a representative row", service,
                baseline).isEmpty();
        if (!released.isEmpty()) {
            assertThat(seeded.rows()).as("the released tables of %s hold data", service).isNotEmpty();
        }
        Set<String> platformTables = new TreeSet<>(seeded.rows().keySet());
        platformTables.retainAll(Set.of("outbox_event", "processed_event"));
        try (Connection connection = MySql.connect(database)) {
            if (DatabaseSchema.read(connection, database).hasTable("outbox_event")) {
                assertThat(seeded.rows()).as("%s outbox row", service).containsKey("outbox_event");
            }
        }

        MigrateResult rest = MySql.migrate(service, database, null);

        assertThat(rest.success).isTrue();
        assertThat(rest.migrationsExecuted).isEqualTo(next.size());
        try (Connection connection = MySql.connect(database)) {
            for (Map.Entry<String, Long> table : counts.entrySet()) {
                assertThat(RowSeeder.count(connection, table.getKey())).as("rows of %s.%s after the new migrations",
                        service, table.getKey()).isEqualTo(table.getValue());
            }
            if (platformTables.contains("outbox_event") && DatabaseSchema.read(connection, database)
                    .columnInfo("outbox_event", "traceparent").isPresent()) {
                try (Statement statement = connection.createStatement();
                     ResultSet row = statement.executeQuery("SELECT traceparent FROM outbox_event")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).as("an outbox row written before Phase 13 has no trace context")
                            .isNull();
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void migrationsNewerThanThePreviousReleaseAreExpandOnly(Service service) throws Exception {
        Version baseline = ServiceMigrations.baseline(service);
        List<Migration> next = service.after(baseline);
        String database = MySql.createDatabase(service);
        MySql.migrate(service, database, baseline);
        DatabaseSchema released;
        try (Connection connection = MySql.connect(database)) {
            released = DatabaseSchema.read(connection, database);
        }
        List<Script> scripts = new ArrayList<>();
        for (Migration migration : next) {
            try {
                scripts.add(new Script(service.name() + "/" + migration.fileName(),
                        Files.readString(migration.file(), StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        List<Violation> violations = ExpandContractLint.lint(released, scripts);

        log.info("{}: expand/contract lint of {} migration(s) newer than V{}: {}", service, next.size(), baseline,
                violations.isEmpty() ? "clean" : violations);
        assertThat(violations).as("migrations of %s newer than the previous release (V%s) must only expand the schema",
                service, baseline).isEmpty();
    }

    @Test
    void everyServiceWithMigrationsHasAValidBaseline() {
        List<Service> services = services();
        Map<String, Version> baselines = ServiceMigrations.baselines();

        assertThat(baselines.keySet()).as("baseline.properties entries without such a service (remove them)")
                .isSubsetOf(services.stream().map(Service::name).toList());
        for (Service service : services) {
            Version baseline = ServiceMigrations.baseline(service);
            if (baseline.compareTo(Version.parse("0")) != 0) {
                assertThat(service.migrations()).extracting(Migration::version)
                        .as("%s: the baseline V%s must be one of its migrations", service, baseline)
                        .contains(baseline);
            }
        }
        assertThat(services).extracting(Service::name).contains("order-service", "ai-orchestrator-service");
    }
}
