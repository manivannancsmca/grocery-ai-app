# API, security, AI/approval and Kafka tests (Phase 13)

A standalone Maven module (Java 25, JUnit 5, REST Assured, AssertJ, no Spring context) that tests a **running** stack
through the API gateway — the contract in [docs/api/phase-13-observability-and-testing.md §4](../../docs/api/phase-13-observability-and-testing.md#4-testing-new-layers-unit-and-integration-tests-stay-in-each-module).

| Tag | What | Classes |
|---|---|---|
| `smoke` | fast post-deploy subset (health of every service, conventions, register/login, edge authorization, internal endpoints not routed, a genuine JWT works) — no store/order fixtures, ~15 s | marked methods in the classes below |
| `api` | contract assertions per service: status codes, ProblemDetail (`errorCode`, `correlationId`, `currentStatus`), `PageResponse` and size caps, validation errors (`errors[]`), idempotency, conflicts | `api/*` |
| `security` | authorization matrix (10 identities × 110 endpoints), IDOR (another user's order, payment, address, review, return, approval, notification, conversation, product, store order, delivery → 404), JWT tampering, spoofed identity headers, `/internal/**` and key endpoints not routed, forged `X-Approval-Token`, CORS, rate limiting (429) | `security/*`, `ai/ToolGuardTest` |
| `ai` | every assistant (shopping, admin-bi, vendor, restaurant, delivery) with the scripted model: allowed tools succeed, ToolGuard blocks (`TOOL_NOT_ALLOWED`, `ROLE_NOT_ALLOWED`, `INVALID_ARGUMENTS`, refused agent), approvals (approve exactly once under concurrency, reject, cancel, duplicate reuse, expiry, four-eyes, decider rules, not visible → 404, tampered body → `APPROVAL_BODY_MISMATCH`) | `ai/*` |
| `kafka` | an order's `OrderCreated` / `OrderAwaitingPayment` on `order.events`: key, outbox headers (`eventId`, `eventType`, `eventVersion`, `aggregateType`, `correlationId` = the caller's `X-Correlation-Id`, `occurredAt`), Confluent Avro (magic byte, registered schema id, decodes to the order), the same event ids in the admin order timeline; `traceparent` when deployed | `kafka/OrderEventFlowTest` |
| `defect` | reproducers of known platform defects — **excluded by default**; they fail until the defect is fixed | see "Known defects" |

## Prerequisites

The full stack, with the deterministic assistant model, short customer-approval expiry and the payment simulator
(no Razorpay keys in `.env`):

```bash
cd infrastructure/local
AI_PROVIDER=scripted AI_APPROVAL_TTL_CUSTOMER=30s AI_APPROVAL_EXPIRY_INTERVAL=5s \
  docker compose --profile all up -d --build
```

- Without `AI_PROVIDER=scripted` the `ai` tests are skipped (with the reason); with a longer customer TTL only the
  expiry test is skipped.
- With real Razorpay keys the tests that pay are skipped (paying needs Razorpay Checkout in a browser).
- The tampered-approval test needs database access (`MYSQL_URL`); without it, it is skipped.

## Configuration (system property or environment variable)

| Name | Default | Notes |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` | the API gateway |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | from a container on the compose network: `kafka:29092` (the host listener advertises `localhost:9092`, which does not work from inside a container) |
| `SCHEMA_REGISTRY_URL` | `http://localhost:8081` | from the compose network: `http://schema-registry:8081` |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | `admin@grocery.local` / `Admin@12345` | the bootstrap SUPER_ADMIN (also read as `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`) |
| `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` | —, `root`, `root` | optional, e.g. `jdbc:mysql://mysql:3306/ai_db` (compose network) or `jdbc:mysql://localhost:3307/ai_db` (host) |

## How to run (Docker only), from the repository root

Everything, from a Maven container on the compose network (so the Kafka check reaches the broker):

```bash
MSYS_NO_PATHCONV=1 docker run --rm --network grocery_default \
  -v "$PWD:/workspace" -v grocery-m2:/root/.m2 -w /workspace maven:3.9.16-eclipse-temurin-25-noble \
  mvn -B -f tests/api-tests/pom.xml verify \
    -DGATEWAY_URL=http://api-gateway:8080 -DKAFKA_BOOTSTRAP=kafka:29092 \
    -DSCHEMA_REGISTRY_URL=http://schema-registry:8081 -DMYSQL_URL=jdbc:mysql://mysql:3306/ai_db
```

(`MSYS_NO_PATHCONV=1` only matters in Git Bash on Windows. `grocery_default` is the compose project's network —
`docker network ls`. Outside that network use `-DGATEWAY_URL=http://host.docker.internal:8080` and leave Kafka at its
default: the `kafka` tests are then skipped with the reason.)

Subsets (JUnit 5 tag expressions):

```bash
… mvn -B -f tests/api-tests/pom.xml verify -DGATEWAY_URL=… -Dgroups=smoke            # post-deploy smoke
… mvn -B -f tests/api-tests/pom.xml verify -DGATEWAY_URL=… -Dgroups='security | ai'
… mvn -B -f tests/api-tests/pom.xml verify -DGATEWAY_URL=… -Dgroups=defect -DexcludedGroups=   # known-defect reproducers
```

With a local JDK 25 + Maven the same `mvn -f tests/api-tests/pom.xml verify` works against the host ports
(defaults: gateway 8080, Kafka 9092, Schema Registry 8081).

**Phase 14 pipeline:** after a deployment run `mvn -B -f tests/api-tests/pom.xml verify -Dgroups=smoke
-DGATEWAY_URL=<the environment's gateway> -DADMIN_EMAIL=… -DADMIN_PASSWORD=…` (the smoke subset needs only the
gateway and the admin credentials; it creates a few throwaway customers). The full suite needs the scripted model and
the payment simulator, so it belongs to the local/CI stack, not to AWS environments. JUnit XML reports:
`tests/api-tests/target/surefire-reports/`.

## How the tests work

- **Own data per run.** Every user, store, product, coupon and supplier carries a run id (`apit-<role>-<n>-<run>@example.com`,
  `Apit Rice Alpha q…`), so the suite is re-runnable and independent of other test data. Stores, partner and
  customers stand in Hubballi (15.35, 75.14), away from the smoke tests' Bengaluru and Mysuru stores, so no other
  delivery partner is offered our deliveries.
- **Fixtures through the public APIs** (`support/World`): admins of every role, two approved grocery stores with
  priced and stocked products, an approved restaurant open all day, an approved delivery partner, customers with an
  address, and one order taken all the way to DELIVERED (store accepts, partner gets the offer, picks up, delivers).
  They are created lazily and shared by all classes.
- **Clean-up.** At the end of the run an admin cancels every order the run placed that is still open (as the smoke
  tests do) — `support/RunCleanupExtension`.
- **Rate limits.** The gateway limits 20 requests/s per user and 2/s per client IP for login/registration; the
  client waits out a 429 and retries (except in `RateLimitTest`, which wants to see it and runs last). The per-IP test
  uses a private forwarded client address so it never throttles other clients behind the same NAT address. The
  assistant allows 30 messages per user and hour, so every assistant test uses users of its own run.
- **Order.** Classes run in `@Order` (cheap checks first, rate limiting last); flows inside a class use method order.

## Known defects (reproducers tagged `defect`)

None open. Fixed during Phase 13:

| Test (now in the normal suite) | Contract | Was |
|---|---|---|
| `HealthAndConventionsApiTest.gateway_generated_responses_echo_a_correlation_id` | Phase 2 §1: every response echoes an `X-Correlation-Id` | responses the gateway writes itself (edge 401/403, `/api/health/gateway`) had no `X-Correlation-Id` header, and an edge 401/403 for a request without one had no `correlationId` in the ProblemDetail. Fixed by the gateway's `CorrelationIdWebFilter` (before the security chain). |
| `GatewayHardeningTest.internal_and_key_endpoints_are_not_reachable_through_the_gateway` | the gateway exposes no actuator publicly | after Phase 13's rollout the gateway's own `/actuator/prometheus` answered 200 on its public port. Fixed: the gateway's actuator listens only on the internal management port 9080. |

Other observations (not asserted):

- An unrouted path (e.g. `GET /api/nope` with a token) answers the gateway's default JSON 404
  (`timestamp`, `path`, `error`, `requestId`), not a ProblemDetail.
- `GET /api/catalog/products/not-a-uuid` answers 404 `PRODUCT_NOT_FOUND`; Phase 3 §2 lists "a malformed id" among the
  400 `INVALID_PARAMETER` cases (the search parameters do answer 400).
- The per-IP login/registration bucket is keyed on the `X-Forwarded-For` entry of the outermost trusted proxy
  (`GATEWAY_TRUSTED_PROXIES`: 1 locally, 2 in AWS behind CloudFront + ALB). A client that reaches the gateway directly
  can still choose its own bucket by sending that header, so only expose the gateway behind those proxies.
- `AI_TOOL_LIMIT` is not reachable with the scripted model (≤ 2 tool calls per message); it is covered by
  ai-orchestrator-service's `ToolGuardIntegrationTest`.
