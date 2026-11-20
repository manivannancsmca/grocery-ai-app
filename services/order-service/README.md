# order-service

Orders (Phase 6, fulfillment in Phase 8, delivery in Phase 9, event-driven hardening in Phase 10, coupons and returns
in Phase 12): places an order from the customer's **live checkout preview** (cart-service), redeems its coupon at
pricing-service, idempotently per `Idempotency-Key`, and stores a **snapshot** of lines, prices, fee, coupon, totals,
delivery address and the store's pickup location that never changes afterwards. Owns the order status and its
history, the cancellation rules, the order stats and (Phase 12) return requests with their prorated refunds. Orders
move on only through events (choreography): inventory, payment, store (vendor / restaurant) and delivery events in,
order events out through the outbox.

| | |
|---|---|
| Port | 8110 |
| Database | `order_db` (user `order_user`) — tables `customer_order`, `order_item`, `order_status_history`, `order_rejected_item`, `idempotency_key`, `outbox_event`, `processed_event`, (Phase 10) `stuck_order`, `order_event_log`, (Phase 12) `order_return`, `order_return_item`, `platform_setting`, `platform_setting_change` (admin platform settings) |
| Reads | cart-service `GET /api/cart/preview?addressId=` with the customer's own token; (Phase 12) pricing-service `POST /internal/coupons/redeem` with the customer's own token |
| Publishes | `order.events` (aggregate `Order`, key orderId): `OrderCreated`, `OrderAwaitingPayment`, `OrderConfirmed` (+ `subtotal`, `note` since Phase 8), `OrderRejected`, `OrderCancelled`, (Phase 8) `OrderAccepted`, `OrderPreparing`, `OrderReadyForPickup`, (Phase 9) `OrderOutForDelivery`, `OrderDelivered`, `OrderDeliveryFailed`; `OrderConfirmed` v3 carries `storeLocation`; (Phase 10) `OrderStuckDetected`; (Phase 12) `OrderCreated` / `OrderConfirmed` + `couponCode`, `discount`, `deliveryFeeDiscount` (1.7.0: + `couponName`, `couponFundedBy`), and `ReturnRequested`, `ReturnApproved`, `ReturnRejected`, `ReturnEscalated` |
| Consumes | `inventory.events` → `StockReserved`, `StockReservationFailed`, `StockReservationExpired` (group `order-service.inventory-events`); `payment.events` → `PaymentInitiated`, `PaymentCaptured`, `PaymentFailed`, `PaymentExpired`, `RefundCompleted` (with `returnId`: the return becomes REFUNDED; `RefundFailed` is logged) (group `order-service.payment-events`); `vendor.events` → `VendorOrderAccepted`, `VendorOrderPreparing`, `VendorOrderReadyForPickup`, `VendorOrderRejected` (group `order-service.vendor-events`); `restaurant.events` → the same `RestaurantOrder*` types (group `order-service.restaurant-events`); `delivery.events` → `DeliveryPartnerAssigned`, `DeliveryPartnerUnassigned`, `DeliveryPickedUp`, `DeliveryCompleted`, `DeliveryFailed` (group `order-service.delivery-events`, listener id `order-delivery-events`) |
| API | `docs/api/phase-6-orders-and-payments.md` §2, §4 (orders and stats), §6 (events); `docs/api/phase-8-vendor-and-restaurant-fulfillment.md` §4 (store decisions, cancellation rules, new events); `docs/api/phase-9-delivery.md` §6, §7 (delivery); `docs/api/phase-10-event-driven-hardening.md` §4, §5 (stuck orders, event timeline); `docs/api/phase-12-post-mvp-features.md` §2 (coupon at placement), §3 (returns) |
| Debezium | `deploy/debezium-connector.json` (connector `order-outbox`, server id 5110) |

```
CREATED ──StockReserved──► AWAITING_PAYMENT ──PaymentCaptured──► CONFIRMED          (grocery)
CREATED ═════════════════► AWAITING_PAYMENT  (same transaction)                     (restaurant)
CREATED ──StockReservationFailed──► REJECTED (OUT_OF_STOCK)
AWAITING_PAYMENT ──PaymentExpired──► CANCELLED (PAYMENT_TIMEOUT)
AWAITING_PAYMENT / CONFIRMED ──StockReservationExpired──► CANCELLED (RESERVATION_EXPIRED)
CONFIRMED ──…OrderAccepted──► ACCEPTED ──…OrderPreparing──► PREPARING ──…OrderReadyForPickup──► READY_FOR_PICKUP
                              ACCEPTED ──…OrderReadyForPickup──► READY_FOR_PICKUP   (no PREPARING in the timeline)
CONFIRMED ──…OrderRejected──► CANCELLED (STORE_REJECTED by STORE, or STORE_NO_RESPONSE by SYSTEM when auto)
READY_FOR_PICKUP ──DeliveryPickedUp──► OUT_FOR_DELIVERY ──DeliveryCompleted──► DELIVERED
                                       OUT_FOR_DELIVERY ──DeliveryFailed──► DELIVERY_FAILED (reason; an admin decides a refund)
CREATED / AWAITING_PAYMENT / CONFIRMED ──customer cancel──► CANCELLED (CUSTOMER_CANCELLED)
CREATED … READY_FOR_PICKUP ──admin cancel──► CANCELLED (ADMIN_CANCELLED)       (nobody cancels after pickup)
```

