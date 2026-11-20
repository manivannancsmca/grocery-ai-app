# grocery-common

A small, **separately versioned** library of technical building blocks. It is not a Maven parent and not
part of a multi-module build: each service depends on a pinned version (`0.4.0`, `0.5.0` for order-service and
payment-service; `0.6.0` adds Avro on the wire; `0.7.0` adds dead-letter replay, a configurable retry policy and
housekeeping; `0.8.0` adds approval-token verification; `0.9.0` adds observability: Prometheus metrics, OpenTelemetry
tracing through HTTP, Kafka and the outbox, trace ids in the logs, the generic business metrics; `0.10.0` adds CSV /
Excel / PDF exports of lists; `0.10.1` gives the Excel and PDF files a title block and highlighted column headers, and
writes `.xlsx` as a plain ZIP that LibreOffice opens) and upgrades independently.
**Never add domain classes** (entities, event payloads, DTOs) here — each consumer defines its own event DTOs.

| Package | What it gives a service | Auto-configured when |
|---|---|---|
| `outbox` | `OutboxPublisher` — writes events to `outbox_event` **inside the caller's transaction**; Debezium relays them to Kafka. Since 0.6.0 it also writes the payload as Avro (`payload_avro`) | a single `DataSource` + Jackson are present |
| `idempotency` | `ProcessedEventGuard` — `INSERT IGNORE` into `processed_event`; returns `false` for duplicates. `IdempotencyKeys` (0.5.0) — the `Idempotency-Key` header for API calls that must happen once: `requireKey`, `hash`, `find`, and `claim` in the same transaction as the work (needs the `idempotency_key` table in its Javadoc) | same as above |
| `correlation` | `CorrelationIdFilter` — reads/creates `X-Correlation-Id`, puts it in the logging MDC, echoes it | servlet web app |
| `web` | `GlobalExceptionHandler` + `ApiException` family → RFC 9457 ProblemDetail with `errorCode` and `correlationId` | servlet web app |
| `id` | `Ids.newId()` — UUIDv7 (time-ordered, generated before INSERT) | always (static helper) |
| `event` | `EventHeaders` — Kafka header names | always (constants) |
| `kafka` | `EventEnvelope`, `CorrelationRecordInterceptor`, the error handler (blocking retries, outages retried up to 30 min, poison → `<topic>.DLT`), `TransientFailures` | Spring Kafka present |
| `avro` (0.6.0) | Avro + Confluent Schema Registry on the wire, invisible to listeners and payload classes — see below | on by default (`grocery.events.avro.enabled`) |
| `deadletter` (0.7.0) | `/api/admin/dead-letters` — list, inspect, replay (in process), discard this service's dead letters; states in `_grocery.dead-letter-actions` — see below | servlet app + Spring Kafka listeners + Spring Security (`grocery.dead-letters.enabled`) |
| `housekeeping` (0.7.0) | scheduled clean-up of `outbox_event`, `processed_event`, `idempotency_key` and (0.8.0) `consumed_approval` (only the tables that exist) — see below | a single `DataSource` (`grocery.housekeeping.enabled`) |
| `approval` (0.8.0) | `ApprovalTokenFilter` — verifies `X-Approval-Token` (human-approved AI actions) and records it as used in `consumed_approval`; `ApprovalContext` for the handler — see below | **off by default**; `grocery.approvals.enabled=true` in a servlet app with Spring Security JWT support and a `DataSource` |
| `export` (0.10.0) | `Exporter` streams a list as CSV, Excel or PDF to the HTTP response; `ExportQueries` reads the list's own query in keyset batches — see below | servlet web app (`Exporter`), JPA on the classpath (`ExportQueries`); `grocery.export.enabled` |
| `observability` (0.9.0) | `/actuator/prometheus` with common tags, Micrometer Tracing → OpenTelemetry → OTLP, W3C `traceparent` over HTTP, Kafka and the outbox, `traceId`/`spanId` in the ECS logs, `grocery_events_published_total`, `grocery_events_consumed_total`, `grocery_dead_letters_pending` — see below | always (`grocery.observability.enabled`) |

Switch any group off with `grocery.common.outbox.enabled=false`, `grocery.common.correlation.enabled=false`,
`grocery.common.error-handling.enabled=false`, `grocery.events.avro.enabled=false`,
`grocery.dead-letters.enabled=false`, `grocery.housekeeping.enabled=false` or `grocery.observability.enabled=false`
(approval tokens are switched **on** with `grocery.approvals.enabled=true`). Every bean backs off if the service
defines its own.

## Changes in 0.10.1: the look of Excel and PDF files

Only the two file writers change (drop-in for 0.10.0; CSV is unchanged). Each Excel sheet now reads like the PDF:

| Row | What it holds |
|---|---|
| 1 | the title (bold, 14 pt, brand green `#065F46`) |
| 2 | the description: row count, when and by whom it was exported, the filters |
| 3 | a thin gap |
| 4 | the **column headers: white bold text on the brand green**, a darker edge underneath, the headers of numbers and dates right-aligned like their values, long headers wrapped; frozen with rows 1–3 so they stay in view while scrolling, with AutoFilter buttons over the whole table, and repeated on every printed page |
| 5… | the data, every other row lightly shaded (`#EEF6F1`) |

The filter range and the shading are added when a sheet is finished, as one rule each (an `autoFilter` and a
conditional format), never as a style per cell, so a million rows cost what they cost before. A sheet past Excel's
limit starts again with the same title and headers. The PDF's column headers use the same white-on-green.

**LibreOffice opens the `.xlsx` files now.** fastexcel zips the workbook with opczip, which ends every part with a
ZIP64 data descriptor without declaring ZIP64; Excel accepts that, LibreOffice 24.x refuses the file as corrupt (and
Java's `ZipInputStream` fails on it). `PlainZipOutputStream` rewrites the ZIP as it streams: version 2.0 headers and
the standard 16-byte descriptors, the central directory's offsets adjusted. An `Inflater` reads along to find where each
part's deflate data ends, so nothing is buffered; a million-row workbook takes the same 23 s and ~100 MB of heap.
Code or people reading an `.xlsx` export: the data starts on row 5 (`XlsxTableWriter.FIRST_DATA_ROW` = 4, 0-based);
CSV stays the format for machines (one header row, then data).

## Changes in 0.10.0: data exports

Every list page can be downloaded as **CSV, Excel (.xlsx) or PDF**: `GET <list path>/export?format=csv|xlsx|pdf`, with
the list's own filters and an optional `sort=<column>,asc|desc`. The export holds **every** row that matches (up to a
million), not the page on screen. **Drop-in for 0.9.0**: nothing changes for a service until it adds an export
endpoint.

How it works (`com.grocery.common.export`):
- **Same query, same rules as the list.** The service hands the export its list query: a JPQL `from … where …` text
  shared with the repository's `@Query` through a constant (`ExportQueries.jpql`), or the list's Specification
  (`ExportQueries.specification`). The endpoint sits next to the list, under the same `@PreAuthorize`, and the
  store / customer / partner id comes from the JWT, never from a parameter. Columns (`ExportTable`) only list what the
  list's view already shows.
- **Keyset batches, never OFFSET.** Rows are read 1,000 at a time as "the next rows after the last one seen"
  (`where key < :last order by key limit 1000`), so the thousandth batch is as fast as the first. Each batch is read
  and mapped in its own short read-only transaction and its persistence context is cleared: no connection is held while
  the file is written to a slow client, and memory stays flat. Sort keys must be never-null columns ending with the
  unique id (`ExportSort`); a client may only sort by the columns the export whitelists.
