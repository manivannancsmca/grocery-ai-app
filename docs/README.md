# Grocery AI Platform — Documentation

This folder documents the architecture of the Grocery AI Platform. The approved proposal is
[`../DEVELOPMENT_PLAN.md`](../DEVELOPMENT_PLAN.md). The documents here explain how that plan works,
using diagrams and short plain-language explanations.

## Architecture diagrams (blueprint §18)

| # | Document | What it shows |
|---|---|---|
| 1 | [High-level architecture](architecture/01-high-level-architecture.md) | All building blocks and how a request travels |
| 2 | [Microservices interaction](architecture/02-microservices-interaction.md) | Which services call each other (REST) and which talk through events |
| 3 | [Stakeholder interaction](architecture/03-stakeholder-interaction.md) | How the 5 stakeholders use the platform and affect each other |
| 4 | [Database ownership](architecture/04-database-ownership.md) | Which service owns which data store |
| 5 | [Kafka event flow](architecture/05-kafka-event-flow.md) | Outbox → Debezium → Kafka → consumers |
| 6 | [Order saga workflow](architecture/06-order-saga-workflow.md) | Order lifecycle by choreography, failures and compensations |
| 7 | [Real-time notifications](architecture/07-real-time-notification.md) | SSE, Redis pub/sub, multiple replicas, reconnect replay |
| 8 | [Agentic AI architecture](architecture/08-agentic-ai-architecture.md) | Agents, ToolGuard, tools, Ollama via Spring AI |
| 9 | [Human approval workflow](architecture/09-human-approval-workflow.md) | Approval lifecycle and signed approval tokens |
| 10 | [Deployment architecture](architecture/10-deployment-architecture.md) | Local Docker Compose; the AWS target (ECS Fargate, managed data services), CI/CD, blue/green and SPA releases |

## Architecture Decision Records

| ADR | Decision |
|---|---|
| [0001](adr/0001-database-per-service.md) | Database per service, single MySQL container locally |
| [0002](adr/0002-transactional-outbox-with-debezium.md) | Transactional outbox relayed by Debezium |
| [0003](adr/0003-catalog-read-model-outbox-snapshot.md) | Catalog read model via outbox snapshot → Elasticsearch |
| [0004](adr/0004-choreography-for-order-saga.md) | Choreography for the order saga |
| [0005](adr/0005-sse-for-real-time.md) | Server-Sent Events for real-time updates |
| [0006](adr/0006-custom-identity-service.md) | Custom identity-service with JWT and refresh-token rotation |
| [0007](adr/0007-grocery-common-library.md) | `grocery-common` as a separately versioned library |
| [0008](adr/0008-ai-agents-with-tool-guard-and-approval.md) | AI agents with ToolGuard and human approval |
| [0009](adr/0009-local-development-in-docker.md) | Local development fully in Docker |
| [0010](adr/0010-razorpay-auto-capture.md) | Razorpay with auto-capture for the MVP |
| [0011](adr/0011-avro-and-schema-registry.md) | Avro event payloads with a central Confluent Schema Registry (BACKWARD_TRANSITIVE) |
| [0012](adr/0012-aws-ecs-fargate-deployment.md) | AWS deployment: ECS Fargate, managed data services, native blue/green, Jenkins and Terraform |
| [0013](adr/0013-observability-opentelemetry.md) | Observability with Micrometer and OpenTelemetry through a collector (Grafana stack locally, AWS managed in AWS) |
| [0014](adr/0014-kubernetes-path.md) | When and how to move from ECS to Kubernetes (EKS) |

## Events

- [Event catalog](events/event-catalog.md): conventions, every topic, every event, producers and consumers.

## API contracts (one per phase)

- [Phase 2 — Identity & stakeholder management](api/phase-2-identity-and-stakeholders.md): auth, JWT claims,
  refresh rotation, customer profile & addresses, vendor / restaurant / delivery-partner onboarding and approvals,
  health routes, Phase 2 events.
- [Phase 3 — Product catalog, restaurant menus & search](api/phase-3-catalog-and-search.md): public search and
  catalog reads, vendor product management, admin categories and republish, restaurant menus, snapshot events.
- [Phase 4 — Inventory & pricing](api/phase-4-inventory-and-pricing.md): stock and the movement ledger,
  reservations, prices with admin approval above a threshold, the cached price quote, price and stock in search.
