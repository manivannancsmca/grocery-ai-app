# restaurant-service

Restaurant onboarding and approval lifecycle (Phase 2); menus, menu prices, item availability, the accepting-orders
switch and the public search listing (Phase 3); the restaurant's order board — accept with a preparation time /
reject / preparing / ready, the acceptance deadline with auto-reject, picked up by the delivery partner (Phase 9) —
and sales reports (Phase 8).

| | |
|---|---|
| Port | 8104 |
| Database | `restaurant_db` (user `restaurant_user`) — tables `restaurant`, `restaurant_cuisine`, `restaurant_opening_hours`, `restaurant_status_change`, `menu_category`, `menu_item`, `restaurant_order`, `restaurant_order_item`, `restaurant_order_status_history`, `outbox_event`, `processed_event`, `platform_setting`, `platform_setting_change` (admin platform settings) |
| Publishes | `restaurant.events` (key = restaurantId): RestaurantRegistered, RestaurantSubmitted, RestaurantApproved, RestaurantRejected, RestaurantSuspended, RestaurantReinstated — payload `{restaurantId, ownerUserId, name, reason, actorUserId}`; **RestaurantListingUpdated** (Phase 3) — the complete public listing, see below; (key = orderId, aggregate `RestaurantOrder`, Phase 8) RestaurantOrderReceived, RestaurantOrderAccepted, RestaurantOrderRejected, RestaurantOrderPreparing, RestaurantOrderReadyForPickup |
| Consumes | `identity.events` → `UserRegistered` with role RESTAURANT_OWNER creates a DRAFT restaurant named after the owner (group `restaurant-service.identity-events`); `order.events` → `OrderConfirmed` / `OrderCancelled` / `OrderOutForDelivery` (Phase 9) of RESTAURANT orders (group `restaurant-service.order-events`) |
| API | `docs/api/phase-2-identity-and-stakeholders.md` §5, `docs/api/phase-3-catalog-and-search.md` §5 and §8, internal menu quote: `docs/api/phase-5-cart-and-checkout-preview.md` §4, order board and sales report: `docs/api/phase-8-vendor-and-restaurant-fulfillment.md` §2, §3, §5, pickup: `docs/api/phase-9-delivery.md` §7 |

## Lifecycle
```
DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
  ▲                      │ reject
  └──── edit ◀── REJECTED ◀┘
```
All transition rules live in `entity/Restaurant.java`. Every change writes a `restaurant_status_change` row and an
outbox event in the same transaction (`service/RestaurantLifecycleRecorder.java`). identity-service consumes the
events and adds/removes the `restaurant_id` JWT claim, so only APPROVED restaurants can use owner-only APIs elsewhere.

Required to submit: name, cuisines (≥ 1), fssaiLicense, contactEmail, contactPhone, address incl. latitude/longitude,
openingHours (≥ 1). GSTIN is optional.

## Cuisines and opening hours
- `cuisines`: up to 8 uppercase tokens `^[A-Z_]{2,30}$` (e.g. `NORTH_INDIAN`), kept in the owner's order, duplicates
  dropped (`restaurant_cuisine`, `sort_order` 0 = primary cuisine).
- `openingHours`: at most one entry per day (`restaurant_opening_hours`, primary key restaurant + day), times `HH:mm`.
  `closesAt` must differ from `opensAt`; `closesAt` before `opensAt` means the kitchen closes after midnight
  (e.g. SATURDAY 18:00–02:00). Responses list the days Monday first. Cross-entry rules: `dto/ValidOpeningHours.java`.
- Each PUT replaces both lists completely.

## Menus (Phase 3)
The owner's restaurant is found from the token's **user id** (like `/api/restaurants/me`), not the `restaurant_id`
claim, so the menu can be built while DRAFT / PENDING_APPROVAL / REJECTED and goes live with the approval.

