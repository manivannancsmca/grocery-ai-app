# customer-service

Customer profile and delivery address book. A changed name or phone is published as `CustomerProfileUpdated`, so the
customer's account (identity) and emails (notification) follow. Preferences arrive in a later phase.

| | |
|---|---|
| Port | 8102 |
| Database | `customer_db` (user `customer_user`) — tables `customer`, `customer_address`, `outbox_event`, `processed_event` |
| Publishes | `customer.events`: CustomerProfileUpdated (Debezium connector `customer-outbox`, `deploy/debezium-connector.json`, server id 5102) |
| Consumes | `identity.events` → `UserRegistered` with role CUSTOMER creates the profile (group `customer-service.identity-events`) |
| API | `docs/api/phase-2-identity-and-stakeholders.md` §4 |

## Identity and profile creation
**customerId = userId**: the `customer.id` is the JWT `sub`, so other services never need a lookup.
The profile is created by the `UserRegistered` event (idempotent via `processed_event`). Until then
`GET /api/customers/me` returns `404 CUSTOMER_PROFILE_NOT_FOUND`, but `PUT /api/customers/me` and adding an
address create the profile on demand from the JWT (`email`, `name` claims), so the UI never depends on event
timing. A registration event that arrives later is a no-op.

## CustomerProfileUpdated (`service/CustomerProfileService.java`)
`PUT /api/customers/me` publishes `CustomerProfileUpdated` `{customerId, fullName, phone, updatedAt}` (aggregate
`Customer`, key customerId = userId) through the outbox, **in the same transaction** as the profile change, and only
when the name or the phone actually changed (saving the same details again publishes nothing; clearing the phone is a
change, `phone: null`). A profile created on demand starts from the JWT's `name`, so saving that same name publishes
nothing either: identity already has it. Consumers: identity-service puts the name and phone on the account (header,
JWT `name` claim, admin user list); notification-service uses the name in email greetings.

## Address book rules (`service/AddressBookService.java`)
- At most 10 addresses per customer → `422 ADDRESS_LIMIT_REACHED`.
- Exactly one default while any address exists: the first address is always default; `isDefault: true` (on
  create/update) or `POST …/{id}/default` moves the default and un-defaults the others in the same transaction;
  `isDefault: false` never leaves the book without a default; deleting the default promotes the most recently
  created remaining address.
- Lists are ordered default first, then newest.
- Another customer's address is always `404 ADDRESS_NOT_FOUND` (never 403 — its existence is not revealed).
- Every change locks the customer row (`SELECT … FOR UPDATE`), so concurrent requests cannot break these rules.

**For order-service (Phase 6):** at checkout it reads the chosen address synchronously with
`GET /api/customers/me/addresses/{id}` (forwarding the customer's own JWT) → `Address`, or `404` if it is not
the caller's.

## Events: Avro (ADR-0011)
- Event payloads travel as **Avro** (Confluent wire format) against the central schemas in `libs/grocery-event-schemas`.
  grocery-common 0.6.0 decodes every consumed value (Avro, or legacy JSON) back to the same JSON the producer wrote,
  so `IdentityEventsListener` and its DTOs are unchanged.
- Schema Registry: `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`, default
  `http://localhost:8081`); tests use the in-memory `mock://customer-service`.
- Rollback switch: `grocery.events.avro.enabled=false` (plain string consumer, as before).
- Published events are written to the outbox as JSON and Avro (`payload_avro`, Flyway V3) against the schema in
  `grocery-event-schemas` 1.7.0 (`avro/customer.events/CustomerProfileUpdated.avsc`); the connector publishes
  `payload_avro`. The Dockerfile installs the schema module first, like every producer's.

## Package layout
```
config/       Clock bean
controller/   CustomerController (/api/customers/me), AddressController (/api/customers/me/addresses…),
              AdminCustomerController (/api/admin/customers)
dto/          request/response records + validation rules
entity/       Customer, CustomerAddress
event/        IdentityEventsListener (Kafka consumer), CustomerEvents (published payloads)
mapper/       CustomerMapper (plain Java)
repository/   Spring Data JPA
service/      CustomerProfileService (profile, registration event), AddressBookService, CustomerAdminService
```

## Run / test
```bash
cd infrastructure/local && docker compose --profile core up -d --build customer-service
# tests (from repo root, Git Bash) — Testcontainers MySQL
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -f services/customer-service/pom.xml verify
```