- **Streamed.** CSV (UTF-8 with BOM, RFC 4180, formula injection guarded), Excel written row by row with fastexcel
  (inline strings, real number and date cells, a new sheet past Excel's 1,048,576 rows; 0.10.1: a title block and
  highlighted, frozen, filterable headers — see above) and PDF with OpenPDF (A4 landscape,
  headers on every page, page numbers; standard Helvetica, so non-Latin text shows as "?" — use CSV or Excel for it).
  Each batch is flushed: the download starts at once and keeps flowing (the gateway's 15 s idle timeout never fires).
- **Limits and errors** (ProblemDetail, before anything is written): 400 INVALID_PARAMETER (format, sort, filters),
  422 EXPORT_TOO_LARGE (over `max-rows`; a PDF over `pdf-max-rows`; a client-sorted export over `sorted-max-rows`),
  429 EXPORT_BUSY (this user's or this instance's exports already running). A failure **after** the first bytes cannot
  become an error response any more: the connection is cut (`ExportStreamException`), so the browser's download fails
  instead of saving a file that only looks complete. A client that goes away just stops the export.
- **Personal data.** Admin exports mark people's e-mail, phone and street address `.personal()`;
  `ExportTable.visibleTo(user)` keeps them only for SUPER_ADMIN, OPS_ADMIN and SUPPORT. Secrets and identity documents
  (password hashes, tokens, licence numbers, bank details) are never exported. Nothing is stored on the server: the file
  goes straight to the client, so there is no export file to secure, expire or clean up.
- **Audit.** Every export logs one line (export, format, rows, duration, user id) and records
  `grocery_export_seconds{export,format,outcome}` and `grocery_export_rows`.
- **Measured** (`ExportScaleIntegrationTest`, opt-in: `mvn test -Dtest=ExportScaleIntegrationTest
  -Dexport.scale=true -DargLine=-Xmx384m`): 1,000,000 rows from MySQL in 22 s as CSV (59 MB) and 24 s as Excel
  (30 MB), peak heap about 100 MB of a 384 MB cap. Keyset batches need an **index on the order's first key**: the same
  million rows without one took 5 minutes (each batch re-sorts the table). Every list's default export order is
  index-backed (catalog-command-service V5 added `idx_product_updated`); a client-chosen sort may not be, which is
  why such exports stop at `sorted-max-rows`.

| Property (`grocery.export.*`) | Default | Meaning |
|---|---|---|
| `enabled` | `true` | off → 404 EXPORT_DISABLED |
| `max-rows` | `1000000` | most rows of one export |
| `pdf-max-rows` | `5000` | most rows of a PDF |
| `sorted-max-rows` | `100000` | most rows when the client chose the order |
| `batch-size` | `1000` | rows per database round trip |
| `max-concurrent` | `3` | exports running at once per instance |
| `max-per-user` | `2` | exports one user may run at once |
| `timezone` | `Asia/Kolkata` | dates and times in the files |

A service's export, end to end (see order-service's `OrderExports`, `OrderQueryService.adminOrdersExport` and
`AdminOrderController.export`):
```java
static final ExportTable<OrderSummary> MY_ORDERS = ExportTable.<OrderSummary>named("my-orders", "My orders")
        .text("Order number", OrderSummary::orderNumber)
        .label("Status", OrderSummary::status)          // OUT_FOR_DELIVERY → "Out for delivery"
        .money("Total (INR)", OrderSummary::total)
        .dateTime("Placed", OrderSummary::createdAt)
        .build();

public RowSource<OrderSummary> customerOrdersExport(UUID customerId, String status) {   // not @Transactional
    return exports.specification(orders, OrderSpecifications.matching(customerId, statuses(status), …))
            .orderBy(ORDER_SORT.resolve(null))
            .rows(OrderMapper::summary);
}

@GetMapping("/export")
public void export(CurrentUser user, @RequestParam(required = false) String format,
                   @RequestParam(required = false) String status, HttpServletResponse response) {
    ExportFormat fileFormat = ExportFormat.parse(format);
    exporter.write(response, user, fileFormat, MY_ORDERS, queries.customerOrdersExport(user.userId(), status),
            Exporter.filters("Status", status));
}
```

### Dependencies (compile)
`org.dhatim:fastexcel` 0.20.2 (+ `opczip`), `com.github.librepdf:openpdf` 3.0.5 without `brotli4j` (native
libraries for a Brotli *decode* filter that writing never uses). Optional: `spring-boot-starter-data-jpa` (the row
sources). Tests only: `org.dhatim:fastexcel-reader`.

## Changes in 0.9.0