## Endpoints
| Method & path | Who | |
|---|---|---|
| `POST /api/orders` | `CUSTOMER` | header `Idempotency-Key` · `{ addressId?, expectedTotal, note? }` → **201** `OrderView` (`Idempotent-Replayed: true` on a retry) |
| `GET /api/orders?status=&page=&size=` | `CUSTOMER` | `PageResponse<OrderSummary>`, newest first; `status` = a status or `OPEN` (CREATED + AWAITING_PAYMENT) |
| `GET /api/orders/{id}` | `CUSTOMER` | `OrderView`; another customer's → 404 `ORDER_NOT_FOUND` |
| `POST /api/orders/{id}/cancel` | `CUSTOMER` | `{ reason? }` (≤ 300) → `OrderView` |
| `GET /api/admin/orders?status=&type=&customerId=&storeId=&q=&from=&to=&page=&size=` | any admin role | `PageResponse<AdminOrderSummary>` (`customerId` + `OrderSummary`), newest first |
| `GET /api/admin/orders/{id}` | any admin role | `customerId` + `OrderView` |
| `POST /api/admin/orders/{id}/cancel` | `SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT` | `{ reason }` (required, ≤ 300) → `customerId` + `OrderView` |
| `GET /api/admin/stats?date=` | any admin role | `OrderStats` for one day in India time (default today) |
| `GET /api/admin/orders/stuck` | any admin role | Phase 10: `[ { orderId, orderNumber, type, status, reason, since, detectedAt, minutesOverdue, store: {id, name}, customerId } ]`, longest overdue first |
| `GET /api/admin/orders/{id}/events` | any admin role | Phase 10: the order event timeline `[ { eventId, eventType, sourceTopic, direction, outcome, detail, occurredAt, recordedAt, correlationId } ]`, oldest first; unknown order → 404 `ORDER_NOT_FOUND` |
| `POST /api/orders/{id}/returns` | `CUSTOMER` (own order) | Phase 12: `{ items: [ { itemId, quantity } ], reason: DAMAGED\|WRONG_ITEM\|MISSING\|QUALITY\|OTHER, note? (≤ 500) }` → **201** `ReturnView` · 422 `RETURN_NOT_ALLOWED` · 422 `RETURN_QUANTITY_EXCEEDED` |
| `GET /api/orders/{id}/returns` · `GET /api/returns?page=&size=` | `CUSTOMER` | the order's returns (oldest first) / the customer's returns (`PageResponse<ReturnView>`, newest first) |
| `GET /api/store/returns?status=&page=&size=` | `VENDOR`, `RESTAURANT_OWNER` | the caller's store's returns (`vendor_id` / `restaurant_id` claim), newest first |
| `POST /api/store/returns/{id}/approve` · `/reject` | `VENDOR`, `RESTAURANT_OWNER` | `{ restock? }` (forced false for a restaurant) / `{ comment }` (≤ 300) → `ReturnView` · 404 `RETURN_NOT_FOUND` (another store's) · 409 `RETURN_NOT_PENDING` (`currentStatus`; only REQUESTED) |
| `GET /api/admin/returns?status=&storeId=&page=&size=` | any admin role | `PageResponse<ReturnView>`, newest first, default REQUESTED + ESCALATED |
| `POST /api/admin/returns/{id}/approve` · `/reject` | `SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT` | same bodies → `ReturnView` · 409 `RETURN_NOT_PENDING` (REQUESTED and ESCALATED can be decided) |

- `q` = order number, exact and case-insensitive (surrounding blanks ignored). `from`/`to` = `yyyy-MM-dd` dates in
  `grocery.order.timezone` (Asia/Kolkata), both inclusive; `from` after `to` → 400 `INVALID_PARAMETER`.
- An unknown `status`/`type` value or a parameter of the wrong type (`customerId=abc`, `from=11/11/2026`, an order id
  that is not a UUID) → 400 `INVALID_PARAMETER` (`controller/InvalidParameterAdvice`).
- Stats: `orders`, `byStatus` (current status, every status listed in lifecycle order: CREATED, AWAITING_PAYMENT,
  CONFIRMED, ACCEPTED, PREPARING, READY_FOR_PICKUP, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, REJECTED,
  CANCELLED) and `byType` count the orders **created**
  that day; `confirmedRevenue` sums the totals of the orders **confirmed (paid)** that day (`confirmed_at`, whatever
  their status now); `averageOrderValue` = revenue / confirmed orders, 2 decimals, 0 when none.
- Phase 8 view fields: `OrderView` has `acceptedAt`, `readyInMinutes`, `readyBy` (null until the store accepted;
  written after `payment`, before `cancellable`); `cancellable` = the **customer's** rule. The admin views (detail,
  cancel answer and every list row) start with `customerId`, `adminCancellable` (an admin may cancel it now).
- Phase 9 view fields (written after `rejection`, before `timeline`): `delivery` = `{ partnerName, assignedAt,
  pickedUpAt, deliveredAt }`, null while no partner is recorded (before assignment, or after an unassignment);
  `partnerName` is the partner's **first name only** (as in delivery-service's tracking view; the full name is
  stored), null when the event had none. `trackable` = `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP`,
  `OUT_FOR_DELIVERY`. A `DELIVERY_FAILED` timeline entry carries the failure reason.

## Placing an order (`service/OrderPlacementService.java`, `service/OrderCreator.java`)
1. `Idempotency-Key` first (grocery-common `IdempotencyKeys.requireKey`): missing → 400 `IDEMPOTENCY_KEY_REQUIRED`,
   not 8–100 of `A-Z a-z 0-9 . _ : -` → 400 `IDEMPOTENCY_KEY_INVALID`. Then the body (400 `VALIDATION_FAILED`).
2. **Fast look-up** (`IdempotencyKeys.find`, scope `order:<customerId>`, request hash = SHA-256 of `{addressId,
   expectedTotal (2 decimals), note (trimmed)}`): a key already used with the same body → **201 with that order** and
   `Idempotent-Replayed: true` — nothing is created or re-checked; with another body → 422 `IDEMPOTENCY_KEY_REUSED`.
