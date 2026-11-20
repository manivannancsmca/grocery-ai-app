# inventory-service

Stock for grocery products (Phase 4): on hand and reserved quantities, the stock movement ledger, reservations for
orders and low-stock thresholds. Products are not created here: a local copy arrives from `catalog.events`. Every
stock change is published on `inventory.events` through the outbox; catalog-query turns `ProductAvailabilityChanged`
into the `inStock` flag of search results. From Phase 6 grocery orders from `order.events` are reserved, held until
pickup, released and expired here (the inventory side of the order saga), and from Phase 9 committed at pickup
(`OrderOutForDelivery` → `StockCommitted`). From Phase 12 stock also comes back in from events — goods received
against a vendor's purchase order (`vendor.events` → `GoodsReceived`) and approved grocery returns with restock
(`order.events` → `ReturnApproved`) — and the vendor assistant's approved stock changes arrive with an approval token.

| | |
|---|---|
| Port | 8107 |
| Database | `inventory_db` (user `inventory_user`) — tables `product_ref`, `stock_item`, `stock_movement`, `stock_reservation`, `stock_reservation_item`, `outbox_event`, `processed_event`, `consumed_approval` (Phase 12, Flyway V5) |
| Publishes | `inventory.events` (aggregate `Stock`, key productId): `StockAdjusted`, `ProductAvailabilityChanged`, `LowStockDetected`; (aggregate `Reservation`, key orderId): `StockReserved`, `StockReservationFailed`, `StockReservationExpired`, `StockReleased`, `StockCommitted` (Phase 9) |
| Consumes | `catalog.events` → `ProductUpserted` only (group `inventory-service.catalog-events`); `order.events` → `OrderCreated` (GROCERY), `OrderConfirmed`, `OrderCancelled`, `OrderOutForDelivery`, `ReturnApproved` (Phase 12, GROCERY with restock) (group `inventory-service.order-events`); `vendor.events` → `GoodsReceived` only (Phase 12, group `inventory-service.vendor-events`) |
| API | `docs/api/phase-4-inventory-and-pricing.md` §1, §2 (events §8); internal availability: `docs/api/phase-5-cart-and-checkout-preview.md` §4; order saga: `docs/api/phase-6-orders-and-payments.md` §1, §6 "Inventory"; commit at pickup: `docs/api/phase-9-delivery.md` §6, §7; restocking and approval tokens: `docs/api/phase-12-post-mvp-features.md` §3, §4, §6 |
| Debezium | `deploy/debezium-connector.json` (connector `inventory-outbox`, server id 5107; routes by the `topic` column, so the reservation events need no change) |

```
available = onHand − reserved      inStock = available > 0      lowStock = threshold > 0 and available ≤ threshold
```

## Endpoints
| Method & path | Who | |
|---|---|---|
| `GET /api/vendor/inventory?q=&filter=&page=&size=` | `VENDOR` with a `vendor_id` claim, else 403 `VENDOR_NOT_ACTIVE` | `PageResponse<StockView>`, sorted by product name |
| `GET /api/vendor/inventory/summary` | 〃 | `{ products, inStock, lowStock, outOfStock }` |
| `GET /api/vendor/inventory/{productId}` | 〃 | `StockView` |
| `POST /api/vendor/inventory/{productId}/adjustments` | 〃 | `AdjustmentRequest` → `StockView` |
| `POST /api/vendor/inventory/adjustments/bulk` | 〃 | `{ items: [ … ] }` (1–100, each product once) → `StockView[]` in request order, all or nothing |
| `PUT /api/vendor/inventory/{productId}/threshold` | 〃 | `{ lowStockThreshold }` (0–100000) → `StockView` |
| `GET /api/vendor/inventory/{productId}/movements?page=&size=` | 〃 | `PageResponse<StockMovementView>`, newest first |
| `POST /api/admin/inventory/republish` | `SUPER_ADMIN`, `OPS_ADMIN` | `{ products }` |
| `POST /internal/inventory/availability` | any authenticated caller (no role) — internal | `{ productIds: [ … ] }` (1–100) → `{ items: [ { productId, vendorId, available, inStock } ], missing: [ … ] }` |

