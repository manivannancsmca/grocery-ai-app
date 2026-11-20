# Troubleshooting a business transaction (runbook)

How to find out what happened to one order (or payment, return, review …) across the gateway, the services and
Kafka, and how to react to each alert. Local URLs below; in AWS the same steps use Amazon Managed Grafana, CloudWatch
Logs and X-Ray (Phase 14).

| Tool | Local URL |
|---|---|
| Admin app: order drawer → **Events** (timeline), **Operations** (dead letters, connectors, stuck orders) | <http://localhost:6420/admin/orders>, <http://localhost:6420/admin/operations> |
| Grafana: dashboards in folder *Grocery*, **Explore** (Loki, Tempo, Prometheus) | <http://localhost:3000> (anonymous viewer) |
| Prometheus (targets, alerts, PromQL) | <http://localhost:9090> |
| Alertmanager (active alerts, silences) | <http://localhost:9093> |
| Mailpit (alert emails to `ops@grocery.local`, customer emails) | <http://localhost:8025> |
| Kafka UI (topics, messages, DLTs, lag) | <http://localhost:8070> |

Requires the compose profile `observability` (see `infrastructure/local/observability/README.md`). Admin API examples
use `$GW` = the gateway (`http://localhost:8080`, or your `GATEWAY_HOST_PORT`) and an admin access token in `$TOKEN`.

What every signal carries:
- **Logs** (Loki): labels `service`, `level`; structured metadata `traceId`, `spanId`, `correlationId`, `container`;
  the line is the service's ECS JSON (`message`, `log.logger`, `error.stack_trace` …).
- **Traces** (Tempo): one trace per business transaction, across Kafka. The outbox stores `traceparent`, Debezium puts
  it in a Kafka header, and consumers continue the trace.
  - An order is several transactions: placing it, paying, the store's decision, pickup, delivery. Each starts with a
    different person's request and reaches every service that reacts to its events (up to 15 services).
  - To see all traces of one order, search its number in the trace explorer (§3).
- **Correlation id**: every HTTP response echoes `X-Correlation-Id`, error bodies carry `correlationId`, and the
  outbox forwards it as a Kafka header, so it follows the transaction through all events.
- **Metrics** (Prometheus): label `service` = `spring.application.name`, `instance` = the container's host name (its
  short id, the meters' own tag). Only the scrape series `up` carries the target address (container:port).

## 1. Start from what the user has

| The user has … | Turn it into |
|---|---|
| an **order number** (`GA-7K3F9Q2M`) | the order id: admin *Orders* search, or `GET $GW/api/admin/orders?q=GA-7K3F9Q2M`; the creation log line also has both: `{service="order-service"} \|= "GA-7K3F9Q2M"` → `Order GA-7K3F9Q2M (<orderId>, GROCERY) created …` |
| a **correlation id** from an error response / `X-Correlation-Id` | use it directly (step 3) |
| a **payment id** / Razorpay order id | `{service="payment-service"} \|= "<payment id>"` → the line names the order |
| only a **time** ("around 14:05") | Platform overview at that time: which service shows errors or latency? Then step 6 |

Time is UTC everywhere in the logs; Grafana shows your browser's time zone.

## 2. Admin order timeline

Admin → *Orders* → the order → **Events** tab (or `GET $GW/api/admin/orders/{id}/events`): every event order-service
published and consumed for this order, oldest first, with `direction`, `outcome` and `correlationId`.

- A step is **missing** (e.g. `PaymentCaptured` never consumed): the producer did not publish it, it is still in
  Kafka (lag), or it was dead-lettered → steps 4 and 5.
- **IGNORED**: a state guard rejected it; `detail` gives the reason (e.g. a late `PaymentCaptured` after
  `OrderCancelled` → a refund follows).
- **DUPLICATE**: already processed (a redelivery or a replay) — harmless.

Copy the `correlationId` of the events for step 3.

## 3. Grafana → Order trace explorer