3. The live preview from cart-service, **outside any database transaction** (no connection or lock is held while
   cart prices the cart). Blocking issue → 409 `CHECKOUT_BLOCKED` with `issues` (the preview's `Issue[]` as received);
   address not the caller's → 404 `ADDRESS_NOT_FOUND`; cart unreachable, slow, 5xx, an unexpected 404, an open
   breaker, or a preview without the data an order needs → 503 `DEPENDENCY_UNAVAILABLE` with `dependency` (`cart`, or
   the service cart itself reported as unreachable, e.g. `customer`).
4. `expectedTotal` ≠ live `total` → 409 `TOTAL_CHANGED` with `expectedTotal`, `currentTotal`.
5. **One transaction**: `IdempotencyKeys.claim` with a pre-generated order id (UUIDv7) and order number, then the
   order, its lines, its history and `OrderCreated` (+ for a restaurant order `AWAITING_PAYMENT` and
   `OrderAwaitingPayment`). Two requests with the same key that both got past step 2 meet at the claim (primary key
   of `idempotency_key`): the second waits until the first commits and answers with its order (replayed). A failed
   attempt (any error above) stores nothing, so the client may retry with the same key.

Order numbers (`service/OrderNumbers.java`): `GA-` + 8 Crockford base32 characters from 40 random bits, unique key
`uk_customer_order_number`; a collision rolls the transaction back and is retried with a new number and id (5 tries).
The payment window `grocery.order.payment-window` (default 15m, env `ORDER_PAYMENT_WINDOW`) gives `payment.dueAt` =
creation + window, also sent as `OrderAwaitingPayment.expiresAt`. The lists (`OrderSummary`, `AdminOrderSummary`)
carry it as `payment: { status, dueAt }`, `dueAt` only while AWAITING_PAYMENT (null otherwise), so the customer's
list can say "Pay within 12 min" exactly; it is a column of the order row (no extra query per row).

## Phase 12: the coupon at placement (`docs/api/phase-12-post-mvp-features.md` §2)
- The preview (cart-service) carries `coupon: { code, valid, discount, deliveryFeeDiscount, reason?, message? }`,
  `discount`, `deliveryFeeDiscount` and the discounted `total`; read tolerantly (`client/CartPreview`): no `coupon` (or
  an older cart) = no coupon. A coupon the cart reports `valid: false` comes with the blocking issue
  `COUPON_NOT_APPLICABLE` → 409 `CHECKOUT_BLOCKED` as for any blocking issue (should it come without, 409
  `COUPON_NOT_APPLICABLE` with the cart's `reason`).
- `expectedTotal` is compared with the **discounted** total (409 `TOTAL_CHANGED` before anything is redeemed).
- Then, after every check and outside any transaction, `POST /internal/coupons/redeem` (`client/PricingClient`, own
  circuit breaker `pricing`, the customer's token, the shared timeouts) with `{ code, orderId, customerId, storeId,
  orderType, subtotal, deliveryFee }` — `orderId` is the id the order is then saved with (also across an order-number
  retry), `deliveryFee` the fee before any discount. Answers: 200 `{ redemptionId, discount, deliveryFeeDiscount,
  couponName, fundedBy }`;
  409 (or 422) with `reason` (or a `COUPON_*` `errorCode`) and maybe `minOrderSubtotal` → **409 `COUPON_NOT_APPLICABLE`**
  with `reason` (+ `minOrderSubtotal`), no order; anything else (unreachable, 5xx, other 4xx, open breaker, discounts
  outside the subtotal / fee) → 503 `DEPENDENCY_UNAVAILABLE` (`dependency: pricing`), no order.
- The redeemed amounts are the order's: `total = subtotal + deliveryFee − discount − deliveryFeeDiscount`; when that is
  not the `expectedTotal` (the coupon changed since the preview) → 409 `TOTAL_CHANGED`.
- A redemption whose order is never saved (a failure after redeeming, a concurrent duplicate that lost the key) stays
  RESERVED at pricing-service until its 30-minute sweeper releases it. A replayed request (same key and body) never
  redeems again.
- Snapshot columns (Flyway `V8`): `coupon_code`, `coupon_redemption_id`, `discount`, `delivery_fee_discount` (0.00
  without a coupon). `OrderView` (between `deliveryFee` and `total`), `OrderSummary` and the admin views gain
  `couponCode` (null without), `discount`, `deliveryFeeDiscount`; `OrderCreated` / `OrderConfirmed` carry the three
  (all null without a coupon). The payment amount (`OrderAwaitingPayment.amount`) is the discounted total.
- **The coupon's name and funding** (schemas 1.7.0, Flyway `V11__coupon_name_and_funding.sql`): `coupon_name` and
  `coupon_funded_by` (`PLATFORM` | `STORE`, the coupon's scope) as the redeem answer gave them, published as
  `couponName` / `couponFundedBy` in `OrderCreated` and `OrderConfirmed` so reporting-service can name coupons and
  split their discounts by who funds them. They are only labels: a missing name or an unknown funder (an older
  pricing-service) is stored as null and the order is placed anyway. Not in the order views. Orders placed before V11
  keep both null (nothing is backfilled).

## Phase 12: returns (`docs/api/phase-12-post-mvp-features.md` §3)
`service/ReturnService.java` (request, decisions, REFUNDED), `service/ReturnRules.java` (the rules and the math),
`service/ReturnQueryService.java`, `service/ReturnEscalationPoller.java`, `entity/OrderReturn.java`.

```
REQUESTED ──store / admin approve──► APPROVED ──RefundCompleted(returnId)──► REFUNDED
REQUESTED ──store / admin reject──► REJECTED (its quantities can be asked for again)
REQUESTED ──decideBy passed (poller)──► ESCALATED ──admin approve / reject──► APPROVED / REJECTED
```
- **Who may ask** (`OrderView.returnable`): the customer of a `DELIVERED` order until `deliveredAt +
  ORDER_RETURN_WINDOW` (48h), while no request of the order is open (REQUESTED / ESCALATED), something is left and
  the payment is not fully `REFUNDED`; otherwise 422 `RETURN_NOT_ALLOWED` (with `currentStatus`, `returnableUntil`,
  `openReturnNumber` or `paymentStatus` as applicable). Restaurant orders use the same flow (never restocked).
- **Quantities**: per line, ordered − every request that is not REJECTED; more (or an item not on the order) → 422
  `RETURN_QUANTITY_EXCEEDED` with `items: [ { itemId, requested, returnable } ]`. The same item twice is added up.
- **Refund** (server-side only): per line `lineTotal × quantity × (subtotal − discount) / (orderedQuantity ×
  subtotal)`, one exact fraction rounded once to 0.01 half up; when the request returns **every remaining unit**, the
  paid delivery fee `deliveryFee − deliveryFeeDiscount` is added (`delivery_fee_refund`). `refundAmount` = lines + fee.
- `decideBy = requestedAt + RETURN_DECISION_TIMEOUT` (24h). The **escalation poller** (every
  `RETURN_ESCALATION_INTERVAL`, 1m, fixed delay; `grocery.order.returns.escalation-enabled`) claims overdue REQUESTED
  rows with `FOR UPDATE SKIP LOCKED` (batches of 100), escalates them and publishes `ReturnEscalated`; safe with
  replicas. Returns do not affect the stuck-order watchdog (a delivered order is terminal).
- **Store** (`StoreRef`: VENDOR → GROCERY + `vendor_id`, RESTAURANT_OWNER → RESTAURANT + `restaurant_id`; no claim →
  403 `VENDOR_NOT_ACTIVE` / `RESTAURANT_NOT_ACTIVE`): decides its own REQUESTED returns only. **Admins** decide
  REQUESTED or ESCALATED ones. Approve: `restock` (absent = false; forced false for RESTAURANT) → `ReturnApproved`
  (payment-service refunds `refundAmount`, reason RETURN; inventory restocks when `restock`). Reject: `comment`
  (required ≤ 300) → `ReturnRejected`.
- **REFUNDED**: `RefundCompleted` with `returnId` (order-service's payment consumer) marks an APPROVED return REFUNDED
  (`refundedAt`) in the event's transaction, besides the order's refund projection; the timeline entry's detail is
  `return RT-… APPROVED → REFUNDED`. `RefundFailed` is logged; the return stays APPROVED (an admin refunds by hand).
- **Locking**: request and decisions lock the order row first, then the return row; the poller locks only return rows
  (`SKIP LOCKED`), so it never deadlocks with a decision. DB guards: unique `return_number` (`RT-` + 8 Crockford
  characters, retried on a collision) and a unique generated `open_order_id` (one open request per order).
- Events (schemas 1.5.0, key orderId, also PUBLISHED entries of the order's event timeline): `ReturnRequested`,
  `ReturnApproved`, `ReturnRejected`, `ReturnEscalated`.
- `OrderView` gains `returnable` and `returns: [ { id, returnNumber, status, reason, refundAmount, requestedAt,
  items: [ { itemId, quantity } ] } ]`
  (oldest first), written after `timeline`.
- Flyway `V8__coupons_and_returns.sql`: the coupon columns, `order_return`, `order_return_item`.

## Events in (`service/OrderEventService.java`, `event/*Listener.java`)
| Event | Applied when the order is | Result |
|---|---|---|
| `StockReserved` | `CREATED` | `AWAITING_PAYMENT` (payment `PENDING`) + `OrderAwaitingPayment` |
| `StockReservationFailed` | `CREATED` | `REJECTED` (`OUT_OF_STOCK`, unavailable items with the line names; payment `NOT_REQUIRED`) + `OrderRejected` |
| `StockReservationExpired` | `AWAITING_PAYMENT`, `CONFIRMED` | `CANCELLED` (`RESERVATION_EXPIRED`, by `SYSTEM`) + `OrderCancelled` |
| `PaymentInitiated` | any | payment `PENDING` while it is `NOT_STARTED`/`PENDING` (never over `PAID`, `NOT_REQUIRED`, …) |
| `PaymentCaptured` | `AWAITING_PAYMENT` | `CONFIRMED` + `OrderConfirmed`, payment `PAID`, `paidAt` = `capturedAt` |
| 〃 | `CANCELLED` | payment `REFUND_PENDING` only (payment-service refunds the late capture) |
| `PaymentFailed` | `AWAITING_PAYMENT` | `lastFailure` = `{code, description, at}`; status unchanged |
| `PaymentExpired` | `AWAITING_PAYMENT` | `CANCELLED` (`PAYMENT_TIMEOUT`, by `SYSTEM`) + `OrderCancelled`, payment `EXPIRED` |
| `RefundCompleted` | any | `refundedAmount` = `totalRefunded` (a lower, stale total is ignored); payment `REFUNDED` / `PARTIALLY_REFUNDED`; with `returnId` (Phase 12) the APPROVED return becomes `REFUNDED` |
| `…OrderAccepted` | `CONFIRMED` | `ACCEPTED` (`acceptedAt`, `readyInMinutes`, `readyBy`) + `OrderAccepted` |
| `…OrderPreparing` | `ACCEPTED` | `PREPARING` (`preparingAt`) + `OrderPreparing` |
| `…OrderReadyForPickup` | `ACCEPTED`, `PREPARING` | `READY_FOR_PICKUP` (`readyAt`) + `OrderReadyForPickup` |
| `…OrderRejected` | `CONFIRMED` | `CANCELLED` + `OrderCancelled`: `STORE_REJECTED` by `STORE` (actor = the store user, note = `"<reason>: <note>"` or just the reason), or with `auto: true` `STORE_NO_RESPONSE` by `SYSTEM` (no actor, no note); payment `REFUND_PENDING` (full refund) |
| `…OrderReceived` | any | skipped (order-service already knows the order) |
| `DeliveryPartnerAssigned` | `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP` | records `partnerId`, `partnerName`, `assignedAt` — no status change, no event |
| `DeliveryPartnerUnassigned` | same | clears the partner (only if it is the one recorded: a stale unassignment never removes a newer partner) |
| `DeliveryPickedUp` | `READY_FOR_PICKUP` | `OUT_FOR_DELIVERY` (`pickedUpAt`) + `OrderOutForDelivery` (`partnerId` = the pickup's partner) |
| `DeliveryCompleted` | `OUT_FOR_DELIVERY` | `DELIVERED` (`deliveredAt`) + `OrderDelivered`; payment stays `PAID` |
| `DeliveryFailed` | `OUT_FOR_DELIVERY` | `DELIVERY_FAILED` (reason, note ≤ 300; the timeline entry's reason) + `OrderDeliveryFailed`; payment stays `PAID` (an admin decides on a refund) |

Delivery events (`event/DeliveryEventsListener.java`): a pickup / completion / failure whose partner differs from the
recorded one (a missed assignment) records the event's partner without a name. Times are delivery-service's own
(truncated to µs; missing → now). Every other type on `delivery.events` — the partner lifecycle (keyed by partnerId),
`DeliveryOfferCreated` / `DeliveryOfferClosed`, `DeliveryAssignmentFailed`, `DeliveryCancelled` — is skipped without
touching the database. A broken delivery event (no `orderId`, no `partnerId`, a failure without `reason`) → the DLT.

`…` = `Vendor` on `vendor.events` (GROCERY orders, store id in `vendorId`) or `Restaurant` on `restaurant.events`
(RESTAURANT orders, `restaurantId`), `event/StoreOrderEvents.java`. A store event for an order of the other type, or
whose store id is not the order's store, is marked processed, ignored and logged (warn). Times: `acceptedAt`,
`readyBy`, `preparingAt`, `readyAt` are the store's own (truncated to µs; missing → now, `readyBy` missing →
`acceptedAt + readyInMinutes`); the timeline entries use order-service's clock. The store lifecycle events on the two
topics are skipped without touching the database.

Every other combination is ignored and logged. Each event runs in **one transaction**: `processed_event` marker
(duplicates stop here) → lock the order → guard → change + history + outbox event. Unknown orders are marked and
ignored. Other event types on the topics (`StockReleased`, Phase 4 stock events, store lifecycle events, …) are
skipped without touching the database. A broken event (no headers, unreadable payload, no `orderId`, no store id, an
accepted event without `readyInMinutes`, a rejected one without `reason`) is `InvalidEventException` → the DLT.
Infrastructure failures (lock wait timeout, deadlock, database down) propagate unchanged, so grocery-common's error
handler (`TransientFailures`) redelivers the same record (up to 30 min) instead of dead-lettering it.

## Cancellation (`service/OrderCancellationService.java`)
- **Customer** (`OrderStatus.isCancellable`, = `OrderView.cancellable`): `CREATED`, `AWAITING_PAYMENT`, `CONFIRMED`
  — until the store accepts. `ACCEPTED`, `PREPARING`, `READY_FOR_PICKUP` → 409 `ORDER_NOT_CANCELLABLE` (the customer
  contacts support).
- **Admin** (`SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT`; `OrderStatus.isAdminCancellable`, = `adminCancellable`): any
  non-terminal status up to and including `READY_FOR_PICKUP`.
- **Nobody after pickup** (Phase 9): `OUT_FOR_DELIVERY`, `DELIVERED`, `DELIVERY_FAILED` → 409
  `ORDER_NOT_CANCELLABLE` with `currentStatus`, for the customer and every admin. A failed delivery is settled with an
  admin refund (payment-service), not a cancellation.

Either way: `CANCELLED` + `OrderCancelled` (`previousStatus`, `reason`, `cancelledBy`, `actorUserId`, `note`). A paid
order's payment becomes `REFUND_PENDING` (payment-service refunds in full, inventory releases the stock, the store
order leaves the board), an unpaid one's `NOT_REQUIRED`. Already `CANCELLED` (by anyone, the store included) → the
cancelled order, unchanged, no second event. `REJECTED` → 409 `ORDER_NOT_CANCELLABLE`. The customer's `reason` and the
admin's `reason` are stored as `cancellation.note` (column widened to 320 for the store's `"<reason>: <note>"`); the
cancellation reason is `CUSTOMER_CANCELLED` / `ADMIN_CANCELLED` (`STORE_REJECTED` / `STORE_NO_RESPONSE` come from the
store events; `cancellation.by` can be `STORE`).

## Phase 10: stuck orders and the event timeline (`docs/api/phase-10-event-driven-hardening.md` §4, §5)

**Stuck-order watchdog** (`service/StuckOrderWatchdog.java`, rules in `service/StuckRule.java`, flags in
`repository/StuckOrders.java`), every `grocery.order.watchdog.interval` (env `ORDER_WATCHDOG_INTERVAL`, 1m, fixed delay):

| Status | Stuck when (env, default) | Reason |
|---|---|---|
| `CREATED` | created more than `ORDER_STUCK_CREATED_AFTER` (2m) ago | `STOCK_CHECK_OVERDUE` |
| `AWAITING_PAYMENT` | `payment.dueAt` + `ORDER_STUCK_PAYMENT_GRACE` (2m) passed | `PAYMENT_EXPIRY_OVERDUE` |
| `CONFIRMED` | confirmed more than `ORDER_STUCK_CONFIRMED_AFTER` (15m) ago | `STORE_RESPONSE_OVERDUE` |
| `ACCEPTED`, `PREPARING` | `readyBy` + `ORDER_STUCK_PREPARATION_GRACE` (30m) passed | `PREPARATION_OVERDUE` |
| `READY_FOR_PICKUP` | ready more than `ORDER_STUCK_PICKUP_AFTER` (30m) ago | `PICKUP_OVERDUE` |
| `OUT_FOR_DELIVERY` | picked up more than `ORDER_STUCK_DELIVERY_AFTER` (90m) ago | `DELIVERY_OVERDUE` |

- One run: resolve the open flags whose order left the flagged status; then, per status, a plain read of the orders
  past the threshold without a flag for that status (batches of `grocery.order.watchdog.batch-size`, 100, longest
  overdue first) and, per order, one short transaction: **lock the order** (`OrderLocks`), re-check the rule on the
  locked row, `INSERT IGNORE` the `stuck_order` flag (primary key order + status) and — only when the flag is new —
  publish `OrderStuckDetected` (`{orderId, orderNumber, orderType, customerId, storeId, status, reason, since,
  detectedAt}`, schema 1.3.0). Safe with replicas: exactly one run flags an order in a status (first detection only);
  a later status gets its own flag and event. The order itself does not change.
- `since` = when the order entered the status (its timeline entry); the flag keeps the threshold as `due_at`
  (`minutesOverdue` = whole minutes past it, the list is sorted by it).
- A flag **resolves itself** (`resolved_at`) in the transaction of the order's next status change (every consumed
  event that changes the status, every cancellation) and, as a safety net, in the next watchdog run.
- `grocery.order.watchdog.enabled=false` switches the schedule off (the tests run the watchdog themselves).

**Order event timeline** (`repository/OrderEventLog.java`, table `order_event_log`): one row per event order-service
**publishes** for an order (`OrderEventPublisher`, i.e. every outbox event: `PUBLISHED` / `APPLIED`, the outbox event
id, `occurredAt` = `recordedAt` = order-service's time, the correlation id the outbox row gets) and per event it
**consumes** for an order — all five consumers, through `OrderEventService` (`CONSUMED`, the event's id, type and
topic, `occurredAt` from the record's `occurredAt` header, the header's correlation id or else the event id):
`APPLIED` (`detail` = `"<before> → <after>"` when the status changed), `IGNORED` (rejected by a state guard; `detail`
= the guard's reason, e.g. `order is CONFIRMED, not CREATED`, `not an order of GROCERY store <id>`,
`totalRefunded 2.00 is below the recorded 5.00`) or `DUPLICATE` (`already processed`). Always written in the
processing transaction (a failed processing leaves no row; its redelivery writes it once); a consumed row is written
before the change, so the events its processing publishes follow it (`ORDER BY recorded_at, id`). Events for unknown
orders and the event types the listeners skip are not recorded.

Flyway `V6__stuck_orders_and_event_log.sql`: `stuck_order`, `order_event_log`.

## Locking rule (`service/OrderLocks.java`)
Every change of an existing order — each consumed event, the customer's and the admin's cancel — runs in one
transaction that **first** takes `SELECT … FOR UPDATE` on the `customer_order` row, then checks the state guard on the
locked row, applies the transition (which appends the history row) and writes the outbox event; commit releases the
lock. Two changes of one order are applied one after the other, each seeing the other's result: a cancel racing a
capture always ends `CANCELLED` with the payment `REFUND_PENDING` and exactly one `OrderCancelled`. Lock-wait
timeouts and deadlock victims become 409 `CONCURRENT_MODIFICATION` (grocery-common) or a Kafka retry. Reads take no
locks.

## Events: Avro (ADR-0011)
- `order.events` payloads are **Avro on the wire**, validated against the central schemas of
  `libs/grocery-event-schemas` **1.7.0** (Phase 10: `OrderStuckDetected`; Phase 12: the coupon fields, the return events; 1.7.0: the coupon's name and funding) (`avro/order.events/<EventType>.avsc`, subject
  `order.events-com.grocery.events.order.<EventType>` in the Schema Registry). grocery-common 0.6.0's outbox writes
  the same payload twice in the business transaction: the JSON `payload` column (kept for audit, debugging and the
  rollback path) and `payload_avro` (Confluent wire format, Flyway `V3__outbox_avro_payload.sql`), which the connector
  publishes byte for byte (`payload_avro`, `BinaryDataConverter`). A payload that does not match its schema fails the
  business transaction instead of publishing a bad event. The payload records are in `event/OrderEvents.java`
  (Phase 8: `OrderConfirmed` + the optional `subtotal`, `note`; new `OrderAccepted`, `OrderPreparing`,
  `OrderReadyForPickup`; `OrderCancelled` with the new reasons, actor `STORE` and previous statuses; Phase 9: new
  `OrderOutForDelivery`, `OrderDelivered`, `OrderDeliveryFailed` (with `partnerId`), and `OrderConfirmed` v3 with the
  optional `storeLocation { name, city, latitude, longitude }` — sent only when the snapshot has both coordinates,
  otherwise `null`, so delivery-service falls back to the drop point).
- Consumed events (`inventory.events`, `payment.events`, `vendor.events`, `restaurant.events`, `delivery.events`) are
  decoded from Avro (or legacy JSON) back to the same JSON before the listeners see them.
- Flyway `V4__store_fulfillment.sql`: `accepted_at`, `ready_in_minutes`, `ready_by`, `preparing_at`, `ready_at`;
  `cancellation_note` VARCHAR(320). The new statuses / reasons / actor fit the existing VARCHAR columns.
- Flyway `V5__delivery.sql`: the store snapshot `store_city`, `store_latitude`, `store_longitude` (from the checkout
  preview's `store`, null when unknown; a half-known location is stored as none), and the delivery `partner_id`,
  `partner_name`, `partner_assigned_at`, `picked_up_at`, `delivered_at`, `delivery_failed_at`,
  `delivery_failure_reason`, `delivery_failure_note`.
- Registry: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (property `grocery.events.avro.schema-registry-url`,
  default `http://localhost:8081`; compose sets `http://schema-registry:8081`). The service never registers schemas.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only, as before; re-point the connector to `payload` first).

## Package layout
```
client/      CartClient (the preview), PricingClient (coupon redemption), Downstream (timeouts, circuit breaker,
             Outcome), CartPreview (own tolerant DTO), CallContext (caller's token + correlation id), Dependency
config/      OrderProperties (grocery.order.*), OrderConfig (Clock µs = DATETIME(6), circuit breaker defaults)
controller/  OrderController (/api/orders, …/{id}/returns), AdminOrderController (/api/admin/orders, …/stuck,
             …/{id}/events), AdminStatsController (/api/admin/stats), CustomerReturnController (/api/returns),
             StoreReturnController (/api/store/returns), AdminReturnController (/api/admin/returns),
             InvalidParameterAdvice
dto/         OrderDtos (requests, OrderView, OrderSummary, admin views, OrderStats), OrderMapper, ReturnDtos,
             ReturnMapper
entity/      CustomerOrder (status and payment rules) + OrderItem, StatusChange, RejectedItem, DeliveryAddress,
             OrderReturn + ReturnItem, enums
event/       OrderEvents (published payloads), InventoryEventsListener, PaymentEventsListener, VendorEventsListener,
             RestaurantEventsListener (Kafka consumers), StoreOrderEvents (the shared store-event reader)
repository/  CustomerOrderRepository (FOR UPDATE lock, stats queries), OrderSpecifications (list filters),
             StuckOrders (stuck_order), OrderEventLog (order_event_log), OrderReturnRepository (SKIP LOCKED claim),
             ReturnSpecifications
service/     OrderPlacementService, OrderCreator, OrderEventService (state guards), OrderCancellationService,
             OrderQueryService, OrderLocks, OrderEventPublisher, OrderNumbers, OrderException, EventSource,
             StuckOrderWatchdog, StuckRule, ReturnService, ReturnRules, ReturnQueryService, ReturnEscalationPoller,
             StoreRef
settings/    admin platform settings (area returns): PlatformSettingStore (table + 15 s cache, history),
             ReturnSettingsService, AdminSettingsController, SettingChange (history entry)
```

## Configuration (`grocery.order.*`)
| Property | Default | |
|---|---|---|
| `payment-window` | `15m` (env `ORDER_PAYMENT_WINDOW`) | `payment.dueAt` / `OrderAwaitingPayment.expiresAt` |
| `timezone` | `Asia/Kolkata` | day boundaries of the stats and the admin `from`/`to` filter |
| `services.cart` | `http://localhost:8109` (env `CART_SERVICE_URL`; profile `docker`: `http://cart-service:8109`) | |
| `services.pricing` | `http://localhost:8108` (env `PRICING_SERVICE_URL`; profile `docker`: `http://pricing-service:8108`) | Phase 12: coupon redemption |
| `returns.window` | `48h` (env `ORDER_RETURN_WINDOW`) | Phase 12: a delivered order can be returned until `deliveredAt` + this |
| `returns.decision-timeout` | `24h` (env `RETURN_DECISION_TIMEOUT`) | the store decides by `requestedAt` + this, then the poller escalates |
| `returns.escalation-interval` | `1m` (env `RETURN_ESCALATION_INTERVAL`) | escalation poller pause (fixed delay); also `returns.escalation-enabled` (true), `returns.batch-size` (100) |
| `http.connect-timeout` / `http.read-timeout` | `1s` / `5s` | the preview itself calls up to six services |
| `circuit-breaker.*` | window 20, ≥ 10 calls, opens at 50 % for 10 s | only 5xx, timeouts and I/O errors count |
| `watchdog.interval` | `1m` (env `ORDER_WATCHDOG_INTERVAL`) | Phase 10 stuck-order watchdog; also `watchdog.enabled` (true), `watchdog.batch-size` (100) |
| `watchdog.created-after`, `payment-grace`, `confirmed-after`, `preparation-grace`, `pickup-after`, `delivery-after` | `2m`, `2m`, `15m`, `30m`, `30m`, `90m` (env `ORDER_STUCK_CREATED_AFTER`, `ORDER_STUCK_PAYMENT_GRACE`, `ORDER_STUCK_CONFIRMED_AFTER`, `ORDER_STUCK_PREPARATION_GRACE`, `ORDER_STUCK_PICKUP_AFTER`, `ORDER_STUCK_DELIVERY_AFTER`) | the thresholds of the watchdog table |

### Platform settings (admin, area `returns`)
`returns.window` and `returns.decision-timeout` are only the **defaults**: admins change them on **Admin → Platform
settings** (`GET` / `PUT /api/admin/settings/returns`, fields `returnWindowHours` 1–720 and `storeDecisionHours`
1–168; read: any admin, save: SUPER_ADMIN or OPS_ADMIN; contract `docs/api/platform-settings.md`). A saved value is
stored in `platform_setting` (Flyway `V10__platform_settings.sql`, audit rows in `platform_setting_change`) and
overrides the default.
- **When it applies:** `ReturnService` and `ReturnQueryService` read the values in force
  (`settings/ReturnSettingsService`) at each request and order view. The window counts from delivery, so a new window
  applies to every delivered order at once (`returnable`, `RETURN_NOT_ALLOWED`); a new decision time applies to returns
  requested from then on, open returns keep their `decideBy`.
- **Delay:** each instance re-reads the overrides every 15 s (`grocery.settings.cache-ttl`, env
  `PLATFORM_SETTINGS_CACHE_TTL`); the instance that saved uses them at once.
- **History:** `GET /api/admin/settings/returns/history?page=&size=` (any admin): the audit rows as a `PageResponse`,
  newest first, `{ field, oldValue, newValue, changedAt, changedBy: { id, name } }`, hours as in `GET`, `null` = the
  default (contract §5).
- **Tests:** `PlatformSettingsIntegrationTest` (48 h / 24 h without an override, an override used by the next request
  while an open return keeps its deadline, reset, 409 `SETTINGS_CHANGED`, validation, roles, audit rows, the history:
  order, values, paging, roles).

## AI-approved calls (Phase 11)

Endpoints an assistant can trigger: `POST /api/orders` (PLACE_ORDER), `POST /api/orders/{id}/cancel` (CANCEL_ORDER) and `POST /api/admin/orders/{id}/cancel` (ADMIN_CANCEL_ORDER). `PlaceOrderRequest` also takes an optional `expectedCartUpdatedAt`: when the cart changed since the preview the customer approved, the answer is 409 `CART_CHANGED` (checked before `TOTAL_CHANGED`). Calls that an AI assistant proposed and a human approved arrive with an `X-Approval-Token` (grocery-common 0.8.0,
`grocery.approvals.enabled: true`, keys from `APPROVAL_JWKS_URI`). The filter checks the signature, the exact
method, path and body hash, and that the approver is the caller, then records the approval id in
`consumed_approval` (Flyway V7), so a second use is 409 `APPROVAL_ALREADY_USED`. Requests without the header are
unchanged. Contract: [Phase 11 §5](../../docs/api/phase-11-agentic-ai-and-approvals.md#5-approval-token--verified-by-the-target-grocery-common-080).

## Run / test
```bash
cd infrastructure/local && docker compose --profile orders up -d --build order-service
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4 + in-process stubs of cart-service and pricing-service; Avro against
# Confluent's in-memory registry (grocery.events.avro.schema-registry-url=mock://order-service)
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/order-service/pom.xml clean verify
```
| Test class | Covers |
|---|---|
| `PlaceOrderIntegrationTest` | grocery (CREATED) and restaurant (AWAITING_PAYMENT) orders: snapshot, view fields, outbox payloads, forwarded token, explicit address, validation, roles |
| `IdempotencyIntegrationTest` | sequential replay, 8 parallel requests with one key → one order, other body → 422, missing / invalid key (checked first), keys per customer, failed attempts reusable, order-number collision |
| `CheckoutFailuresIntegrationTest` | CHECKOUT_BLOCKED (issues, empty cart, no address), TOTAL_CHANGED, cart down / its dependency down / slow / unexpected 404 / open breaker → 503, ADDRESS_NOT_FOUND |
| `InventoryEventsIntegrationTest` | every inventory row of the state-guard table, duplicates, out-of-state events, other event types, unknown orders, broken events |
| `PaymentEventsIntegrationTest` | every payment row of the state-guard table incl. late capture after a cancel, refunds, RefundFailed, broken events, a lock-wait timeout is transient and the redelivery applies the event |
| `StoreEventsIntegrationTest` | every store row of the Phase 8 state-guard table for vendor and restaurant: accept / preparing / ready (incl. ACCEPTED → READY directly), rejection with and without note, auto-rejection; duplicates, out-of-state and past-the-step events, wrong store type / store, skipped Received and lifecycle events, unknown orders, broken events, a lock-wait timeout is transient |
| `CancellationIntegrationTest` | customer / admin cancel in every status (customer 409 once accepted, admin cancel of ACCEPTED / PREPARING / READY_FOR_PICKUP with `previousStatus`), `adminCancellable`, idempotency, ownership, admin roles and reason, cancel racing a capture |
| `OrderQueriesIntegrationTest` | customer list (newest first, `OPEN`, paging), ownership 404, admin filters and India-time dates, admin detail, roles, stats numbers, the store statuses in stats / lists / views |
| `DeliveryEventsIntegrationTest` | every row of the Phase 9 delivery state-guard table (assigned / unassigned in each pre-pickup status, picked up, delivered, failed with and without note), events out of state and after the terminal statuses, duplicates, a pickup without a recorded assignment, `delivery` / `trackable` in the views, nobody cancels after pickup (409 `currentStatus`), the new statuses in stats and lists, the store-location snapshot and `OrderConfirmed.storeLocation` (null without coordinates), skipped event types, unknown orders, broken events, a lock-wait timeout is transient |
| `AvroOutboxRoundTripIntegrationTest` | every order event type from the real operations (Phase 8 types, OrderConfirmed with `subtotal` / `note`, OrderCancelled with the store reasons; Phase 9 OrderOutForDelivery / OrderDelivered / OrderDeliveryFailed and OrderConfirmed v3 with and without `storeLocation`; Phase 10 OrderStuckDetected from the real watchdog; Phase 12 the coupon fields and the four return events): `payload_avro` is Confluent wire format under the right subject and decodes (grocery-common's `AvroEventDeserializer`) to the JSON `payload` |
| `StuckOrderWatchdogIntegrationTest` | Phase 10 §4: every row of the threshold table, orders inside their threshold and terminal ones never flagged, the `OrderStuckDetected` payload and first detection only, two watchdogs at once, self-resolution on the next status change (reservation, store acceptance, delivery, admin cancel) and in the job, a new flag for a later status, `GET /api/admin/orders/stuck` (longest overdue first, shape, `minutesOverdue`, store, roles) |
| `OrderEventTimelineIntegrationTest` | Phase 10 §5: PUBLISHED and CONSUMED entries of a whole grocery order (inventory, payment, vendor, delivery), DUPLICATE, IGNORED with the guard's reason (wrong state, wrong store, stale refund, partner not pending), `occurredAt` / `correlationId` from the headers, the request's correlation id on a cancellation, the watchdog's entry, no entry for unknown orders or a failed processing, `GET /api/admin/orders/{id}/events` (order, 404, 400, roles) |
| `CouponPlacementIntegrationTest` | Phase 12 §2 against an in-process pricing stub (`StubPricing`): redeem with the new order id, the customer's token and the preview's amounts; snapshot, views, OrderCreated / OrderConfirmed, the discounted payment amount; the coupon's name and funding (PLATFORM, STORE; null when pricing sends none or an unknown funder); free delivery; `expectedTotal` vs the discounted total; refused (`reason`, `minOrderSubtotal`) → no order; cart-invalid coupon → CHECKOUT_BLOCKED; pricing down → 503; a redemption that differs from the preview → TOTAL_CHANGED; replay redeems once; an order-number retry keeps the redeemed id |
| `ReturnsIntegrationTest` | Phase 12 §3: request (view, events, timeline, lists), eligibility (state, 48h window, open request, ownership, roles), quantities (exceeded, unknown item, duplicates, validation, rejected frees / approved keeps), the refund math (partial, full with the fee, PERCENT, FLAT half-up, FREE_DELIVERY), store approve / reject (other store 404, unapproved 403, RETURN_NOT_PENDING, restaurant restock forced false), escalation and admin decisions (roles, queue), REFUNDED on RefundCompleted (timeline detail, duplicate, fully refunded closes returns) |
| `entity/OrderRulesTest`, `service/OrderNumbersTest`, `service/StuckRuleTest`, `service/ReturnRulesTest` | transitions, order and return numbers, the stuck rules (due times, `since`), the guard texts and the consumed event's headers, the return rules and refund math, without a database |

Environment: `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_LISTENERS_ENABLED`,
`IDENTITY_JWKS_URI`, `JWT_ISSUER`, `CART_SERVICE_URL`, `ORDER_PAYMENT_WINDOW`, `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL`,
(Phase 10) `ORDER_WATCHDOG_INTERVAL`, `ORDER_STUCK_CREATED_AFTER`, `ORDER_STUCK_PAYMENT_GRACE`,
`ORDER_STUCK_CONFIRMED_AFTER`, `ORDER_STUCK_PREPARATION_GRACE`, `ORDER_STUCK_PICKUP_AFTER`, `ORDER_STUCK_DELIVERY_AFTER`,
(Phase 12) `PRICING_SERVICE_URL`, `ORDER_RETURN_WINDOW`, `RETURN_DECISION_TIMEOUT`, `RETURN_ESCALATION_INTERVAL`
(profile `docker` defaults to the compose service names).
