# vendor-service

Vendor (grocery seller) onboarding and approval lifecycle (Phase 2); the vendor's order board — accept / reject /
packing / ready, the acceptance deadline with auto-reject, picked up by the delivery partner (Phase 9) — and sales
reports (Phase 8); purchasing and supply — suppliers, purchase orders, goods receipts (Phase 12).

| | |
|---|---|
| Port | 8103 |
| Database | `vendor_db` (user `vendor_user`) — tables `vendor`, `vendor_status_change`, `vendor_order`, `vendor_order_item`, `vendor_order_status_history`, `outbox_event`, `processed_event`, `consumed_approval`; Phase 12 (Flyway V6): `product_ref`, `supplier`, `purchase_order`, `purchase_order_line`, `purchase_receipt`, `purchase_receipt_line`, `idempotency_key`; admin platform settings (Flyway V8): `platform_setting`, `platform_setting_change` |
| Publishes | `vendor.events` (key vendorId): VendorRegistered, VendorSubmitted, VendorApproved, VendorRejected, VendorSuspended, VendorReinstated; (key orderId, aggregate `VendorOrder`, Phase 8): VendorOrderReceived, VendorOrderAccepted, VendorOrderRejected, VendorOrderPreparing, VendorOrderReadyForPickup; (key vendorId, aggregate `PurchaseOrder`, Phase 12): GoodsReceived |
| Consumes | `identity.events` → `UserRegistered` with role VENDOR creates a DRAFT profile (group `vendor-service.identity-events`); `order.events` → `OrderConfirmed` / `OrderCancelled` / `OrderOutForDelivery` (Phase 9) of GROCERY orders (group `vendor-service.order-events`); `catalog.events` → `ProductUpserted` (Phase 12, the product copy, group `vendor-service.catalog-events`) |
| API | `docs/api/phase-2-identity-and-stakeholders.md` §5; public store directory (`/api/stores/**`, no token): `docs/api/phase-3-catalog-and-search.md` §9; internal store view: `docs/api/phase-5-cart-and-checkout-preview.md` §4; order board and sales report: `docs/api/phase-8-vendor-and-restaurant-fulfillment.md` §2, §3, §5; pickup: `docs/api/phase-9-delivery.md` §7; purchasing: `docs/api/phase-12-post-mvp-features.md` §4 |

## Lifecycle
```
DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
  ▲                      │ reject
  └──── edit ◀── REJECTED ◀┘
```
All transition rules live in `entity/Vendor.java`. Every change writes a `vendor_status_change` row and an
outbox event in the same transaction (`service/VendorLifecycleRecorder.java`). identity-service consumes the
events and adds/removes the `vendor_id` JWT claim, so only APPROVED vendors can use vendor-only APIs elsewhere.

## Events: Avro (ADR-0011)
- Payloads travel as **Avro** (Confluent wire format), validated against the central schemas in
  `libs/grocery-event-schemas` (`avro/vendor.events/*.avsc`, pinned dependency `grocery-event-schemas` 1.7.0; 1.1.0
  added the `VendorOrder*` schemas, 1.5.0 `GoodsReceived`).
  grocery-common 0.6.0 writes each outbox row twice in the business transaction: `payload` (JSON, kept for
  auditing/debugging/rollback) and `payload_avro` (Flyway `V3__outbox_avro_payload.sql`), which the Debezium
  connector publishes (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- Consumed `identity.events`, `order.events` and `catalog.events` are decoded from Avro (or legacy JSON) back to the
  same JSON; the listeners are unchanged.
- Schema Registry: `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`, default
  `http://localhost:8081`); tests use the in-memory `mock://vendor-service`.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only; re-point the connector to `payload` first).

## Internal endpoint (Phase 5)
| Method & path | Who | |
|---|---|---|
| `GET /internal/vendors/{vendorId}/store` | any authenticated caller (no role) — internal | `{ vendorId, name, status, active, city, latitude, longitude }` · 404 `VENDOR_NOT_FOUND` |

