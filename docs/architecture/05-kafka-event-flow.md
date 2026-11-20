# 5. Kafka Event Flow

## From a business change to every interested service

```mermaid
flowchart LR
  subgraph SVC["Producer service, e.g. order-service"]
    API["REST API call"] --> TX{{"One MySQL transaction"}}
    TX --> BT[("business tables")]
    TX --> OB[("outbox_event")]
  end

  OB -->|"binlog row insert"| DBZ["Debezium MySQL connector<br/>one per service"]
  DBZ -->|"Outbox EventRouter SMT<br/>key = aggregate_id<br/>headers = version, correlation"| T[["order.events<br/>partitioned by orderId"]]

  T --> G1["inventory-service.saga"]
  T --> G2["payment-service.saga"]
  T --> G3["notification-service.dispatch"]
  T --> G4["delivery-service.assignment"]

  G1 --> P1{{"Consumer transaction:<br/>insert processed_event<br/>+ business change<br/>+ own outbox event"}}
  P1 -->|"duplicate eventId"| SKIP["skip safely"]
  P1 -->|"transient error"| RETRY["blocking retry<br/>exponential backoff"]
  RETRY -->|"still failing or not retryable"| DLT[["order.events.DLT"]]
```

## Topic map

```mermaid
flowchart TB
  subgraph Topics
    T1[["identity.events"]]
    T2[["customer.events"]]
    T3[["vendor.events"]]
    T4[["restaurant.events"]]
    T5[["catalog.events"]]
    T6[["pricing.events"]]
    T7[["inventory.events"]]
    T8[["order.events"]]
    T9[["payment.events"]]
    T10[["delivery.events"]]
    T11[["delivery.location<br/>best-effort, 1h retention"]]
    T12[["review.events"]]
    T13[["ai.events"]]
  end
  T5 & T6 & T7 & T3 & T4 & T12 --> CQ["catalog-query indexer → Elasticsearch"]
  T7 & T9 & T3 & T4 & T10 --> ORD["order-service status owner"]
  T1 & T3 & T4 & T6 & T7 & T8 & T9 & T10 & T11 & T13 --> NOT["notification-service"]
```

The full list of events, producers and consumers is in the [event catalog](../events/event-catalog.md).

## In plain language

- A service never sends a business event to Kafka directly. It writes the event into its own
  **`outbox_event` table**, in the **same transaction** as the business change. Either both are saved or neither is.
- **Debezium** watches the MySQL binlog. When an outbox row appears, it publishes the event to the right
  Kafka topic. No events are lost, and no distributed transaction is needed.
- Each event is keyed by its **aggregate ID**, such as the order ID. All events about the same order land in the same
  partition and are read **in order**.
- Each consuming service reads with its own **consumer group**. Every interested service gets every event,
  but inside a service each event is processed by only one replica.
- Kafka can deliver an event twice. Consumers remember processed event IDs in `processed_event`, so a duplicate
  is simply skipped.
- If processing fails for a temporary reason, the consumer retries with growing delays. If it still fails, or the
  message is malformed, it moves to a **dead-letter topic** (`<topic>.DLT`) for inspection and replay.
  This way one bad message does not block the others.

## Key rules

- **Topics:** one per domain, named `<domain>.events`, plus `delivery.location` for telemetry.
- **Message key:** the aggregate ID. Partitions: 3 locally, 6–12 in production.
- **Production Kafka settings:** replication factor 3, `min.insync.replicas=2`, idempotent producers.
- **Headers:** `eventId`, `eventType`, `eventVersion`, `correlationId`, `causationId`, `occurredAt`.
- **Payloads are JSON.** Readers ignore unknown fields (tolerant reader). Additive changes keep the version number.
  A breaking change publishes a new version side by side until all consumers migrate.
- **Retries:** blocking retries on order-critical consumers, so ordering is preserved. Non-blocking retry topics are
  allowed only for notifications.
- **Cross-topic ordering is not guaranteed.** Consumers protect themselves with state-machine guards and compensate
  late events (e.g. a payment captured after cancellation is refunded).
- **Outbox cleanup:** rows older than 7 days are deleted by a scheduled job.
