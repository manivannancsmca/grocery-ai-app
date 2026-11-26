# Local observability stack (Phase 13)

Metrics, logs, traces, dashboards and alerts for the local Docker stack, in compose profile **`observability`**.
Contract: [docs/api/phase-13-observability-and-testing.md](../../../docs/api/phase-13-observability-and-testing.md).
Runbook: [docs/operations/troubleshooting.md](../../../docs/operations/troubleshooting.md).

## Start

From `infrastructure/local`, next to the app profiles you already use:

```bash
docker compose --profile core --profile catalog --profile inventory --profile cart --profile orders \
               --profile realtime --profile observability up -d --build
# or everything: docker compose --profile all up -d --build
# only the observability containers (the app keeps running):
docker compose --profile observability up -d
```

Starting the profile never recreates an application container: none of these services has a `depends_on` towards
the app. Exporters simply report "down" until their target exists.

**Services on this machine (host-run).** When the Spring Boot services run on Windows (`host-run/start-all.cmd`)
instead of in containers, tell Prometheus where they are, once, in `infrastructure/local/.env`:

```bash
SERVICES_RUN_ON=host      # default: docker
```

Prometheus then scrapes `host.docker.internal:<port>` (`prometheus/targets/services-host.yml`) instead of the
container names (`services-docker.yml`). Run `docker compose --profile observability up -d prometheus` after changing
it. Without it, every service target is DOWN (`lookup cart-service … no such host`) and the dashboards stay empty.
In this mode the services' logs are in their terminal tabs, not in Loki (Alloy ships container logs), and they send
no traces (`run-service.cmd` samples nothing because the collector is not published to the host).

| URL | What | Login |
|---|---|---|
| <http://localhost:3000> | **Grafana**: dashboards (folder *Grocery*), Explore for Loki / Tempo / Prometheus | anonymous viewer (may use Explore, cannot save); admin `admin` / `${GRAFANA_ADMIN_PASSWORD:-admin}` |
| <http://localhost:9090> | **Prometheus**: targets (`/targets`), alert rules (`/alerts`), PromQL | — |
| <http://localhost:9093> | **Alertmanager**: active alerts, silences | — |
| <http://localhost:8025> | **Mailpit**: alert emails to `ops@grocery.local` | — |

Host ports can be changed with `GRAFANA_HOST_PORT`, `PROMETHEUS_HOST_PORT`, `ALERTMANAGER_HOST_PORT` (e.g. in
`infrastructure/local/.env`). These UIs listen on `BIND_ADDRESS` (default `127.0.0.1`, this machine only). If another
program on Windows already listens on `127.0.0.1:9093`, Alertmanager cannot start: set `ALERTMANAGER_HOST_PORT=9094`.

Loki, Tempo, Alloy and the OpenTelemetry Collector are internal only. The services send OTLP to
`http://otel-collector:4318`. Prometheus also accepts remote write at `http://prometheus:9090/api/v1/write`, used by
the k6 performance tests.

## Containers, images and memory

| Container | Image | mem_limit | Notes |
|---|---|---|---|
| prometheus | `prom/prometheus:v3.15.1` | 320m | scrape every 15 s, retention 3 d (and at most 2 GB), rules → Alertmanager, exemplars on, remote-write receiver |
| alertmanager | `prom/alertmanager:v0.34.2` | 48m | email through `mailpit:1025` |
| grafana | `grafana/grafana:12.4.15` | 320m | provisioned datasources and dashboards; SQLite with write-ahead log (`GF_DATABASE_WAL`), see *Troubleshooting* |
| loki | `grafana/loki:3.7.9` | 192m | filesystem, retention 3 d; labels `service`, `level` only |
| alloy | `grafana/alloy:v1.21.3` | 160m | reads the Docker socket and ships logs of the `grocery` compose project only |
| tempo | `grafana/tempo:2.10.10` | 256m | local blocks, retention 1 d (sized for k6 runs at 100 % sampling) |
| otel-collector | `otel/opentelemetry-collector-contrib:0.165.0` | 288m | OTLP 4317/4318 → Tempo (memory limiter 230 MiB; idles at ~115 MiB) |
| kafka-exporter | `danielqsj/kafka-exporter:v1.11.0` | 48m | consumer lag, offsets (DLT sizes) |
| kafka-connect-jmx-exporter | built here (`kafka-connect-jmx/`, JMX exporter 1.7.0 on `eclipse-temurin:25.0.5_8-jre-alpine`) | 64m | Kafka Connect / Debezium JMX |
| mysqld-exporter | `prom/mysqld-exporter:v0.21.0` | 32m | read-only `monitoring_user` |
| redis-exporter | `oliver006/redis_exporter:v1.97.0-alpine` | 32m | |
| elasticsearch-exporter | `prometheuscommunity/elasticsearch-exporter:v1.11.0` | 32m | ES runs with the `search` / `catalog` profiles |
| mailpit (shared with `realtime`) | `axllent/mailpit:v1.32.0` | 64m | also started by this profile |