cart-service reads the store of a grocery cart with the customer's forwarded token
(`docs/api/phase-5-cart-and-checkout-preview.md` §1, §4). `/internal/**` is never routed by the gateway, but the JWT
is still validated here (no token → 401 `UNAUTHENTICATED`); any role may ask. `name` = business name, `active` =
status `APPROVED` (every other status is answered with `active: false` — the cart turns a suspended store into
`STORE_UNAVAILABLE`). `city`, `latitude`, `longitude` come from the business address (the pickup point for the
delivery fee) and are `null` while it is not set. Read-only (`service/VendorStoreService.java`).

## Store orders (Phase 8)
The vendor's view of its paid GROCERY orders (`vendor_order` + `vendor_order_item` + `vendor_order_status_history`).
Same API and shapes as restaurant-service's board; contract: `docs/api/phase-8-vendor-and-restaurant-fulfillment.md`.

```
OrderConfirmed ─▶ NEW ──accept──▶ ACCEPTED ──preparing (packing)──▶ PREPARING ──ready──▶ READY_FOR_PICKUP
                   │                  └──────────────────ready──────────────────────────────▲      │
                   ├─reject / nobody before acceptBy (auto) ─▶ REJECTED              OrderOutForDelivery (Phase 9)
                   └─OrderCancelled (from any non-terminal status) ─▶ CANCELLED                    ▼
                                                                                              PICKED_UP
```
Terminal: `PICKED_UP` (success, Phase 9), `REJECTED`, `CANCELLED`.

**Created from `order.events`** (`event/OrderEventsListener`, `service/StoreOrderProjection`, group
`vendor-service.order-events`):
- `OrderConfirmed` with `orderType` GROCERY → store order NEW, `acceptBy = now + grocery.fulfillment.acceptance-timeout`,
  and `VendorOrderReceived` (`ownerUserId` = the vendor's owner from `vendor`). A RESTAURANT order is ignored (not
  marked processed); a vendor id this service does not know is logged and skipped; an order already on the board
  (replay) is skipped. Snapshot: lines, `itemCount` (units), `subtotal` (from the event, or the sum of the line totals
  for older events), `total`, `note` (null when absent or blank), `customer.name` = the address' recipient name,
  `customer.area` = landmark (else line 2) + ", " + city (just the city without either; null without an address) —
  never the phone or the street.
- `OrderCancelled` → a NEW / ACCEPTED / PREPARING / READY_FOR_PICKUP store order becomes CANCELLED with the event's
  reason, `cancelledBy` and `cancelledAt`; no event (order-service already knows). REJECTED / CANCELLED / PICKED_UP
  orders (e.g. the `STORE_REJECTED` cancellation that follows our own rejection) and orders never confirmed are left
  alone.
- `OrderOutForDelivery` (Phase 9, GROCERY; the delivery partner picked the order up) → the store order becomes
  **PICKED_UP**: the terminal success, off the `ACTIVE` board, with a timeline entry at the event's `pickedUpAt` (no
  actor); no event (order-service already knows; notification-service refreshes the board with
  `STORE_ORDERS_CHANGED`). Normally from READY_FOR_PICKUP; from ACCEPTED / PREPARING too (logged as a warning — the
  goods have left either way). A NEW or terminal store order, a RESTAURANT order and an order never on the board are
  left alone (logged). Every action on a PICKED_UP order is 409 with `currentStatus: PICKED_UP`.
- Every event is marked in `processed_event` in the transaction that applies it; database failures propagate, so
  grocery-common's error handler retries the record (outages for up to 30 minutes). An unreadable event (no order id,
  no lines, …) is `InvalidEventException` → `order.events.DLT`.

**API** (`controller/VendorOrderController`, role `VENDOR` + the `vendor_id` claim; no claim → 403
`VENDOR_NOT_ACTIVE`; another vendor's or an unknown order → 404 `STORE_ORDER_NOT_FOUND`; a non-UUID id or a bad
`page` → 400 `INVALID_PARAMETER`):