**Internal availability (Phase 5, `docs/api/phase-5-cart-and-checkout-preview.md` §1, §4).** cart-service re-checks
live stock every time a cart is shown, calling with the customer's forwarded token. `/internal/**` is never routed
by the gateway, but the JWT is still validated here (no token → 401 `UNAUTHENTICATED`); any role may ask.
- 0 or more than 100 ids, a missing list or a `null` id → 400 `VALIDATION_FAILED`. An id listed twice is answered
  once. `items` and `missing` keep the request order.
- `missing` = unknown or **archived** products. DRAFT / UNPUBLISHED products are answered (visibility is the
  catalog's business). `available = onHand − reserved`, `inStock = available > 0` (same rule as below).
- One query for the whole batch (`StockItemRepository.findAvailable`) in a read-only transaction and **no locks**:
  a plain InnoDB consistent read never waits for a stock change in flight and sees the last committed stock.
  Nothing is reserved (stock is held only when an order is created, Phase 6).

- `q` = product name contains (case-insensitive) or SKU equals. `filter` = `IN_STOCK` (available > 0),
  `OUT_OF_STOCK` (available = 0) or `LOW_STOCK` (the `lowStock` flag above — includes out-of-stock products whose
  threshold is > 0); anything else → 400 `INVALID_PARAMETER`. The summary's `lowStock` counts products that are low
  but not out.
- Another vendor's or an unknown product → 404 `PRODUCT_NOT_FOUND` (a product created a moment ago may take a
  second or two to arrive from the catalog).
- Archived products are kept: every change → 409 `PRODUCT_ARCHIVED`; lists, summary and republish leave them out;
  `GET /{productId}` and its movements still work (read-only, `productStatus: "ARCHIVED"`).

## Rules
| Adjustment | Quantity | Effect | Errors |
|---|---|---|---|
| `INCREASE` | 1–100000 | on hand + q | 422 `STOCK_LIMIT_EXCEEDED` above 1,000,000 |
| `DECREASE` | 1–100000 | on hand − q | 409 `INSUFFICIENT_STOCK` when q > available (`available`, `reserved` properties) |
| `SET` | 0–1000000 | on hand = q (a stock count) | 409 `STOCK_BELOW_RESERVED` when q < reserved (`available`, `reserved` properties) |

Reasons: `RESTOCK | RETURN | DAMAGED | EXPIRED | STOCK_COUNT | CORRECTION | OTHER` (required), `note` ≤ 300.
Invalid input → 400 `VALIDATION_FAILED` with `errors: [{ field, message }]` (bulk: `items[3].quantity`; a product
listed twice: `items[i].productId`). In a bulk request the first failing item fails everything and the error's
`productId` property names it. Rules live in `entity/StockItem.java`; the database also enforces
`0 ≤ reserved ≤ on_hand ≤ 1,000,000` with CHECK constraints.

**Reservations** (`service/ReservationService.java`, no REST endpoints — the order saga drives them, see below):
- `reserve(orderId, items, ttl)`: all or nothing; idempotent per order id (the same order id returns the existing
  reservation — even a released one — and never holds stock twice). Insufficient, unknown or archived products →
  `ReservationFailedException` (`INSUFFICIENT_STOCK`, `unavailableItems: [{productId, requested, available}]`),
  nothing changed. `expires_at` = now + ttl. Publishes `StockReserved` when (and only when) it creates the reservation.
- `holdUntilPickup(orderId)`: a RESERVED reservation no longer expires (`expires_at` = NULL, Flyway V3). No stock
  change, no event; released / committed / unknown → no-op.
- `release(orderId)`: reserved − q for every line (RESERVED → RELEASED) + `StockReleased(ORDER_CANCELLED)`.
  Idempotent; unknown order → no-op; an already released one publishes nothing. **Releasing a COMMITTED
  reservation is a no-op** (returned unchanged): the goods have left the store, putting them back is a vendor
  `INCREASE` with reason `RETURN`.
- `releaseExpired(now, limit)`: one batch of the expiry poller (below).
- `commit(orderId)`: on hand − q and reserved − q (RESERVED → COMMITTED; Phase 9, at pickup) + `StockCommitted` in
  the same transaction. Idempotent (an already committed reservation publishes nothing); unknown order → 404
  `RESERVATION_NOT_FOUND`; a RELEASED reservation → 409 `RESERVATION_RELEASED`. The saga's variant
  `commit(orderId, guard)` treats an unknown or RELEASED reservation as "nothing to commit" (logged and ignored).
