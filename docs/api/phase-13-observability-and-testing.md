# Phase 13 contract — Observability and extended testing

Observability (metrics, traces, logs, dashboards, alerts) and the testing layers that were deferred:
- API, database, Kafka, contract, end-to-end, security and performance testing;
- AI tool-execution and approval-workflow testing.

**Locally** everything runs in Docker under compose profile **`observability`**. **In AWS** (Phase 14) the same signals
go to managed services (the product owner's choice):
- ADOT (OpenTelemetry) collectors → Amazon Managed Prometheus (metrics), CloudWatch Logs (logs) and X-Ray (traces);
- Amazon Managed Grafana with the same dashboards, CloudWatch alarms and AMP alert rules → SNS.

## 1. Signals

**Metrics.** Micrometer in every Spring service (grocery-common **0.9.0** adds the Prometheus registry and the common
setup):
- Each service exposes `/actuator/prometheus` on its own port. The gateway never routes `/actuator/**`, and, being
  the only internet-facing unit, serves its own actuator (health probes, info, metrics) only on the internal
  management port 9080; its public port answers 404 for every `/actuator/**` path. Load balancers check the gateway
  with `GET /api/health/gateway` (readiness-aware: 503 while it refuses traffic).
- Common tags on every meter: `service` (= `spring.application.name`), `env` (`GROCERY_ENV`, default `local`),
  `instance`.
- Prometheus scrapes every service, plus exporters for Kafka (consumer lag), MySQL, Redis, Elasticsearch and Kafka
  Connect (JMX, connector/task status). Scrape interval: 15 s.

**Traces.** Micrometer Tracing with the OpenTelemetry bridge.
- Export is OTLP to the collector (`OTEL_EXPORTER_OTLP_ENDPOINT`, default `http://otel-collector:4318`), which
  forwards to Tempo locally.
- Sampling: `TRACING_SAMPLING_PROBABILITY`, 1.0 locally and 0.1 by default in AWS.
- **W3C `traceparent`** is propagated:
  - over HTTP: gateway → services → internal calls, through RestClient/WebClient observation;
  - **over Kafka through the outbox.** `OutboxEvent` stores the current `traceparent` in a new outbox column
    `traceparent` (Flyway in every producing service). Debezium's EventRouter places it as a Kafka header
    (`table.fields.additional.placement` `traceparent:header:traceparent`). Consumers continue the trace (Spring
    Kafka listener observation), and the in-process DLT replay keeps it.
  - So one order is **one trace**, from `POST /api/orders` through inventory, payment, store, delivery and
    notification.

**Logs.** Structured JSON (ECS) on stdout, as today.
- Every line carries `traceId`, `spanId`, `correlationId` (from MDC) and `service`.
- Locally, Grafana **Alloy** reads the Docker containers' logs and ships them to **Loki**. It parses the JSON and
  keeps `service`, `level`, `traceId` and `correlationId` as fields (labels only: `service`, `level`).

**Business metrics** (grocery-common 0.9.0 provides the generic ones; services add gauges):

| Metric (Prometheus name) | Type | Tags | Source |
|---|---|---|---|
| `grocery_events_published_total` | counter | `topic`, `event_type` | outbox publisher (every domain event: orders placed/confirmed/cancelled, payments captured/failed, refunds, deliveries, reviews, returns …) |
| `grocery_events_consumed_total` | counter | `consumer_group`, `event_type`, `outcome` (`APPLIED`, `DUPLICATE`, `FAILED`) | grocery-common consumer support (`ProcessedEventGuard` / listener interceptor) |
| `grocery_dead_letters_pending` | gauge | `consumer_group` | dead-letter index (Phase 10) |
| `grocery_outbox_unpublished_oldest_seconds` | gauge | — | age of the oldest outbox row not yet older than the cleanup retention — **only** where cheap; optional |
| `grocery_orders_stuck` | gauge | `reason` | order-service (open `stuck_order` flags) |
| `grocery_orders_placement_seconds` | timer | `outcome` | order-service placement |
| `grocery_payments_reconciliation_recovered_total` / `_failures_total` | counter | — | payment-service |
| `grocery_returns_open` | gauge | `status` (REQUESTED/ESCALATED) | order-service |
| `grocery_ai_tool_calls_total` | counter | `agent`, `tool`, `status` | ai-orchestrator |
| `grocery_ai_approvals_pending` | gauge | `action` | ai-orchestrator |
| `grocery_ai_model_available` | gauge | `provider` | ai-orchestrator (1/0) |
| `grocery_ai_model_latency_seconds` | timer | `agent`, `outcome` | ai-orchestrator |
| `grocery_emails_total` | counter | `status` (SENT/FAILED) | notification-service |
| `grocery_sse_connections` | gauge | — | notification-service |
| `grocery_coupon_redemptions_total` | counter | `outcome` (RESERVED/CONFIRMED/RELEASED/REFUSED) | pricing-service |

## 2. Local observability stack (compose profile `observability`)

| Container | Image (pin an existing tag) | Purpose | Host port |
|---|---|---|---|
| `prometheus` | prom/prometheus | scrape + rules → Alertmanager; retention 3 d | 9090 |
| `alertmanager` | prom/alertmanager | email via **Mailpit** (`mailpit:1025`) to `ops@grocery.local` | 9093 |
| `grafana` | grafana/grafana | datasources Prometheus, Loki, Tempo (trace↔logs↔metrics links); provisioned dashboards; anonymous viewer locally, admin `admin`/`${GRAFANA_ADMIN_PASSWORD:-admin}` | 3000 |
| `loki` | grafana/loki | logs, retention 3 d | (internal) |
| `alloy` | grafana/alloy | Docker log collection → Loki (only containers labelled `com.docker.compose.project=grocery`) | (internal) |
| `tempo` | grafana/tempo | traces, retention 1 d | (internal) |
| `otel-collector` | otel/opentelemetry-collector-contrib | OTLP in (4317/4318) → Tempo (traces), with `traceparent` | (internal) |
| `kafka-exporter`, `mysqld-exporter`, `redis-exporter`, `elasticsearch-exporter` | danielqsj/kafka-exporter, prom/mysqld-exporter, oliver006/redis_exporter, prometheuscommunity/elasticsearch-exporter | infra metrics | (internal) |

- Kafka Connect: enable its JMX → Prometheus endpoint (Debezium image env `ENABLE_JMX_EXPORTER`/`JMXPORT` or a
  javaagent), so connector and task status and Debezium lag (`MilliSecondsBehindSource`) are scraped.
- Memory caps keep the whole profile **≤ 1.5 GB**.

**Dashboards** (`infrastructure/local/observability/grafana/dashboards/*.json`, provisioned, also used in AWS):
1. **Platform overview:**
   - services up/down;
   - request rate, errors and p95 per service;
   - Kafka lag per group;
   - pending DLT records, stuck orders, connector status;
   - firing alerts.
2. **Service detail** (variable `service`): HTTP RED by route, JVM memory/GC/threads, Hikari pool, Kafka consumer rate
   and errors, logs panel (Loki) and recent traces (Tempo).
3. **Kafka & Debezium:** lag per group and topic, messages in/out per topic, connector/task status, Debezium lag,
   DLT sizes.
4. **Databases & caches:** MySQL (connections, QPS, slow queries, replication/binlog), Redis (memory, ops, hits),
   Elasticsearch (health, docs, query latency).
5. **Business:**
   - orders placed, confirmed, cancelled and delivered per minute, from `grocery_events_published_total`;
   - payments captured and failed, refunds, returns, coupon redemptions;
   - reviews, AI tool calls and approvals, emails.
6. **Order trace explorer:** search by `traceId`, `correlationId` or order number (Loki), jump to the Tempo trace.

**Alerts** (`infrastructure/local/observability/prometheus/alerts.yml`; the same expressions become AMP rules in AWS):

| Alert | Expression (idea) | For | Severity |
|---|---|---|---|
| ServiceDown | `up{job="services"} == 0` | 1m | critical |
| HighErrorRate | 5xx share of `http_server_requests_seconds_count` > 5 % per service | 5m | critical |
| SlowRequests | p95 `http_server_requests_seconds` > 1 s per service (excluding SSE/chat streams) | 10m | warning |
| KafkaConsumerLagHigh | `kafka_consumergroup_lag` sum per group > 100 | 5m | warning |
| DeadLettersPending | `grocery_dead_letters_pending` > 0 | 10m | warning |
| ConnectorTaskFailed | connect task status != running | 2m | critical |
| StuckOrders | `grocery_orders_stuck` > 0 | 5m | warning |
| PaymentFailureSpike | failed / (failed + captured) payments > 30 % over 15 m with ≥ 5 attempts | 5m | warning |
| ReconciliationFailures | increase of reconciliation failures > 0 in 15 m | 0m | warning |
| DatabaseDown / RedisDown / ElasticsearchDown | exporter `*_up == 0` | 1m | critical |
| JvmHeapHigh | heap used / max > 90 % | 10m | warning |
| AiModelUnavailable | `grocery_ai_model_available == 0` | 10m | info |
| EmailFailures | increase of `grocery_emails_total{status="FAILED"}` > 0 in 30 m | 0m | warning |

Alertmanager groups by `alertname` and `service` and emails `ops@grocery.local` (visible in Mailpit, :8025).
Critical alerts repeat every hour, warnings every 4 h.

## 3. Troubleshooting a business transaction (runbook — `docs/operations/troubleshooting.md`)

1. Start from what the user has: order number, payment id, the time, or a correlation id from an error response.
2. Admin **order timeline** (Phase 10): every published and consumed event, with its outcome. Is a step missing,
   IGNORED or DUPLICATE?
3. **Grafana → Order trace explorer:** search the order number or correlation id in Loki, open the `traceId` in Tempo,
   and see the whole path through the gateway, the services and Kafka, with timings and errors.
4. **Kafka & Debezium dashboard:** is a consumer group lagging, a connector task failed, or a DLT growing?
5. **Operations page** (Phase 10): inspect and replay dead letters; stuck orders; connector restart.
6. **Service detail dashboard:** errors and latency of the suspect service at that time, plus its logs filtered by
   `traceId`.
7. Fix, replay, and confirm in the timeline.

## 4. Testing (new layers; unit and integration tests stay in each module)

| Layer | Tool | Where | Runs against |
|---|---|---|---|
| API | **REST Assured** + JUnit 5 (Maven module, no Spring context) | `tests/api-tests` | a running stack (`GATEWAY_URL`) |
| Security | the authorization matrix (roles × admin/store/customer endpoints → 401/403), IDOR (another user's order, review, return, approval → 404), JWT tampering, header spoofing, rate limiting (in `tests/api-tests`, tag `security`); **OWASP ZAP** baseline + API scan; **Trivy** (filesystem/dependencies, images, IaC config, secrets) | `tests/api-tests`, `tests/security` | running stack / repository / images |
| AI tool execution & human approval | API tests with the **scripted** model: every agent's tools through chat, ToolGuard blocks (allowlist, role, invalid args, tool limit), approval lifecycle (approve, reject, expire, cancel, four-eyes, double approve, tampered body) | `tests/api-tests` (tag `ai`) | running stack with `AI_PROVIDER=scripted` |
| Contract (HTTP between services) | **Pact JVM**, file-based (no broker): consumers write pacts to `contracts/pacts/`, providers verify them in their test phase | consumer/provider services' `src/test` | consumer: Pact mock server; provider: the provider's Spring context with stubbed state |
| Contract (events) | the Avro compatibility gate (Phase 6 cross-cutting work) + **event consumer contract tests**: each consumer's listener parses a sample of every event type it consumes, generated from the schemas | `libs/grocery-event-schemas` + consumer services | — |
| Database | **migration tests**: every service's Flyway migrations apply on MySQL 8.4 from scratch and on top of the previous release (expand/contract lint: no `DROP`/`RENAME` of columns or tables, no `NOT NULL` without a default on existing tables); a schema lint (primary key on every table, utf8mb4, an index for every foreign key, outbox columns) | `tests/db-tests` | Testcontainers MySQL |
| Kafka integration | existing Testcontainers tests per service + an end-to-end event-flow check in the API tests (an order produces the expected events with the right headers, Avro schema ids and `traceparent`) | services + `tests/api-tests` | Kafka |
| End-to-end (UI) | **Playwright** (TypeScript, Chromium) | `tests/e2e` | the running frontend (`APP_URL`) + stack |
| Performance | **k6** with thresholds; results to Prometheus (remote write) and a summary JSON | `tests/performance` | a running stack |

**End-to-end journeys** (Playwright):
1. **Customer:** register, add an address, search, add to the cart, apply a coupon, pay through the simulator, and see
   the order live.
2. **Vendor:** accept and pack the order. **Partner:** pick it up and deliver it (simulated location). **Customer:**
   rate it.
3. **Admin:** approve a vendor; dead letters page; AI approvals; reports.
4. **Customer assistant (scripted):** "add 2 …", "checkout", then confirm the approval card.

**Performance scenarios** (k6, sized for a laptop; the AWS targets are in the plan):
- `browse`: anonymous search and product pages, 50 virtual users.
- `shop`: signed-in cart and preview, 20 virtual users.
- `checkout`: place, pay through the simulator, and confirm, 5 virtual users.

Thresholds: p95 < 800 ms for search and cart and < 2 s for placement; errors < 1 %; checks > 99 %.

**How each runs** (Docker only), from the repository root:
- API tests: `mvn -f tests/api-tests/pom.xml verify -DGATEWAY_URL=…`
- E2E: the `mcr.microsoft.com/playwright` image.
- Performance: the `grafana/k6` image.
- ZAP: `zaproxy/zap-stable` `zap-baseline.py`/`zap-api-scan.py`.
- Trivy: `aquasec/trivy`.

Each folder has a README with the exact command, and Phase 14's pipeline runs them.

## 5. Acceptance

- **Traces:** one order shows as a single trace in Tempo across gateway, order, inventory, payment, store, delivery and
  notification services, Kafka included.
  - *As verified:* every business transaction of an order is one trace across all the services it reaches, Kafka
    included. The transactions are placing it, paying, the store's decision, pickup and delivery; each starts with its
    own request by a different person.
  - The gateway-rooted traces seen spanned up to 15 services, with Kafka consumer spans in 14. The Order trace
    explorer lists all traces of an order by its number.
- **Logs:** they are searchable in Loki by `traceId`, `correlationId` and order number, linked from the trace.
- **Metrics and dashboards:** every service is scraped, and the six dashboards show live data.
- **Alerts:**
  - stopping a service fires **ServiceDown** and an email arrives in Mailpit;
  - a poison message fires **DeadLettersPending**.
- **Tests:** the API, security, AI/approval, contract, database and E2E suites pass against the local stack, and the
  ZAP and Trivy reports have **no unaccepted high findings** (accepted ones listed with a reason). k6 meets its
  thresholds, with results reported.