| Method & path | Role | Body → Response |
|---|---|---|
| `GET /api/restaurant/menu` | RESTAURANT_OWNER | → `MenuView` |
| `POST /api/restaurant/menu/categories` | RESTAURANT_OWNER | `MenuCategoryRequest` → 201 `MenuCategoryView` |
| `PUT /api/restaurant/menu/categories/{id}` | RESTAURANT_OWNER | `MenuCategoryRequest` → `MenuCategoryView` |
| `DELETE /api/restaurant/menu/categories/{id}` | RESTAURANT_OWNER | → 204 (only when empty) |
| `POST /api/restaurant/menu/items` | RESTAURANT_OWNER | `MenuItemRequest` → 201 `MenuItemView` |
| `PUT /api/restaurant/menu/items/{id}` | RESTAURANT_OWNER | `MenuItemRequest` → `MenuItemView` (full replacement; may move the item to another category) |
| `PATCH /api/restaurant/menu/items/{id}/availability` | RESTAURANT_OWNER | `{"available": false}` → `MenuItemView` |
| `DELETE /api/restaurant/menu/items/{id}` | RESTAURANT_OWNER | → 204 |
| `PUT /api/restaurant/accepting-orders` | RESTAURANT_OWNER | `{"acceptingOrders": false}` → `{"acceptingOrders": false}` |
| `GET /api/admin/restaurants/{id}/menu` | any admin | → `MenuView` (review before approving) |
| `POST /api/admin/restaurants/listings/republish` | SUPER_ADMIN / OPS_ADMIN | → `{"restaurants": 12}` |

`RestaurantView` (`/api/restaurants/me`, admin views) now includes `acceptingOrders`.

Rules and errors:
- Limits: ≤ 30 categories and ≤ 500 items per restaurant → 422 `MENU_LIMIT_REACHED`.
- Category names are unique per menu, case-insensitively (pre-check + unique key `uk_menu_category_restaurant_name`
  on a `_ci` collation) → 409 `MENU_CATEGORY_NAME_TAKEN`. Deleting a category that still has items → 409
  `MENU_CATEGORY_NOT_EMPTY`.
- Unknown ids and other restaurants' ids look the same: 404 `MENU_CATEGORY_NOT_FOUND` / `MENU_ITEM_NOT_FOUND`
  (an item's `categoryId` must belong to the same restaurant). No profile yet → 404 `RESTAURANT_NOT_FOUND`.
- While SUSPENDED every owner change (menu and accepting-orders) → 409 `RESTAURANT_SUSPENDED`; reading still works.
- Validation (`dto/MenuDtos.java`): category name ≤ 80, description ≤ 300; item name ≤ 120, description ≤ 500,
  price 1.00–100000.00 with ≤ 2 decimals (stored `DECIMAL(10,2)`, returned with 2 decimals), foodType
  `VEG|NON_VEG|EGG`, imageUrl http(s) ≤ 500; sortOrder 0–9999 (default 0); `available` defaults to true.
- Ordering everywhere (views and events): categories and items by `sortOrder`, then name.
- Setting availability or accepting-orders to the value it already has is a no-op (nothing is published).

## RestaurantListingUpdated and the locking rule
`RestaurantListingUpdated` (topic `restaurant.events`, aggregate `Restaurant`, key = restaurantId) carries the
complete public listing — `restaurantId, ownerUserId, status, name, description, cuisines, address{…}, openingHours[…],
acceptingOrders, menu[{categoryId, name, description, sortOrder, items[{itemId, name, description, price, foodType,
imageUrl, available, sortOrder}]}], listingVersion, updatedAt` (payload record: `event/RestaurantListingUpdated.java`).
It is published only for restaurants that were **ever approved** (`approved_at` set): on approval (version 1, the menu
built so far included), suspension (status `SUSPENDED`, search hides it), reinstatement, every menu change, the
accepting-orders switch, and admin republish. Draft menus publish nothing. identity-service ignores it.

catalog-query uses `listingVersion` as the Elasticsearch **external version** and drops anything not newer, so a
higher version must never carry older data. `service/RestaurantListingPublisher.java` enforces this:
1. **Lock first** — the transaction's first read is `SELECT … FOR UPDATE` on the restaurant row
   (`lockById` / `lockByOwner`), before any menu row is read. All changes to one restaurant's listing are serialised,
   and the transaction's snapshot starts after the previous change committed. (This also makes the limit and
   name checks race-free.)