| Method & path | Body → Response |
|---|---|
| `GET /api/vendor/orders?status=&page=&size=` | → `PageResponse<StoreOrderView>`; `status` = a store status or `ACTIVE` (NEW, ACCEPTED, PREPARING, READY_FOR_PICKUP), blank = all, else 400 `INVALID_PARAMETER`; NEW first by `acceptBy` ascending, then the rest newest first; `size` capped at 100 |
| `GET /api/vendor/orders/{orderId}` | → `StoreOrderView` |
| `POST /api/vendor/orders/{orderId}/accept` | optional `{ "readyInMinutes": 15 }` (5–180, default 15) → ACCEPTED + `VendorOrderAccepted`; ACCEPTED again → 200 unchanged; other status → 409 `STORE_ORDER_NOT_PENDING` (`currentStatus`); NEW past `acceptBy` → 409 `ACCEPTANCE_EXPIRED` |
| `POST /api/vendor/orders/{orderId}/reject` | `{ "reason": "OUT_OF_STOCK" \| "STORE_CLOSED" \| "TOO_BUSY" \| "OTHER", "note": "…" }` (note ≤ 300, required for OTHER) → REJECTED + `VendorOrderRejected`; REJECTED again → 200 unchanged; other status → 409 `STORE_ORDER_NOT_PENDING` |
| `POST /api/vendor/orders/{orderId}/preparing` | packing started: ACCEPTED → PREPARING + `VendorOrderPreparing`; repeat → 200; else 409 `INVALID_STORE_ORDER_TRANSITION` (`currentStatus`) |
| `POST /api/vendor/orders/{orderId}/ready` | ACCEPTED / PREPARING → READY_FOR_PICKUP + `VendorOrderReadyForPickup`; repeat → 200; else 409 `INVALID_STORE_ORDER_TRANSITION` |

`StoreOrderView` = `{ orderId, orderNumber, storeId, status, items[{itemId, name, quantity, unitPrice, lineTotal}],
itemCount, subtotal, total, currency, customer{name, area}, note, receivedAt, acceptBy, acceptedAt, readyInMinutes,
readyBy, preparingAt, readyAt, rejection{reason, note, auto, at} | null, cancellation{reason, by, at} | null,
timeline[{status, at}] }`. Validation errors are 400 `VALIDATION_FAILED` with `errors[{field, message}]`
(`readyInMinutes`, `reason` — NO_RESPONSE is the poller's only —, `note`). `STORE_ORDER_NOT_PENDING` and
`INVALID_STORE_ORDER_TRANSITION` carry the store order's status in the property `currentStatus`; `status` stays RFC
9457's numeric HTTP status (409).

**Locking rule** (`service/StoreOrderService`): every transition — the vendor's actions, the poller, `OrderCancelled`
— locks the `vendor_order` row first (`SELECT … FOR UPDATE`; the poller `FOR UPDATE SKIP LOCKED`), then checks the
status, changes it, appends the history row and writes the outbox event in the same transaction. Accept vs auto-reject
vs a customer's cancel: exactly one wins a NEW order and the others see its result (409, or a no-op for the
cancellation of a REJECTED order).

**Auto-reject** (`service/AutoRejectPoller`): every `grocery.fulfillment.poll-interval` (fixed delay) it claims NEW
orders with `accept_by < now` in batches of `grocery.fulfillment.poll-batch-size`, `FOR UPDATE SKIP LOCKED` in a READ
COMMITTED transaction (one transaction per batch, until a batch comes back short, at most 50 per run), and rejects them
with reason `NO_RESPONSE`, `auto: true`, no actor, no note → `VendorOrderRejected`. Safe with several replicas (they
claim disjoint batches; an order being accepted right now is skipped). Exactly at `acceptBy` the vendor may still
accept; 1 µs later the accept is 409 `ACCEPTANCE_EXPIRED` and the next run rejects it.

## Sales report (Phase 8)
`GET /api/vendor/reports/sales?from=&to=` (role `VENDOR` + `vendor_id` claim; `service/SalesReportService`) →
`SalesReport { from, to, timezone, currency, ordersReceived, ordersAccepted, ordersRejected, autoRejected,
ordersCancelled, acceptanceRate, grossSales, itemsSold, averageOrderValue, daily[{date, ordersReceived, ordersAccepted,
grossSales}], topItems[{itemId, name, quantity, sales}] }`.
- `from` / `to`: dates in India time (`grocery.fulfillment.timezone`), inclusive. `to` defaults to today, `from` to six
  days before `to` (no parameters = the last 7 days including today). `from` after `to`, more than 92 days, or a value
  that is not `yyyy-MM-dd` → 400 `INVALID_PARAMETER`.
