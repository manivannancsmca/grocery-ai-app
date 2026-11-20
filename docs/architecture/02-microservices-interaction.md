# 2. Microservices Interaction

## Synchronous calls (REST, only where an immediate answer is needed)

```mermaid
flowchart LR
  GW["API Gateway"]
  GW --> ALL["every service<br/>routes /api/..."]

  ORD[order] -->|"read cart"| CART[cart]
  ORD -->|"price quote - grocery"| PRI[pricing]
  ORD -->|"menu quote + open status"| RES[restaurant]
  ORD -->|"delivery address"| CUS[customer]
  CART -->|"price quote"| PRI
  CART -->|"menu quote"| RES
  PAY[payment] -->|"Orders API, Refunds API"| RZP["Razorpay"]
  AI[ai-orchestrator] -->|"tools via gateway<br/>with user JWT"| GW
  AI -->|"chat + tool calling"| LLM["Ollama"]
  ALLV["every service"] -.->|"JWKS public keys, cached"| ID[identity]
```

## Asynchronous collaboration (Kafka events)

```mermaid
flowchart LR
  ID[identity] -->|"identity.events"| CUS[customer] & VEN[vendor] & NOT[notification]
  VEN -->|"vendor.events"| ID & CC[catalog-command] & CQ[catalog-query] & ORD[order] & NOT
  RES[restaurant] -->|"restaurant.events"| ID & CQ & ORD & NOT
  CC -->|"catalog.events"| CQ
  PRI[pricing] -->|"pricing.events"| CQ & NOT
  INV[inventory] -->|"inventory.events"| ORD & CQ & NOT
  ORD -->|"order.events"| INV & PAY[payment] & VEN & RES & DEL[delivery] & CART[cart] & REV[review] & NOT
  PAY -->|"payment.events"| ORD & NOT
  DEL -->|"delivery.events"| ID & ORD & RES & NOT
  DEL -->|"delivery.location"| NOT
  REV -->|"review.events"| CQ & NOT
  AI[ai-orchestrator] -->|"ai.events"| NOT
```

## Interaction matrix

| Service | Calls synchronously | Publishes | Consumes |
|---|---|---|---|
| api-gateway | all services (routing) | — | — |
| identity | — | `identity.events` | vendor, restaurant, delivery approval and suspension events |
| customer | — | `customer.events` | `UserRegistered` |
| vendor | — | `vendor.events` | `UserRegistered`, `OrderConfirmed`, `OrderCancelled` |
| restaurant | — | `restaurant.events` | `OrderConfirmed`, `OrderCancelled`, `DeliveryPartnerAssigned` |
| catalog-command | — | `catalog.events` | `VendorSuspended` |
| catalog-query | — | — | catalog, pricing, inventory, vendor, restaurant, review events |
| inventory | — | `inventory.events` | `OrderCreated`, `OrderCancelled`, `OrderOutForDelivery` |
| pricing | — | `pricing.events` | — |
| cart | pricing, restaurant | — | `OrderCreated` |
| order | cart, pricing, restaurant, customer (at creation only) | `order.events` | inventory, payment, vendor, restaurant, delivery events |
| payment | Razorpay | `payment.events` | `OrderAwaitingPayment`, `OrderCancelled` |
| delivery | — | `delivery.events`, `delivery.location` | `OrderAccepted`, `OrderReadyForPickup`, `OrderCancelled` |
| notification | — | — | almost all topics |
| review | — | `review.events` | `OrderDelivered` |
| ai-orchestrator | business services through the gateway, Ollama | `ai.events` | — |

## In plain language

- Services talk to each other in two ways: **direct REST calls** and **events through Kafka**.
- REST calls are used only when someone is waiting for the answer. Example: when you press
  "Place order", the order service must check your cart and the current prices right away.
- Everything after the order is created happens through **events**. A service announces a fact
  ("stock reserved", "payment captured") and the interested services react on their own.
- Events make the system resilient. If the delivery service is down for a minute, its events wait in Kafka
  and are processed when it comes back.
- Every service validates JWTs itself, using public keys from the identity service. They are fetched once and cached.

## Key rules

- No service calls another service's database. Data is shared only through APIs or events.
- Every synchronous call has a timeout: connect 1 s, read 2–3 s. It runs behind a Resilience4j circuit breaker,
  and only idempotent reads are retried.
- A synchronous call must never be needed to finish a step that is already in progress through events.
- Events describe facts that already happened (past tense) and carry the stakeholder IDs needed by consumers.
- `delivery.location` is best-effort telemetry. It is not written through the outbox.