2. **Change** — while holding the lock.
3. **Publish once, last** — `publishChange` increments `restaurant.listing_version` and writes one outbox event built
   from the current state, in the same transaction. It refuses a restaurant that was not loaded with the lock.

Approve / suspend / reinstate lock the row the same way and publish the listing right after the (unchanged)
lifecycle event. **Republish** re-sends the current listing of every ever-approved restaurant **without** incrementing
versions (search ignores equal versions and recreates lost documents); it pages through restaurants by id in batches
(`restaurant.listings.republish-batch-size`, default 100), one transaction per batch, reading restaurants first and
their menus second in the same snapshot.

## Events: Avro (ADR-0011)
- Payloads travel as **Avro** (Confluent wire format), validated against the central schemas in
  `libs/grocery-event-schemas` (`avro/restaurant.events/*.avsc`, pinned dependency `grocery-event-schemas` 1.1.0,
  which adds the `RestaurantOrder*` schemas; `RestaurantListingUpdated` prices are `decimal(10,2)` like
  `menu_item.price`). grocery-common 0.6.0 writes each
  outbox row twice in the business transaction: `payload` (JSON, kept for auditing/debugging/rollback) and
  `payload_avro` (Flyway `V4__outbox_avro_payload.sql`), which the Debezium connector publishes
  (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- Consumed `identity.events` are decoded from Avro (or legacy JSON) back to the same JSON; the listener is unchanged.
- Schema Registry: `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`, default
  `http://localhost:8081`); tests use the in-memory `mock://restaurant-service`.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only; re-point the connector to `payload` first).

## Internal menu quote (Phase 5, service-to-service)
`POST /internal/restaurants/{restaurantId}/menu-quote` `{ "itemIds": [ … ] }` → `MenuQuote` — what cart-service
needs to price and check restaurant lines. Not routed by the gateway; **any authenticated caller** (the caller
forwards the user's JWT; no token → 401).
```jsonc
{ "restaurantId": "…", "name": "Spice Route", "status": "APPROVED", "acceptingOrders": true, "openNow": true,
  "city": "Bengaluru", "latitude": 12.9784, "longitude": 77.6408,
  "items": [ { "itemId": "…", "name": "Chicken Biryani", "price": 249.00, "foodType": "NON_VEG",
               "available": true, "imageUrl": null } ],
  "missing": [ … ] }
```
- **Authoritative**: read from MySQL (not the search copy) in one read-only transaction with plain consistent reads —
  **no locks**, so it never waits for an owner's menu change (which holds the row's `FOR UPDATE` lock).