- Orders count on the India-time day they were received (`received_at`). Received = every store order in the range;
  accepted = `accepted_at` set (also when cancelled later); rejected = REJECTED, of which auto = `rejection_auto`;
  cancelled = CANCELLED (before or after acceptance). A PICKED_UP order (Phase 9) has `accepted_at` and is not
  cancelled, so it counts as accepted and sold with no change to the rules.
- Sold = accepted and not cancelled: `grossSales` = sum of their `subtotal` (the delivery fee is not the store's),
  `itemsSold` = their units, `averageOrderValue` = grossSales / their number; `acceptanceRate` = accepted / received.
  Ratios have 2 decimals (half up) and are `0.00` when there is nothing to divide by.
- `daily`: every day of the range, zeros included. `topItems`: the 5 items with the most units in the sold orders
  (ties: higher sales, then item id), with the name of the item's most recent line.
- Served by `idx_vendor_order_received (vendor_id, received_at)`; the per-day totals are summed in Java (exact for any
  zone), the top items in SQL.

## Purchasing & supply (Phase 12 §4)
A vendor's suppliers and purchase orders; receiving goods writes a receipt and publishes `GoodsReceived`, which
inventory-service turns into stock (`INCREASE`, reason `RESTOCK`, note `PO-…`). Role `VENDOR` + the `vendor_id` claim
(no claim → 403 `VENDOR_NOT_ACTIVE`); another vendor's supplier / purchase order is 404 (`SUPPLIER_NOT_FOUND`,
`PURCHASE_ORDER_NOT_FOUND`); a non-UUID id, a bad `status` / `active` / `page` → 400 `INVALID_PARAMETER`; bean
validation → 400 `VALIDATION_FAILED` with `errors[{field, message}]`.

**Products** come from `catalog.events` → `ProductUpserted` (`event/CatalogEventsListener`,
`service/ProductProjectionService`, group `vendor-service.catalog-events`, table `product_ref`): idempotent per event
id, a snapshot applied only with a newer `productVersion` (the pricing / inventory approach). A purchase order line may
name only the caller's own product that is not ARCHIVED (DRAFT / UNPUBLISHED are fine — stock up before selling);
anything else → 422 `INVALID_PRODUCT` (`productId`). The product name is snapshotted on the line.

**Suppliers** (`controller/VendorSupplierController`, `service/SupplierService`):

| Method & path | Body → Response |
|---|---|
| `GET /api/vendor/suppliers?active=` | → `SupplierView[]` (active first, then by name; `active=true\|false` filters) |
| `POST /api/vendor/suppliers` | `SupplierRequest` → 201 `SupplierView` (`active` defaults to true) |
| `GET /api/vendor/suppliers/{id}` | → `SupplierView` |
| `PUT /api/vendor/suppliers/{id}` | `SupplierRequest` (replaces every field; `active` null = unchanged, `false` = deactivate) → `SupplierView` |
| `DELETE /api/vendor/suppliers/{id}` | deactivate → 204 (again → 204); 409 `SUPPLIER_IN_USE` (`openPurchaseOrders`) while DRAFT / SENT / PARTIALLY_RECEIVED purchase orders name it (also for PUT `active: false`) |

`SupplierRequest = { name (required ≤ 120), contactName ≤ 100, phone (10–15 digits, optional +), email, gstin
(15-char GSTIN), address (free text ≤ 300), leadTimeDays 0–90, notes ≤ 500, active }` — blank optional fields are
stored as null. `SupplierView = { id, name, contactName, phone, email, gstin, address, leadTimeDays, notes, active,
createdAt, updatedAt }`.

**Purchase orders** (`controller/VendorPurchaseOrderController`, `service/PurchaseOrderService`,
`service/PurchaseReceivingService`):
```
DRAFT ──send──▶ SENT ──receive (part)──▶ PARTIALLY_RECEIVED ──receive (rest)──▶ RECEIVED
  │              │  └────────────receive (all)─────────────────────────────────────▲
  └──cancel──────┴──cancel──▶ CANCELLED          PARTIALLY_RECEIVED ──close──▶ CLOSED (short-closed)
```