- Ledger: `RESERVE` / `RELEASE` (delta 0) and `COMMIT` (negative delta), `reference` = order id, no actor.

## Order saga (Phase 6, `event/OrderEventsListener.java`, `service/OrderStockService.java`)
Consumer group `inventory-service.order-events` (listener id `inventory-order-events`) on `order.events`:

| Event | Effect | Publishes (aggregate `Reservation`, key orderId) |
|---|---|---|
| `OrderCreated`, `orderType` GROCERY | reserve every line (`items[].itemId` = productId, `quantity`; repeats added) for `grocery.inventory.reservation-ttl` (20 min) | `StockReserved { orderId, reservationId, items: [{productId, quantity}], expiresAt }` |
| 〃 when any product lacks stock (unknown / archived = 0 available) | nothing held | `StockReservationFailed { orderId, unavailableItems: [{productId, requested, available}] }` |
| `OrderConfirmed` | the reservation no longer expires (held until pickup, committed in Phase 9) | nothing |
| `OrderCancelled` | release | `StockReleased { orderId, reservationId, reason: "ORDER_CANCELLED" }` — only if stock was given back |
| `OrderOutForDelivery` (Phase 9), GROCERY | commit: the held stock leaves the store (on hand − q, reserved − q, `COMMIT` movements) | `StockCommitted { orderId, reservationId, items: [{productId, quantity}], committedAt }` — only if this event committed it. Unknown reservation → nothing (no processed mark, like a release); RELEASED or already COMMITTED → marked processed, nothing changed, logged. A failed delivery later does **not** return the stock (the vendor adjusts it) |
| `OrderCreated` RESTAURANT, `OrderAwaitingPayment`, `OrderRejected`, … | ignored (no processed mark) | — |

- **Exactly-once effects.** Each event is marked in `processed_event` (consumer `inventory-service.order-events`)
  as the first statement of the transaction that applies it, together with the stock change, the movements and the
  outbox events (reservation + Phase 4 stock events): all commit or roll back together, so a redelivered event
  changes and publishes nothing. On top of that `reserve` keeps its own idempotency: an `OrderCreated` with another
  event id for an order already reserved (or released) publishes no second `StockReserved`; a cancel that finds the
  stock already given back (cancelled twice, expired first, never reserved, rejected) publishes no `StockReleased`.
- **Failed reservation:** the reserving transaction (with its processed mark) rolls back; a second transaction writes
  the processed mark and `StockReservationFailed` (no stock change, nothing held). A redelivery finds the mark.
- **Errors:** unreadable payloads, missing `orderId` / `orderType`, no items or an item without `itemId` / a
  positive `quantity` → `InvalidEventException` (DLT at once). Database outages, lock-wait timeouts and deadlock
  victims propagate as Spring's transient data-access exceptions → grocery-common's error handler retries the same
  record with the outage backoff (`TransientFailures`), never the DLT. Nothing is swallowed.

**Expiry poller** (`service/ReservationExpiryPoller.java`): every `grocery.inventory.expiry.interval` (30 s, fixed
delay) it releases RESERVED reservations with `expires_at` < now in batches of `grocery.inventory.expiry.batch-size`
(20), each batch its own READ COMMITTED transaction, until a batch comes back short (at most 50 batches per run) →
`StockReleased { reason: "RESERVATION_EXPIRED" }` + `StockReservationExpired { orderId, reservationId }` per
reservation (order-service cancels the order with `RESERVATION_EXPIRED`). Held reservations (`expires_at` NULL) are
never in range. The 20-minute TTL outlives the 15-minute payment window, so normally the payment expiry cancels first.
Safe with several replicas: the batch is claimed with `SELECT … FOR UPDATE SKIP LOCKED` (served by
`idx_stock_reservation_status_expires`; NULLs are outside the `< now` range), so replicas take disjoint batches and
never wait for each other there. The clock is injectable (tests run pollers "in the future").
Settings: `RESERVATION_TTL` (20m), `RESERVATION_EXPIRY_ENABLED` (true; off = no poller in this instance),
`RESERVATION_EXPIRY_INTERVAL` (30s), `grocery.inventory.expiry.batch-size` (20, 1–500).