- 1–100 item ids (UUIDs), else 400 `VALIDATION_FAILED`. Duplicates are quoted once; `items` and `missing` keep the
  request order. `items` = ids on **this** restaurant's menu (sold-out ones included, `available: false`);
  `missing` = everything else (unknown, deleted, or another restaurant's item). Unknown restaurant → 404
  `RESTAURANT_NOT_FOUND`. The restaurant is quoted in any status — the caller decides what is orderable.
- `openNow` (`service/OpeningSchedule.java`, the same rule as catalog-query's `OpeningHours`): the weekly hours read
  in `grocery.restaurant.timezone` (default `Asia/Kolkata`) at the injected `Clock`'s instant, minute precision,
  opening inclusive / closing exclusive. A range closing after midnight counts for the previous day (SUNDAY
  18:00–02:00 → open Monday 01:59, closed at 02:00); a day without hours is closed.

## Store orders (Phase 8)
The restaurant's view of its paid RESTAURANT orders (`restaurant_order` + `restaurant_order_item` +
`restaurant_order_status_history`). Same API, shapes, rules and code structure as vendor-service's board; contract:
`docs/api/phase-8-vendor-and-restaurant-fulfillment.md`.

```
OrderConfirmed ─▶ NEW ──accept (+ preparation time)──▶ ACCEPTED ──preparing──▶ PREPARING ──ready──▶ READY_FOR_PICKUP
                   │                                       └──────────────────ready──────────────────────▲      │
                   ├─reject / nobody before acceptBy (auto) ─▶ REJECTED                  OrderOutForDelivery (Phase 9)
                   └─OrderCancelled (from any non-terminal status) ─▶ CANCELLED                            ▼
                                                                                                      PICKED_UP
```
Terminal: `PICKED_UP` (success, Phase 9), `REJECTED`, `CANCELLED`.

**Created from `order.events`** (`event/OrderEventsListener`, `service/StoreOrderProjection`, group
`restaurant-service.order-events`):
- `OrderConfirmed` with `orderType` RESTAURANT → store order NEW, `acceptBy = now + grocery.fulfillment.acceptance-timeout`,
  and `RestaurantOrderReceived` (`ownerUserId` = the restaurant's owner from `restaurant`). A GROCERY order is ignored
  (not marked processed); a restaurant id this service does not know is logged and skipped; an order already on the
  board (replay) is skipped. Snapshot: lines, `itemCount` (units), `subtotal` (from the event, or the sum of the line
  totals for older events), `total`, `note` (null when absent or blank), `customer.name` = the address' recipient name,
  `customer.area` = landmark (else line 2) + ", " + city (just the city without either; null without an address) —
  never the phone or the street.
- `OrderCancelled` → a NEW / ACCEPTED / PREPARING / READY_FOR_PICKUP store order becomes CANCELLED with the event's
  reason, `cancelledBy` and `cancelledAt`; no event (order-service already knows). REJECTED / CANCELLED / PICKED_UP
  orders (e.g. the `STORE_REJECTED` cancellation that follows our own rejection) and orders never confirmed are left
  alone.
- `OrderOutForDelivery` (Phase 9, RESTAURANT; the delivery partner picked the order up) → the store order becomes
  **PICKED_UP**: the terminal success, off the `ACTIVE` board, with a timeline entry at the event's `pickedUpAt` (no
  actor); no event (order-service already knows; notification-service refreshes the board with
  `STORE_ORDERS_CHANGED`). Normally from READY_FOR_PICKUP; from ACCEPTED / PREPARING too (logged as a warning — the
  food has left either way). A NEW or terminal store order, a GROCERY order and an order never on the board are left
  alone (logged). Every action on a PICKED_UP order is 409 with `currentStatus: PICKED_UP`.
- Every event is marked in `processed_event` in the transaction that applies it; database failures propagate, so
  grocery-common's error handler retries the record. An unreadable event is `InvalidEventException` → `order.events.DLT`.

**API** (`controller/RestaurantOrderController`, role `RESTAURANT_OWNER` + the `restaurant_id` claim — unlike the menu,
which works from the user id before approval; no claim → 403 `RESTAURANT_NOT_ACTIVE`; another restaurant's or an
unknown order → 404 `STORE_ORDER_NOT_FOUND`; a non-UUID id or a bad `page` → 400 `INVALID_PARAMETER`):

| Method & path | Body → Response |
|---|---|
| `GET /api/restaurant/orders?status=&page=&size=` | → `PageResponse<StoreOrderView>`; `status` = a store status or `ACTIVE` (NEW, ACCEPTED, PREPARING, READY_FOR_PICKUP), blank = all, else 400 `INVALID_PARAMETER`; NEW first by `acceptBy` ascending, then the rest newest first; `size` capped at 100 |
| `GET /api/restaurant/orders/{orderId}` | → `StoreOrderView` |
| `POST /api/restaurant/orders/{orderId}/accept` | optional `{ "readyInMinutes": 25 }` = the preparation time (5–180, default 20) → ACCEPTED + `RestaurantOrderAccepted`; ACCEPTED again → 200 unchanged; other status → 409 `STORE_ORDER_NOT_PENDING` (`currentStatus`); NEW past `acceptBy` → 409 `ACCEPTANCE_EXPIRED` |
| `POST /api/restaurant/orders/{orderId}/reject` | `{ "reason": "OUT_OF_STOCK" \| "STORE_CLOSED" \| "TOO_BUSY" \| "OTHER", "note": "…" }` (note ≤ 300, required for OTHER) → REJECTED + `RestaurantOrderRejected`; REJECTED again → 200 unchanged; other status → 409 `STORE_ORDER_NOT_PENDING` |
| `POST /api/restaurant/orders/{orderId}/preparing` | ACCEPTED → PREPARING + `RestaurantOrderPreparing`; repeat → 200; else 409 `INVALID_STORE_ORDER_TRANSITION` (`currentStatus`) |
| `POST /api/restaurant/orders/{orderId}/ready` | ACCEPTED / PREPARING → READY_FOR_PICKUP + `RestaurantOrderReadyForPickup`; repeat → 200; else 409 `INVALID_STORE_ORDER_TRANSITION` |

`StoreOrderView` = `{ orderId, orderNumber, storeId, status, items[{itemId, name, quantity, unitPrice, lineTotal}],
itemCount, subtotal, total, currency, customer{name, area}, note, receivedAt, acceptBy, acceptedAt, readyInMinutes,
readyBy, preparingAt, readyAt, rejection{reason, note, auto, at} | null, cancellation{reason, by, at} | null,
timeline[{status, at}] }`. Validation errors are 400 `VALIDATION_FAILED` with `errors[{field, message}]`.
`STORE_ORDER_NOT_PENDING` and `INVALID_STORE_ORDER_TRANSITION` carry the store order's status in the property
`currentStatus`; `status` stays RFC 9457's numeric HTTP status (409).

**Locking rule** (`service/StoreOrderService`): every transition — the owner's actions, the poller, `OrderCancelled`
— locks the `restaurant_order` row first (`SELECT … FOR UPDATE`; the poller `FOR UPDATE SKIP LOCKED`), then checks the
status, changes it, appends the history row and writes the outbox event in the same transaction: exactly one of
accept / reject / auto-reject / cancel wins a NEW order.

**Auto-reject** (`service/AutoRejectPoller`): every `grocery.fulfillment.poll-interval` it claims NEW orders with
`accept_by < now` in batches (`FOR UPDATE SKIP LOCKED`, READ COMMITTED, one transaction per batch) and rejects them with
reason `NO_RESPONSE`, `auto: true`, no actor, no note → `RestaurantOrderRejected`. Safe with several replicas.

## Sales report (Phase 8)
`GET /api/restaurant/reports/sales?from=&to=` (role `RESTAURANT_OWNER` + `restaurant_id` claim;
`service/SalesReportService`) → `SalesReport { from, to, timezone, currency, ordersReceived, ordersAccepted,
ordersRejected, autoRejected, ordersCancelled, acceptanceRate, grossSales, itemsSold, averageOrderValue, daily[…],
topItems[…] }`. Same rules as vendor-service: India-time dates, inclusive, `to` defaults to today and `from` to six days
before `to`, at most 92 days (else 400 `INVALID_PARAMETER`, also for `from` after `to` or a bad date); orders count on
the India-time day they were received; accepted = reached ACCEPTED (also when cancelled later); `grossSales` / `itemsSold`
/ `averageOrderValue` over the orders accepted and not cancelled (subtotal, no delivery fee); 2-decimal ratios, `0.00`
when there is nothing to divide by; every day of the range in `daily`; the top 5 items by units (ties: sales, item id).
A PICKED_UP order (Phase 9) was accepted and is not cancelled, so it counts as accepted and sold with no change to the
rules.

## Settings (`grocery.fulfillment.*`, `config/FulfillmentProperties`)
| Property | Env | Default | |
|---|---|---|---|
| `acceptance-timeout` | `ACCEPTANCE_TIMEOUT` | `5m` | `acceptBy = receivedAt + timeout` |
| `poll-interval` | `ACCEPTANCE_POLL_INTERVAL` | `15s` | pause between poller runs (also the initial delay) |
| `poll-batch-size` | — | `20` | orders per poller transaction (1–500) |
| `auto-reject-enabled` | `ACCEPTANCE_AUTO_REJECT_ENABLED` | `true` | `false` = no poller in this instance (tests) |
| `default-ready-in-minutes` | — | `20` | accept without `readyInMinutes` (5–180) |
| `timezone` | — | `Asia/Kolkata` | the report's days (`grocery.restaurant.timezone` stays the opening hours' zone) |
Invalid values fail the startup.