- [Phase 5 — Cart & checkout preview](api/phase-5-cart-and-checkout-preview.md): the Redis cart, live pricing and
  availability, issue codes, delivery fee and checkout preview, the `/internal/**` service-to-service endpoints.
- [Phase 6 — Orders & payments](api/phase-6-orders-and-payments.md): idempotent order placement with a price
  snapshot, the choreographed order flow and its state guards, stock reservation, Razorpay checkout / verify /
  webhooks, the payment window, refunds (automatic and admin), the local payment simulator, admin monitor and stats.
- [Phase 7 — Real-time updates](api/phase-7-real-time-updates.md): the Server-Sent Events stream (notifications,
  signals, session events, replay with Last-Event-ID), the inbox API, who is notified of which event, privacy rules and
  multi-replica fan-out through Redis.
- [Phase 8 — Vendor & restaurant fulfillment](api/phase-8-vendor-and-restaurant-fulfillment.md): store order boards
  (accept with a ready time, reject, preparing, ready), the acceptance deadline and auto-reject, the new order and store
  events, customer and admin cancellation rules, sales reports.
- [Phase 9 — Delivery](api/phase-9-delivery.md): offers to the nearest online partners (waves, radii, first accept
  wins), partner app API, live location, customer tracking, pickup / deliver / fail, admin monitor and reassignment.
- [Phase 10 — Event-driven hardening](api/phase-10-event-driven-hardening.md): dead letters (inspect, replay in
  process, discard), the retry policy, payment reconciliation, the stuck-order watchdog, the order event timeline,
  housekeeping of old rows, and Debezium connector health (watchdog and admin API).
- [Phase 11 — Agentic AI & human approval](api/phase-11-agentic-ai-and-approvals.md): the Shopping and Admin BI
  assistants (chat over SSE, tools, ToolGuard, the scripted model), approval requests and their lifecycle, the signed
  approval token the target services verify, insights with structured output, the AI audit, and the new events.
- [Phase 12 — Post-MVP features](api/phase-12-post-mvp-features.md): reviews and ratings, coupons, returns and partial
  refunds, vendor purchasing, email notifications, the vendor, restaurant and delivery assistants, and reporting.
- [Phase 13 — Observability & extended testing](api/phase-13-observability-and-testing.md): metrics, traces and logs
  (Micrometer, OpenTelemetry, Prometheus, Tempo, Loki, Grafana), business metrics, dashboards and alerts, and the test
  layers (API, security, AI and approvals, Kafka, contract, database, E2E, performance, ZAP and Trivy).
- [Phase 14 — CI/CD & AWS](api/phase-14-cicd-and-aws.md): the AWS target architecture, Terraform, images and scanning
  gates, blue/green with automatic rollback, expand/contract migrations, the Jenkins pipelines and the `aws` profile.
- [Platform settings](api/platform-settings.md): the business rules admins change in the app (partner search,
  acceptance times, return and review windows, delivery fees), which service owns each, the API, the bounds, how soon
  a change applies and what existing records keep.

## Operations

- [Troubleshooting a business transaction](operations/troubleshooting.md): from an order number to its trace, logs,
  Kafka lag, dead letters and connector state, and what to do for each alert (Phase 13).
- [Deploy and rollback](operations/deploy-and-rollback.md): pipeline stages, pre-production checklist, what to watch
  during the bake, automatic and manual rollback of services and the web app (Phase 14).
- [Disaster recovery](operations/disaster-recovery.md): RPO/RTO per failure and the restore procedures for the
  databases, Kafka, Redis, search, the web app, secrets, a Region and Terraform state (Phase 14).

## Pinned platform versions

| Component | Version |
|---|---|
| Java | 25 (LTS) |
| Spring Boot | 4.1.2 |
| Spring Cloud | 2025.1.4 |
| Spring AI | 2.0.2 |
| MySQL | 8.4.13 |
| Apache Kafka | 4.3.2 (KRaft) |
| Debezium (Kafka Connect) | 3.7.1.Final |
| Elasticsearch | 9.4.6 (server) · elasticsearch-java 9.4.8 (client, managed by Spring Boot) |
| Redis | 8.8.4 |
| Ollama | 0.40.15 |
| Node.js (frontend tooling) | 24 |

## Reading Mermaid diagrams

The diagrams use [Mermaid](https://mermaid.js.org/). They render on GitHub and GitLab, and in VS Code with a
Mermaid preview extension.
