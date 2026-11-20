# ADR-0013: Observability with OpenTelemetry and Micrometer: Grafana stack locally, AWS managed services in production

- **Status:** Accepted (product owner's choice: AWS managed services for monitoring)
- **Date:** 2026-11-13

## Context

One customer order crosses the gateway and up to seven services, partly over HTTP and partly over Kafka (outbox →
Debezium → consumers). Before Phase 13 a problem meant reading logs service by service and matching correlation ids
by hand. The platform needs:
- **traces** that follow one business transaction across HTTP *and* Kafka;
- **metrics** for services, infrastructure (Kafka lag, connectors, MySQL, Redis, Elasticsearch) and the business
  (orders, payments, dead letters, stuck orders, AI tool calls);
- **searchable logs** linked to traces;
- **dashboards and alerts** that work the same on a laptop and in AWS;
- no vendor lock-in in the service code.

## Decision

**Instrument once, with open standards** (grocery-common 0.9.0 sets this up for every service):
- **Metrics:** Micrometer with the Prometheus registry on `/actuator/prometheus` (never routed by the gateway). Common
  tags `service`, `env` (`GROCERY_ENV`), `instance`. Business meters as listed in the Phase 13 contract §1.
- **Traces:** Micrometer Tracing with the OpenTelemetry bridge, exported over **OTLP** to
  `OTEL_EXPORTER_OTLP_ENDPOINT`. W3C `traceparent` is propagated over HTTP and **through the outbox**: the outbox row
  stores `traceparent`, Debezium's EventRouter turns it into a Kafka header, and consumers continue the trace. One
  order is one trace.
- **Logs:** structured JSON (ECS format) on stdout with `traceId`, `spanId`, `correlationId` and `service`.
- Sampling `TRACING_SAMPLING_PROBABILITY`: 1.0 locally, 0.1 by default in AWS.

**Ship through a collector, never from the code to a vendor.** The services only know an OTLP endpoint and a
Prometheus endpoint. What sits behind them differs per place:

| Signal | Local (compose profile `observability`) | AWS |
|---|---|---|
| Metrics | Prometheus scrapes every service and the exporters | **ADOT collector sidecar** in every task scrapes `localhost` and remote-writes to **Amazon Managed Prometheus** (SigV4) |
| Traces | `otel-collector` → **Tempo** | ADOT sidecar (`localhost:4318`) → **AWS X-Ray** (OTLP) |
| Logs | Grafana **Alloy** reads Docker logs → **Loki** | `awslogs` driver → **CloudWatch Logs** (one log group per service, JSON kept) |
| Dashboards | Grafana, provisioned from `infrastructure/local/observability/grafana/dashboards/*.json` | **Amazon Managed Grafana**, the same JSON (datasources AMP, X-Ray, CloudWatch) |
| Alerts | Prometheus rules → Alertmanager → email (Mailpit) | The same rule expressions as **AMP alert rules**, plus CloudWatch alarms (ALB 5xx, unhealthy targets, task failures, RDS, MSK) → **SNS** email |
| Infra metrics | kafka-, mysqld-, redis-, elasticsearch-exporter; Kafka Connect JMX | CloudWatch metrics of RDS, MSK, MSK Connect, ElastiCache (via the CloudWatch datasource); Elastic Cloud's own monitoring |

- **A sidecar, not a central collector service**, in AWS: it scrapes the app on `localhost`, so there is no service
  discovery to configure, it scales with the service, and one service's telemetry cannot starve another's.
- **Alarms drive deployments too:** the CloudWatch alarms that page people are the same ones that trigger the ECS
  blue/green automatic rollback (ADR-0012).
- **Runbook:** `docs/operations/troubleshooting.md` (from the order number to the trace, the logs and the lagging
  consumer).

## Consequences

- ✅ Identical service code and configuration shape locally and in AWS. Only `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `GROCERY_ENV` and the sampling rate change.
- ✅ One order is one trace, Kafka hops included, so the slow or failing step is visible at once.
- ✅ No metrics, logs or trace servers to run in production: AMP, X-Ray, CloudWatch Logs and AMG are managed.
- ✅ The dashboards and alert rules are written and tested once (`promtool` tests locally) and reused.
- ⚠️ The datasources differ (Tempo/Loki vs X-Ray/CloudWatch Logs), so the "logs" and "traces" panels need a
  datasource variable, and Loki LogQL panels become CloudWatch Logs Insights queries in AWS. Metric panels are
  unchanged (PromQL on AMP).
- ⚠️ The ADOT sidecar adds about 0.25 vCPU / 256 MB per task in the task size. Sampling at 0.1 keeps X-Ray costs
  predictable but means only one order in ten has a stored trace; logs still carry the `traceId` of every request.
- ⚠️ The X-Ray console can show a trace id in its own `1-<8 hex>-<24 hex>` form: the same 32 hex digits as the
  `traceId` in the logs, split after the first eight. Searching from a log line works once the id is written that way.
- ⚠️ AMP charges per sample ingested and CloudWatch Logs per GB ingested: the 15 s scrape interval, metric
  cardinality (no ids as tags) and log retention per environment matter for cost.

## Alternatives considered

- **The Grafana stack (Prometheus/Loki/Tempo) self-hosted on ECS in production:** the same tools as locally, but
  stateful services to run, scale and back up. Rejected by the product owner in favour of managed services.
- **Grafana Cloud / Datadog / New Relic:** excellent, but a new vendor contract and data leaving AWS; the OTLP
  approach keeps this open as a later swap of the collector's exporters.
- **CloudWatch only (Container Insights, CloudWatch metrics via EMF, ServiceLens):** no PromQL, so the dashboards and
  alert rules would be written twice and differently from local.
- **Exporting from the services straight to X-Ray/AMP:** couples service code to AWS SDKs and credentials. The
  collector keeps the code vendor-neutral.
- **Zipkin/B3 propagation:** W3C Trace Context is the standard that Micrometer, OpenTelemetry and X-Ray (OTLP) all
  understand.
