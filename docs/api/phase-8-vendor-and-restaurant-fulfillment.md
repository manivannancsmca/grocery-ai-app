# Phase 8 API contract — Vendor & restaurant fulfillment

Single source of truth for the fulfillment backend (vendor-service, restaurant-service, order-service,
notification-service) and the React app. Public paths go through the API gateway (`http://localhost:8080`).
Conventions are unchanged from [Phase 2](phase-2-identity-and-stakeholders.md#1-conventions). Events are Avro on the
wire with central schemas (ADR-0011, `libs/grocery-event-schemas` **1.1.0**, already written and registered).

## 1. Who owns what

| Service | Owns in Phase 8 |
|---|---|
| **vendor-service** (8103) | the vendor's view of its GROCERY orders (`vendor_order`), accept / reject / packing / ready, the acceptance deadline and auto-reject, sales reports |
| **restaurant-service** (8104) | the same for RESTAURANT orders (`restaurant_order`), accept with a preparation time, reject, preparing / ready, menu item availability (already exists) |
| **order-service** (8110) | applies the stores' decisions to the order (ACCEPTED → PREPARING → READY_FOR_PICKUP, or CANCELLED and refunded) and narrows customer cancellation |
| **notification-service** (8113) | the new-order alert for the store, live board updates, the customer's "accepted / ready / rejected" notifications |

### The flow (choreography; nobody calls another service to move an order on)
```
order ─ OrderConfirmed ─► vendor-service (GROCERY) / restaurant-service (RESTAURANT): store order NEW, acceptBy = now + timeout
                          └ VendorOrderReceived / RestaurantOrderReceived ─► notification: NEW_ORDER alert + board refresh
store user ─ accept ─► …OrderAccepted ─► order: ACCEPTED + OrderAccepted ─► notification: customer "accepted"
store user ─ preparing ─► …OrderPreparing ─► order: PREPARING + OrderPreparing
store user ─ ready ─► …OrderReadyForPickup ─► order: READY_FOR_PICKUP + OrderReadyForPickup   (delivery: Phase 9)
store user ─ reject / nobody before acceptBy ─► …OrderRejected ─► order: CANCELLED (STORE_REJECTED / STORE_NO_RESPONSE)
                          + OrderCancelled ─► payment refunds in full, inventory releases the stock, notification tells everyone
customer / admin cancels ─► OrderCancelled ─► the store order becomes CANCELLED (leaves the board)
```

**Order statuses now:** `CREATED → AWAITING_PAYMENT → CONFIRMED → ACCEPTED → PREPARING → READY_FOR_PICKUP`; terminal
`REJECTED`, `CANCELLED`. (`OUT_FOR_DELIVERY`, `DELIVERED` arrive in Phase 9.) A store may go from ACCEPTED straight to
READY_FOR_PICKUP (small grocery orders): the order then skips PREPARING.

## 2. Store orders (vendor-service and restaurant-service)

Same API and shapes in both services; only the prefix and the ownership claim differ:

| | Vendor (grocery) | Restaurant |
|---|---|---|
| Prefix | `/api/vendor/orders` | `/api/restaurant/orders` |
| Role / claim | `VENDOR` with `vendor_id` = the order's `storeId` | `RESTAURANT_OWNER` with `restaurant_id` = the order's `storeId` |
| Acceptance timeout | `grocery.fulfillment.acceptance-timeout` (`${ACCEPTANCE_TIMEOUT:5m}`); the default of the admin platform setting `grocery-stores` ([platform-settings.md](platform-settings.md)) | same property, same default; platform setting `restaurants` |
| Default `readyInMinutes` | 15 | 20 |
| "Preparing" wording in the UI | Packing | Preparing |

| Method & path | Body → Response |
|---|---|
| `GET {prefix}?status=&page=&size=` | → `PageResponse<StoreOrderView>`. `status` = a store status or `ACTIVE` (NEW, ACCEPTED, PREPARING, READY_FOR_PICKUP). Order: NEW by `acceptBy` ascending first, then the rest newest first. `size` ≤ 100 |
| `GET {prefix}/{orderId}` | → `StoreOrderView`. Another store's order or unknown → 404 `STORE_ORDER_NOT_FOUND` |
| `POST {prefix}/{orderId}/accept` | `{ "readyInMinutes": 15 }` (optional, 5–180) → `StoreOrderView` (ACCEPTED). Not NEW → 409 `STORE_ORDER_NOT_PENDING` (property `currentStatus`); deadline passed → 409 `ACCEPTANCE_EXPIRED`. Accepting an ACCEPTED order again → 200, unchanged |
| `POST {prefix}/{orderId}/reject` | `{ "reason": "OUT_OF_STOCK" \| "STORE_CLOSED" \| "TOO_BUSY" \| "OTHER", "note": "…" }` (note ≤ 300, required for OTHER) → `StoreOrderView` (REJECTED). Only from NEW (409 `STORE_ORDER_NOT_PENDING`); rejecting again → 200 unchanged |
| `POST {prefix}/{orderId}/preparing` | → (PREPARING) from ACCEPTED; repeat → 200; otherwise 409 `INVALID_STORE_ORDER_TRANSITION` (property `currentStatus`) |
| `POST {prefix}/{orderId}/ready` | → (READY_FOR_PICKUP) from ACCEPTED or PREPARING; repeat → 200; otherwise 409 `INVALID_STORE_ORDER_TRANSITION` |

Store statuses: `NEW` (waiting for acceptance until `acceptBy`) → `ACCEPTED` → `PREPARING` → `READY_FOR_PICKUP`;
terminal `REJECTED` (by the store, or automatically at the deadline), `CANCELLED` (the customer or an admin cancelled).
Conflict errors carry the store order's status as `currentStatus`, never `status`: ProblemDetail already has a
numeric `status`. Every transition locks the store order row first (`SELECT … FOR UPDATE`), writes its history and the outbox event in
the same transaction; actions race safely (accept vs auto-reject vs a customer's cancel: exactly one wins).

```jsonc
// StoreOrderView
{ "orderId": "…", "orderNumber": "GA-7K3F9Q2M", "storeId": "…",
  "status": "NEW",
  "items": [ { "itemId": "…", "name": "Ghee Roast Dosa", "quantity": 2, "unitPrice": 120.00, "lineTotal": 240.00 } ],
  "itemCount": 2, "subtotal": 240.00, "total": 270.00, "currency": "INR",
  "customer": { "name": "Ravi Kumar", "area": "Indiranagar, Bengaluru" },   // recipient name; landmark/line2 or city — no phone, no full address
  "note": "No onions",                                                       // the customer's note, or null
  "receivedAt": "…", "acceptBy": "…",
  "acceptedAt": null, "readyInMinutes": null, "readyBy": null,
  "preparingAt": null, "readyAt": null,
  "rejection": null,      // { "reason": "TOO_BUSY", "note": null, "auto": false, "at": "…" }
  "cancellation": null,   // { "reason": "CUSTOMER_CANCELLED", "by": "CUSTOMER", "at": "…" }
  "timeline": [ { "status": "NEW", "at": "…" } ] }
```

**Auto-reject:** a poller (every `grocery.fulfillment.poll-interval` = `${ACCEPTANCE_POLL_INTERVAL:15s}`,
`SELECT … FOR UPDATE SKIP LOCKED`, small batches, safe with several replicas) rejects NEW orders past `acceptBy` with
reason `NO_RESPONSE`, `auto: true`, no actor.

**Created from** `OrderConfirmed` (group `vendor-service.order-events` / `restaurant-service.order-events`, only the
service's own `orderType`; the store must exist — an unknown store id is logged and skipped). `OrderCancelled` turns any
non-terminal store order into CANCELLED (no event needed: order-service already knows). Duplicates are ignored
(`processed_event`). `subtotal` and `note` come from the new optional OrderConfirmed fields; when absent (older
events) `subtotal` = sum of the line totals and `note` = null.

## 3. Sales reports

`GET /api/vendor/reports/sales?from=&to=` and `GET /api/restaurant/reports/sales?from=&to=` (same claims as §2).
`from`/`to` are dates in India time, inclusive; default the last 7 days including today; at most 92 days
(400 `INVALID_PARAMETER`). Orders are counted on the India-time day they were received.

```jsonc
// SalesReport
{ "from": "2026-11-05", "to": "2026-11-11", "timezone": "Asia/Kolkata", "currency": "INR",
  "ordersReceived": 12,     // every store order created in the range
  "ordersAccepted": 9,      // reached ACCEPTED or later (including later cancelled ones)
  "ordersRejected": 2,      // REJECTED, of which…
  "autoRejected": 1,        // …automatically at the deadline
  "ordersCancelled": 1,     // CANCELLED by the customer or an admin (before or after acceptance)
  "acceptanceRate": 0.75,   // ordersAccepted / ordersReceived, 2 decimals (0 when none)
  "grossSales": 2340.00,    // sum of subtotal of orders accepted and NOT cancelled (delivery fee is not the store's)
  "itemsSold": 31,          // units in those orders
  "averageOrderValue": 292.50,   // grossSales / number of those orders, 2 decimals (0 when none)
  "daily": [ { "date": "2026-11-05", "ordersReceived": 2, "ordersAccepted": 2, "grossSales": 480.00 } ],  // every day of the range, zeros included
  "topItems": [ { "itemId": "…", "name": "Ghee Roast Dosa", "quantity": 8, "sales": 960.00 } ] }       // top 5 by quantity
```

## 4. Orders (order-service changes)

**State guards for the store events** (consumer groups `order-service.vendor-events` on `vendor.events` and
`order-service.restaurant-events` on `restaurant.events`; only the `*Order*` event types; a vendor event for a
RESTAURANT order or a store id that is not the order's store is ignored and logged):

| Event | Applied when the order is | Result |
|---|---|---|
| …OrderAccepted | `CONFIRMED` | `ACCEPTED` + `OrderAccepted` (readyInMinutes, readyBy) |
| …OrderPreparing | `ACCEPTED` | `PREPARING` + `OrderPreparing` |
| …OrderReadyForPickup | `ACCEPTED` or `PREPARING` | `READY_FOR_PICKUP` + `OrderReadyForPickup` |
| …OrderRejected | `CONFIRMED` | `CANCELLED` + `OrderCancelled` with reason `STORE_REJECTED` (by `STORE`, actorUserId, note = "<reason>: <note>") or, when `auto`, `STORE_NO_RESPONSE` (by `SYSTEM`) |
| …OrderReceived | any | ignored (order-service already knows the order) |
| any of them when the order is CANCELLED or already past that step | ignored and logged |

`OrderConfirmed` now also carries `subtotal` and `note` (optional fields, compatible with v1).

**Cancellation rules from Phase 8:**
- **Customer:** while `CREATED`, `AWAITING_PAYMENT` or `CONFIRMED` (until the store accepts). Later → 409
  `ORDER_NOT_CANCELLABLE` with property `currentStatus` (the customer contacts support). `OrderView.cancellable` follows this rule.
- **Admin** (SUPER_ADMIN, OPS_ADMIN, SUPPORT): any non-terminal status up to and including `READY_FOR_PICKUP` →
  `CANCELLED` (`ADMIN_CANCELLED`), full refund, stock released. The admin views get `"adminCancellable": true|false`.
- `OrderView` gains `acceptedAt`, `readyInMinutes`, `readyBy` (null until accepted); the timeline shows ACCEPTED,
  PREPARING and READY_FOR_PICKUP; `cancellation.by` can be `STORE`; cancellation reasons add `STORE_REJECTED` and
  `STORE_NO_RESPONSE`.
- Stats (`/api/admin/stats`) `byStatus` lists the new statuses too.

New events on `order.events` (schemas in `libs/grocery-event-schemas` 1.1.0):
```jsonc
OrderAccepted       { "orderId", "orderNumber", "orderType", "customerId", "storeId", "readyInMinutes", "readyBy", "acceptedAt" }
OrderPreparing      { "orderId", "orderNumber", "orderType", "customerId", "storeId", "preparingAt" }
OrderReadyForPickup { "orderId", "orderNumber", "orderType", "customerId", "storeId", "readyAt" }
```

## 5. Store events (vendor.events / restaurant.events, key orderId)

Outbox aggregate types `VendorOrder` / `RestaurantOrder`. Payloads exactly as the schemas
(`libs/grocery-event-schemas/src/main/avro/vendor.events/VendorOrder*.avsc` and `restaurant.events/RestaurantOrder*.avsc`):
```jsonc
VendorOrderReceived       { "orderId", "orderNumber", "vendorId", "ownerUserId", "itemCount", "total", "acceptBy", "receivedAt" }
VendorOrderAccepted       { "orderId", "orderNumber", "vendorId", "readyInMinutes", "readyBy", "acceptedAt", "actorUserId" }
VendorOrderRejected       { "orderId", "orderNumber", "vendorId", "reason", "note", "auto", "rejectedAt", "actorUserId" }
VendorOrderPreparing      { "orderId", "orderNumber", "vendorId", "preparingAt", "actorUserId" }
VendorOrderReadyForPickup { "orderId", "orderNumber", "vendorId", "readyAt", "actorUserId" }
// RestaurantOrder* : identical with "restaurantId" instead of "vendorId"
```
Existing consumers of these topics (identity, catalog-command, catalog-query) ignore the new types.

## 6. Notifications (notification-service changes; Phase 7 contract §4 still applies otherwise)

| Kafka event | Recipient | `type` · severity | Title · body · link |
|---|---|---|---|
| VendorOrderReceived / RestaurantOrderReceived | store owner (`ownerUserId`) | `NEW_ORDER` · INFO | "New order {no}" · "{itemCount} items · {total} · accept by {HH:mm India time}" · `/vendor/orders` or `/restaurant/orders` — **replaces** the Phase 7 NEW_ORDER sent on OrderConfirmed, so the board already has the order when the alert arrives |
| OrderAccepted | customer | `ORDER_ACCEPTED` · SUCCESS | "{storeName} accepted order {no}" · "Ready in about {readyInMinutes} min." · `/orders/{id}` |
| OrderReadyForPickup | customer | `ORDER_READY` · INFO | "Order {no} is ready" · grocery "Packed and waiting for a delivery partner." · restaurant "Your food is ready and waiting for a delivery partner." · `/orders/{id}` |
| OrderCancelled `STORE_REJECTED` | customer | `ORDER_CANCELLED` · WARNING | "Order {no} cancelled" · "{storeName} could not accept your order ({reason label}{: note}). A full refund is on its way." |
| OrderCancelled `STORE_NO_RESPONSE` | customer | `ORDER_CANCELLED` · WARNING | "Order {no} cancelled" · "The store did not respond in time. A full refund is on its way." |
| …OrderRejected with `auto: true` | store owner | `ORDER_AUTO_REJECTED` · WARNING | "Order {no} was auto-rejected" · "It was not accepted in time. The customer has been refunded." · board link |

Reason labels: OUT_OF_STOCK "items out of stock", STORE_CLOSED "store closed", TOO_BUSY "too busy right now", OTHER "".
`data` carries `orderId`, `orderNumber` (and `acceptBy` on NEW_ORDER).

New signal **`STORE_ORDERS_CHANGED`** `{ orderId, status }` → the store owner, for every store order event
(Received / Accepted / Rejected / Preparing / ReadyForPickup) and for OrderCancelled of an order the store had
(previousStatus CONFIRMED or later): the board refreshes in every open tab. `ORDER_UPDATED` (customer) and
`ADMIN_ORDERS_CHANGED` (admins) are sent for the new order events as for the others. The order projection keeps the
new statuses.

## 7. Health, gateway, compose

- Gateway: `/api/vendor/orders`, `/api/vendor/orders/**`, `/api/vendor/reports/**` → vendor-service (new);
  `/api/restaurant/**` already reaches restaurant-service.
- Compose: `ACCEPTANCE_TIMEOUT` (default 5m) and `ACCEPTANCE_POLL_INTERVAL` (15s) for vendor- and restaurant-service
  (the smoke test uses 30s / 3s).
