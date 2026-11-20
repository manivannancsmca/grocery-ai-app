# ADR-0001: Database per service, single MySQL container locally

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

The platform has 16 independently deployable services. Shared tables would couple services: a schema change in one
service could break another, and no service could be deployed or scaled on its own. MySQL must remain the source of
truth for transactional data. Running 13 MySQL servers on a developer laptop would waste memory.

## Decision

- Each service that needs relational data owns **its own MySQL database**: identity, customer, vendor, restaurant,
  catalog, inventory, pricing, order, payment, delivery, notification, review, ai.
- cart-service uses Redis only, and catalog-query-service uses Elasticsearch only.
- **Locally**, all 13 databases live in **one MySQL 8.4.13 container**. Each service has **its own database user**
  granted only on its own schema. A separate `debezium` user has replication rights and `SELECT` on `outbox_event` tables only.
- **In production**, start with one RDS instance that keeps separate schemas and users. Move busy or critical
  databases (order, payment, inventory) to dedicated instances when load or blast radius justifies it.
- Flyway per service. Foreign keys only inside a service. UUIDv7 `BINARY(16)` keys. `DECIMAL(12,2)` money in INR.
  UTC timestamps.

## Consequences

- ✅ Services evolve and deploy independently. Cross-service access is technically impossible, not just forbidden by convention.
- ✅ Low local memory use, about 768 MB for all databases.
- ⚠️ No cross-service joins or transactions. Data is combined through APIs, events and read models,
  which means eventual consistency.
- ⚠️ Reporting across services needs per-service stats endpoints now, and a reporting read model later.

## Alternatives considered

- **Shared database with shared tables:** simplest at first, but it breaks independent deployment. Rejected.
- **One MySQL container per service locally:** closest to production isolation, but ~13× the memory. Rejected for local dev.
- **Schema per service with one shared user:** isolation would depend only on discipline. Rejected.