## Restocking from events (Phase 12, `service/RestockService.java`)
Stock that comes back in is an adjustment `INCREASE` per product made by the system (no actor), with the same ledger
entry and stock events as a vendor adjustment: `StockAdjusted` (`reason`, `actorUserId: null`), plus
`ProductAvailabilityChanged` when the product comes back in stock.

| Event (consumer group) | When | Movement |
|---|---|---|
| `vendor.events` → `GoodsReceived` (`inventory-service.vendor-events`, listener `inventory-vendor-events`, `event/VendorEventsListener`; every other vendor.events type is ignored) | always | `INCREASE` per line, reason `RESTOCK`, `note` = the purchase order number (`PO-7K3M9QX2`), `reference` = the purchase order id |
| `order.events` → `ReturnApproved` (`inventory-service.order-events`, the existing order listener) | `restock: true` and `orderType` GROCERY; otherwise ignored (no processed mark) | `INCREASE` per item (`itemId` = productId), reason `RETURN`, `note` = `RT-… (order GA-…)`, `reference` = the order id |

- **Once per event:** one transaction marks the event in `processed_event` (first statement), locks every product's
  stock row in ascending id order (the locking rule below), changes, records and publishes; a redelivery changes and
  publishes nothing. Lines of the same product are added up into one movement.
- **Lines that cannot be applied:** another vendor's product, or an archived one (read-only), is skipped with a warning
  and the rest applies. A product inventory does not know yet: `GoodsReceived` fails as a whole (nothing applied, not
  marked) and is retried — the product may still be on its way from `catalog.events`; after the retries it goes to
  `vendor.events.DLT` and can be replayed — while a return skips it. On hand above 1,000,000 → `InvalidEventException`
  (DLT at once, nothing applied). A payload without ids, lines, a positive quantity or (`ReturnApproved`) `restock` /
  `orderType` is `InvalidEventException` too.

## AI-approved calls (Phase 12 §6)
The vendor assistant's `ADJUST_STOCK` calls `POST /api/vendor/inventory/{productId}/adjustments` with an
`X-Approval-Token` (grocery-common 0.8.0, `grocery.approvals.enabled: true`, keys from `APPROVAL_JWKS_URI`, audience
`inventory-service`). The filter checks the signature, the exact method, path and body hash, and that the approver is
the caller, then records the approval id in `consumed_approval` (Flyway V5) before the controller runs, so a second
use is 409 `APPROVAL_ALREADY_USED` (a forged token 403 `APPROVAL_TOKEN_INVALID`, another body 403
`APPROVAL_BODY_MISMATCH`). Requests without the header are unchanged. Contract:
[Phase 11 §5](../../docs/api/phase-11-agentic-ai-and-approvals.md#5-approval-token--verified-by-the-target-grocery-common-080).

## Locking and versioning (no lost updates, no overselling)
Every stock change — adjustment, bulk adjustment, threshold change, reserve, release, commit and the catalog
projection — runs in one transaction that:
1. **first** locks the `stock_item` row(s) with `SELECT … FOR UPDATE` (several products one by one in ascending
   product id = BINARY(16) byte order), **before reading anything else** — so everything read afterwards includes the
   previous lock holder's committed change, and overlapping multi-product changes queue instead of deadlocking;
2. applies the change on the locked row; `stock_version` + 1 on every quantity change (adjustment, set, reserve,
   release, commit — not on a threshold change);
3. writes the `stock_movement` row and the outbox events (carrying the new `stockVersion`); commit releases the lock.

Simultaneous changes of one product are therefore applied one after the other, and a higher `stockVersion` never
describes older stock. A lock-wait timeout or deadlock victim becomes 409 `CONCURRENT_MODIFICATION` (grocery-common)
or a Kafka retry. Details: `service/StockLocks.java`.

**Reservation rows.** Release and commit lock the reservation (`FOR UPDATE` by primary key) *after* the stock rows;
`OrderConfirmed` changes no stock and locks only the reservation. **The expiry poller is the one documented exception
to the order** (`ReservationService.releaseExpired`): it first claims its batch of reservations (`FOR UPDATE SKIP
LOCKED`), then locks the stock rows of *all* claimed lines at once in ascending product id order, then changes them.
Stock rows are still locked before they are changed and never in two rounds. Claiming first is deliberate: it is
what lets `SKIP LOCKED` spread the work over replicas (claiming after the stock locks would make every replica pick
the same candidates and queue on the same stock rows). The price: a cancel (or commit) of *the same order* in the
same instant as its expiry holds the stock row and wants the reservation while the poller holds the reservation and
wants the stock row. InnoDB detects that deadlock at once and rolls back the lighter transaction: a poller victim
retries its batch in the next run and finds the order released; a Kafka victim is a transient failure, retried, and
finds it released by the expiry (so it publishes nothing) — `ReservationExpiryIntegrationTest` reproduces it.
Pollers never deadlock with each other (disjoint claims, stock rows ascending), and adjustments, reserves and other
releases only lock stock rows and insert new reservations, which the claim's record locks (READ COMMITTED: no gap
locks) never block.