Observability, contract [Phase 13 §1](../../docs/api/phase-13-observability-and-testing.md). **Drop-in for 0.8.0**: a
service that only bumps the version keeps working unchanged — no new table is required (the outbox detects the new
column), no collector needs to run, nothing in a listener changes. What it gets at once: traces (exported to
`http://localhost:4318` unless `OTEL_EXPORTER_OTLP_ENDPOINT` says otherwise), `traceId`/`spanId` in the logs, the
metrics with common tags, Kafka listener/template observation. Exposing `/actuator/prometheus` and the outbox
`traceparent` need the small per-service changes of the [rollout checklist](#rollout-checklist-per-service).

### Dependencies (compile, so every service gets them transitively)
| Artifact (versions managed by Spring Boot 4.1.2) | Why |
|---|---|
| `io.micrometer:micrometer-registry-prometheus` (1.17.2) | `/actuator/prometheus` (Boot's `PrometheusMetricsExportAutoConfiguration` + scrape endpoint) |
| `org.springframework.boot:spring-boot-starter-opentelemetry` (4.1.2), **excluding** `io.micrometer:micrometer-registry-otlp` | Boot 4's own OpenTelemetry starter: Micrometer Tracing + OTel bridge (`micrometer-tracing-bridge-otel` 1.7.2), OTel SDK 1.62.0, OTLP exporter (`opentelemetry-exporter-otlp`), Boot's tracing/OTel auto-configurations (`spring-boot-micrometer-tracing-opentelemetry`, `spring-boot-opentelemetry`). The OTLP *metrics* registry is excluded: metrics are scraped by Prometheus, never pushed |

Tests only: `spring-boot-starter-actuator`, `io.opentelemetry:opentelemetry-sdk-testing` (in-memory span exporter).

### Defaults (lowest precedence — a service's yml, profile, env or system property wins)
Added by `GroceryObservabilityEnvironmentPostProcessor` (registered in `META-INF/spring.factories`) right after
`application.yml` is loaded:

| Property | Default | Env var |
|---|---|---|
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | — |
| `management.tracing.sampling.probability` | `1.0` (Boot's own default is 0.1); parent-based: a trace started upstream keeps its decision | `TRACING_SAMPLING_PROBABILITY` (0.1 in AWS) |
| `management.opentelemetry.tracing.export.otlp.endpoint` | `${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces`, else `http://localhost:4318/v1/traces` (OTLP/HTTP) | `OTEL_EXPORTER_OTLP_ENDPOINT` (Docker: `http://otel-collector:4318`); Boot 4.1 itself also maps it, and every other `OTEL_*` variable, at highest precedence |
| `spring.kafka.listener.observation-enabled` / `spring.kafka.template.observation-enabled` | `true` | — |
| `logging.structured.ecs.service.environment` / `.node-name` | `GROCERY_ENV` (lower-cased, default `local`) / the instance | `GROCERY_ENV`, `HOSTNAME` |
| `management.opentelemetry.resource-attributes[deployment.environment.name]` / `[service.instance.id]` | the same | |
| `management.metrics.distribution.slo[http.server.requests]`, `[grocery.orders.placement]`, `[grocery.ai.model.latency]` | `${grocery.observability.latency-slo:50ms,100ms,250ms,500ms,1s,2s,5s}` — explicit histogram buckets (not a full percentiles histogram) so Prometheus computes p95 with `histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket[5m])))` | — |

Propagation is Boot's default: produce W3C `traceparent`, accept W3C and B3. `service.name` of the spans is
`spring.application.name`.

**Common tags** on every meter (`GroceryObservabilityAutoConfiguration`, a `MeterRegistryCustomizer`): `service`
(= `spring.application.name`, exactly), `env` (= `GROCERY_ENV`, default `local`), `instance` (= `HOSTNAME` — the
container id in Docker, the pod name in Kubernetes — else `COMPUTERNAME`, else the local host name).

**Latency histograms.** The three timers above get the explicit buckets 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2 s, 5 s
(+Inf) — `http_server_requests_seconds_bucket{le=…}`, and the same for `grocery_orders_placement_seconds` /
`grocery_ai_model_latency_seconds` once order- and ai-orchestrator-service record those timers (they need nothing
else). Change the buckets of all three with `grocery.observability.latency-slo: 100ms,500ms,1s`; one timer, or
another timer, with Spring Boot's property (the key in brackets because it contains dots):
```yaml
management:
  metrics:
    distribution:
      slo:
        "[grocery.orders.placement]": 250ms,500ms,1s,2s,5s,10s
        "[http.client.requests]": 50ms,100ms,250ms,500ms,1s,2s,5s
```

**Exemplars.** With tracing on (the default), Spring Boot's `PrometheusExemplarsAutoConfiguration` attaches the
current sampled span to histogram buckets and counters as an exemplar with the labels `trace_id` and `span_id`
(`management.tracing.exemplars.include`, default sampled traces). Exemplars are only in the **OpenMetrics** output
(`Accept: application/openmetrics-text`), which Prometheus requests when started with
`--enable-feature=exemplar-storage`; Grafana then links a latency bucket to its Tempo trace (`trace_id`).

### Switching things off
| What | How |
|---|---|
| Trace export **and** propagation (spans still get ids, logs keep `traceId`) | `management.tracing.export.enabled=false` (Boot 4's name). `management.tracing.enabled=false` — Boot 3's name, ignored by Boot 4 — is translated to it by grocery-common |
| Only the OTLP export | `management.tracing.export.otlp.enabled=false` (or `OTEL_TRACES_EXPORTER=none`) |
| The OpenTelemetry SDK altogether | `management.opentelemetry.enabled=false` (or `OTEL_SDK_DISABLED=true`) |
| Kafka spans | `spring.kafka.listener.observation-enabled=false`, `spring.kafka.template.observation-enabled=false` |
| All of grocery-common's observability (defaults, common tags, business metrics, dead-letter gauge) | `grocery.observability.enabled=false` (Boot's own metrics/tracing auto-configuration then runs with Boot's defaults) |

Without a collector, tracing never breaks a service: spans are exported asynchronously in batches (every 5 s); a
failed export is dropped and logged by OpenTelemetry's rate-limited logger (`io.opentelemetry.exporter.internal.*`)
— set `logging.level.io.opentelemetry.exporter=off` to silence it where no collector is wanted. Requests, listeners
and the outbox never wait for the collector.

### Trace propagation
- **HTTP in**: Spring Boot's server observation (`http.server.requests`) continues an incoming `traceparent` (the
  gateway's). `CorrelationIdFilter` still runs first, so `correlationId` and `traceId` are both in the MDC.
- **HTTP out**: RestClient / WebClient built from Boot's auto-configured `RestClient.Builder` / `WebClient.Builder`
  (as order-, cart- and payment-service do) send `traceparent`. A client made with `RestClient.builder()` /
  `WebClient.builder()` (static) is **not** observed — inject the builder (see the checklist).
- **Kafka through the outbox**: `JdbcOutboxPublisher` writes the current span's W3C `traceparent`
  (`00-<32 hex>-<16 hex>-<2 hex flags>`, always 55 chars; the OTel SDK sets `03` = sampled + random trace id on traces it starts, `01`/`00` arrive from other tracers) to `outbox_event.traceparent`; Debezium's EventRouter places it in
  the Kafka header `traceparent`; the consumer's listener observation (on by default) handles the record in a child
  span (kind CONSUMER) of that trace. Whether the table has the column is looked up once per process (on the first
  event published inside a span, through `DatabaseMetaData` on the transaction's connection); without it the INSERT
  is exactly 0.8.0's. No current span → the column stays `NULL` → the consumer starts a new trace.
  `OutboxEvent` is unchanged: the trace context is taken by the publisher, not stored in the event object.
- **Kafka directly** (e.g. `delivery.location` through `KafkaTemplate`): template observation adds `traceparent`.
- **DLT replay** (in process): the dead letter keeps the original headers (`traceparent` included) and the replay
  runs inside a Kafka listener observation of the record (when the container has observation enabled), so it shows
  as a span of the original trace, with `traceId`/`spanId` in the MDC.

### Log fields (ECS JSON, `logging.structured.format.console: ecs`)
Verified by `ObservabilityApplicationTest`: `traceId` (32 hex) and `spanId` (16 hex) — top-level, from the MDC
(Micrometer Tracing's `Slf4JEventListener`, registered by Boot) — next to `correlationId` (and `causationId` in
listeners); `service.name` (= `spring.application.name`), plus new `service.environment` (= `GROCERY_ENV`) and
`service.node.name` (= instance); `log.level`, `log.logger`, `message`, `@timestamp`, `process.thread.name`,
`ecs.version`. Example:
```json
{"@timestamp":"2026-11-13T10:00:00.123Z","log":{"level":"INFO","logger":"com.grocery.order.OrderService"},
 "process":{"pid":1,"thread":{"name":"tomcat-handler-3"}},
 "service":{"name":"order-service","environment":"local","node":{"name":"3f2a9c1b7d4e"}},
 "message":"Order 0190… placed","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7",
 "correlationId":"8c1d…","ecs":{"version":"8.11"}}
```
Lines logged outside any span (start-up, background threads) have no `traceId`/`spanId`.

### Business metrics (`GroceryEventMetrics`)
| Prometheus name | Type | Tags | Counted |
|---|---|---|---|
| `grocery_events_published_total` | counter | `topic`, `event_type` (+ common) | by `JdbcOutboxPublisher`, in a transaction synchronization **after commit** (a rolled-back event is not counted) |
| `grocery_events_consumed_total` | counter | `consumer_group`, `event_type` (header `eventType`, `unknown` without), `outcome` | `APPLIED` / `DUPLICATE` when the listener returns normally (record interceptor); `FAILED` once per record when the error handler dead-letters it (not per retry, and not for an outage still being retried); a successful DLT replay counts `APPLIED`/`DUPLICATE` again (the event was finally applied), a failed replay nothing |
| `grocery_dead_letters_pending` | gauge | `consumer_group` | per consumer group of the service, dead letters in the instance's index that are still open: `PENDING` **or** `REPLAY_FAILED`; 0 for a group without any (registered at start-up, so alerts resolve). Every replica reports the same value — aggregate with `max by (service, consumer_group)` |

How `APPLIED` vs `DUPLICATE` is decided: grocery-common's `CorrelationRecordInterceptor` opens the record on the
listener thread; `JdbcProcessedEventGuard.markProcessed` reports its answer (`ConsumedEvents.report`); when the
listener returns, the record is `DUPLICATE` if the guard said "duplicate" for every call, otherwise `APPLIED` — a
listener that never calls the guard (catalog-query, cart, notification, an ignored event type) is `APPLIED`.
**A listener that detects duplicates without the guard** (own version check, upsert that changed nothing) calls
`ConsumedEvents.markDuplicate()` on the listener thread. A service's own `ProcessedEventGuard` implementation calls
`ConsumedEvents.report(first)`. A service's own `RecordInterceptor` bean replaces grocery-common's (and with it the
APPLIED/DUPLICATE counting and the correlation MDC); a service's own `CommonErrorHandler` loses the FAILED count.
Services add their own gauges/timers (contract table) to the same registry; they get the common tags automatically.

### New and changed types
- New package `com.grocery.common.observability`: `GroceryObservability` (names, env vars), `GroceryEventMetrics`,
  `ConsumedEvents`, `TraceParent`, `GroceryObservabilityEnvironmentPostProcessor`.
- New: `GroceryObservabilityAutoConfiguration` (beans `groceryCommonTagsCustomizer`, `groceryEventMetrics`),
  `kafka.FailedEventCountingRecoverer`, `deadletter.DeadLetterMetrics` (bean `groceryDeadLetterMetrics`).
- Changed (source-compatible, old constructors kept): `JdbcOutboxPublisher` (new 7-argument constructor with
  `DataSource`, `traceparent` supplier, metrics), `CorrelationRecordInterceptor(GroceryEventMetrics)`,
  `DeadLetterReplayer(…, ObservationRegistry, GroceryEventMetrics)`, `JdbcProcessedEventGuard` (reports the outcome).
- Security: the shared chain also permits `/actuator/prometheus` (scraped on the service's own port; the gateway never
  routes `/actuator/**`).

### Rollout checklist (per service)
1. **pom.xml**: `<grocery-common.version>0.9.0</grocery-common.version>`. Nothing else (the starter, the Prometheus
   registry and the OTel exporter come with the library; actuator is already there).
2. **application.yml**: `management.endpoints.web.exposure.include: health,info` → `health,info,prometheus` (or delete
   the line: the library's default is exactly that). Keep `management.endpoint.health.probes.enabled: true`.
   Nothing else is required; optional: `management.tracing.sampling.probability: ${TRACING_SAMPLING_PROBABILITY:1.0}`
   is already the default.
3. **Producing services** (ai-orchestrator, catalog-command, delivery, identity, inventory, order, payment, pricing,
   restaurant, review, vendor), a new Flyway migration (next free version):
   ```sql
   ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55) NULL AFTER causation_id;
   ```
   (`ALGORITHM=INSTANT` on MySQL 8.4; nullable, no default → expand-only.)
4. **`deploy/debezium-connector.json`** of the same services: append `,traceparent:header:traceparent` to
   `transforms.outbox.table.fields.additional.placement`, i.e.
   `"id:header:eventId,event_type:header:eventType,event_version:header:eventVersion,aggregate_type:header:aggregateType,correlation_id:header:correlationId,causation_id:header:causationId,occurred_at:header:occurredAt,traceparent:header:traceparent"`.
   Order: deploy the migration (service start) **before** updating the connector (Debezium fails on a placement column
   the table does not have). Rows without a value give a header with a null value — consumers then start a new trace.
5. **Own `SecurityFilterChain`** (review-service `ReviewSecurityConfig`): add `"/actuator/prometheus"` to its
   `permitAll()` matchers (the shared chain does it already).
6. **HTTP clients built with the static builders** (ai-orchestrator-service `GatewayClient`, `ModelStatus`,
   `ModelProviderConfig`): inject `RestClient.Builder` / `WebClient.Builder` (and `.clone()` it) instead of
   `RestClient.builder()` / `WebClient.builder()`, or the call is not traced and `traceparent` is not sent (the
   gateway-bound `GatewayClient` matters most: AI tool calls then join the user's trace).
7. **Listeners**: nothing for listeners that use `ProcessedEventGuard` or need no duplicate detection. Call
   `ConsumedEvents.markDuplicate()` where a listener recognises a duplicate without the guard.
8. **Docker Compose / deployment env**: `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`, `GROCERY_ENV`
   (`local`), `TRACING_SAMPLING_PROBABILITY` (1.0 locally, 0.1 in AWS). Without the observability profile running,
   leave them unset or point at nothing — services are unaffected.
9. **Prometheus scrape config**: the metrics carry their own `instance` label; use `honor_labels: true` for the
   services job (otherwise Prometheus renames it `exported_instance`).
10. **api-gateway** (no grocery-common): needs the same by hand — `spring-boot-starter-opentelemetry` (excluding
    `micrometer-registry-otlp`) + `micrometer-registry-prometheus`, exposure `health,info,prometheus`, `permitAll` for
    `/actuator/prometheus` in its `SecurityConfig`, `management.tracing.sampling.probability`,
    `management.metrics.tags.{service,env,instance}`, `OTEL_EXPORTER_OTLP_ENDPOINT`.

## Changes in 0.8.0

Drop-in for 0.7.0 services: bump the version, nothing else changes (no new table, no new bean) until a service sets
`grocery.approvals.enabled=true`. What is new:

1. **Approval tokens** (contract Phase 11 §5, ADR-0008): the target side of human-approved AI actions — a servlet
   filter that verifies `X-Approval-Token` and consumes the approval exactly once. Only order-, payment-, vendor- and
   restaurant-service enable it ([how](#enabling-it-in-a-target-service)).
2. **Housekeeping** of `consumed_approval` (30 days, `grocery.approvals.consumed-retention`) where the table exists.

New auto-configuration: `GroceryApprovalAutoConfiguration`; new beans (only when enabled):
`groceryApprovalTokenVerifier`, `groceryConsumedApprovals`, `groceryApprovalTokenFilter`,
`groceryApprovalTokenFilterRegistration`; new properties class `ApprovalProperties`
(`HousekeepingProperties` gains `approvals.consumed-retention`).

## Changes in 0.7.0

Drop-in for 0.6.0 services: bump the version, nothing else is required (no new table, no code change). Behaviour with
the default settings is identical for consuming and producing; what is new:

1. **Dead letters** (contract Phase 10 §1): every servlet service with Kafka listeners gets
   `/api/admin/dead-letters` for its own consumer groups — list / detail / replay / discard / replay-all, with the
   states in the compacted topic `_grocery.dead-letter-actions` (so catalog-query and cart, without MySQL, work the
   same way). A background reader per instance reads the `<topic>.DLT` of its listeners.
2. **Retry policy** (contract §2): the consumer error handler's numbers are now `grocery.kafka.retry.*`; the defaults
   are exactly the 0.6.0 policy.
3. **Housekeeping** (contract §6): `outbox_event` (7 days), `processed_event` (35 days) and `idempotency_key`
   (7 days) are cleaned on a library-owned schedule, in batches of 1000, where the tables exist.

New auto-configurations: `GroceryDeadLetterAutoConfiguration`, `GroceryHousekeepingAutoConfiguration`; new beans:
`groceryDeadLetter*` (reader, service, controller, action log, …), `groceryTableCleaner`,
`groceryHousekeepingScheduler`; new properties classes `KafkaRetryProperties`, `DeadLetterProperties`,
`HousekeepingProperties`.

## Dead letters (0.7.0)

### API (inside each service; the gateway maps `/api/admin/ops/<service>/dead-letters/**` here)
| Method & path | Roles | |
|---|---|---|
| `GET /api/admin/dead-letters?status=&consumerGroup=&eventType=&page=&size=` | any admin | `PageResponse<DeadLetterView>`, newest first, `size` 1–100 (default 20). `status` takes several values, repeated (`status=PENDING&status=REPLAY_FAILED`) or comma-separated (`status=PENDING,REPLAY_FAILED`); none = all; unknown → 400 `INVALID_PARAMETER` |
| `GET /api/admin/dead-letters/{id}` | any admin | `DeadLetterView` + `payload` (decoded JSON; a JSON string for non-JSON text; `null` if undecodable) + `stackTrace` (first 4 KB). Unknown → 404 `DEAD_LETTER_NOT_FOUND` |
| `POST /api/admin/dead-letters/{id}/replay` | SUPER_ADMIN, OPS_ADMIN | outcome `REPLAYED`, or `REPLAY_FAILED` + `lastError` (`attempts` + 1). Already `REPLAYED`/`DISCARDED` → 409 `DEAD_LETTER_CLOSED` + `currentStatus` |
| `POST /api/admin/dead-letters/{id}/discard` | SUPER_ADMIN, OPS_ADMIN | `{ "reason" }` required, ≤ 300 → `DISCARDED`; missing/blank/too long → 400 `VALIDATION_FAILED` (`errors[{field: "reason"}]`); closed → 409 `DEAD_LETTER_CLOSED` + `currentStatus` |
| `POST /api/admin/dead-letters/replay-all` | SUPER_ADMIN, OPS_ADMIN | body optional (`{}`, `consumerGroup`, `eventType`) → `{ attempted, replayed, failed }`: PENDING and REPLAY_FAILED, oldest first, ≤ 100 per call |

`id` = `<dltTopic>:<partition>:<offset>`; it may arrive raw or URL-encoded (`vendor.events.DLT%3A0%3A12`), dots are
never a file extension. Roles are checked by the library itself (`DeadLetterAccess`: JWT `ROLE_*` authorities), not by
the service's `SecurityConfig`; the shared chain only has to let authenticated requests through (it does:
`anyRequest().authenticated()`). `lastActionBy` is the acting user's id (JWT `sub`). Every action is logged (INFO /
WARN with dead-letter id, event, group, actor, outcome). A state that cannot be written to Kafka → 503
`DEAD_LETTER_STATUS_NOT_SAVED` (a replay that did run keeps its outcome on this instance; a discard changes nothing).

`DeadLetterView` fields that come from headers or the payload (`key`, `eventId`, `eventType`, `eventVersion`,
`occurredAt`, `originalPartition`, `originalOffset`, `exceptionClass`, `exceptionMessage`, `payloadPreview`) are `null`
when a record does not carry them or cannot be decoded; such a record is still listed. `exceptionClass` /
`exceptionMessage` are those of the real failure (the first exception of the cause chain that is not Spring Kafka's
`ListenerExecutionFailedException` / `DeserializationException` wrapper, read from `kafka_dlt-exception-stacktrace`).

### How it works
- **Which dead letters**: the listener containers of `KafkaListenerEndpointRegistry` give this service's consumer
  groups and topics; their `<topic>.DLT` are read (`grocery.dead-letters.excluded-topics`, default
  `delivery.location`, are skipped). A DLT is shared by every consumer group of its topic; only records whose
  `kafka_dlt-original-consumer-group` is one of this service's groups are kept.
- **Reader**: one daemon thread per instance (`grocery-dead-letters`), its own consumer in `assign` mode (no group, no
  commits, `allow.auto.create.topics=false`), reads every DLT partition and `_grocery.dead-letter-actions` from the
  beginning into a bounded in-memory index (`max-records`, 2000: closed ones are dropped first, then the oldest).
  Never blocks start-up; Kafka down → one WARN, retried in the background (up to 30 s apart); missing topics are looked
  up again every `metadata-refresh-interval`; records removed by the DLT retention are forgotten.
- **Replicas**: before a detail, a list and every action, the instance waits (≤ `catch-up-timeout`, 3 s) until it has
  read up to the current end offsets of the action topic (and of the record's DLT partition), so "replay twice → the
  second is 409" holds whichever replica answers. Actions on one instance are serialised per dead letter. Two replays
  of the same dead letter started at the very same moment on two replicas may both run: harmless, the listener's
  `processed_event` idempotency applies the event once.
- **Replay in process**: the original bytes are decoded with the consumer factory's own value deserializer
  (`ErrorHandlingDeserializer(AvroEventDeserializer)`: Avro → the producer's JSON, legacy JSON unchanged) and handed as
  a `ConsumerRecord` with the original topic, partition, offset, timestamp, key and headers (minus `kafka_dlt-*`) to the
  message listener of the container of that consumer group and topic, synchronously on the request thread, with the
  event's correlation/causation ids in the MDC. Never re-published, so other consumer groups are unaffected. An
  exception (or an undecodable value) → `REPLAY_FAILED` + `lastError`; the record stays in the DLT.
- **States**: compacted topic `_grocery.dead-letter-actions`, key `<consumerGroup>|<dltTopic>|<partition>|<offset>`,
  value `{"status","attempts","lastError","actor","at","reason"}` (`reason` = the discard reason, an addition to the
  contract's fields). Missing key = PENDING. Written through the action log's own producer (`acks=all`, 5 s timeouts).

### Properties (`grocery.dead-letters.*`)
| Property | Default | |
|---|---|---|
| `enabled` | `true` | the API and the reader |
| `auto-startup` | `${spring.kafka.listener.auto-startup}` (true) | start the reader with the application; service tests that set `spring.kafka.listener.auto-startup=false` do not start it (the API then answers from an empty view) |
| `actions-topic` | `_grocery.dead-letter-actions` | compacted, 1 partition (`infrastructure/local/kafka/create-topics.sh`) |
| `excluded-topics` | `delivery.location` | topics never dead-lettered |
| `max-records` | `2000` | dead letters kept in memory per instance |
| `catch-up-timeout` | `3s` | bounded wait before answering |
| `metadata-refresh-interval` | `30s` | new topics / partitions, expired records |
| `poll-timeout` | `500ms` | reader poll |

## Retry policy (0.7.0, `grocery.kafka.retry.*`)
| Failure | Policy | Settings (default) |
|---|---|---|
| `InvalidEventException`, undecodable bytes | `<topic>.DLT` at once | — |
| processing error | blocking retries 1 s, 2 s, 4 s, 8 s (per-key order kept), then the DLT | `max-attempts` (4 retries after the first delivery), `initial-interval` (1s), `multiplier` (2.0), `max-interval` (10s) |
| outage (`TransientFailures`) | the same record, 1 s, 2 s, 4 s … capped at `outage-max-interval` (30s), for `outage-max-elapsed` (30m), then the DLT | `outage-max-interval`, `outage-max-elapsed`; the outage waits also start at `initial-interval` and grow by `multiplier` |
| `delivery.location` | dropped and logged by its listener | — |

Invalid values (multiplier < 1, non-positive intervals, negative attempts) fail the start.

## Housekeeping (0.7.0)
| Table | Rule | Settings (default, env) |
|---|---|---|
| `outbox_event` | `occurred_at` older than the retention (Debezium's outbox router ignores deletes) | `grocery.outbox.cleanup.retention` (7d, `GROCERY_OUTBOX_CLEANUP_RETENTION`), `grocery.outbox.cleanup.interval` (1h, `GROCERY_OUTBOX_CLEANUP_INTERVAL`) |
| `processed_event` | `processed_at` older than 35 days (> the 30-day DLT retention, so a replay never applies an event twice) | `grocery.processed-events.retention` (35d, `GROCERY_PROCESSEDEVENTS_RETENTION`) |
| `idempotency_key` | `created_at` older than 7 days | `grocery.idempotency.retention` (7d, `GROCERY_IDEMPOTENCY_RETENTION`) |
| `consumed_approval` (0.8.0) | `consumed_at` older than 30 days (tokens live 60 s; the rows are the single-use record and audit) | `grocery.approvals.consumed-retention` (30d, `GROCERY_APPROVALS_CONSUMEDRETENTION`) |

- `DELETE FROM <table> WHERE <time> < ? ORDER BY <order> LIMIT 1000`, repeated until a batch is not full (at most
  `grocery.housekeeping.max-batches-per-run`, 1000). `ORDER BY` is `occurred_at` / `consumed_at` (indexed) for the
  outbox and `consumed_approval`, and the primary key elsewhere (UUIDv7 event ids are time-ordered, so no index on
  `processed_at` is needed).
- Each batch is its own auto-commit statement in READ COMMITTED (no gap locks), with 20 ms between batches: business
  transactions are never blocked by more than one batch of very old rows. Replicas may run it at the same time.
- Only tables (and time columns) that exist are cleaned, only on MySQL/MariaDB; services without a `DataSource`
  (catalog-query, cart) get nothing. Every non-empty run is logged at INFO (table, rows, cut-off, batches, duration).
- Scheduling is the library's own (one daemon thread `grocery-housekeeping`, no `@EnableScheduling` needed): started on
  `ApplicationReadyEvent`, first run after `grocery.housekeeping.initial-delay` (30m — a service's integration tests
  never see a run), then every `grocery.outbox.cleanup.interval` (outbox) / `grocery.housekeeping.interval` (1h, the
  other three); stopped between two batches on shutdown. A failing run is logged and retried at the next one.
- Other settings: `grocery.housekeeping.enabled` (true), `grocery.housekeeping.batch-size` (1000).

Required tables (copy into the service's Flyway V1 — see `services/identity-service/src/main/resources/db/migration/V1__platform_tables.sql`):
`outbox_event` and `processed_event`. With 0.6.0, `outbox_event` also needs `payload_avro LONGBLOB NULL`
(`ALTER TABLE outbox_event ADD COLUMN payload_avro LONGBLOB NULL AFTER payload;`).

## Approval tokens (0.8.0)

The target side of contract [Phase 11 §5](../../docs/api/phase-11-agentic-ai-and-approvals.md) (design:
[ADR-0008](../../docs/adr/0008-ai-agents-with-tool-guard-and-approval.md)). When a human approves an AI-proposed
action, ai-orchestrator-service calls the stored method, path and body with the approver's own `Authorization` and a
signed `X-Approval-Token`; the target service checks the token here before its handler runs.

**Threat model.** The token binds what the human approved — the exact method, path and body — to one execution by
this service, so parameters changed after approval (by a manipulated model, in the stored request, or in transit) are
refused and an approval cannot be replayed. It does not replace normal authorization: the request is still
authenticated and authorised as the approver (security chain, `@PreAuthorize`, ownership and business rules), and since
the header is optional by contract, a call *without* it is simply an ordinary request of whoever's JWT it carries.

### What the filter does
`ApprovalTokenFilter` is a servlet filter registered right **after** Spring Security's filter chain (order
`spring.security.filter.order` + 10, i.e. −90) and before the `DispatcherServlet`, so it sees the authenticated user
and only runs for requests the security chain let through (401/403 come first).

- **No `X-Approval-Token` header: the request is untouched** — an ordinary user request (no JWKS fetch, no row).
- With the header, in the contract's order (the first failure answers; the handler never runs):

| # | Check | Refused with |
|---|---|---|
| 1 | Exactly one header; a well-formed **RS256** JWS with a `kid`; the key comes from the JWKS (cached; an unknown `kid` refetches it once); the signature verifies; header `typ` = `approval+jwt` (`application/approval+jwt` is the same type); `jti` a UUID, `act` an action name (`[A-Z][A-Z0-9_]{0,39}`), `obo` a UUID if present | 403 `APPROVAL_TOKEN_INVALID`, `reason: SIGNATURE` · JWKS unreachable / unusable → **503 `APPROVAL_KEYS_UNAVAILABLE`** |
| 2 | `iss` = `ai-orchestrator-service` and `aud` = exactly this service (one value) | `reason: AUDIENCE` |
|   | `exp` not passed and `iat` (`nbf`) not in the future, each with `clock-skew` (5 s) | `reason: EXPIRED` |
| 3 | `htm` = the request method, `htu` = the request path as the service received it (`HttpServletRequest#getRequestURI`: undecoded, no query). A request **with** a query string only matches an `htu` that carries the same `?query` — approved requests have none | `reason: METHOD_OR_PATH` |
| 4 | `bsh` = base64url(SHA-256(the raw body bytes)), unpadded. The body is read once (≤ 1 MiB) into a cached request, so the handler receives exactly the same bytes; an empty body hashes to `47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU` | 403 `APPROVAL_BODY_MISMATCH` |
| 5 | `sub` = the authenticated JWT's `sub` (the approver) | `reason: SUBJECT` |
| 6 | `INSERT` of `jti` into `consumed_approval` on its own pooled connection in **auto-commit** (its own transaction, committed before the handler starts). Two concurrent requests with one approval: exactly one wins | duplicate key → **409 `APPROVAL_ALREADY_USED`** · database error → 500 `INTERNAL_ERROR` (nothing runs) |

Then the handler runs with the request attribute `ApprovalContext` (`approvalId`, `action`, `approverId`,
`onBehalfOf` — `null` for admin actions): `ApprovalContext.from(request)` or, anywhere in the request thread,
`ApprovalContext.current()` — e.g. to store the approval id with the order for audit. **A consumed approval whose
business call then fails stays consumed** (a new approval is needed; the orchestrator never retries).

Refusals are ProblemDetail (`application/problem+json`: `type`, `title`, `status`, `detail`, `errorCode`, `reason` for
`APPROVAL_TOKEN_INVALID`, `correlationId`), like every other error of the platform. Every token is logged — INFO
`Approval token accepted: approval=… action=… approver=… onBehalfOf=… request=POST /api/orders`, WARN
`Approval token refused: APPROVAL_TOKEN_INVALID/AUDIENCE approval=… action=… request=… (aud [payment-service] is not
[order-service])` (ERROR for 503/500). The token itself is never logged; for refused tokens the approval id and action
are what the token *claims*, printed only when well-formed (a UUID, an action name).

### Properties (`grocery.approvals.*`)
| Property | Default | |
|---|---|---|
| `enabled` | `false` | `true` in order-, payment-, vendor- and restaurant-service only |
| `jwks-uri` | `APPROVAL_JWKS_URI` (env), else `http://localhost:8115/.well-known/approval-jwks.json` | the orchestrator's approval JWKS (internal port; the gateway does not route it) |
| `audience` | `spring.application.name` | expected `aud` |
| `issuer` | `ai-orchestrator-service` | expected `iss` |
| `clock-skew` | `5s` | for `exp` / `iat` / `nbf` |
| `jwks-cache-ttl` | `5m` | a fetched key set is used this long, then fetched again |
| `jwks-min-refetch-interval` | `30s` | at most one extra fetch per interval for unknown `kid`s (a key rotation is picked up at once; a flood of made-up `kid`s never reaches the orchestrator and is answered `SIGNATURE`). `0` = no limit; must be shorter than the cache TTL |
| `jwks-timeout` | `2s` | connect and read timeout of a JWKS fetch |
| `consumed-retention` | `30d` | housekeeping of `consumed_approval` (env `GROCERY_APPROVALS_CONSUMEDRETENTION`) |

Enabled without a `DataSource`, without `spring.application.name` / `audience`, with a malformed `jwks-uri` or a
refetch interval ≥ the cache TTL, the service **fails to start** — approval tokens are never silently left unverified.

### Enabling it in a target service
1. `pom.xml`: `<grocery-common.version>0.8.0</grocery-common.version>`.
2. `application.yml`: `grocery.approvals.enabled: true` (the audience is `spring.application.name`, which already is
   `order-service`, `payment-service`, `vendor-service`, `restaurant-service`).
3. Docker / deployment: `APPROVAL_JWKS_URI=http://ai-orchestrator-service:8115/.well-known/approval-jwks.json`.
4. Flyway (new migration), MySQL 8.4:
   ```sql
   CREATE TABLE consumed_approval (
       approval_id BINARY(16)  NOT NULL,                -- jti (UUID_TO_BIN(id), no swap)
       action      VARCHAR(40) NOT NULL,                -- act
       approver_id BINARY(16)  NOT NULL,                -- sub
       consumed_at DATETIME(6) NOT NULL,                -- UTC
       CONSTRAINT pk_consumed_approval PRIMARY KEY (approval_id),
       INDEX idx_consumed_approval_consumed_at (consumed_at)
   ) ENGINE = InnoDB;
   ```
   Whether an approval ran: `SELECT * FROM consumed_approval WHERE approval_id = UUID_TO_BIN('<approvalId>')`.
5. Security: nothing to wire. The filter is a servlet filter after the whole security chain, so it works with the
   shared chain or a service's own `SecurityFilterChain`; do not add it to a chain yourself.
6. The `X-Approval-Token` header must reach the service unchanged (path and body too: no rewriting between the
   orchestrator and the service).

### Testing in a service
The library ships no signer (only the orchestrator signs). A service test mints its own tokens with Nimbus
(already on the classpath) and serves the public key from a stub JWKS (WireMock, or the JDK's `HttpServer` as this
library's own `ApprovalTokenIntegrationTest` / `StubJwks` / `TestApprovalSigner` do):
```java
RSAKey key = new RSAKeyGenerator(2048).keyID("test-approvals").generate();
// serve new JWKSet(key.toPublicJWK()).toString() as JSON at /.well-known/approval-jwks.json, e.g. WireMock:
//   stubFor(get("/.well-known/approval-jwks.json").willReturn(okJson(new JWKSet(key.toPublicJWK()).toString())));
// test property: grocery.approvals.jwks-uri=http://localhost:<port>/.well-known/approval-jwks.json

byte[] body = "{\"addressId\":\"…\"}".getBytes(StandardCharsets.UTF_8);
JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer("ai-orchestrator-service").audience("order-service")
        .jwtID(UUID.randomUUID().toString()).subject(customerId.toString())
        .claim("obo", customerId.toString()).claim("act", "PLACE_ORDER")
        .claim("htm", "POST").claim("htu", "/api/orders")
        .claim("bsh", ApprovalTokenVerifier.bodyHash(body))
        .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(60)))
        .build();
SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
        .type(new JOSEObjectType("approval+jwt")).keyID("test-approvals").build(), claims);
token.sign(new RSASSASigner(key));

mockMvc.perform(post("/api/orders").with(jwt().jwt(j -> j.subject(customerId.toString())))
        .header("X-Approval-Token", token.serialize())
        .contentType(MediaType.APPLICATION_JSON).content(body));
```
`@AutoConfigureMockMvc` adds the filter (it is a registered servlet filter), and the request JWT's `sub` must be the
token's `sub`. Tests that send no `X-Approval-Token` are unaffected and never contact the JWKS.

## Usage
```java
@Transactional
public Order placeOrder(...) {
    Order order = orderRepository.save(...);
    outboxPublisher.publish(OutboxEvent.of("order.events", "Order", order.getId().toString(),
            "OrderCreated", new OrderCreatedPayload(...)));
    return order;   // order row + event row commit together, or neither does
}
```

## Avro on the wire (0.6.0)

Events travel as Avro, validated against central schemas in the Confluent Schema Registry, while **no listener,
payload class or business code changes**: topics, keys (the plain string aggregate id), headers and the JSON each
listener receives stay the same.

### Producing
`OutboxPublisher.publish(...)` (unchanged API) now writes, in the same transaction:
- `payload` — the JSON, exactly as before (audit, debugging, rollback path);
- `payload_avro` — the same payload in the Confluent wire format (magic byte `0`, 4-byte schema id, Avro binary),
  converted from exactly that JSON. Debezium publishes this column unchanged.

`AvroEventEncoder` (bean `groceryAvroEventEncoder`, implementation `SchemaRegistryAvroEventEncoder`):
1. the schema comes from the classpath (the `grocery-event-schemas` jar, a dependency of every producing service):
   `avro/<topic>/<EventType>.avsc` for eventVersion 1, `avro/<topic>/<EventType>.v<n>.avsc` for n ≥ 2, parsed once;
2. `JsonAvroConverter` converts the JSON strictly (table below);
3. Confluent's `KafkaAvroSerializer` looks the schema up under the subject `<topic>-<record full name>`
   (`TopicRecordNameStrategy`; `auto.register.schemas=false`, `use.latest.version=false`) and writes the wire format.

The business transaction **fails** (nothing is published) when there is no schema for (topic, eventType, version)
(`IllegalStateException` naming `avro/<topic>/...avsc`), when the payload does not match it
(`AvroConversionException`, e.g. `Payload of order.events OrderCreated v1 does not match avro/order.events/OrderCreated.avsc: OrderCreated.items[1].unitPrice: 1.005 cannot be stored as decimal(10,2) without rounding`),
or when the registry does not know the schema / cannot be reached (`IllegalStateException` with the registry error as cause).

**Warm-up:** on `ApplicationReadyEvent`, `AvroSchemaWarmUp` resolves (in a background thread) the registry id of
every `classpath*:avro/**/*.avsc`, so a publish normally needs no registry call. Failures are only logged — startup
never depends on the registry; a 404 is reported per schema, the first outage stops the warm-up and ids are then
resolved lazily on first publish (retried on every publish until the registry is back). Resolved ids are cached
for the life of the process, so a registry outage after warm-up does not affect publishing.

### JSON ↔ Avro mapping (schema authors follow this)
| Avro | JSON (as Jackson 3 writes the payload) |
|---|---|
| `string` | string |
| `{"type":"string","logicalType":"uuid"}` | UUID string (validated, kept as written) |
| `{"type":"long","logicalType":"timestamp-micros"}` (also `-millis`, `-nanos`) | ISO-8601 instant; rendered with `Instant.toString()`. Finer digits than the schema's unit are truncated |
| `{"type":"int","logicalType":"date"}` | `"yyyy-MM-dd"` |
| `{"type":"bytes","logicalType":"decimal","precision":P,"scale":S}` (also fixed) | number, rescaled to S **exactly** (never rounded), precision checked; rendered with S decimals (`125.00`) |
| `int` / `long` / `float` / `double` / `boolean` | number / boolean, range-checked (an int given `3.5` or `2^40` is an error; `3.0` is 3) |
| `array` / `map` / `record` | array / object / object |
| `["null", T]` and other unions | null or the branch matching the JSON node (schema order); nulls rendered explicitly |
| `{"type":"string","logicalType":"json"}` | any JSON subtree, stored as its JSON text |
| `enum` | string (one of the symbols) |
| `bytes` / `fixed` | base64 string |

Strict: an unknown field, a missing field without a default, or a wrong type is an error naming the event and the
field path. A missing field **with** a default takes the default.

### Consuming
Every consumer created by Spring Boot's consumer factory reads values with
`ErrorHandlingDeserializer(AvroEventDeserializer)` (installed by the `DefaultKafkaConsumerFactoryCustomizer` bean
`groceryAvroConsumerFactoryCustomizer`; skipped if the service configured another value deserializer). Keys stay
`StringDeserializer`. Listeners keep receiving `ConsumerRecord<String, String>`:
- `null` → `null`;
- Confluent wire format (byte 0 = `0`, ≥ 5 bytes) → writer schema from the registry (cached per id) → Confluent's
  `KafkaAvroDeserializer` (generic record, writer schema) → `JsonAvroConverter` → the JSON the producer wrote;
- anything else → the bytes as UTF-8, unchanged (JSON events published before the switch, and their replays).

### Failure behaviour (`GroceryKafkaAutoConfiguration` error handler)
| Failure | Classified as | What happens |
|---|---|---|
| Registry unreachable, timeout, HTTP 5xx / 408 / 429 | outage (`TransientEventProcessingException`) | the **same record** is retried with the outage backoff (1 s … 30 s, up to 30 min), so ordering holds and nothing valid is dead-lettered |
| Corrupt Avro, trailing bytes, schema id unknown (404) or other 4xx | poison (`InvalidEventException`) | `<topic>.DLT` at once |
| Listener failures | unchanged | 4 short retries (1, 2, 4, 8 s) or the outage policy, `InvalidEventException` → DLT at once |

Spring Kafka classifies `DeserializationException` as not retryable before looking at its cause; grocery-common
removes that classification so the cause decides. After an outage, records with a not-yet-cached schema id fail
fast for 500 ms, so one poll never waits for many registry timeouts. `TransientFailures` also treats Confluent
`RestClientException` 5xx/408/429 (and Kafka `RetriableException`s, which Confluent maps them to) as outages and
404/422/other 4xx as not.

**DLT fidelity:** a dead-lettered record carries the record's **original value bytes** (Avro wire format or legacy
JSON), not the decoded JSON, so a later replay (Phase 10) is byte-identical. The deserializer keeps the original
bytes in the consumer-local header `groceryOriginalValue`; `OriginalValueDeadLetterPublishingRecoverer` (bean
`groceryDeadLetterRecoverer`) publishes them and strips that header, through its own producer (configured like the
application's, serializers for `byte[]` and `String`). Original headers and the standard `kafka_dlt-*` headers are kept.

### Properties (`grocery.events.avro.*`)
| Property | Default | |
|---|---|---|
| `enabled` | `true` | `false` = the **rollback switch**: exactly the 0.5.0 beans (JSON-only INSERT, StringDeserializer, plain DLT recoverer) |
| `schema-registry-url` | `http://localhost:8081` | comma-separated list allowed; Docker sets `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL`; tests use `mock://<scope>` |
| `auto-register-schemas` | `false` | production schemas are registered (compatibility-checked, BACKWARD_TRANSITIVE) by the pipeline, never by services; `true` only in tests |
| `client.*` | timeouts 5 s connect / 10 s read | passed to the Confluent client, e.g. `client.basic.auth.credentials.source=USER_INFO`, `client.basic.auth.user.info=${SR_USER}:${SR_PASSWORD}`, `client.schema.registry.ssl.truststore.location=...`, `client.http.connect.timeout.ms`, `client.max.retries` |

Service tests:
```properties
grocery.events.avro.schema-registry-url=mock://<service-name>
grocery.events.avro.auto-register-schemas=true
```

### Debezium connector (each service's `deploy/debezium-connector.json`)
```json
"transforms.outbox.table.field.event.payload": "payload_avro",
"value.converter": "io.debezium.converters.BinaryDataConverter",
"value.converter.delegate.converter.type": "org.apache.kafka.connect.json.JsonConverter",
"value.converter.delegate.converter.type.schemas.enable": "false",
"binary.handling.mode": "bytes"
```
and **remove** `transforms.outbox.table.expand.json.payload` and `value.converter.schemas.enable`. Everything else
(routing by `topic`, key `aggregate_id`, the `additional.placement` headers, `key.converter` StringConverter) stays.
`BinaryDataConverter` is what Debezium documents for an EventRouter payload that is already Avro: a `BYTES` value
(the `LONGBLOB` column, `binary.handling.mode=bytes`) is written to Kafka byte-for-byte, and any non-`BYTES` record
(heartbeat, schema change, should one ever be enabled) goes to the delegate converter instead of failing the task —
Kafka's `ByteArrayConverter` would copy the bytes the same way but fails the connector on any non-`BYTES` record.
It ships in Debezium 3.7's MySQL plugin (`debezium-connect-plugins`).

### Rollout order
1. Schema Registry running and every schema registered (`schema-init` locally, the pipeline elsewhere).
2. Services on grocery-common 0.6.0 with the `payload_avro LONGBLOB NULL` Flyway migration (producers also depend on
   `grocery-event-schemas`): consumers now read Avro **and** JSON, producers write both columns.
3. Per producer, once **every consumer of its topics** runs 0.6.0 and its connector has caught up: switch the
   connector to `payload_avro` (settings above). Rows inserted before step 2 must already be published, because
   their `payload_avro` is `NULL` (it would be published as a null value, which consumers dead-letter).

**Rollback:** first switch the connector back to `payload` (`expand.json.payload=true`, `JsonConverter`) — 0.6.0
consumers read JSON too, and producers still fill `payload`. Only then, if needed, set
`grocery.events.avro.enabled=false`: on a producer it stops writing `payload_avro` (its connector must already read
`payload`); on a consumer only once it has consumed every Avro record still on its topics (otherwise those go to the DLT).

## Build
Inside Docker (no local JDK needed), from the repository root (the tests start MySQL and Kafka with Testcontainers):
```bash
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace \
  maven:3.9.16-eclipse-temurin-25-noble mvn -B -f libs/grocery-common/pom.xml install
```
Service Dockerfiles build and install this library in their first stage automatically (`-DskipTests`). The
Confluent artifacts come from `https://packages.confluent.io/maven/` (declared in this pom); Confluent's own
`kafka-clients` (`*-ccs`) is excluded, services keep the one Spring Boot manages.