### Platform settings (admin, area `restaurants`)
`acceptance-timeout` is only the **default**: admins change the acceptance time on **Admin → Platform settings**
(`GET` / `PUT /api/admin/settings/restaurants`, field `acceptanceTimeMinutes`, 1–60; read: any admin, save:
SUPER_ADMIN or OPS_ADMIN; contract `docs/api/platform-settings.md`). A saved value is stored in `platform_setting`
(Flyway `V8__platform_settings.sql`, audit rows in `platform_setting_change`) and overrides the default.
- **When it applies:** `StoreOrderProjection` reads the value in force (`settings/RestaurantSettingsService`) when an
  order reaches the board, so new orders get the new `acceptBy`; orders already waiting keep theirs.
- **Delay:** each instance re-reads the overrides every 15 s (`grocery.settings.cache-ttl`, env
  `PLATFORM_SETTINGS_CACHE_TTL`); the instance that saved uses them at once.
- **History:** `GET /api/admin/settings/restaurants/history?page=&size=` (any admin): the audit rows as a
  `PageResponse`, newest first, `{ field, oldValue, newValue, changedAt, changedBy: { id, name } }`, minutes as in
  `GET`, `null` = the default (contract §5).
- **Tests:** `PlatformSettingsIntegrationTest` (5 minutes without an override, an override used by new orders only,
  reset, 409 `SETTINGS_CHANGED`, validation, roles, audit row, the history: order, values, paging, roles).

