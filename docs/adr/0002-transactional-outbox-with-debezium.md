# ADR-0002: Transactional outbox relayed by Debezium

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

A service must change its data **and** publish an event, reliably. Writing to MySQL and then sending to Kafka is
a dual write: a crash between the two loses the event or publishes a change that never committed. Distributed
(XA) transactions between MySQL and Kafka are complex and fragile.

## Decision

- Every service writes domain events into its own `outbox_event` table **in the same transaction** as the
  business change. The table has these columns:
  - `id` (eventId)
  - `aggregate_type`, `aggregate_id`
  - `event_type`, `event_version`
  - `payload` JSON
  - `correlation_id`, `causation_id`
  - `occurred_at`
- **Debezium 3.7.1.Final** (Kafka Connect) runs **one MySQL connector per service**. Each service keeps its
  connector config in `deploy/debezium-connector.json`.
- Debezium's **Outbox Event Router** SMT:
  - sends each event to `<aggregate_type>.events`
  - uses `aggregate_id` as the message key
  - puts version, correlation and causation IDs and type into headers
- Consumers are idempotent: a `processed_event` row is inserted in the same transaction as the business change.
- A scheduled job deletes outbox rows older than 7 days.
- `delivery.location` telemetry is the only exception. It is best-effort and sent directly.

## Consequences

- ✅ No lost or phantom events. Publishing is exactly as durable as the business commit.
- ✅ One consistent mechanism for all 13 producing services. There is no polling code in the services.
- ✅ Per-aggregate ordering through the message key.
- ⚠️ More moving parts: Kafka Connect must run. MySQL needs binlog `ROW` format, GTID and enough binlog retention.
- ⚠️ A failed connector stops event flow silently. Connector health must be checked (scripted status check now,
  alerting later in Phase 13).
- ⚠️ Delivery is at-least-once, so consumers must be idempotent. They are, by design.

## Alternatives considered

- **Polling publisher:** a scheduled job reads the outbox and sends to Kafka. It is simpler to understand, but it adds latency
  and database load, and needs its own locking across replicas. Rejected because Debezium is already required.
- **Direct `KafkaTemplate.send` after commit:** this is the dual-write problem. Rejected.
- **Kafka transactions + chained transaction manager:** does not make MySQL and Kafka atomic. Rejected.

## Refinements (Phase 9 incident)

- **What happened:** every Debezium connector reads the binlog of the single shared MySQL server, so every connector
  parses every DDL statement, including other services' migrations.
  - delivery-service's Phase 9 migration added a column named `online`. MySQL accepts that name, but Debezium's
    parser treats it as a keyword.
  - All nine connector tasks failed on that one statement, and **every service silently stopped publishing events**.
- **Fix:** every connector now sets `schema.history.internal.skip.unparseable.ddl=true`.
  - This is safe because each connector captures only its own `outbox_event` table, whose DDL is simple and stable.
    A skipped statement can only belong to a table the connector does not capture.
  - After the restart, the connectors resumed from their stored binlog positions and published everything written
    during the outage. **Nothing was lost**, which is the guarantee the outbox exists for.
- **To watch:**
  - A connector can report `RUNNING` while its task is `FAILED`. Check task states
    (`/connectors/<name>/status`), not just connector states.
  - Phase 10 adds connector-task health to the admin System status and an automatic restart of failed tasks.
  - Smoke tests check all tasks before they start.
- **Production:** the same risk exists wherever several connectors share one database server. Keep the skip flag,
  avoid DDL that touches `outbox_event` in non-standard ways, and alert on failed tasks.

## Refinements (Phase 10)

- **Connector health is now watched:**
  - A `connect-watchdog` container checks every connector **and every task** every 30 s. It restarts failed tasks
    (`restart?includeTasks=true&onlyFailed=true`) at most 3 times per connector in 10 minutes. After that the task
    stays FAILED, so a permanent error is not restarted forever. Every action is logged.
  - Admins see connector and task states, with the error trace, on the Operations page
    (`GET /api/admin/system/connectors`, through the gateway). SUPER_ADMIN and OPS_ADMIN can restart a connector there.
  - Smoke tests still check all tasks before they start (`check-connectors.sh`).
  - **Production:** the same check belongs in monitoring and alerting (MSK Connect metrics or the Connect REST API).
    The watchdog is a local stand-in, not a substitute for an alert.
- **A failed task loses nothing:** while a task is FAILED, the events stay in the binlog. After a restart, the task
  resumes from its last committed offset. The Phase 10 smoke test proves it: it registers a user while the identity
  connector is broken, and the user's `UserRegistered` event is delivered after the fix. Records published just before
  the failure may be sent again (at-least-once), and consumers de-duplicate them.
- **Outbox cleanup:** rows older than 7 days are deleted in batches (grocery-common 0.7.0; settings
  `GROCERY_OUTBOX_CLEANUP_RETENTION` and `GROCERY_OUTBOX_CLEANUP_INTERVAL`). The EventRouter ignores deletes, and the
  connectors set `tombstones.on.delete=false`, so cleanup publishes nothing. A connector that is behind is not
  affected either, because it reads each INSERT from the binlog, not from the table. What limits recovery is **binlog
  retention**: a connector that is down for longer than that cannot resume and needs a new snapshot. Locally that is
  3 days (`--binlog-expire-logs-seconds=259200`); in production, set it longer than the longest connector outage you
  must survive.