| Trigger | Events |
|---|---|
| every vendor adjustment (single or bulk item), every restock from `GoodsReceived` / `ReturnApproved` (Phase 12) | `StockAdjusted` |
| any stock change that moves available 0 ↔ > 0 (adjustment, reserve, release, expiry, commit) | `ProductAvailabilityChanged` |
| any stock change that moves available from above the threshold to ≤ threshold (threshold > 0) | `LowStockDetected` |
| order saga (aggregate `Reservation`, key orderId): reservation created / failed / released / expired / committed | `StockReserved`, `StockReservationFailed`, `StockReleased`, `StockReservationExpired`, `StockCommitted` (see "Order saga") |
| `POST /api/admin/inventory/republish` | `ProductAvailabilityChanged` for every non-archived product, **current** `stockVersion`, no increment, batches of 500 (each its own transaction) |

`ProductUpserted` is applied only when its `productVersion` is newer than the stored copy, at most once per event id
(`processed_event`), and creates the stock row (0 on hand, threshold 5) the first time a product is seen (any status).
It never changes stock quantities, so it publishes nothing.

## Events: Avro ([ADR-0011](../../docs/adr/0011-avro-and-schema-registry.md))
- `inventory.events` payloads travel as **Avro** (Confluent wire format), validated against the central schemas in
  `libs/grocery-event-schemas` (`avro/inventory.events/*.avsc`; dependency `grocery-event-schemas:1.7.0`; 1.2.0 added `StockCommitted`, 1.5.0 `GoodsReceived` and `ReturnApproved`).
  Subjects: `inventory.events-com.grocery.events.inventory.<EventType>`.