## Package layout (same template as vendor-service)
```
config/       Clock bean (UTC, microsecond ticks = DATETIME(6)), RestaurantProperties (grocery.restaurant.timezone),
              FulfillmentProperties (grocery.fulfillment.*), scheduling switch
controller/   RestaurantController (/api/restaurants/me…), RestaurantMenuController (/api/restaurant/menu…),
              RestaurantOperationsController (/api/restaurant/accepting-orders), AdminRestaurantController (/api/admin/restaurants…),
              InternalRestaurantController (/internal/restaurants/{id}/menu-quote),
              RestaurantOrderController (/api/restaurant/orders…), RestaurantReportController (/api/restaurant/reports/sales),
              StoreOrderParameterAdvice (400 INVALID_PARAMETER)
dto/          request/response records + validation rules (RestaurantDtos, MenuDtos, ValidOpeningHours, StoreOrderDtos)
entity/       Restaurant (state machine, accepting-orders, listing version), BusinessAddress, OpeningHours, RestaurantStatus,
              RestaurantStatusChange, MenuCategory, MenuItem, FoodType,
              RestaurantOrder (store order state machine), StoreOrderItem, StoreOrderStatusChange, StoreOrderStatus,
              RejectionReason
event/        RestaurantEvents (published lifecycle payload), RestaurantListingUpdated (listing payload),
              RestaurantOrderEvents (store order payloads), IdentityEventsListener, OrderEventsListener
mapper/       RestaurantMapper, MenuMapper (views + listing snapshot; plain Java), StoreOrderMapper
repository/   Spring Data JPA (RestaurantRepository has the FOR UPDATE and keyset queries; RestaurantOrderRepository the
              store order lock, SKIP LOCKED claim, board and report queries)
service/      RestaurantOnboardingService (owner's own profile), RestaurantMenuService, RestaurantOperationsService,
              RestaurantAdminService, RestaurantLifecycleRecorder, RestaurantListingPublisher,
              MenuQuoteService + OpeningSchedule (internal menu quote, "open now"),
              StoreOrderProjection (order.events), StoreOrderService (board + transitions + auto-reject batch),
              StoreOrderEventPublisher, AutoRejectPoller, SalesReportService, StoreOrderException
settings/     admin platform settings (area restaurants): PlatformSettingStore (table + 15 s cache, history),
              RestaurantSettingsService, AdminSettingsController, SettingChange (history entry)
```