| Method & path | Body → Response |
|---|---|
| `GET /api/vendor/purchase-orders?status=&supplierId=&number=&page=&size=` | → `PageResponse<PurchaseOrderView>`, newest first; `status` = a status or `OPEN` (DRAFT, SENT, PARTIALLY_RECEIVED); `number` = `PO-…` exactly (case-insensitive) |
| `POST /api/vendor/purchase-orders` | `PurchaseOrderRequest` → 201 `PurchaseOrderView` (DRAFT, new number `PO-XXXXXXXX`: 8 Crockford base32 characters) |
| `GET /api/vendor/purchase-orders/{id}` | → `PurchaseOrderView` |
| `PUT /api/vendor/purchase-orders/{id}` | `PurchaseOrderRequest` (replaces supplier, `expectedAt`, notes and every line) → `PurchaseOrderView`; only a DRAFT, else 409 `PURCHASE_ORDER_NOT_EDITABLE` (`currentStatus`) |
| `POST /api/vendor/purchase-orders/{id}/send` · `/cancel` · `/close` | → `PurchaseOrderView`; send: DRAFT → SENT; cancel: DRAFT / SENT → CANCELLED; close: PARTIALLY_RECEIVED → CLOSED; from any other status (a repeat too) 409 `INVALID_STATUS_TRANSITION` (`currentStatus`) |
| `POST /api/vendor/purchase-orders/{id}/receive` | header `Idempotency-Key` + `{ lines: [ { productId, quantity } ], note? }` → `PurchaseOrderView` with the new receipt (see below) |
| `GET /api/admin/purchase-orders?vendorId=&status=&page=&size=` · `GET /api/admin/purchase-orders/{id}` | any admin role, read-only (`controller/AdminPurchaseOrderController`) |

`PurchaseOrderRequest = { supplierId (the caller's active supplier, else 422 INVALID_SUPPLIER with supplierId),
expectedAt?, notes? ≤ 500, lines: [ { productId, orderedQuantity 1–100000 (alias quantity), unitCost ≥ 0 with ≤ 2
decimals } ] (1–100, each product once) }`. `expectedAt`: an ISO-8601 instant (an offset is converted to UTC) or a
date `yyyy-MM-dd` = 00:00 of that day India time.

`PurchaseOrderView = { id, number, vendorId, supplierId, supplierName, status, expectedAt, notes, currency: "INR",
lines[{productId, productName, orderedQuantity, receivedQuantity, openQuantity, unitCost, lineTotal}], orderedUnits,
receivedUnits, totalCost (Σ ordered × unitCost), receivedCost (Σ received × unitCost), receipts[{id, receivedAt,
receivedBy, note, lines[{productId, productName, quantity, unitCost, lineCost}], units, totalCost}] (oldest first),
createdAt, updatedAt, sentAt, cancelledAt, closedAt, fullyReceivedAt }` — money always with 2 decimals.

**Receiving** (`service/PurchaseReceivingService`), in order:
1. `Idempotency-Key` missing → 400 `IDEMPOTENCY_KEY_REQUIRED`, malformed (8–100 of `A-Za-z0-9._:-`) → 400
   `IDEMPOTENCY_KEY_INVALID`; then the body (1–100 lines, each product once, quantity 1–100000, note ≤ 300).
2. A key this vendor already used for the same purchase order and body → the purchase order **as it is now**, with
   header `Idempotent-Replayed: true` — nothing received again, no second `GoodsReceived`. The same key with another
   body or purchase order → 422 `IDEMPOTENCY_KEY_REUSED`. Keys are scoped per vendor (`idempotency_key`, scope
   `purchase-receipt:<vendorId>`, result = the receipt id).