- The outbox (grocery-common 0.6.0) writes both columns in the business transaction: `payload` (JSON, kept for
  auditing and the tests) and `payload_avro` (Flyway `V4`), which Debezium publishes byte for byte
  (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- `catalog.events`, `order.events` and `vendor.events` are read through grocery-common's Avro deserializer: listeners still get the
  same JSON (decimals at their schema scale, e.g. `unitValue` `500.000`, which this service does not read).
- Schema Registry URL: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`);
  tests use the in-memory `mock://inventory-service`.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only; switch the connector back to `payload` first).

## Package layout
```
config/       Clock (µs precision = DATETIME(6)), InventoryProperties (grocery.inventory.*), scheduling of the poller
controller/   VendorInventoryController (/api/vendor/inventory…), AdminInventoryController (/api/admin/inventory),
              InternalInventoryController (/internal/inventory/availability)
dto/          request/response records + validation, StockMapper
entity/       StockItem (stock rules), StockMovement, ProductRef, StockReservation (+ ReservedItem), enums
event/        InventoryEvents (published payloads), CatalogEventsListener, OrderEventsListener, VendorEventsListener (Kafka consumers)
repository/   Spring Data JPA (FOR UPDATE lock queries, the SKIP LOCKED expiry claim, list/summary queries)
service/      StockLocks (the locking rule), StockAdjustmentService, StockQueryService, AvailabilityService, ReservationService,
              OrderStockService (order saga), RestockService (GoodsReceived, ReturnApproved), ReservationExpiryPoller, CatalogProjectionService, RepublishService,
              StockEventPublisher, InventoryException
```

## Run / test
```bash
# (once infrastructure/local/docker-compose.yml has an inventory-service entry)
cd infrastructure/local && docker compose up -d --build inventory-service
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4; includes the concurrency tests
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx384m -v "$PWD":/workspace -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/inventory-service/pom.xml verify
```
Tests need no Kafka broker (`spring.kafka.listener.auto-startup=false`; records with the outbox router's headers are
handed to the listeners directly) and switch the scheduled poller off (`grocery.inventory.expiry.enabled=false`):
they run pollers themselves with a clock ahead of the real one.

Environment: `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (docker profile
`kafka:29092`), `KAFKA_LISTENERS_ENABLED`, `IDENTITY_JWKS_URI`, `JWT_ISSUER`, `RESERVATION_TTL` (20m),
`RESERVATION_EXPIRY_ENABLED` (true), `RESERVATION_EXPIRY_INTERVAL` (30s), `APPROVAL_JWKS_URI` (the orchestrator's approval JWKS, Phase 12).

| Test class | Covers |
|---|---|
| `OrderEventsIntegrationTest` | order.events through the listener: reserve → `StockReserved` (payload, routing), redeliveries / a second OrderCreated publish nothing, not enough stock → `StockReservationFailed` (nothing held, once), restaurant orders and other event types ignored, confirm → no expiry, cancel → `StockReleased(ORDER_CANCELLED)` once (never reserved / already released → nothing), pickup (`OrderOutForDelivery`) → commit + `StockCommitted` once (payload, routing, redelivery, a cancel after pickup gives nothing back), unknown / released / restaurant → nothing committed, availability + low-stock events on reserve/release, broken events |
| `ReservationExpiryIntegrationTest` | expiry poller: not before the TTL, release + `StockReleased(RESERVATION_EXPIRED)` + `StockReservationExpired` (payloads, Phase 4 transitions), no second release (poller, cancel, OrderCreated), confirmed reservations never expire, a locked reservation is skipped not waited for, two pollers in parallel release each reservation once, the cancel ↔ expiry deadlock (transient, retried, released once), settings |
| `service/ReservationExpiryPollerSchedulingTest` | the poller's scheduling without a database: interval and batch size from the settings, default `30s`, switched off by `grocery.inventory.expiry.enabled=false` |
| `CatalogProjectionIntegrationTest` | product copy + stock row, stale versions, duplicate events, ignored/broken events, archived products |
| `StockAdjustmentIntegrationTest` | INCREASE/DECREASE/SET events and ledger, validation, error properties, bulk all-or-nothing, lists/filters/summary/threshold |
| `ReservationIntegrationTest` | reserve/release/commit maths, idempotency, all-or-nothing, movements |
| `StockConcurrencyIntegrationTest` | 24 parallel adjustments (no lost update), 25 parallel reservations of 10 units (exactly 10 win), same order in parallel (held once), mixed reserve/decrease, opposite-order bulk (no deadlock), waiting for a foreign row lock |
| `AvailabilityIntegrationTest` | internal availability: available/inStock with reservations, unknown/archived → missing, duplicates, one query per batch, no waiting on a row lock in flight, 1–100 validation, 401 anonymous / any role accepted |
| `AccessAndRepublishIntegrationTest` | ownership 404, missing claim / wrong role 403, 401, republish roles and counts without version changes |
| `RestockIntegrationTest` | Phase 12: `GoodsReceived` → INCREASE / RESTOCK once (note PO number, reference, no actor, `StockAdjusted` + availability, movements API; redelivery ignored, a second receipt adds, repeated lines summed), other vendor.events types ignored, an unknown product fails the whole receipt until it arrives, archived / foreign products skipped, stock limit → DLT with nothing applied, broken receipts; `ReturnApproved` restock + GROCERY → INCREASE / RETURN once, restock false and RESTAURANT ignored (not marked), unknown / archived items skipped, broken returns |
| `ApprovalTokenIntegrationTest` | Phase 12: an ADJUST_STOCK approval token for `aud` `inventory-service` (the real auto-configured verifier; JWKS from a local HTTP server) runs the adjustment once and lands in `consumed_approval` (second use 409 `APPROVAL_ALREADY_USED`), another body 403 `APPROVAL_BODY_MISMATCH`, a forged token, another user or another audience 403 `APPROVAL_TOKEN_INVALID` (nothing changed), no header = ordinary request |
| `entity/StockRulesTest` | stock arithmetic and lock order without a database |
