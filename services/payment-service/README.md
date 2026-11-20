# payment-service

Payments for orders (Phase 6): one payment per order, its Razorpay order, checkout verification, Razorpay webhooks,
the 15-minute payment window and refunds, (Phase 10) reconciliation with the provider and (Phase 12) the refunds of
approved returns. Orders arrive from `order.events` (`OrderAwaitingPayment`, `OrderCancelled`, `ReturnApproved`);
every payment fact leaves on `payment.events` through the outbox, and order-service moves the order on from there. Razorpay runs with auto-capture (ADR-0010); without Razorpay test keys a built-in **simulator** stands in
locally.

| | |
|---|---|
| Port | 8111 |
| Database | `payment_db` (user `payment_user`) — tables `payment`, `payment_attempt`, `refund` (Phase 12: + `return_id`, `requested_amount`), `webhook_event`, `idempotency_key`, `outbox_event`, `processed_event`, (Phase 10) `reconciliation_stats`, `reconciliation_recovery` |
| Publishes | `payment.events` (aggregate `Payment`, key orderId): `PaymentInitiated`, `PaymentCaptured`, `PaymentFailed`, `PaymentExpired`, `RefundCompleted`, `RefundFailed` (Phase 12: both with `returnId`, null except for reason `RETURN`) |
| Consumes | `order.events` → `OrderAwaitingPayment`, `OrderCancelled`, (Phase 12) `ReturnApproved` (group `payment-service.order-events`); everything else is ignored |
| Calls | Razorpay REST API (`grocery.payment.razorpay.base-url`), or the in-memory simulator |
| API | `docs/api/phase-6-orders-and-payments.md` §3 (payments), §4 (admin payments and refunds), §6 (events); ADR-0010; `docs/api/phase-10-event-driven-hardening.md` §3 (reconciliation); `docs/api/phase-12-post-mvp-features.md` §3 (return refunds) |
| Debezium | `deploy/debezium-connector.json` (connector `payment-outbox`, server id 5111) |

## Endpoints
| Method & path | Who | |
|---|---|---|
| `GET /api/payments/orders/{orderId}/checkout` | `CUSTOMER` (own order) | `CheckoutSession` · 409 `PAYMENT_NOT_READY` / `PAYMENT_ALREADY_CAPTURED` / `PAYMENT_EXPIRED` / `PAYMENT_CANCELLED` · 404 `PAYMENT_NOT_FOUND` · 503 `PAYMENT_PROVIDER_UNAVAILABLE` |
| `POST /api/payments/razorpay/verify` | `CUSTOMER` | `{ razorpayOrderId, razorpayPaymentId, razorpaySignature }` → `PaymentView` · 400 `INVALID_SIGNATURE` · 404 `PAYMENT_NOT_FOUND` · 409 `PAYMENT_FAILED` · 503 |
| `GET /api/payments/orders/{orderId}` | `CUSTOMER` (own), any admin role | `PaymentView` (with `attempts` and `refunds`) · 404 `PAYMENT_NOT_FOUND` |
| `POST /api/payments/razorpay/webhook` | **public** (HMAC signature instead of a token) | Razorpay webhook → 200 · 400 `INVALID_SIGNATURE` |
| `POST /api/payments/simulator/orders/{orderId}/pay` | `CUSTOMER` (own), **simulator mode only** (404 otherwise) | `{ outcome: SUCCESS \| FAIL }` → `{ razorpayOrderId, razorpayPaymentId, razorpaySignature }` (signature `null` for FAIL) |
| `GET /api/admin/payments?status=&refundStatus=&orderId=&from=&to=&page=&size=` | any admin role | `PageResponse<PaymentView>` without `attempts`, newest first; `from`/`to` = dates in India time, inclusive |
| `GET /api/admin/payments/{id}` | any admin role | `PaymentView` |
| `GET /api/admin/payments/reconciliation` | any admin role | Phase 10: `{ lastRunAt, checked, recovered, failures, lastRecovered: [ { orderId, orderNumber, outcome, at } ] }` (last 20, newest first) |
| `POST /api/admin/refunds` | `SUPER_ADMIN`, `FINANCE_ADMIN` · header `Idempotency-Key` | `{ orderId, amount?, note }` → **201** `RefundView` (reason `ADMIN`) · 409 `PAYMENT_NOT_CAPTURED` · 422 `REFUND_EXCEEDS_CAPTURED` (`refundable`) · 404 `PAYMENT_NOT_FOUND` |