The limits add up to **1856 MiB (about 1.8 GB)** with Mailpit; measured idle use is about 0.85 GB before the services expose
metrics. Prometheus grows with the number of series once the services are scraped (see *Cardinality* below).

## What is collected

**Metrics** (`prometheus/prometheus.yml`):
- job `services`: every Spring service at `/actuator/prometheus` on its own port (gateway: its internal management port `api-gateway:9080`,
  identity 8101 … reporting 8116, ai-orchestrator 8115, both notification replicas). The addresses come from
  `prometheus/targets/services-docker.yml`, or `services-host.yml` with `SERVICES_RUN_ON=host` (see *Start*). The target label `service` is
  `spring.application.name`; both notification replicas share `service="notification-service"` and differ by
  `instance`. Services of profiles you did not start are simply DOWN targets.
- jobs `kafka`, `kafka-connect`, `mysql`, `redis`, `elasticsearch`: the exporters.
- jobs `prometheus`, `alertmanager`, `observability` (Grafana, Loki, Tempo, Alloy, collector; a few metrics only).

**Kafka Connect / Debezium.** The Debezium image's `ENABLE_JMX_EXPORTER` switch needs a JMX-exporter javaagent jar
that `quay.io/debezium/connect:3.7.1.Final` does not contain, so `kafka-connect` instead opens remote JMX
(`JMXHOST=kafka-connect`, `JMXPORT=9012`, internal network only) and `kafka-connect-jmx-exporter` turns it into
metrics (`kafka-connect-jmx/kafka-connect.yml`):
`kafka_connect_connector_status{connector,status}`, `kafka_connect_connector_task_status{connector,task,status}`
(value 1 for the current state), `kafka_connect_worker_connector_<state>_task_count`,
`kafka_connect_source_task_source_record_{poll,write}_total`, `debezium_streaming_milliseconds_behind_source{server}`,
`debezium_streaming_connected`, `debezium_streaming_events_seen_total` … (`server` = the connector's `topic.prefix`;
the connector is `<server>-outbox`). `jmx_scrape_error 1` means kafka-connect's JMX port is not reachable — it opens
only after kafka-connect was (re)created with these settings.

**Logs** (`alloy/config.alloy` → `loki/loki.yml`): every container of the compose project `grocery`. Labels:
`service` (ECS `service.name` for Spring services, otherwise the compose service) and `level`. `traceId`, `spanId`,
`correlationId` and `container` are structured metadata; the line is kept as the original ECS JSON.

```logql
{service="order-service", level="error"}
{service=~".+"} | traceId="4bf92f3577b34da6a3ce929d0e0e4736"
{service=~".+"} | correlationId="apit-tog9redx-kafka"
{service=~".+"} |= "GA-7K3F9Q2M"                       # an order number
{service="payment-service"} | json | message=~".*declined.*"
```

**Traces** (`otel-collector/config.yaml` → `tempo/tempo.yml`): OTLP from the services, W3C `traceparent` across HTTP
and Kafka (outbox header). In Grafana: Explore → Tempo → TraceQL, e.g.
`{ resource.service.name = "order-service" && status = error }`. A span links to its logs (Loki, by `traceId`) and to
the request metrics of its service; a Loki line links to its trace; Prometheus exemplars link to traces.

## Dashboards (`grafana/dashboards/`, folder *Grocery*)

| Dashboard | UID |
|---|---|
| Platform overview (home) | `grocery-overview` |
| Service detail (variable `service`) | `grocery-service` |
| Kafka & Debezium | `grocery-kafka` |
| Databases & caches | `grocery-data` |
| Business | `grocery-business` |
| Order trace explorer | `grocery-trace-explorer` |
| Knowledge base (help center, RAG: docs/rag/) | `grocery-knowledge-base` |

They use the datasource UIDs `prometheus`, `loki`, `tempo` (and `alertmanager`), provisioned by
`grafana/provisioning/datasources/datasources.yml`; in Amazon Managed Grafana create the datasources with the same
UIDs to reuse the JSON.

