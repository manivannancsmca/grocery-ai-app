# Phase 6 API contract — Orders & payments

Single source of truth for the Phase 6 backend and the React app. Public paths go through the API gateway
(`http://localhost:8080`). Conventions (auth header, ProblemDetail errors with `errorCode`, `PageResponse`, UUIDv7 ids,
ISO-8601 UTC timestamps, correlation IDs, money as JSON numbers in INR with 2 decimals) are unchanged from
[Phase 2](phase-2-identity-and-stakeholders.md#1-conventions). Internal endpoints follow the Phase 5 `/internal/**`
convention.

## 1. Who owns what

| Service | Port | Store | Owns |
|---|---|---|---|
| **order-service** (new) | 8110 | MySQL `order_db` | orders (price snapshot), the order status and its history, cancellation rules, idempotent order creation, order stats |
| **payment-service** (new) | 8111 | MySQL `payment_db` | payments, payment attempts, refunds, Razorpay orders / signatures / webhooks, the payment window |
| inventory-service | 8107 | `inventory_db` | now also consumes `order.events`: reserves, releases and expires stock for orders |
| cart-service | 8109 | Redis | now also consumes `OrderCreated`: empties the cart the order was placed from |

### The order flow (choreography — no service calls another to move an order on)
```
customer ─POST /api/orders─► order ──(user JWT)──► cart  GET /api/cart/preview   (live prices, stock, fee, address)
                               │ CREATED
                               ├─ OrderCreated ──► cart (empty the cart)
grocery:                       └─ OrderCreated ──► inventory ─ StockReserved ─────────► order: AWAITING_PAYMENT
                                                             └ StockReservationFailed ─► order: REJECTED (OUT_OF_STOCK)
restaurant: order goes straight to AWAITING_PAYMENT (the preview already checked menu, hours and accepting-orders)

order ─ OrderAwaitingPayment ─► payment: creates the Razorpay order ─ PaymentInitiated
customer ─► GET checkout session ─► Razorpay Checkout ─► POST verify ─► payment ─ PaymentCaptured ─► order: CONFIRMED
                                    (webhooks are the source of truth; verify also asks Razorpay directly)       └ OrderConfirmed ─► inventory (hold until pickup)
```

**Order statuses in this phase:** `CREATED → AWAITING_PAYMENT → CONFIRMED`; terminal `REJECTED`, `CANCELLED`.
Orders end at `CONFIRMED` in Phase 6; acceptance, preparation and delivery arrive in Phases 8–9.

| What goes wrong | Who notices | Result |
|---|---|---|
| Not enough stock | inventory → `StockReservationFailed` | `REJECTED` (`OUT_OF_STOCK`); no payment is created |
| Card declined | payment → `PaymentFailed` | stays `AWAITING_PAYMENT`; the customer retries in Checkout |
| Not paid within 15 min | payment's poller → `PaymentExpired` | `CANCELLED` (`PAYMENT_TIMEOUT`) → stock released |
| Reservation expired first (safety net, 20 min) | inventory's poller → `StockReservationExpired` | `CANCELLED` (`RESERVATION_EXPIRED`) → refunded if already paid |
| Paid after the order was cancelled ("late capture") | payment | refunded automatically (`LATE_CAPTURE`) → `RefundCompleted` |
| Customer / admin cancels | order API | `CANCELLED` → stock released, refunded if paid |
| Same webhook twice | payment | ignored (Razorpay event id) |
| Client retries `POST /api/orders` | order | same `Idempotency-Key` → the same order, created once |

`OrderCancelled` is the single compensation trigger: inventory releases stock, payment cancels the session or refunds.

## 2. Orders (order-service, role `CUSTOMER`)

| Method & path | Body → Response |
|---|---|
| `POST /api/orders` | header **`Idempotency-Key`** (required) · `PlaceOrderRequest` → **201** `OrderView` |
| `GET /api/orders?status=&page=&size=` | → `PageResponse<OrderSummary>`, newest first. `status` = one of the order statuses, or `OPEN` (CREATED + AWAITING_PAYMENT) |
| `GET /api/orders/{id}` | → `OrderView` · another customer's order → 404 `ORDER_NOT_FOUND` |
| `POST /api/orders/{id}/cancel` | `{ "reason": "Ordered by mistake" }` (optional, ≤ 300) → `OrderView` (`CANCELLED`) · 409 `ORDER_NOT_CANCELLABLE` |

```jsonc
// PlaceOrderRequest
{ "addressId": "…",              // optional: default address when absent (same rule as the checkout preview)
  "expectedTotal": 285.00,       // required: the total the customer saw; must equal the live total
  "note": "Ring the bell twice", // optional ≤ 300, delivery instructions
  "expectedCartUpdatedAt": "2026-11-11T10:00:00.123Z" } // optional (Phase 11): the preview's updatedAt the customer
                                 // saw; when present it must equal the live cart's (milliseconds, the cart's precision)
```

**Placing an order**
1. The key is checked first: `Idempotency-Key` missing → 400 `IDEMPOTENCY_KEY_REQUIRED`; malformed (8–100 of
   `A-Z a-z 0-9 . _ : -`) → 400 `IDEMPOTENCY_KEY_INVALID`. A key already used **by this customer** with the same body
   → **201 with the original order** and header `Idempotent-Replayed: true` (nothing is created or re-checked);
   with a different body → 422 `IDEMPOTENCY_KEY_REUSED`. Keys are scoped per customer.
2. order-service reads the live checkout preview from cart-service (`GET /api/cart/preview?addressId=`, the customer's
   own token). Any blocking issue → 409 `CHECKOUT_BLOCKED` with property `issues` (the preview's `Issue[]`, including
   `EMPTY_CART`, `ADDRESS_REQUIRED`, `OUT_OF_STOCK`, `STORE_CLOSED`, …). Address not the caller's → 404 `ADDRESS_NOT_FOUND`.
3. `expectedTotal` ≠ live `total` → 409 `TOTAL_CHANGED` with properties `expectedTotal`, `currentTotal` (the client
   shows the new preview and asks again).
   - *(Phase 11)* `expectedCartUpdatedAt` present and ≠ the preview's `updatedAt` (compared in milliseconds) → 409
     `CART_CHANGED` with properties `expectedCartUpdatedAt`, `currentCartUpdatedAt` (ISO-8601; the latter is left out
     for a cart that was never written). It binds an order approved through the assistant to exactly the items the
     customer saw. Without the field nothing changes.
   - Order of the checks: blocking issues (step 2), then `CART_CHANGED`, then `TOTAL_CHANGED`. A changed cart explains
     a changed total, and the customer must review the items, not just the price.
   - Both fields are part of the body for the `Idempotency-Key` rule (step 1).
   - *(Phase 12, [§2](phase-12-post-mvp-features.md#2-promotions--coupons-pricing-service-cart-service-order-service))*
     the live `total` is the **discounted** total (`subtotal + deliveryFee − discount − deliveryFeeDiscount`). After
     these checks the cart's coupon, if any, is redeemed at pricing-service (`POST /internal/coupons/redeem`, the
     customer's token) for the new order id; refused → 409 `COUPON_NOT_APPLICABLE` with `reason` (and
     `minOrderSubtotal` for `COUPON_MIN_ORDER`), pricing unreachable → 503 `DEPENDENCY_UNAVAILABLE`
     (`dependency: pricing`); either way no order is placed. Redeemed amounts that give another total than
     `expectedTotal` → 409 `TOTAL_CHANGED`. A replayed request never redeems again.
4. The order is stored with a **snapshot** of lines, prices, fee, totals and the delivery address; it never changes
   when catalog prices change later. Status `CREATED`, `OrderCreated` is published (same transaction).
   - Restaurant order: in the same transaction it moves on to `AWAITING_PAYMENT` and publishes `OrderAwaitingPayment`.
   - Grocery order: it waits for inventory (usually < 1 s).
5. cart-service or a dependency unreachable → 503 `DEPENDENCY_UNAVAILABLE` (property `dependency`); nothing is stored,
   the client may retry with the same key.

The payment window is **15 minutes from creation** (`grocery.order.payment-window`) and shown as `payment.dueAt`.

**Cancellation by the customer:** allowed while `CREATED`, `AWAITING_PAYMENT` or `CONFIRMED` (until the store accepts —
Phase 8 narrows this). Cancelling twice returns the cancelled order (idempotent). `REJECTED` → 409 `ORDER_NOT_CANCELLABLE`.

```jsonc
// OrderView
{ "id": "…", "orderNumber": "GA-7K3F9Q2M",            // short, unique, shown to people
  "type": "GROCERY",                                   // GROCERY | RESTAURANT
  "status": "AWAITING_PAYMENT",                        // CREATED | AWAITING_PAYMENT | CONFIRMED | REJECTED | CANCELLED
  "store": { "id": "…", "type": "GROCERY", "name": "Green Basket" },
  "items": [ { "itemId": "…", "type": "PRODUCT", "name": "Basmati Rice", "unitLabel": "1 kg", "imageUrl": null,
               "foodType": "VEG", "quantity": 2, "unitPrice": 50.00, "mrp": 60.00, "lineTotal": 100.00 } ],
  "itemCount": 2, "subtotal": 100.00, "mrpTotal": 120.00, "savings": 20.00,
  "deliveryFee": 25.00,
  "couponCode": null, "discount": 0.00, "deliveryFeeDiscount": 0.00,   // Phase 12: the redeemed coupon (null / 0.00 without)
  "total": 125.00, "currency": "INR",                  // = subtotal + deliveryFee − discount − deliveryFeeDiscount
  "deliveryAddress": { "label", "recipientName", "phone", "line1", "line2", "landmark", "city", "state",
                       "postalCode", "latitude", "longitude" },
  "deliveryDistanceKm": 4.6, "note": "Ring the bell twice",
  "payment": { "status": "PENDING",                    // see below
               "dueAt": "…", "paidAt": null, "refundedAmount": 0.00, "lastFailure": null },
  "cancellable": true,
  "cancellation": { "reason": "PAYMENT_TIMEOUT", "note": null, "by": "SYSTEM", "at": "…" } | null,
  "rejection": { "reason": "OUT_OF_STOCK",
                 "unavailableItems": [ { "itemId": "…", "name": "Basmati Rice", "requested": 2, "available": 1 } ] } | null,
  "timeline": [ { "status": "CREATED", "at": "…", "reason": null } ],   // oldest first
  "returnable": false,                                 // Phase 12 §3: a return can be requested now
  "returns": [ { "id", "returnNumber": "RT-7K3M9QX2", "status": "REQUESTED", "reason": "DAMAGED",
                 "refundAmount": 50.00, "requestedAt": "…",
                 "items": [ { "itemId": "…", "quantity": 1 } ] } ],      // Phase 12 §3, oldest first
  "createdAt": "…", "updatedAt": "…" }

// OrderSummary
{ "id", "orderNumber", "type", "status", "store", "itemCount",
  "couponCode", "discount", "deliveryFeeDiscount",     // Phase 12
  "total", "currency", "firstItemName": "Basmati Rice",
  "payment": { "status": "PENDING",
               "dueAt": "2026-11-18T09:15:00Z" | null },  // added 2026-11-18: the OrderView's payment.dueAt, only
                                                           // while AWAITING_PAYMENT (null otherwise)
  "createdAt" }
```

*(Phase 12)* `OrderCreated` and `OrderConfirmed` also carry `couponCode`, `discount`, `deliveryFeeDiscount` (all three
null without a coupon); the payment amount (`OrderAwaitingPayment.amount`) is the discounted total. Returns:
[Phase 12 §3](phase-12-post-mvp-features.md#3-returns--partial-refunds-order-service).

`payment.status` (order-service's own view, kept from payment events): `NOT_STARTED` (CREATED, waiting for stock),
`PENDING` (waiting for the customer), `PAID`, `EXPIRED`, `NOT_REQUIRED` (rejected before payment / cancelled unpaid),
`REFUND_PENDING`, `REFUNDED`, `PARTIALLY_REFUNDED`. `lastFailure` = `{ "code", "description", "at" }` of the most
recent declined attempt while still payable. Cancellation reasons: `CUSTOMER_CANCELLED`, `ADMIN_CANCELLED`,
`PAYMENT_TIMEOUT`, `RESERVATION_EXPIRED`. `by` = `CUSTOMER` | `ADMIN` | `SYSTEM`.

**MCP (2026-11-20).** A customer's order by its number is also an MCP tool, `getOrderStatus(orderNumber)`, on
order-service's MCP server (`POST /api/mcp/orders`, stateless Streamable HTTP, a token required): the same
`OrderView` as `GET /api/orders/{id}`, customers only, their own orders only. See
[phase-11 §3 "MCP pilot"](phase-11-agentic-ai-and-approvals.md#3-tools-risk-classes-and-toolguard).

## 3. Payments (payment-service, role `CUSTOMER` unless noted)

| Method & path | Body → Response |
|---|---|
| `GET /api/payments/orders/{orderId}/checkout` | → `CheckoutSession` |
| `POST /api/payments/razorpay/verify` | `{ "razorpayOrderId", "razorpayPaymentId", "razorpaySignature" }` → `PaymentView` |
| `GET /api/payments/orders/{orderId}` | → `PaymentView` (the customer's own; any admin may read any) |
| `POST /api/payments/razorpay/webhook` | **public** (no token) · Razorpay webhook, see below → 200 |
| `POST /api/payments/simulator/orders/{orderId}/pay` | only in simulator mode, see §3.3 |

Errors of the checkout session: no payment for the order yet (stock still being reserved, or the event is on its
way) → 409 `PAYMENT_NOT_READY` (retry after a second); another customer's order → 404 `PAYMENT_NOT_FOUND`; already
paid → 409 `PAYMENT_ALREADY_CAPTURED`; window over → 409 `PAYMENT_EXPIRED`; order cancelled → 409 `PAYMENT_CANCELLED`;
Razorpay unreachable while creating its order → 503 `PAYMENT_PROVIDER_UNAVAILABLE`.

```jsonc
// CheckoutSession — everything Razorpay Checkout needs (the key secret never leaves payment-service)
{ "provider": "RAZORPAY",                 // RAZORPAY | SIMULATOR
  "keyId": "rzp_test_…",
  "razorpayOrderId": "order_…",
  "amount": 12500,                        // paise (integer) — what Checkout expects
  "displayAmount": 125.00, "currency": "INR",
  "orderId": "…", "orderNumber": "GA-7K3F9Q2M",
  "merchantName": "Grocery AI", "description": "Order GA-7K3F9Q2M",
  "prefill": { "name": "…", "email": "…", "contact": "+919876543210" },   // from the token and the delivery address
  "expiresAt": "…" }

// PaymentView
{ "id": "…", "orderId": "…", "orderNumber": "GA-7K3F9Q2M", "customerId": "…",
  "status": "CAPTURED",                   // CREATED | PENDING | CAPTURED | EXPIRED | CANCELLED
  "amount": 125.00, "currency": "INR", "method": "upi" | null,
  "razorpayOrderId": "order_…", "razorpayPaymentId": "pay_…" | null,
  "refundedAmount": 0.00, "refundStatus": "NONE",   // NONE | PENDING | PARTIAL | FULL
  "expiresAt": "…", "capturedAt": "…" | null, "createdAt": "…",
  "attempts": [ { "razorpayPaymentId": "pay_…", "status": "FAILED", "method": "card",
                  "errorCode": "BAD_REQUEST_ERROR", "errorDescription": "Payment declined", "at": "…" } ],
  "refunds": [ RefundView ] }

// RefundView
{ "id": "…", "paymentId": "…", "orderId": "…", "amount": 125.00,
  "reason": "ORDER_CANCELLED",            // ORDER_CANCELLED | LATE_CAPTURE | ADMIN | RETURN (Phase 12)
  "note": null, "status": "PROCESSED",    // PENDING | PROCESSED | FAILED
  "razorpayRefundId": "rfnd_…" | null, "failureReason": null,
  "requestedBy": null,                    // admin user id for ADMIN refunds
  "returnId": null,                       // Phase 12: the return of a RETURN refund
  "createdAt": "…", "processedAt": "…" | null }
```

### 3.1 Payment lifecycle
- `OrderAwaitingPayment` → a payment row (`CREATED`, amount and `expiresAt` from the event, unique per order) and a
  Razorpay order (`amount` in paise, `receipt` = order number, `notes.orderId`) → `PENDING` + `PaymentInitiated`. If
  Razorpay is unreachable the event is retried; the checkout endpoint also creates a missing Razorpay order on demand.
  Both paths lock the payment row, so exactly one Razorpay order exists per payment.
- **Verify**: the signature is `HMAC_SHA256(razorpayOrderId + "|" + razorpayPaymentId, keySecret)` (hex). Wrong →
  400 `INVALID_SIGNATURE` (nothing changes). Unknown Razorpay order or another customer's → 404 `PAYMENT_NOT_FOUND`.
  When it matches, payment-service **asks Razorpay for the payment** (it does not trust the browser): `captured` →
  captured; `authorized` → payment-service captures it (`POST /payments/{id}/capture`) → captured; `failed` → a
  failed attempt (409 `PAYMENT_FAILED` with the error). Verify is idempotent: verifying a captured payment again
  returns it unchanged.
- **Captured** (from verify or webhook, whichever is first; the Razorpay payment id is unique): `CAPTURED`,
  `PaymentCaptured`. If the payment was already `EXPIRED` or `CANCELLED` (the order is gone) → **late capture**:
  `PaymentCaptured` is still published (facts are facts), and a full refund with reason `LATE_CAPTURE` starts at once.
- **Declined**: `payment.failed` webhook → a failed attempt + `PaymentFailed` (`retryable: true` while the window is
  open). The payment stays `PENDING`; Razorpay Checkout lets the customer try again on the same Razorpay order.
- **Window over**: a poller (every 30 s, `SELECT … FOR UPDATE SKIP LOCKED`, safe with several replicas) takes
  `CREATED`/`PENDING` payments past `expiresAt`. It first asks Razorpay whether the order has a captured payment (a
  lost webhook): yes → captured as above; no → `EXPIRED` + `PaymentExpired`.
- **`OrderCancelled`**: `CREATED`/`PENDING` → `CANCELLED` (no further capture is accepted without a refund);
  `CAPTURED` → full refund with reason `ORDER_CANCELLED`.

### 3.2 Webhook (`POST /api/payments/razorpay/webhook`)
- No token. The raw body is checked against `X-Razorpay-Signature` = `HMAC_SHA256(rawBody, webhookSecret)` (hex) →
  wrong or missing: 400 `INVALID_SIGNATURE`.
- De-duplicated by the `X-Razorpay-Event-Id` header (stored in `webhook_event`, unique): a repeat → 200, nothing done.
- Handled: `payment.captured`, `payment.authorized` (capture it), `payment.failed`, `refund.processed`,
  `refund.failed`. Anything else → 200, stored, ignored. Unknown Razorpay order → 200, stored, logged.
- Answers 200 as soon as the event is stored and applied (same transaction); provider calls triggered by it (e.g. a
  late-capture refund) happen after commit.

### 3.3 Local simulator (no Razorpay keys)
Razorpay test keys are a per-developer secret (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` in
`infrastructure/local/.env`). Without them, payment-service runs in **simulator mode**
(`grocery.payment.provider=simulator`, refused unless the `docker` or `local` profile is active) so the whole flow
still works locally:
- The checkout session says `"provider": "SIMULATOR"` and `"keyId": "rzp_test_simulator"`; the React app shows its own
  test-payment dialog instead of Razorpay Checkout.
- `POST /api/payments/simulator/orders/{orderId}/pay` `{ "outcome": "SUCCESS" }` (or `"FAIL"`) → for `SUCCESS`
  `{ "razorpayOrderId", "razorpayPaymentId", "razorpaySignature" }` signed with the simulator's key secret, which the
  app then posts to `/verify` exactly like after Razorpay Checkout; for `FAIL` the same as a `payment.failed` webhook.
  In Razorpay mode this endpoint is 404.
- The simulator implements the same provider interface as the Razorpay client (create order, fetch payment, capture,
  refund, list an order's payments), so verify, webhooks, expiry, late capture and refunds run the same code. Webhooks
  can be posted by hand with the simulator's webhook secret (`simulator_webhook_secret`).

## 4. Admin (order-service and payment-service)

Reads: any admin role. Actions: as noted.

| Method & path | Body → Response |
|---|---|
| `GET /api/admin/orders?status=&type=&customerId=&storeId=&q=&from=&to=&page=&size=` | → `PageResponse<AdminOrderSummary>` (`OrderSummary` + `customerId`), newest first. `q` = order number (exact, case-insensitive). `from`/`to` = dates (India time), inclusive |
| `GET /api/admin/orders/{id}` | → `OrderView` + `customerId` |
| `POST /api/admin/orders/{id}/cancel` | `SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT` · `{ "reason": "…" }` (required ≤ 300) → view (`CANCELLED`, reason `ADMIN_CANCELLED`) · 409 `ORDER_NOT_CANCELLABLE` |
| `GET /api/admin/stats?date=` | → `OrderStats` for one day in India time (default today) |
| `GET /api/admin/payments?status=&refundStatus=&orderId=&from=&to=&page=&size=` | → `PageResponse<PaymentView>` without `attempts`, newest first |
| `GET /api/admin/payments/{id}` | → `PaymentView` |
| `POST /api/admin/refunds` | `SUPER_ADMIN`, `FINANCE_ADMIN` · header `Idempotency-Key` · `{ "orderId": "…", "amount": 50.00, "note": "Damaged item" }` (`amount` optional = everything not yet refunded; note required ≤ 300) → **201** `RefundView` (reason `ADMIN`) · 409 `PAYMENT_NOT_CAPTURED` · 422 `REFUND_EXCEEDS_CAPTURED` (property `refundable`) · 404 `PAYMENT_NOT_FOUND` |

```jsonc
// OrderStats
{ "date": "2026-11-11", "timezone": "Asia/Kolkata",
  "orders": 42,                                     // created that day
  "byStatus": { "CREATED": 0, "AWAITING_PAYMENT": 3, "CONFIRMED": 30, "REJECTED": 2, "CANCELLED": 7 },
  "byType": { "GROCERY": 25, "RESTAURANT": 17 },
  "confirmedRevenue": 10250.00,                     // sum of totals of orders confirmed (paid) that day
  "averageOrderValue": 341.67,                      // confirmedRevenue / confirmed orders, 2 decimals (0 when none)
  "currency": "INR" }
```

An admin refund does not change the order status (a partial refund for a damaged item is normal); it only appears in
`payment.refundedAmount` and `payment.status` (`PARTIALLY_REFUNDED` / `REFUNDED`).

Refunds go to Razorpay's Refunds API (`POST /payments/{id}/refund`, amount in paise). In test mode they are processed
immediately; otherwise `refund.processed` / `refund.failed` webhooks finish them. A provider failure that is not
temporary → `FAILED` + `RefundFailed` (the admin can try again with a new key); a temporary one is retried with backoff.

## 5. Health (public, through the gateway)

`GET /api/health/order` and `GET /api/health/payment`.

## 6. Events introduced in Phase 6 (see docs/events/event-catalog.md)

All are keyed by **orderId**, so every event of one order stays in order within its topic. Outbox aggregate types:
`Order` (order.events), `Reservation` (inventory.events), `Payment` (payment.events). Money = JSON number, 2 decimals.

| Topic | Event (v1) | Producer → consumers (group) |
|---|---|---|
| `order.events` | `OrderCreated` | order → inventory (`inventory-service.order-events`, grocery only), cart (`cart-service.order-events`) |
| `order.events` | `OrderAwaitingPayment` | order → payment (`payment-service.order-events`) |
| `order.events` | `OrderConfirmed` | order → inventory (hold the stock until pickup); vendor / restaurant from Phase 8 |
| `order.events` | `OrderRejected` | order → notification (Phase 7) |
| `order.events` | `OrderCancelled` | order → inventory (release), payment (cancel / refund) |
| `inventory.events` | `StockReserved`, `StockReservationFailed`, `StockReservationExpired`, `StockReleased` | inventory → order (`order-service.inventory-events`) |
| `payment.events` | `PaymentInitiated`, `PaymentCaptured`, `PaymentFailed`, `PaymentExpired`, `RefundCompleted`, `RefundFailed` | payment → order (`order-service.payment-events`) |

```jsonc
// order.events
OrderCreated         { "orderId", "orderNumber", "orderType": "GROCERY", "customerId",
                       "storeId", "storeName",                 // vendorId for GROCERY, restaurantId for RESTAURANT
                       "items": [ { "itemId", "type": "PRODUCT", "name", "quantity", "unitPrice", "lineTotal" } ],
                       "subtotal", "deliveryFee", "total", "currency",
                       "cartUpdatedAt": "…",                   // the cart version the order was placed from
                       "paymentDueAt", "createdAt" }
OrderAwaitingPayment { "orderId", "orderNumber", "customerId", "amount", "currency", "expiresAt",
                       "contactPhone": "+919876543210" }
OrderConfirmed       { "orderId", "orderNumber", "orderType", "customerId", "storeId", "items": [ … ],
                       "total", "deliveryAddress": { … }, "confirmedAt" }
OrderRejected        { "orderId", "orderNumber", "orderType", "customerId", "storeId", "reason": "OUT_OF_STOCK",
                       "unavailableItems": [ { "itemId", "requested", "available" } ] }
OrderCancelled       { "orderId", "orderNumber", "orderType", "customerId", "storeId",
                       "previousStatus": "AWAITING_PAYMENT", "reason": "PAYMENT_TIMEOUT",
                       "cancelledBy": "SYSTEM", "actorUserId": null, "note": null, "cancelledAt" }

// inventory.events (aggregate Reservation, key orderId)
StockReserved            { "orderId", "reservationId", "items": [ { "productId", "quantity" } ], "expiresAt" }
StockReservationFailed   { "orderId", "unavailableItems": [ { "productId", "requested", "available" } ] }
StockReservationExpired  { "orderId", "reservationId" }
StockReleased            { "orderId", "reservationId", "reason": "ORDER_CANCELLED" }   // or RESERVATION_EXPIRED

// payment.events (aggregate Payment, key orderId)
PaymentInitiated { "orderId", "paymentId", "razorpayOrderId", "amount", "currency", "expiresAt" }
PaymentCaptured  { "orderId", "paymentId", "razorpayPaymentId", "amount", "method", "capturedAt",
                   "late": false }                             // true = the payment had expired / was cancelled
PaymentFailed    { "orderId", "paymentId", "razorpayPaymentId", "errorCode", "errorDescription", "retryable": true }
PaymentExpired   { "orderId", "paymentId" }
RefundCompleted  { "orderId", "paymentId", "refundId", "amount", "reason", "totalRefunded", "fullyRefunded": true,
                   "returnId": null }                          // Phase 12: set for reason RETURN
RefundFailed     { "orderId", "paymentId", "refundId", "amount", "reason", "errorCode", "returnId": null }
```

**State guards (how order-service applies events)** — every event is applied once (`processed_event`) and only in
the state it belongs to; everything else is ignored and logged:

| Event | Applied when the order is | Result |
|---|---|---|
| `StockReserved` | `CREATED` | `AWAITING_PAYMENT` + `OrderAwaitingPayment` |
| `StockReservationFailed` | `CREATED` | `REJECTED` (`OUT_OF_STOCK`, with the items) + `OrderRejected` |
| `StockReservationExpired` | `AWAITING_PAYMENT` or `CONFIRMED` | `CANCELLED` (`RESERVATION_EXPIRED`) + `OrderCancelled` |
| `PaymentInitiated` | any | payment status `PENDING` (when not paid) |
| `PaymentCaptured` | `AWAITING_PAYMENT` | `CONFIRMED` + `OrderConfirmed`, payment `PAID`. In `CANCELLED`: payment `REFUND_PENDING` only (payment-service refunds) |
| `PaymentFailed` | `AWAITING_PAYMENT` | `lastFailure` recorded, status unchanged |
| `PaymentExpired` | `AWAITING_PAYMENT` | `CANCELLED` (`PAYMENT_TIMEOUT`) + `OrderCancelled`, payment `EXPIRED` |
| `RefundCompleted` | any | `refundedAmount` = `totalRefunded`; payment `REFUNDED` / `PARTIALLY_REFUNDED`; with `returnId` (Phase 12) the APPROVED return becomes `REFUNDED` |

**Inventory** (consumer `inventory-service.order-events`): `OrderCreated` (GROCERY) → reserve all lines for
20 minutes (`grocery.inventory.reservation-ttl`) → `StockReserved`, or `StockReservationFailed` when any product lacks
stock (nothing held). `OrderConfirmed` → the reservation no longer expires (held until pickup, committed in Phase 9).
`OrderCancelled` → release → `StockReleased` (only if something was held). A poller (every 30 s, `SKIP LOCKED`) releases
`RESERVED` reservations past `expires_at` → `StockReleased` (`RESERVATION_EXPIRED`) + `StockReservationExpired`.
The 20-minute reservation outlives the 15-minute payment window, so normally the payment expiry cancels first.

**Cart** (consumer `cart-service.order-events`): `OrderCreated` → the customer's cart is emptied **only if it has not
changed since** (`cartUpdatedAt` equals the cart's `updatedAt`); a cart edited after placing the order is kept.
