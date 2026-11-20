# db-tests: database migration tests

Phase 13 test layer "Database" ([contract §4](../../docs/api/phase-13-observability-and-testing.md#4-testing-new-layers-unit-and-integration-tests-stay-in-each-module)).
A standalone Maven module (no parent, no Spring context, like `tests/api-tests`). It reads every service's Flyway
migrations straight from `services/*/src/main/resources/db/migration` and runs them against **MySQL 8.4.13** in
Testcontainers. The server is configured like the local stack's (GTID consistency enforced, `utf8mb4` /
`utf8mb4_0900_ai_ci`, UTC), and each database is created the way `infrastructure/local/mysql/init` creates it. Flyway
is the services' own version (12.4.0, from the Spring Boot 4.1.2 BOM) with Spring Boot's defaults: no service
customises `spring.flyway.*`, so `baseline-on-migrate` is off and `validate-on-migrate` is on.

Services are **discovered**: a new service with a `db/migration` folder is covered without touching this module. It
then only needs its line in `baseline.properties`, and the test fails until that line is there.

## Run (Docker only, from the repository root, Git Bash)

```
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f tests/db-tests/pom.xml clean verify
```

- The Docker socket is mounted so that Testcontainers can start the MySQL container next to the Maven container.
  `TESTCONTAINERS_HOST_OVERRIDE` lets the Maven container reach it. On Linux CI agents, use the agent's address, or
  run Maven on the agent itself.
- `-Drepo.root=<path>` points at another checkout. By default the module walks up from its own folder (the pom passes
  `../..`), so it works with the repository mounted at `/workspace`.
- `-Dmysql.image=mysql:8.4.x` tests another server patch version.
- About 2 minutes: one MySQL container, then 3 databases per service.

## What is checked

| Test | Per service | Fails when |
|---|---|---|
| `ServiceMigrationsTest.appliesFromScratchAndPassesTheSchemaLint` | all migrations on an empty database, then the **schema lint** | a migration fails, or a lint finding is not an accepted exception (or an exception no longer matches a finding) |
| `ServiceMigrationsTest.appliesOnTopOfThePreviousReleaseWithData` | migrate to the baseline, insert **one representative row into every table**, apply the rest | a table cannot be seeded, a new migration fails on existing data, or rows are lost |
| `ServiceMigrationsTest.migrationsNewerThanThePreviousReleaseAreExpandOnly` | the **expand/contract lint** of the migrations above the baseline, against the released schema | a rule is broken (each failure names file, line and rule) |
| `ServiceMigrationsTest.everyServiceWithMigrationsHasAValidBaseline` | `baseline.properties` | a service is missing, an entry is stale, or a baseline is not one of the service's versions |
| `lint.ExpandContractLintTest` | — | the lint misses a bad SQL sample or rejects an allowed one (unit tests, no database) |
| `SchemaLintRulesTest` | — | the schema lint misses a rule on a deliberately bad schema, or flags the shared platform tables |

### Expand/contract lint (migrations newer than the baseline)

Old and new versions of a service run side by side during a rolling deploy or a rollback, so a migration of a released
schema may only expand it:

| Rule | Rejects |
|---|---|
| `no-drop-table` | `DROP TABLE` of a table that existed before |
| `no-drop-column` | `ALTER TABLE … DROP [COLUMN] c` |
| `no-rename-table` | `RENAME TABLE`, `ALTER TABLE … RENAME [TO\|AS]` |
| `no-rename-column` | `RENAME COLUMN`, `CHANGE [COLUMN] a b …` with a different name |
| `no-narrowing-modify` | `MODIFY` / `CHANGE` to a type that cannot hold every existing value (shorter, smaller, fewer decimals or fraction digits, fewer ENUM values, signedness change, another type family), or from NULL to NOT NULL |
| `no-not-null-without-default` | `ADD [COLUMN] … NOT NULL` without `DEFAULT` (and not `AUTO_INCREMENT` or generated) on an existing table |

The SQL is read the way the server reads it. Keywords are case-insensitive. Comments (`-- `, `#`, `/* */`) are
ignored, but the body of a versioned comment `/*!80000 … */` is code. String literals and quoted identifiers are not
scanned, and a file may hold any number of statements. Tables created by the linted migrations themselves are new, so
the rules do not apply to them. `DELIMITER` (stored programs) is reported as unsupported rather than misread. Only
migrations **newer than the baseline** are linted. Earlier phases were pre-release.

### Schema lint (after the full migration, from `information_schema`)

| Rule | Requires |
|---|---|
| `primary-key` | a primary key on every table, Flyway's `flyway_schema_history` included |
| `utf8mb4` | utf8mb4 table collation and character columns |
| `foreign-key-index` | every foreign key's columns are the leftmost columns of an index. InnoDB creates such an index itself, so this rule guards hand-made schemas and other engines |
| `outbox-table` | `outbox_event` with grocery-common 0.9.0's columns: `id` CHAR(36) PK, `topic`, `aggregate_type`, `aggregate_id`, `event_type`, `event_version` INT, `payload` JSON, `payload_avro` LONGBLOB NULL, `correlation_id`, `causation_id`, `traceparent` VARCHAR(≥55) NULL, `occurred_at` DATETIME(6) |
| `processed-event-table` | for a service with a `@KafkaListener`: `processed_event(event_id CHAR(36), consumer, processed_at DATETIME(6))`, PK `(event_id, consumer)` |

Accepted findings are listed with their reason in
[`src/test/resources/schema-lint-exceptions.properties`](src/test/resources/schema-lint-exceptions.properties). Prefer
a new expand-only migration in the service.

## The baseline (`src/test/resources/baseline.properties`)

`<service>=<last migration version of the previous release>`. For Phase 13 this is the version below each service's
`V<n>__outbox_traceparent.sql`, or the service's latest version where Phase 13 added none. **When a release ships,
move every value to that release's last version.** A service that is new in a release gets `0`.

## Seeding the previous release

Every table at the baseline gets one row. The seeder fills parents before children and reuses the parent's key. Each
column gets a value of the right type and size, or the literal a CHECK constraint names. Timestamps increase with the
column position, so `valid_until > valid_from` holds. A full row is tried first, then a minimal one (nullable columns
NULL, defaults used). After the new migrations, every table must still hold its rows, and the pre-Phase-13 outbox row
must have a NULL `traceparent`.
