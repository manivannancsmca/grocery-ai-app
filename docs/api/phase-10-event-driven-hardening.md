# Phase 10 contract — Event-driven hardening

Makes the event backbone operable: dead letters can be inspected and replayed safely, lost webhooks are recovered,
stuck orders are found, an order's full event history is visible, old rows are cleaned up, and a broken Debezium task
is noticed and restarted. Public paths go through the gateway (`http://localhost:8080`); conventions unchanged
([Phase 2](phase-2-identity-and-stakeholders.md#1-conventions)); conflict errors carry `currentStatus`.
Events are Avro (ADR-0011); the one new event's schema is in `libs/grocery-event-schemas` **1.3.0**.

## 1. Dead letters — inspect, replay, discard (grocery-common 0.7.0, every consuming service)

Every service that consumes Kafka (identity, customer, vendor, restaurant, delivery, catalog-command, catalog-query,
inventory, pricing, cart, order, payment, notification) exposes the same API for **its own** consumer groups.

| Method & path (inside the service) | Roles | Body → Response |
|---|---|---|
| `GET /api/admin/dead-letters?status=&consumerGroup=&eventType=&page=&size=` | any admin | → `PageResponse<DeadLetterView>`, newest first (`size` ≤ 100). Only records whose `kafka_dlt-original-consumer-group` is one of this service's groups |
| `GET /api/admin/dead-letters/{id}` | any admin | → `DeadLetterView` + `payload` (decoded JSON) + `stackTrace` (first 4 KB). Unknown → 404 `DEAD_LETTER_NOT_FOUND` |
| `POST /api/admin/dead-letters/{id}/replay` | SUPER_ADMIN, OPS_ADMIN | → `DeadLetterView` with the outcome (`REPLAYED` or `REPLAY_FAILED` + `lastError`). Already `REPLAYED` or `DISCARDED` → 409 `DEAD_LETTER_CLOSED` (`currentStatus`) |
| `POST /api/admin/dead-letters/{id}/discard` | SUPER_ADMIN, OPS_ADMIN | `{ "reason": "…" }` (required ≤ 300) → `DISCARDED` |
| `POST /api/admin/dead-letters/replay-all` | SUPER_ADMIN, OPS_ADMIN | `{ "consumerGroup"?, "eventType"? }` → `{ "attempted", "replayed", "failed" }` (PENDING and REPLAY_FAILED only, oldest first, at most 100 per call) |

```jsonc
// DeadLetterView — id = "<dltTopic>:<partition>:<offset>"
{ "id": "vendor.events.DLT:0:12", "dltTopic": "vendor.events.DLT",
  "originalTopic": "vendor.events", "originalPartition": 1, "originalOffset": 845,
  "consumerGroup": "notification-service.dispatch", "key": "…",
  "eventId": "…", "eventType": "VendorSubmitted", "eventVersion": 1, "occurredAt": "…",
  "deadLetteredAt": "…",                                        // the DLT record's timestamp
  "exceptionClass": "com.grocery.common.kafka.InvalidEventException", "exceptionMessage": "…",
  "payloadPreview": "{\"vendorId\":…",                          // decoded through the Avro deserializer, ≤ 500 chars
  "status": "PENDING",                                          // PENDING | REPLAYED | REPLAY_FAILED | DISCARDED
  "attempts": 0, "lastError": null, "lastActionBy": null, "lastActionAt": null }
```

**How a replay works:** the DLT record holds the **original bytes** (Avro or legacy JSON) and the original headers.
The service decodes it with its normal value deserializer and hands it, with the original topic, partition, offset,
key and headers (minus `kafka_dlt-*`), **directly to its own listener for that consumer group, in process**. It is
never re-published to the topic, so other consumer groups are not affected. Business idempotency is unchanged
(`processed_event`): replaying an event that was in fact applied has no effect. The listener runs synchronously in
the request; an exception becomes `REPLAY_FAILED` (the record stays in the DLT; it can be retried or discarded).
A replay follows each service's normal rules: notification-service, for example, only updates its projections for an
event older than 15 minutes (Phase 7 §5), so replaying an old dead letter never sends a stale notification.

**Bookkeeping without a database:** the status of each dead letter is stored in the compacted topic
`_grocery.dead-letter-actions` (key `<consumerGroup>|<dltTopic>|<partition>|<offset>`, value
`{status, attempts, lastError, actor, at}`), so services without MySQL (catalog-query, cart) work the same way.
Missing key = PENDING.

**Gateway:** `/api/admin/ops/<service>/dead-letters/**` → that service's `/api/admin/dead-letters/**`
(service = identity, customer, vendor, restaurant, delivery, catalog-command, catalog-query, inventory, pricing, cart,
order, payment, notification). The admin app aggregates them.

## 2. Retry policy (reviewed; now configurable)

Unchanged behaviour, made explicit and configurable through `grocery.kafka.retry.*` (grocery-common 0.7.0):

| Failure | Policy | Settings (default) |
|---|---|---|
| Unreadable or invalid event (`InvalidEventException`, undecodable bytes) | straight to `<topic>.DLT` | — |
| Processing error | blocking retries 1 s, 2 s, 4 s, 8 s (per-key order kept), then DLT | `max-attempts` (4), `initial-interval` (1s), `multiplier` (2.0), `max-interval` (10s) |
| Outage (database, search, registry, Kafka retriable, lock timeouts — `TransientFailures`) | same record retried, capped at 30 s apart, for up to 30 min, then DLT | `outage-max-interval` (30s), `outage-max-elapsed` (30m) |
| `delivery.location` (best effort) | dropped and logged, never retried or dead-lettered | — |

Everything dead-lettered is replayable for the DLT retention (30 days). `processed_event` is kept 35 days (> DLT
retention), so a replay can never apply an event twice.

## 3. Payment reconciliation (payment-service)

A job every `grocery.payment.reconcile-interval` (`${PAYMENT_RECONCILE_INTERVAL:2m}`) checks payments still `PENDING`
whose provider order is older than `grocery.payment.reconcile-after` (`${PAYMENT_RECONCILE_AFTER:2m}`) and not yet
expired: it lists the order's payments at the provider and applies what it finds through the same code as the
webhook (captured → `PaymentCaptured`; authorized → capture; failed → failed attempt). `SKIP LOCKED` + lease, safe with
replicas; provider outages are logged and retried next run. The payment-window poller keeps its own last check.

`GET /api/admin/payments/reconciliation` (any admin) → `{ "lastRunAt", "checked", "recovered", "failures",
"lastRecovered": [ { "orderId", "orderNumber", "outcome", "at" } ] }` (last 20).
`checked`, `recovered` and `failures` are cumulative since the first run (all replicas); `outcome` is what the
provider had: `CAPTURED`, `AUTHORIZED` (we captured it) or `FAILED` (a decline nobody reported); `lastRunAt` is null
before the first run.

**Acceptance:** a payment whose verify call and webhook were both lost is captured within one interval and the order
becomes CONFIRMED.

## 4. Stuck-order watchdog (order-service)

A job every `grocery.order.watchdog.interval` (`${ORDER_WATCHDOG_INTERVAL:1m}`) flags orders that stayed too long in a
status (thresholds are configurable, env in brackets):

| Status | Stuck when | Reason |
|---|---|---|
| CREATED | created > 2 min ago (`ORDER_STUCK_CREATED_AFTER`) | `STOCK_CHECK_OVERDUE` |
| AWAITING_PAYMENT | `payment.dueAt` + 2 min passed (`ORDER_STUCK_PAYMENT_GRACE`) | `PAYMENT_EXPIRY_OVERDUE` |
| CONFIRMED | confirmed > 15 min ago (`ORDER_STUCK_CONFIRMED_AFTER`) | `STORE_RESPONSE_OVERDUE` |
| ACCEPTED, PREPARING | `readyBy` + 30 min passed (`ORDER_STUCK_PREPARATION_GRACE`) | `PREPARATION_OVERDUE` |
| READY_FOR_PICKUP | ready > 30 min ago (`ORDER_STUCK_PICKUP_AFTER`) | `PICKUP_OVERDUE` |
| OUT_FOR_DELIVERY | picked up > 90 min ago (`ORDER_STUCK_DELIVERY_AFTER`) | `DELIVERY_OVERDUE` |

- Table `stuck_order` (order id, status, reason, since, due_at, detected_at, resolved_at; one flag per order and
  status). `since` is when the order entered the status (its status history); `due_at` is when it became overdue, which
  gives `minutesOverdue`. A flag resolves itself as soon as
  the order leaves that status (checked by the same job and on every status change).
- On first detection: `OrderStuckDetected` on `order.events` (schema 1.3.0:
  `{ orderId, orderNumber, orderType, customerId, storeId, status, reason, since, detectedAt }`).
- `GET /api/admin/orders/stuck` (any admin) → `[ { orderId, orderNumber, type, status, reason, since, detectedAt,
  minutesOverdue, store: {id, name}, customerId } ]`, longest overdue first.
- notification-service: `OrderStuckDetected` → `STUCK_ORDER` · WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT
  ("Order {no} is stuck: {reason label}" · "{status} since {HH:mm}." · `/admin/orders`) + signal
  `ADMIN_ORDERS_CHANGED`.

## 5. Order event timeline (order-service)

`order_event_log` records every event order-service **publishes** for an order and every event it **consumes** for
an order (inventory, payment, vendor/restaurant store events, delivery), in the same transaction as the processing:
`{ eventId, eventType, sourceTopic, direction: PUBLISHED | CONSUMED, outcome: APPLIED | IGNORED | DUPLICATE,
detail, occurredAt, recordedAt, correlationId }` (`IGNORED` = rejected by a state guard, with the guard's reason in
`detail`; `DUPLICATE` = already processed).

`GET /api/admin/orders/{id}/events` (any admin) → that list, oldest first. 404 `ORDER_NOT_FOUND`.

## 6. Housekeeping (grocery-common 0.7.0 unless noted)

| What | Rule | Setting |
|---|---|---|
| `outbox_event` | delete rows older than 7 days (Debezium has long published them; deletes are ignored by the outbox router) | `grocery.outbox.cleanup.retention` (7d, env `GROCERY_OUTBOX_CLEANUP_RETENTION`), `grocery.outbox.cleanup.interval` (1h, env `GROCERY_OUTBOX_CLEANUP_INTERVAL`), batches of 1000 |
| `processed_event` | delete older than 35 days | `grocery.processed-events.retention` (35d) |
| `idempotency_key` | delete older than 7 days | `grocery.idempotency.retention` (7d) |
| `location_sample` (delivery-service) | delete older than 30 days | `grocery.delivery.location-sample-retention` (30d) |

Every cleanup runs on a schedule in small batches, never blocks business transactions, and logs what it deleted.

## 7. Debezium connector health

- **Watchdog** — a `connect-watchdog` container (always on, next to kafka-connect) checks every 30 s that every
  connector **and every task** is RUNNING; a FAILED task is restarted
  (`POST /connectors/<name>/restart?includeTasks=true&onlyFailed=true`), at most 3 times per task per 10 minutes
  (then it stays FAILED and visible). Every action is logged.
- **Gateway (admin only)**: `GET /api/admin/system/connectors` (any admin) → Kafka Connect's
  `/connectors?expand=status` as is (`{ "<name>": { "status": { "connector": {"state"}, "tasks": [ {"id","state","trace"} ] } } }`);
  `POST /api/admin/system/connectors/{name}/restart` (SUPER_ADMIN, OPS_ADMIN) → restart of the failed tasks.
- The admin System status page shows each connector and task state with a restart button.

## 8. Frontend

- **Operations** page `/admin/operations` (nav "Operations"): Dead letters (all services, filters, detail drawer
  with payload / exception, Replay, Discard with reason, Replay all), Connectors (states, restart), Stuck orders.
- Admin dashboard: "Stuck orders" card and a "Pipeline" card (connectors healthy? dead letters pending).
- Admin order drawer: "Events" tab with the order event timeline.
- Notifications: `STUCK_ORDER`.

## 9. Acceptance (phase plan)

- A poison message goes to the DLT without blocking the partition (the next event of the same key is processed).
- Replay is idempotent (replaying twice applies once).
- A missed webhook is recovered (reconciliation).
- Stuck orders show up on the admin dashboard (and resolve themselves).
- A failed connector task is visible and restarted.