Only `/api/payments/razorpay/webhook` is public (`grocery.security.public-paths`); every other path validates the JWT
here (zero trust). A parameter of the wrong type (an id that is not a UUID, `status=PAID`) → 400 `INVALID_PARAMETER`;
an invalid body → 400 `VALIDATION_FAILED`. `PAYMENT_FAILED` carries `razorpayPaymentId`, `providerErrorCode` and
`errorDescription` (Razorpay's message, for the customer).

## Payment lifecycle (contract §3.1)
```
OrderAwaitingPayment ─► CREATED ──Razorpay order──► PENDING (PaymentInitiated) ──capture──► CAPTURED (PaymentCaptured)
                           │                            │                                      │
                           ├── window over (poller) ────┴─► EXPIRED (PaymentExpired) ──capture─┤ late capture:
                           └── OrderCancelled ──────────┴─► CANCELLED ─────────────────capture─┘ PaymentCaptured late=true
                                                                                                 + full LATE_CAPTURE refund
```
- **OrderAwaitingPayment** → payment row (`CREATED`, amount, `expiresAt`, `contactPhone`; unique `order_id`),
  committed, then its Razorpay order (`amount` in paise, `receipt` = order number, `notes.orderId`/`paymentId`) →
  `PENDING` + `PaymentInitiated`. A redelivered or replayed event never creates a second payment or Razorpay order.
  An event that arrives after its window created the payment only (no Razorpay order); the poller expires it.
- **Verify**: signature `hex(HMAC_SHA256(razorpayOrderId + "|" + razorpayPaymentId, keySecret))`, constant-time
  compare (wrong → 400, nothing changes); the Razorpay order must be one of the caller's payments (else 404); then
  payment-service **asks Razorpay** (`GET /payments/{id}`): `captured` → captured; `authorized` → captured at Razorpay
  (`POST /payments/{id}/capture`) first; `failed` → declined attempt + `PaymentFailed`, answered 409 `PAYMENT_FAILED`.
  Verifying a payment already captured by that Razorpay payment answers it unchanged, without calling Razorpay.
- **Captured** (verify, webhook or poller — whichever is first; the Razorpay payment id is unique): `CAPTURED` +
  `PaymentCaptured`. On an `EXPIRED` / `CANCELLED` payment it is a **late capture**: `PaymentCaptured` with
  `late: true` and a full `LATE_CAPTURE` refund, sent to Razorpay right after commit. Verify answers the `PaymentView`
  (not an error) in that case.
- **Declined** (`payment.failed`, verify of a failed payment, simulator FAIL): attempt `FAILED` + `PaymentFailed`
  (`retryable` = still open and inside the window), once per Razorpay payment id. The payment stays `PENDING`.
- **OrderCancelled**: `CREATED`/`PENDING` → `CANCELLED` (no event); `CAPTURED` → full `ORDER_CANCELLED` refund of
  whatever is still refundable (nothing when a late-capture refund already covers it); otherwise nothing.

**Refunds** (`service/RefundService.java`): opened `PENDING` in the transaction that decides them (pending refunds count
against `refundable` = captured − processed − pending), committed, then sent to Razorpay
(`POST /payments/{id}/refund`, amount in paise, our refund id as `receipt`, `notes.refundId` and
`X-Refund-Idempotency` header):
`processed` → `PROCESSED` + `RefundCompleted` (`totalRefunded`, `fullyRefunded`); 4xx or `failed` → `FAILED` +
`RefundFailed` (`errorCode` = Razorpay's code, or `REFUND_FAILED`; the amount is refundable again); `pending` → stays
`PENDING` with Razorpay's refund id until the `refund.processed` / `refund.failed` webhook (matched by refund id, or by
`notes.refundId` when our call timed out); Razorpay unreachable → stays `PENDING` and the refund poller retries it with
backoff. A refund's provider call never fails the change that opened it (event, verify, admin request).
`PaymentView.refundStatus`: `PENDING` while a refund runs, else `NONE` / `PARTIAL` / `FULL`.

**Admin refunds**: the `Idempotency-Key` (8–100 of `A-Z a-z 0-9 . _ : -`, scoped per admin user) is checked first
(400 `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_INVALID`), then the body. The key is claimed
(`IdempotencyKeys.claim`, pre-generated refund id) in the same transaction that locks the payment, checks it and inserts
the refund, so an error leaves no trace of the key. A retry with the same key and body → 201 with the original refund
as it is now and `Idempotent-Replayed: true`; another body → 422 `IDEMPOTENCY_KEY_REUSED`. `amount` absent = all that
is still refundable; amounts are normalised to 2 decimals before hashing (`10` and `10.00` are the same request).

## Webhook (contract §3.2)
The raw body bytes are checked first: `X-Razorpay-Signature` = `hex(HMAC_SHA256(rawBody, webhookSecret))`, constant
time (wrong or missing → 400, nothing stored; no webhook secret configured → every webhook is 400). Then one
transaction stores the event in `webhook_event` (`INSERT IGNORE` on the unique `X-Razorpay-Event-Id`; a repeat → 200,
nothing done) and applies it with the payment locked: `payment.captured`, `payment.failed`, `payment.authorized`
(attempt recorded; captured at Razorpay after commit), `refund.processed`, `refund.failed`. Anything else, an unknown
Razorpay order or refund, or a signed body that is not JSON → 200, stored with `processed = false` and an `outcome`.
Provider calls an event triggers (capture, late-capture refund) run after commit; their failures are left to the
pollers. A webhook without `X-Razorpay-Event-Id` is de-duplicated by the SHA-256 of its body.

## Pollers
| Poller | Interval (fixed delay) | What |
|---|---|---|
| `PaymentExpiryPoller` | `grocery.payment.expiry-poll-interval` = 30s, env **`GROCERY_PAYMENT_EXPIRY_POLL_INTERVAL`** | `CREATED`/`PENDING` payments past `expires_at`, batches of `expiry-batch-size` (50) |
| `RefundRetryPoller` | `grocery.payment.refund-retry-interval` = 60s, env **`GROCERY_PAYMENT_REFUND_RETRY_INTERVAL`** | `PENDING` refunds due: no Razorpay id yet → create again (same idempotency key); with an id → re-check (`GET /refunds/{id}`, a lost webhook). Backoff: interval × 2ⁿ, at most 30 min |

Both are safe with several replicas and never call Razorpay while holding a lock: a batch is claimed with
`SELECT … FOR UPDATE SKIP LOCKED`, given a lease (`payment.poll_lease_until` / `refund.lease_until` =
now + `grocery.payment.poll-lease`, 2 min) and committed; then each row is handled on its own. Another poller skips
locked rows during the claim and leased rows afterwards. The expiry poller first lists the Razorpay order's payments
(`GET /orders/{id}/payments`): a captured one (lost webhook, closed browser) → captured exactly like verify; an
authorized one → captured first (never expired while money is authorized); nothing → `EXPIRED` + `PaymentExpired`
(state re-checked under the lock). Razorpay unreachable → the payment keeps its lease and is tried again after it.
`grocery.payment.pollers-enabled=false` switches them off — reconciliation too (tests run them by hand); time comes
from the injectable `Clock` bean.

## Reconciliation (Phase 10, `docs/api/phase-10-event-driven-hardening.md` §3)
`PaymentReconciler` (schedule, counters) + `PaymentReconciliationService` (claim, check, finish), every
`grocery.payment.reconcile-interval` (env **`PAYMENT_RECONCILE_INTERVAL`**, 2m, fixed delay): a payment whose verify
call **and** webhook were both lost is still captured, and the order confirmed, within one interval.
- **Due**: `PENDING` payments whose Razorpay order is older than `grocery.payment.reconcile-after` (env
  **`PAYMENT_RECONCILE_AFTER`**, 2m; column `payment.provider_order_at`, set when the Razorpay order is created) and
  whose window is still open (`expires_at` in the future — after it, the expiry poller keeps its own last check).
- **Claim**: `SELECT … FOR UPDATE SKIP LOCKED` (index `idx_payment_status_provider_order`), lease
  `payment.reconcile_lease_until` = now + `poll-lease`, commit; batches of `reconcile-batch-size` (50).
- **Check**: `GET /orders/{id}/payments` (`PaymentGateway.listOrderPayments`; the simulator answers from its memory),
  then the same code as verify and the webhook (`CaptureService.settle`): a captured payment → `CAPTURED` +
  `PaymentCaptured` (order-service confirms the order); else an authorized one → captured at the provider first; then
  every declined one not yet recorded → a failed attempt + `PaymentFailed` (`retryable` only while the payment is
  still open — after a capture nobody is notified).
- **Finish**: the lease is released and `payment.reconciled_at` = the run's start, so a run checks a payment once and
  the next run checks it again (until it is captured, expired or cancelled). The provider unreachable (timeout, 5xx,
  open breaker) → logged, counted in `failures`, asked again in the next run.
- **Durable summary**: every new capture / decline is a row of `reconciliation_recovery` with what the provider had —
  outcome `CAPTURED` (an unreported capture), `AUTHORIZED` (captured by us after finding it authorized) or `FAILED`
  (an unreported declined attempt); a capture that became a late capture because the order was cancelled meanwhile
  (refunded at once) keeps the outcome of what was found. Each run adds its numbers to the single
  `reconciliation_stats` row (counters since the job first ran, every replica; `recovered` = recovery rows; `checked`
  includes the failed checks; `lastRunAt` = when a run last finished, `null` until the first run).
  `GET /api/admin/payments/reconciliation` reads both, so the summary survives restarts and is the same on every
  replica.
- Flyway `V4__reconciliation.sql`: `payment.provider_order_at` (existing Razorpay orders: their payment's
  `created_at`), `reconcile_lease_until`, `reconciled_at`, the index, `reconciliation_stats`,
  `reconciliation_recovery`.

## Locking (`service/PaymentLocks.java`)
Every change locks the **payment row first** (`SELECT … FOR UPDATE` by primary key, located beforehand with an id-only
query), then, if needed, a refund row — always in that order. Connections run in **READ COMMITTED**
(`spring.datasource.hikari.transaction-isolation`), so everything read after the lock is the latest committed state.
A capture racing an `OrderCancelled` is therefore either "capture, then ORDER_CANCELLED refund" or "cancel, then late
capture and LATE_CAPTURE refund" — the money is always refunded. Lock waits that time out become 409
`CONCURRENT_MODIFICATION` (grocery-common) or a Kafka retry.

### One Razorpay order per payment
The consumer (right after inserting the payment) and the checkout endpoint (on demand) can both ask for the Razorpay
order. Both go through `ProviderOrderService.ensure`: lock the payment row, re-check that it still has no Razorpay
order, call `POST /orders` **while holding the lock**, store the id (`PENDING`, `PaymentInitiated`), commit. A second
caller waits on the lock and then finds the id. This is the one provider call made under a row lock, bounded by the
HTTP timeouts (2 s connect, 10 s read) and the circuit breaker; it only ever blocks callers interested in the same
payment. The alternative — committing a "creating" marker before the call — needs a marker timeout, after which a
crashed or slow call leads to a second Razorpay order anyway, plus a 409 for callers that hit the marker; the lock gives
the guarantee with less machinery. Limitation: when Razorpay creates the order but the response is lost (read timeout),
nothing is stored and the retry creates another order; the orphan is harmless (its id never reaches a customer, so it
cannot be paid).

### Phase 12: refunds of approved returns (`docs/api/phase-12-post-mvp-features.md` §3)
`ReturnApproved` (order.events, same consumer) → `OrderEventsService.returnApproved`, in one transaction: the
`processed_event` mark (a redelivery stops here), the order's payment located and **locked**, then:
- a refund of this `returnId` exists already (any status; unique `refund.return_id`, Flyway `V6`) → nothing: one
  refund per return, whatever the event redeliveries or repeats (two events of one return at the same moment: the
  loser's unique-key violation is answered as "refunded already");
- otherwise `RefundLedger.openForReturn`: amount = `refundAmount` **capped at what is still refundable** (captured −
  processed − pending refunds), the request kept in `requested_amount` and the note `Return RT-… (capped at the
  refundable X of Y)`; reason `RETURN`, PENDING, sent to Razorpay after commit (notes carry `returnId`) →
  `RefundCompleted` / `RefundFailed` with `returnId`; a temporary provider failure is retried by `RefundRetryPoller`
  like every refund;
- nothing refundable any more (refunded in full, not captured) → recorded at once as `FAILED` with failure code
  `NOTHING_REFUNDABLE` + `RefundFailed` (with `returnId`), no provider call;
- no payment for the order → logged (error), nothing stored; `refundAmount` 0 → logged, nothing stored.

order-service marks the return REFUNDED on `RefundCompleted` with its `returnId`. `RefundView` gains `returnId`.

### Failures of the `order.events` consumer
Unreadable events (missing headers, ids, a non-positive amount or more than 2 decimals) → `InvalidEventException`
(DLT). Razorpay unreachable while creating the order → `TransientEventProcessingException`, database outages as they
are: grocery-common's error handler retries the record with its outage backoff (`TransientFailures`), never
dead-letters it. Because the payment row and the processed mark are committed before the Razorpay call, the retry only
creates the Razorpay order (and the checkout endpoint can do so meanwhile). Razorpay **refusing** the order (4xx, e.g.
wrong keys) is logged and not retried by the consumer (it would never succeed): the payment stays `CREATED`, checkout
answers 503 until the keys are fixed, and the poller ends it with its window.

## Configuration
| Property (env) | Default | |
|---|---|---|
| `grocery.payment.provider` (`PAYMENT_PROVIDER` or `GROCERY_PAYMENT_PROVIDER`) | empty | `razorpay` \| `simulator`; empty = `razorpay` when a key id is set, otherwise `simulator` |
| `grocery.payment.razorpay.key-id` (`RAZORPAY_KEY_ID`) | empty | `rzp_test_…`; the only key that leaves the service (checkout session) |
| `grocery.payment.razorpay.key-secret` (`RAZORPAY_KEY_SECRET`) | empty | basic auth for the API, checkout signatures |
| `grocery.payment.razorpay.webhook-secret` (`RAZORPAY_WEBHOOK_SECRET`) | empty | the secret set on the Razorpay webhook |
| `grocery.payment.razorpay.base-url` (`RAZORPAY_BASE_URL`) | `https://api.razorpay.com/v1` | tests point it at a local fake |
| `grocery.payment.merchant-name` | `Grocery AI` | shown in Checkout |
| `grocery.payment.http.connect-timeout` / `read-timeout` | 2s / 10s | every Razorpay call; one circuit breaker (`razorpay`) around them, 4xx never open it |
| `grocery.payment.expiry-poll-interval` (`GROCERY_PAYMENT_EXPIRY_POLL_INTERVAL`) | 30s | see Pollers; `expiry-batch-size` 50 |
| `grocery.payment.refund-retry-interval` (`GROCERY_PAYMENT_REFUND_RETRY_INTERVAL`) | 60s | see Pollers; `refund-batch-size` 20 |
| `grocery.payment.poll-lease` | 2m | poller / in-flight refund lease |
| `grocery.payment.pollers-enabled` | true | the pollers and reconciliation |
| `grocery.payment.reconcile-interval` (`PAYMENT_RECONCILE_INTERVAL`) | 2m | Phase 10 reconciliation; `reconcile-batch-size` 50 |
| `grocery.payment.reconcile-after` (`PAYMENT_RECONCILE_AFTER`) | 2m | only Razorpay orders at least this old are checked |
| `grocery.payment.simulator.allow-any-profile` | false | tests only |

**Razorpay test keys.** Razorpay Dashboard → *Test Mode* → *Account & Settings* → *API Keys* → generate a key pair;
*Webhooks* → add a webhook (events `payment.captured`, `payment.authorized`, `payment.failed`, `refund.processed`,
`refund.failed`) with a secret of your choice. Put `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and
`RAZORPAY_WEBHOOK_SECRET` in `infrastructure/local/.env` (never commit them, never use live keys). Razorpay cannot
reach `localhost` with webhooks; that is fine locally: verify asks Razorpay directly and the expiry poller catches
anything the browser did not report (or expose the gateway through a tunnel to receive webhooks). Razorpay mode refuses
to start without a key id and key secret; a missing webhook secret is logged and every webhook is then rejected.

**Simulator mode** (no keys; contract §3.3). `SimulatorGateway` implements the same `PaymentGateway` interface in
memory (auto-capture: a successful payment is `captured`; refunds are `processed` at once, like Razorpay test mode), so
verify, webhooks, expiry, late capture and refunds run the same code. Key id `rzp_test_simulator`, key secret
`simulator_key_secret`, webhook secret `simulator_webhook_secret` (post webhooks by hand, signed with it). The checkout
session says `"provider": "SIMULATOR"`; the React app shows its own test dialog and calls
`POST /api/payments/simulator/orders/{orderId}/pay`, which behaves like a Checkout tab left open: it only needs the
caller's payment and its simulator order — the payment may already be `EXPIRED` or `CANCELLED` (that is how the smoke
test produces a real late capture) — and answers the signed ids for verify (`SUCCESS`) or records the decline like a
`payment.failed` webhook (`FAIL`). Simulator mode **refuses to start unless the `docker` or `local` profile is active**,
so a deployed instance without keys can never accept fake payments. It is **single-instance development only**: its
state lives in the JVM and is lost on restart (an order created before a restart can still be paid; refunds of payments
made before a restart fail).

**Not covered here**: a payment captured twice on one Razorpay order (Razorpay prevents it; if it happened, the second
capture is recorded as an attempt and logged as an error for a manual refund); reconciliation after the payment window
(the expiry poller's last check covers its end).

## Events: Avro (ADR-0011)
- `payment.events` payloads are **Avro on the wire**, validated against the central schemas of
  `libs/grocery-event-schemas` (`avro/payment.events/<EventType>.avsc`, subject
  `payment.events-com.grocery.events.payment.<EventType>` in the Schema Registry). grocery-common 0.6.0's outbox
  writes the same payload twice in the business transaction: the JSON `payload` column (kept for audit, debugging and
  the rollback path) and `payload_avro` (Confluent wire format, Flyway `V3__outbox_avro_payload.sql`), which the
  connector publishes byte for byte (`payload_avro`, `BinaryDataConverter`). A payload that does not match its schema
  fails the business transaction instead of publishing a bad event. The payload records are in
  `event/PaymentEvents.java` (Phase 12, schemas 1.5.0: `RefundCompleted` and `RefundFailed` + the optional `returnId`,
  written as `null` for other refunds).
- Consumed `order.events` are decoded from Avro (or legacy JSON) back to the same JSON before the listener sees them
  (Phase 12: the listener also reads `ReturnApproved`).
- Registry: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (property `grocery.events.avro.schema-registry-url`,
  default `http://localhost:8081`; compose sets `http://schema-registry:8081`). The service never registers schemas.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only, as before; re-point the connector to `payload` first).

## Package layout
```
config/       PaymentConfig (Clock µs, circuit breaker, scheduling), PaymentGatewayConfig (provider choice + startup
              checks), PaymentProperties, ProviderCredentials, ProviderMode
controller/   PaymentController (/api/payments…), RazorpayWebhookController, SimulatorController,
              AdminPaymentController (/api/admin/payments, …/reconciliation, /api/admin/refunds),
              InvalidParameterAdvice
dto/          request/response records + validation, PaymentMapper
entity/       Payment (state rules), PaymentAttempt, Refund, enums
event/        PaymentEvents (published payloads), OrderEventsListener (Kafka consumer)
gateway/      PaymentGateway, RazorpayGateway (RestClient), SimulatorGateway, RazorpayJson, Signatures, Paise, exceptions
repository/   Spring Data JPA (FOR UPDATE / SKIP LOCKED queries), WebhookEventStore (JDBC, INSERT IGNORE),
              ReconciliationLedger (JDBC: reconciliation_stats, reconciliation_recovery)
service/      PaymentLocks (the locking rule), PaymentRecorder (capture / decline state machine), RefundLedger,
              ProviderOrderService, CaptureService, CheckoutService, VerifyService, WebhookService, OrderEventsService,
              PaymentExpiryService + PaymentExpiryPoller, RefundService + RefundRetryPoller, AdminRefundService,
              PaymentReconciliationService + PaymentReconciler, SimulatorService, PaymentQueryService,
              PaymentEventPublisher, PaymentException
```

## AI-approved calls (Phase 11)

Endpoint an assistant can trigger: `POST /api/admin/refunds` (REFUND_PAYMENT, `Idempotency-Key` = the approval id). Calls that an AI assistant proposed and a human approved arrive with an `X-Approval-Token` (grocery-common 0.8.0,
`grocery.approvals.enabled: true`, keys from `APPROVAL_JWKS_URI`). The filter checks the signature, the exact
method, path and body hash, and that the approver is the caller, then records the approval id in
`consumed_approval` (Flyway V5), so a second use is 409 `APPROVAL_ALREADY_USED`. Requests without the header are
unchanged. Contract: [Phase 11 §5](../../docs/api/phase-11-agentic-ai-and-approvals.md#5-approval-token--verified-by-the-target-grocery-common-080).

## Run / test
```bash
cd infrastructure/local && docker compose up -d --build payment-service
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4; Razorpay is faked by a local HTTP server, never called
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD":/workspace -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/payment-service/pom.xml verify
```
Integration tests run in Razorpay mode against `FakeRazorpay` (a `com.sun.net.httpserver.HttpServer` with Razorpay's
JSON shapes, failure injection and a slow mode), except `SimulatorModeIntegrationTest` and `ReconciliationSimulatorIntegrationTest` (their own context). Kafka
listeners and pollers are off; tests call the listener with `ConsumerRecord`s carrying Debezium's headers and run the
pollers themselves (`TestClock` moves time). Both contexts encode Avro against Confluent's in-memory registry
(`grocery.events.avro.schema-registry-url=mock://payment-service`, auto-register on).

| Test class | Covers |
|---|---|
| `OrderEventsIntegrationTest` | one payment + one Razorpay order (fields, basic auth), redelivery / replay, Razorpay down → transient retry + checkout on demand, Razorpay refusing, consumer vs checkout races (exactly one Razorpay order), late event, invalid events, OrderCancelled before / after capture / without payment, refund left PENDING when Razorpay is down |
| `CheckoutIntegrationTest` | session fields, every checkout error code, roles / 401 / invalid id, PaymentView for customer and admins, simulator endpoint 404 in Razorpay mode |
| `VerifyIntegrationTest` | captured, authorized → capture call, bad signatures, failed → 409 + PaymentFailed, idempotent re-verify, other customer / unknown / foreign payment 404, 503, late capture after cancel and after expiry |
| `WebhookIntegrationTest` | signature over raw bytes, duplicate event ids, payment.captured / failed / authorized, retryable flag, late capture via webhook, refund.processed / failed (by id and by notes.refundId), unknown events / orders / refunds / non-JSON stored, missing event id |
| `ExpiryPollerIntegrationTest` | expires + PaymentExpired, lost capture captured instead, authorized captured, Razorpay down (lease, retry), no Razorpay order, injected clock, two pollers in parallel (each payment once) |
| `AdminRefundIntegrationTest` | partial then full, idempotency replay / reuse / per-admin scope / missing / invalid key, roles and validation, not captured / unknown / exceeds, provider refusal → FAILED + RefundFailed, outage → PENDING + poller backoff, re-check of pending refunds, admin monitor filters |
| `OutboxPayloadIntegrationTest` | exact fields of all six events (Phase 12: `returnId` null outside returns), topic / aggregate / key / version |
| `ReturnRefundIntegrationTest` | Phase 12 §3: ReturnApproved → one RETURN refund (provider notes, payment view, redelivery and a second event of the same return change nothing, a second return refunds again), capped at the refundable and recorded, nothing refundable → FAILED `NOTHING_REFUNDABLE` + RefundFailed, provider refusal → RefundFailed with `returnId`, outage → the refund poller, broken events → DLT, no payment → skipped, unpaid → FAILED |
| `AvroOutboxRoundTripIntegrationTest` | all six event types (incl. a late capture) from the real operations and (Phase 12) RETURN refunds with `returnId`: `payload_avro` is Confluent wire format under the right subject and decodes (grocery-common's `AvroEventDeserializer`) to the JSON `payload` |
| `SimulatorModeIntegrationTest` | simulator session, pay SUCCESS → verify → captured, FAIL → PaymentFailed, late capture after cancel / expiry via the simulator, admin refund, pay errors, hand-posted webhooks |
| `ReconciliationIntegrationTest` | Phase 10 §3: a capture whose verify and webhook were lost (and the decline before it), an authorization captured at Razorpay (outcome AUTHORIZED), a decline nobody reported (FAILED, retryable, recorded once, then a later capture), Razorpay down (failure counted, lease released, recovered in the next run), not yet due, captured / expired / cancelled / order-less payments not checked, one check per run, two reconcilers at once, the summary (counters = the runs' numbers, `lastRunAt` null before the first run, last 20 newest first, shape, plain array, roles) |
| `ReconciliationSimulatorIntegrationTest` | the smoke test's scenario in simulator mode: pay SUCCESS without verify → reconciliation captures it (`PaymentCaptured`, summary lists the order number); a simulated decline is not recovered twice |
| `config/ProviderModeTest` | provider choice; simulator refused without `docker`/`local` profile; Razorpay without secret refused |
| `gateway/GatewayUnitTest` | signatures (RFC 4231 vector), paise, Razorpay JSON, simulator behaviour (incl. listing an order's payments for reconciliation) |