Dashboard **Grocery · Order trace explorer** (<http://localhost:3000/d/grocery-trace-explorer>):

1. Type the order number, order id, payment id or correlation id into *Order number / correlation id / text*;
   widen the time range if needed.
2. **Traces for the search** lists the `traceId`s of the matching log lines → *Show this trace below* (or *Open in
   Explore* for the trace and its logs side by side).
3. The trace shows the whole path with timings and errors: gateway → service → Kafka → consumers. Red spans are
   errors; a span's *Logs for this span* opens the matching Loki lines.
4. **Correlation ids for the search** → *Search this correlation id* shows every line of the transaction, also in
   services the trace does not reach (scheduled jobs, replays).

The same in **Explore**:

```logql
# Loki — lines for an order (number or id), newest first
{service=~".+"} |= "GA-7K3F9Q2M"
{service=~".+"} |= "01a1d5de-a291-7633-b8a6-a9572463f9b1"
# everything of one transaction / one trace
{service=~".+"} | correlationId="apit-tog9redx-kafka"
{service=~".+"} | traceId="4bf92f3577b34da6a3ce929d0e0e4736"
# errors of that transaction, with the exception
{service=~".+", level="error"} | correlationId="apit-tog9redx-kafka" | json | line_format "{{.service_name}} {{.message}} {{.error_type}}"
# which services touched it, and how often
sum by (service) (count_over_time({service=~".+"} | correlationId="apit-tog9redx-kafka" [24h]))
```

```traceql
# Tempo — failed traces of a service / slow requests at the gateway / failed spans of order placement
{ resource.service.name = "order-service" && status = error }
{ resource.service.name = "api-gateway" && duration > 2s }
{ resource.service.name = "order-service" && name =~ "(?i).*post.*orders.*" && status = error }
# Kafka consumer spans of a service (the trace continues through the outbox header)
{ resource.service.name = "inventory-service" && kind = consumer }
```

## 4. Kafka & Debezium dashboard

**Grocery · Kafka & Debezium** (<http://localhost:3000/d/grocery-kafka>):

- **Lag per consumer group**: a group that keeps growing is not consuming (service down, stuck on a retry, or a
  poison message in the 30-minute outage retry). `sum by (consumergroup, topic) (kafka_consumergroup_lag)`
- **Connectors / Tasks** tables: anything not `running` means outbox rows of that service are not published
  (orders stay `CREATED`, payments never reach order-service …). `kafka_connect_connector_task_status{status!="running"} == 1`
- **Debezium lag** (`debezium_streaming_milliseconds_behind_source`) and *time since the last binlog event*: a connected
  but silent reader is fine when nothing happens; a lag growing with traffic means Debezium is behind.
- **DLT records / pending dead letters**: a growing `*.DLT` topic is an event that failed processing → step 5.

## 5. Operations page

Admin → **Operations** (<http://localhost:6420/admin/operations>), Phase 10:

- **Dead letters**: filter by service / consumer group / event type; open one for the payload and the exception
  (the same exception is in Loki: `{service="<service>", level="error"} |= "<eventId>"`). Fix the cause, then
  **Replay** (SUPER_ADMIN / OPS_ADMIN; idempotent, only in the failed service) or **Discard** with a reason.
  API: `GET $GW/api/admin/ops/<service>/dead-letters?status=PENDING`, `POST …/{id}/replay`.
- **Connectors**: state of each connector and task; *Restart* restarts failed tasks
  (`POST $GW/api/admin/system/connectors/{name}/restart`). The `connect-watchdog` container already retried 3 times
  in 10 minutes if a task is still FAILED: `docker compose logs connect-watchdog`.
- **Stuck orders**: orders overdue in a status with the reason (`GET $GW/api/admin/orders/stuck`). They resolve
  themselves when the order moves on.

## 6. Service detail dashboard

**Grocery · Service detail** (<http://localhost:3000/d/grocery-service>), choose `service`, set the time range around
the incident:

- HTTP requests, 4xx/5xx and p95 **by route**, outgoing HTTP calls (RestClient/WebClient);
- JVM heap/GC/threads, HikariCP pool (active, pending, acquire time, timeouts), Kafka listeners (rate, errors,
  `grocery_events_consumed_total` by outcome `APPLIED` / `DUPLICATE` / `FAILED`), consumer lag of its groups;
- **Logs** panel — put a `traceId` or correlation id into *Log search* — and its recent (error) traces.

```promql
# error ratio and p95 of one service
sum(rate(http_server_requests_seconds_count{service="payment-service", status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{service="payment-service"}[5m]))
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{service="payment-service"}[5m])))
# failed event processing per event type
sum by (event_type) (increase(grocery_events_consumed_total{service="order-service", outcome="FAILED"}[1h]))
```

## 7. Fix, replay, confirm

1. Fix the cause (configuration, data, a deployment, a provider outage that ended …).
2. Replay the dead letters (step 5) or restart the failed connector task; for a lost payment confirmation wait for the
   reconciliation job (every 2 minutes) or check `GET $GW/api/admin/payments/reconciliation`.
3. Confirm in the **order timeline** (step 2) that the missing event is now `APPLIED`, the order has the expected
   status, the DLT pending count is back to 0 and the alert resolved (a *RESOLVED* email arrives in Mailpit).

---

## Alerts

Every alert email names the alert, the service and links (annotations `dashboard`, `runbook`). Critical alerts repeat
every hour, warnings every 4 h, info every 24 h, until resolved or silenced in Alertmanager.

### ServiceDown

Prometheus cannot scrape `/actuator/prometheus` of a service that was up during the last 24 h.

- `docker compose ps <service>` — exited, restarting, unhealthy? `docker compose logs --tail 200 <service>` or
  `{service="<service>"}` in Loki (OOM: `docker inspect grocery-<service>-1 --format '{{.State.OOMKilled}}'`).
- Up but DOWN in Prometheus <http://localhost:9090/targets>: the *Error* column (401/403 = actuator not reachable,
  404 = metrics not exposed, timeout = overloaded JVM).
- Start it again: `docker compose up -d <service>`. The gateway notices the new container within 5 s.

### HighErrorRate / SlowRequests

More than 5 % 5xx for 5 minutes / p95 above 1 s for 10 minutes (SSE and assistant chat streams excluded).

- Service detail → *4xx / 5xx by route* or *p95 by route*: one route or all?
- Errors: `{service="<service>", level="error"}` → open the `traceId` → which span failed (a downstream service, the
  database, Kafka, a provider)?
- Latency: HikariCP *pending* and *acquire time* (pool exhausted), GC time, outgoing HTTP calls, MySQL *threads
  running* / slow queries on the Databases & caches dashboard.
- At the gateway, 502/503/504 point to the downstream service (ServiceDown?) or its response time (15 s timeout).

### JvmHeapHigh

Heap above 90 % for 10 minutes. Service detail → *Heap* and *GC time*. A sawtooth that goes back down is a busy but
healthy JVM; a floor that keeps rising is a leak or a container that is too small (`mem_limit` in
`docker-compose.yml`; heap = 75 % of it). Restarting helps only temporarily.

### KafkaConsumerLagHigh

A consumer group lags more than 100 records for 5 minutes.

- Kafka & Debezium → *Lag per group and topic*: which topic? Is the consuming service up (ServiceDown)?
- Its logs: `{service="<service>"} |~ "(?i)retry|backoff"` — an outage retry holds the partition up to
  30 minutes (Phase 10 retry policy), then the record goes to the DLT.
- A rebalance loop shows as repeated `Revoking previously assigned partitions` lines.

### DeadLettersPending

An event failed processing and waits in a DLT for more than 10 minutes. Operations → **Dead letters** (step 5):
read the exception, fix, replay or discard. In Loki: `{service="<service>", level="error"} |= "<eventType>"`.
A poison message (unreadable/invalid event) never succeeds: discard it with a reason.

### ConnectorTaskFailed

A Debezium connector or task is not RUNNING for 2 minutes: outbox events of that service are not published.

- Operations → **Connectors** or `bash infrastructure/local/scripts/check-connectors.sh`; the task's `trace` holds the
  exception. Loki: `{service="kafka-connect", level="error"}` and `{service="connect-watchdog"}`.
- Restart the failed task (Operations, or
  `curl -X POST "localhost:8083/connectors/<name>/restart?includeTasks=true&onlyFailed=true"`); outbox rows written
  meanwhile are published then.
- Every connector UNASSIGNED and restarts answer 409: the worker is stuck in a rebalance → `docker restart
  grocery-kafka-connect-1` (offsets are kept). See the README *Troubleshooting* section.
- If the dashboard shows *JMX reachable: NO*, the connector metrics are missing (kafka-connect started without its
  JMX settings, or it is down) — this alert cannot fire then; use Operations → Connectors.

### StuckOrders

The order watchdog flagged orders overdue in a status for 5 minutes (reason in the alert). Operations → **Stuck
orders** lists them; open the order's **Events** timeline (step 2):

| Reason | Usually means |
|---|---|
| `STOCK_CHECK_OVERDUE` | inventory did not answer `OrderCreated` (inventory-service down, lag, DLT, connector of order-service) |
| `PAYMENT_EXPIRY_OVERDUE` | the payment-window poller did not expire the payment (payment-service down?) |
| `STORE_RESPONSE_OVERDUE` | the store did not accept/reject and the auto-reject did not run (vendor/restaurant service) |
| `PREPARATION_OVERDUE`, `PICKUP_OVERDUE`, `DELIVERY_OVERDUE` | operational delay at the store / partner, or delivery-service events not flowing |

### PaymentFailureSpike / ReconciliationFailures

More than 30 % of payment attempts failed in 15 minutes (≥ 5 attempts) / the reconciliation job failed.

- Business dashboard → *Payments per minute*; Loki: `{service="payment-service", level=~"warn|error"}`.
- Provider side (Razorpay test mode): keys in `.env`, provider status, webhook secret; in simulator mode failures are
  usually deliberate test cards.
- Reconciliation: `GET $GW/api/admin/payments/reconciliation` (`lastRunAt`, `failures`); failures are retried on the
  next run — they matter when payments stay `PENDING` and orders `AWAITING_PAYMENT`.

### AiModelUnavailable

The assistant's model provider has been unreachable for 10 minutes (info only). Is Ollama running and the model
pulled (`docker compose --profile ai up -d`, `docker compose logs ollama-init`)? With `AI_PROVIDER=scripted` no model
is needed. Chats answer `AI_UNAVAILABLE` meanwhile; nothing else is affected.

### EmailFailures

notification-service could not hand emails to SMTP in the last 30 minutes. Locally: is Mailpit running
(`docker compose ps mailpit`)? Admin → *Emails* shows the email outbox; failed emails are retried automatically.
Loki: `{service="notification-service"} |~ "(?i)mail"`.

### DatabaseDown / RedisDown / ElasticsearchDown

The exporter cannot reach MySQL / Redis / Elasticsearch for 1 minute (ElasticsearchDown only if Elasticsearch was up
in the last 24 h — it runs with the `search` / `catalog` profiles).

- `docker compose ps mysql redis elasticsearch`, then their logs (`{service="mysql"}`, `{service="elasticsearch"}`).
- Memory pressure on Docker Desktop is the usual local cause (Elasticsearch needs 1 GB): `docker stats`.
- DatabaseDown suppresses HighErrorRate / SlowRequests / ConnectorTaskFailed emails (they are consequences).

### MonitoringTargetDown

An exporter or observability component cannot be scraped for 5 minutes, so the alerts that depend on it cannot fire.
Prometheus → *Targets* shows the error; `docker compose up -d <container>` (see
`infrastructure/local/observability/README.md`).

### LogShippingStalled

No log line has reached Loki for 15 minutes, although Kafka and Kafka Connect alone log every minute. Alloy, the log
shipper, is wedged: in the Phase 13 load test a Docker API call failed under load and it stopped tailing while its
own metrics still answered, so MonitoringTargetDown stayed quiet.
- `docker inspect grocery-alloy-1 --format '{{.State.Health.Status}}'` usually shows `unhealthy`; Docker does not
  restart unhealthy containers by itself.
- `docker restart grocery-alloy-1`. Alloy resumes from its saved positions, so the lines written meanwhile are
  shipped from the containers' own log files (as long as Docker still has them).