**Add or change a dashboard.** Provisioned dashboards are read-only in Grafana. Sign in as admin, build the
dashboard (or *Save as* a copy of an existing one), then *Share → Export → Save to file* (keep "Export for sharing
externally" **off**, so the datasource UIDs stay `prometheus` / `loki` / `tempo`), and save the JSON as
`grafana/dashboards/<name>.json` with a unique `uid` and the tag `grocery`. Grafana loads new and changed files within
30 seconds — no restart needed. Delete the UI copy afterwards.

## Alerts

Rules: `prometheus/alerts.yml` (ServiceDown, HighErrorRate, SlowRequests, JvmHeapHigh, KafkaConsumerLagHigh,
DeadLettersPending, ConnectorTaskFailed, StuckOrders, PaymentFailureSpike, ReconciliationFailures, AiModelUnavailable,
EmailFailures, DatabaseDown, RedisDown, ElasticsearchDown, MonitoringTargetDown). Each has a `runbook` annotation
pointing into `docs/operations/troubleshooting.md`.

**How an alert reaches Mailpit:** Prometheus evaluates the rules every 15 s → a firing alert goes to Alertmanager
(`alertmanager:9093`) → Alertmanager groups by `alertname` + `service` (wait 30 s, group interval 5 min) → emails
`ops@grocery.local` from `alertmanager@grocery.local` through `mailpit:1025` → <http://localhost:8025>. Resolved alerts
are emailed too. Repeats: critical every hour, warning every 4 h, info every 24 h. While `ServiceDown` fires for a
service, its warnings are inhibited; `DatabaseDown` inhibits HighErrorRate / SlowRequests / ConnectorTaskFailed.

Send a test email without breaking anything:

```bash
curl -X POST -H 'Content-Type: application/json' http://localhost:9093/api/v2/alerts \
  -d '[{"labels":{"alertname":"ObservabilityTest","severity":"warning","service":"test"},
        "annotations":{"summary":"test alert"}}]'
# ~30 s later: Mailpit shows "[FIRING:1] ObservabilityTest · test (warning)"
```

Silence an alert: Alertmanager UI (<http://localhost:9093>) → *New silence*, or with amtool from the image:

```bash
docker compose exec alertmanager amtool --alertmanager.url=http://localhost:9093 \
  silence add alertname=StuckOrders --duration=2h --author=me --comment="local test"
```

Notes:
- `ServiceDown` / `ElasticsearchDown` fire only for a target that was up at some point in the last 24 h, so services
  of compose profiles that were never started do not page.
- Change rules → `bash infrastructure/local/observability/validate.sh` → `curl -X POST http://localhost:9090/-/reload`.

## Validate (Docker only)

```bash
bash infrastructure/local/observability/validate.sh
```

Runs `promtool check config`, `promtool check rules`, `promtool test rules` (`prometheus/tests/alerts.test.yml`:
firing and non-firing cases for every alert), `amtool check-config` and `alloy validate`.

## What the services must provide (grocery-common 0.9.0 rollout)

- `/actuator/prometheus` exposed **and reachable without a token** from inside the Docker network (today the
  services answer 401 to Prometheus).
- Common tags `service` (= `spring.application.name`), `env`, `instance`.
- Histogram buckets for `http.server.requests` (p95 panels and SlowRequests), for the gateway also
  `spring.cloud.gateway.requests` (the gateway reports proxied requests as `uri="UNKNOWN"`, so its p95 comes from the
  route timer), and for `grocery.orders.placement` and `grocery.ai.model.latency`. Prefer explicit SLO buckets
  (e.g. 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2 s, 5 s, 10 s) over `percentiles-histogram` to keep the series count low.
- The business metric names of the contract (§1), e.g. `grocery_events_published_total{topic,event_type}`,
  `grocery_dead_letters_pending{consumer_group}`, `grocery_orders_stuck{reason}`.
- Prometheus exemplars carry the label `trace_id` (Micrometer default) for the metrics → trace link.

**Cardinality.** Kafka client metrics per partition and per broker node are dropped at scrape time
(`metric_relabel_configs`). If Prometheus approaches its memory limit, look at
<http://localhost:9090/tsdb-status> for the biggest metrics.

## Stop / reset

Do **not** use `docker compose --profile observability down`: it also stops the always-on infrastructure
(MySQL, Kafka, Redis …). Name the containers instead:

```bash
OBS="prometheus alertmanager grafana loki alloy tempo otel-collector kafka-exporter kafka-connect-jmx-exporter \
     mysqld-exporter redis-exporter elasticsearch-exporter"
docker compose stop $OBS                    # keeps data
docker compose rm -sf $OBS                  # removes the containers, keeps data
docker volume rm grocery_prometheus-data grocery_loki-data grocery_tempo-data grocery_grafana-data \
                 grocery_alertmanager-data grocery_alloy-data   # deletes metrics, logs, traces, Grafana state
```

After deleting `grocery_alloy-data`, Alloy ships the containers' full log history once more on its next start.

## Troubleshooting

- **Grafana or Alloy shows `Exited` in `docker ps -a`.** A container someone stopped (`docker stop`,
  `docker compose stop`) stays stopped: `restart: unless-stopped` only restarts containers that crash. Start them
  again with `docker compose --profile observability up -d grafana alloy`.
- **Grafana logs `database is locked (5) (SQLITE_BUSY)` and its API times out.** Grafana keeps its state in SQLite.
  Without a write-ahead log, every write blocks all readers. Grafana 12's background jobs (alerting, dashboard
  storage, cleanup) then wait on each other, especially while the machine is busy (a model running on the CPU, a
  Maven build). Since this was seen, the compose file starts Grafana with `GF_DATABASE_WAL=true` and
  `GF_DATABASE_QUERY_RETRIES=3`, and with 320 MiB instead of 224 MiB. An existing `grafana.db` switches to the WAL
  on the next start: `docker compose exec grafana ls /var/lib/grafana` then shows `grafana.db-wal`.
- **Alloy is at its memory limit right after starting.** On a first start, or after a long stop, it catches up on
  the containers' log history, then settles at about 100 MiB.