## AI-approved calls (Phase 11)

Endpoint an assistant can trigger: `POST /api/admin/restaurants/{id}/suspend` (SUSPEND_RESTAURANT, always approved by a second admin). Calls that an AI assistant proposed and a human approved arrive with an `X-Approval-Token` (grocery-common 0.8.0,
`grocery.approvals.enabled: true`, keys from `APPROVAL_JWKS_URI`). The filter checks the signature, the exact
method, path and body hash, and that the approver is the caller, then records the approval id in
`consumed_approval` (Flyway V6), so a second use is 409 `APPROVAL_ALREADY_USED`. Requests without the header are
unchanged. Contract: [Phase 11 §5](../../docs/api/phase-11-agentic-ai-and-approvals.md#5-approval-token--verified-by-the-target-grocery-common-080).

## Run / test
```bash
cd infrastructure/local && docker compose --profile core up -d --build restaurant-service
# tests (from repo root, Git Bash) — Testcontainers MySQL
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/restaurant-service/pom.xml clean verify
```
Tests: `RestaurantLifecycleIntegrationTest` (Phase 2 onboarding/approval), `RestaurantMenuIntegrationTest` (menus,
listing events and versions, concurrency, limits, ownership, suspension, republish, roles), `MenuQuoteIntegrationTest`
(Phase 5 menu quote: fields, other restaurants' items missing, openNow with a pinned clock incl. overnight hours,
validation, 404, 401 without a token / any role accepted, no waiting on a row lock), `AvroOutboxRoundTripIntegrationTest`
(every event's `payload_avro` decodes to its JSON `payload`, under subject
`restaurant.events-com.grocery.events.restaurant.<EventType>`, incl. the five `RestaurantOrder*` events) and
`OpeningScheduleTest`. Phase 8 (the same cases as vendor-service's, for restaurant orders):
`OrderEventsProjectionIntegrationTest` (OrderConfirmed / OrderCancelled projection, own type only, duplicates, unknown
restaurant, subtotal / note fallback, customer area, poison events), `StoreOrderApiIntegrationTest` (every endpoint,
event payloads, validation, idempotent repeats, every 409, board order and paging, ownership / claim / role),
`AutoRejectIntegrationTest` (deadline, SKIP LOCKED, two pollers, races accept vs auto-reject and store action vs
OrderCancelled → exactly one outcome), `SalesReportIntegrationTest` (hand-checkable numbers, India day boundary,
defaults, range validation) and `AutoRejectPollerSchedulingTest`. Phase 9: `PickupIntegrationTest` (OrderOutForDelivery
→ PICKED_UP with its timeline entry and no event, off the ACTIVE board, every action afterwards 409 `currentStatus`
PICKED_UP, redelivery / late cancel no-ops, NEW / terminal / unknown / GROCERY orders untouched, broken event, the
report counts it as accepted and sold).
Integration tests run with `MutableClock` (the real clock unless a test pins it), without the scheduled poller
(`grocery.fulfillment.auto-reject-enabled=false`; tests run pollers with a clock of their choice) and call the Kafka
listeners directly with records carrying the outbox headers.
Debezium connector config: `deploy/debezium-connector.json` (`restaurant-outbox`).