3. One transaction: claim the key, lock the purchase order (`FOR UPDATE`; another vendor's → 404), status must be SENT
   or PARTIALLY_RECEIVED (else 409 `INVALID_STATUS_TRANSITION` with `currentStatus`), every line ≤ what is still open
   (else 422 `RECEIPT_EXCEEDS_ORDERED` with `productId` and `openQuantity`; a product not on the purchase order has
   0 open), add the quantities, write the receipt (unit costs from the purchase order) and put `GoodsReceived` in the
   outbox. RECEIVED when nothing is open any more (`fullyReceivedAt`), else PARTIALLY_RECEIVED. Any error rolls the
   key back with everything else, so the same key works once the request is fixed. Concurrent retries with one key
   wait for the first and then answer its result.

`GoodsReceived` (topic `vendor.events`, aggregate `PurchaseOrder`, key = **vendorId**, schema 1.5.0, converted
strictly): `{ vendorId, purchaseOrderId, purchaseOrderNumber, receiptId, supplierId, lines[{productId, quantity,
unitCost}], receivedAt }`.

**Locking rule:** every purchase-order change locks its row first (`SELECT … FOR UPDATE`), then checks ownership and
status, then changes it, in one transaction. Creating or editing a purchase order reads the supplier `FOR SHARE`;
deactivating a supplier locks it `FOR UPDATE` before counting its open purchase orders — so either the purchase order
commits first and the deactivation answers 409 `SUPPLIER_IN_USE`, or the deactivation commits first and the purchase
order answers 422 `INVALID_SUPPLIER`.

## Settings (`grocery.fulfillment.*`, `config/FulfillmentProperties`)
| Property | Env | Default | |
|---|---|---|---|
| `acceptance-timeout` | `ACCEPTANCE_TIMEOUT` | `5m` | `acceptBy = receivedAt + timeout` |
| `poll-interval` | `ACCEPTANCE_POLL_INTERVAL` | `15s` | pause between poller runs (also the initial delay) |
| `poll-batch-size` | — | `20` | orders per poller transaction (1–500) |
| `auto-reject-enabled` | `ACCEPTANCE_AUTO_REJECT_ENABLED` | `true` | `false` = no poller in this instance (tests) |
| `default-ready-in-minutes` | — | `15` | accept without `readyInMinutes` (5–180) |
| `timezone` | — | `Asia/Kolkata` | the report's days |
Invalid values fail the startup.

### Platform settings (admin, area `grocery-stores`)
`acceptance-timeout` is only the **default**: admins change the acceptance time on **Admin → Platform settings**
(`GET` / `PUT /api/admin/settings/grocery-stores`, field `acceptanceTimeMinutes`, 1–60; read: any admin, save:
SUPER_ADMIN or OPS_ADMIN; contract `docs/api/platform-settings.md`). A saved value is stored in `platform_setting`
(Flyway `V8__platform_settings.sql`, audit rows in `platform_setting_change`) and overrides the default.
- **When it applies:** `StoreOrderProjection` reads the value in force (`settings/GroceryStoreSettingsService`) when an
  order reaches the board, so new orders get the new `acceptBy`; orders already waiting keep theirs.
- **Delay:** each instance re-reads the overrides every 15 s (`grocery.settings.cache-ttl`, env
  `PLATFORM_SETTINGS_CACHE_TTL`); the instance that saved uses them at once.
- **History:** `GET /api/admin/settings/grocery-stores/history?page=&size=` (any admin): the audit rows as a
  `PageResponse`, newest first, `{ field, oldValue, newValue, changedAt, changedBy: { id, name } }`, minutes as in
  `GET`, `null` = the default (contract §5).
- **Tests:** `PlatformSettingsIntegrationTest` (5 minutes without an override, an override used by new orders only,
  reset, 409 `SETTINGS_CHANGED`, validation, roles, audit row, the history: order, values, paging, roles).

## Package layout (the template for stakeholder services)
```
config/       Clock bean (UTC, microsecond ticks = DATETIME(6)), FulfillmentProperties, StoreDirectoryProperties
              (grocery.stores.*), scheduling switch
controller/   VendorController (/api/vendors/me…), AdminVendorController (/api/admin/vendors…),
              InternalVendorController (/internal/vendors/{id}/store), VendorOrderController (/api/vendor/orders…),
              VendorReportController (/api/vendor/reports/sales), StoreOrderParameterAdvice (400 INVALID_PARAMETER),
              VendorSupplierController (/api/vendor/suppliers…), VendorPurchaseOrderController
              (/api/vendor/purchase-orders…), AdminPurchaseOrderController (/api/admin/purchase-orders…),
              StoreDirectoryController (/api/stores/**, public)
dto/          request/response records + validation rules (VendorDtos, StoreOrderDtos, PurchasingDtos,
              StoreDirectoryDtos)
entity/       Vendor (state machine), BusinessAddress, VendorStatus, VendorStatusChange,
              VendorOrder (store order state machine), StoreOrderItem, StoreOrderStatusChange, StoreOrderStatus,
              RejectionReason, ProductRef, ProductStatus, Supplier, PurchaseOrder (purchase order state machine),
              PurchaseOrderLine, PurchaseOrderStatus, PurchaseReceipt, ReceiptLine
event/        VendorEvents, VendorOrderEvents, PurchasingEvents (published payloads), IdentityEventsListener,
              OrderEventsListener, CatalogEventsListener
mapper/       VendorMapper, StoreOrderMapper, PurchasingMapper (plain Java)
repository/   Spring Data JPA (VendorOrderRepository: FOR UPDATE, SKIP LOCKED claim, board and report queries;
              PurchaseOrderRepository / SupplierRepository: FOR UPDATE / FOR SHARE, list queries)
service/      VendorOnboardingService (vendor's own actions), VendorAdminService, VendorLifecycleRecorder,
              VendorStoreService (internal store view), StoreOrderProjection (order.events), StoreOrderService
              (board + transitions + auto-reject batch), StoreOrderEventPublisher, AutoRejectPoller,
              SalesReportService, StoreOrderException, ProductProjectionService (catalog.events), SupplierService,
              PurchaseOrderService, PurchaseReceivingService (Idempotency-Key + GoodsReceived), PurchaseOrderNumbers,
              PurchasingEventPublisher, PurchasingException
settings/     admin platform settings (area grocery-stores): PlatformSettingStore (table + 15 s cache, history),
              GroceryStoreSettingsService, AdminSettingsController, SettingChange (history entry)
```

## AI-approved calls (Phase 11, Phase 12)

Endpoints an assistant can trigger: `POST /api/admin/vendors/{id}/suspend` (SUSPEND_VENDOR, always approved by a
second admin) and (Phase 12, the vendor assistant) `POST /api/vendor/purchase-orders/{id}/send`
(SEND_PURCHASE_ORDER, decided by the vendor); its `draftPurchaseOrder` tool is LOW_RISK and calls
`POST /api/vendor/purchase-orders` without a token (lines may say `quantity` for `orderedQuantity`). Calls that an AI assistant proposed and a human approved arrive with an `X-Approval-Token` (grocery-common 0.8.0,
`grocery.approvals.enabled: true`, keys from `APPROVAL_JWKS_URI`). The filter checks the signature, the exact
method, path and body hash, and that the approver is the caller, then records the approval id in
`consumed_approval` (Flyway V5), so a second use is 409 `APPROVAL_ALREADY_USED`. Requests without the header are
unchanged. Contract: [Phase 11 §5](../../docs/api/phase-11-agentic-ai-and-approvals.md#5-approval-token--verified-by-the-target-grocery-common-080).

## Run / test
```bash
cd infrastructure/local && docker compose --profile core up -d --build vendor-service
# tests (from repo root, Git Bash) — Testcontainers MySQL
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD":/workspace -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/vendor-service/pom.xml clean verify
```
Integration tests run with `MutableClock` (the real clock unless a test pins it) and without the scheduled poller
(`grocery.fulfillment.auto-reject-enabled=false`); they run pollers themselves with a clock ahead of the service's.
Kafka listeners are called directly with records carrying the outbox headers.

| Test class | Covers |
|---|---|
| `VendorLifecycleIntegrationTest` | registration event, profile edit/submit, approval lifecycle with history and events, permissions |
| `InternalStoreIntegrationTest` | store view per status (`active` only when APPROVED), missing address → null location, 404 `VENDOR_NOT_FOUND`, 401 anonymous / any role accepted |
| `OrderEventsProjectionIntegrationTest` | OrderConfirmed → NEW + VendorOrderReceived (exact payload, acceptBy = +5m, owner), RESTAURANT orders ignored, unknown vendor skipped, duplicates (same / another event id), subtotal / note fallback, customer area variants, OrderCancelled from every non-terminal status (no event), terminal and unknown orders untouched, poison events |
| `StoreOrderApiIntegrationTest` | the whole view, every transition with its event payload, defaults and validation (`readyInMinutes`, `reason`, `note`), idempotent repeats, every 409 (`STORE_ORDER_NOT_PENDING` + `currentStatus`, `ACCEPTANCE_EXPIRED` at acceptBy + 1 µs, `INVALID_STORE_ORDER_TRANSITION`), board order / filters / paging, ownership (404), claim / role (403), 401, 400 |
| `PickupIntegrationTest` | Phase 9: OrderOutForDelivery → PICKED_UP (timeline at `pickedUpAt`, no event, off the ACTIVE board, listed by status), every action afterwards 409 with `currentStatus` PICKED_UP, redelivery / second pickup / late cancel are no-ops, missing `pickedUpAt`, from PREPARING, NEW / terminal / unknown / RESTAURANT orders untouched, broken event, the report counts it as accepted and sold |
| `AutoRejectIntegrationTest` | contract defaults, rejection only past the deadline (NO_RESPONSE, auto, no actor), a locked row is skipped not waited for, two pollers reject each order exactly once, races accept vs auto-reject / reject vs OrderCancelled / accept vs OrderCancelled → exactly one outcome, a waiting accept sees the committed change |
| `SalesReportIntegrationTest` | a hand-checkable week (received 9, accepted 5, rejected 2 / auto 1, cancelled 2, rate 0.56, gross 1540.00, items 13, AOV 385.00, daily zeros, top 5 with the sixth cut), the India day boundary (23:59:59.999999 vs 00:00), defaults, other vendors excluded, empty report, range validation (92 ok / 93 no, from > to, bad dates), 403 / 401 |
| `AutoRejectPollerSchedulingTest` | the poller's schedule (duration string, default 15s), the per-instance switch, invalid settings fail the startup |
| `AvroOutboxRoundTripIntegrationTest` | every vendor event incl. the five `VendorOrder*` events and (Phase 12) `GoodsReceived` with schemas 1.7.0: `payload_avro` in the Confluent wire format under subject `vendor.events-com.grocery.events.vendor.<EventType>`, decoding to the JSON `payload` |
| `ProductProjectionIntegrationTest` | Phase 12: ProductUpserted → `product_ref` (newer versions only, stale / equal / redelivered ignored, archived kept), other catalog events ignored, broken snapshots → `InvalidEventException` |
| `SupplierIntegrationTest` | Phase 12: create (whole view, blanks → null), validation (name, lead time 0–90, GSTIN, email, phone, lengths), update (replace, active null keeps), delete = deactivate (204, repeat, filters, inactive → 422 `INVALID_SUPPLIER` on a new PO), 409 `SUPPLIER_IN_USE` with open POs (DRAFT, SENT, PARTIALLY_RECEIVED; not after close / cancel), other vendor → 404, 403 / 401 |
| `PurchaseOrderIntegrationTest` | Phase 12: create (number, lines, totals, 2-decimal money, `expectedAt` date / instant, `quantity` alias), validation (duplicates, quantities, cost decimals, lines, notes), own non-archived products only (422 `INVALID_PRODUCT`), edit only in DRAFT (409 `PURCHASE_ORDER_NOT_EDITABLE`), every transition and every 409 `INVALID_STATUS_TRANSITION` with `currentStatus`, other vendor → 404, list filters (status, OPEN, supplier, number) and paging, admin list / detail (read-only), 403 / 401 / 400 |
| `GoodsReceivingIntegrationTest` | Phase 12: partial then final receipt (PARTIALLY_RECEIVED → RECEIVED, receipts, `GoodsReceived` payload and routing: aggregate PurchaseOrder, key vendorId), Idempotency-Key (same key → same result with `Idempotent-Replayed`, no second receipt or event; other body / PO → 422 `IDEMPOTENCY_KEY_REUSED`; keys per vendor), 6 concurrent retries → one receipt, 422 `RECEIPT_EXCEEDS_ORDERED` (`openQuantity`, product not on the PO) with nothing changed and the key reusable, receive on DRAFT / CANCELLED → 409, key and body validation |
| `PurchaseOrderApprovalIntegrationTest` | Phase 12 §6: SEND_PURCHASE_ORDER with body `{}` and a token for `aud` `vendor-service` (the real auto-configured verifier; JWKS from a local HTTP server) sends once and lands in `consumed_approval`, a second use 409 `APPROVAL_ALREADY_USED`, a token for another audience 403 (`reason` AUDIENCE), a plain `{}` send works |
| `service/PurchaseOrderRulesTest` | the purchase order state machine and receiving arithmetic, the action sets, `PO-` number format, `expectedAt` parsing (no Spring) |
