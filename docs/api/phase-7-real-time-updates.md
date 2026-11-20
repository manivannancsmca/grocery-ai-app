# Phase 7 API contract — Real-time updates

Single source of truth for notification-service and the React app's realtime layer. Public paths go through the API
gateway (`http://localhost:8080`). Conventions are unchanged from [Phase 2](phase-2-identity-and-stakeholders.md#1-conventions):
auth header, ProblemDetail errors with `errorCode`, `PageResponse`, UUIDv7 ids, ISO-8601 UTC timestamps.
Architecture: [ADR-0005](../adr/0005-sse-for-real-time.md) (Server-Sent Events).

## 1. Who owns what

| Service | Port | Store | Owns |
|---|---|---|---|
| **notification-service** (new) | 8113 | MySQL `notification_db` + Redis pub/sub | the notification inbox, recipient resolution, the SSE connections, fan-out across replicas |

notification-service **only consumes** events. It publishes no Kafka events and has no outbox connector. It can run
as several replicas: every replica consumes in one consumer group, and every replica delivers to its own connections
through Redis pub/sub.

```
Kafka (all domain topics) ──► notification-service replica that owns the partition
     1. update its projections (store owners, orders)
     2. resolve recipients on the server (never from the client)
     3. store persistent notifications in MySQL (same transaction as processed_event)
     4. after commit: PUBLISH one message on Redis channel grocery:realtime
Redis grocery:realtime ──► EVERY replica ──► SSE to its locally connected users whose audiences match
```

## 2. The stream — `GET /api/notifications/stream`

- **Auth:** `Authorization: Bearer <access token>`, any role. Browsers use a fetch-based SSE client, because
  `EventSource` cannot send headers. No token → 401, as on every API.
- **Headers in:** `Last-Event-ID` (optional, the last notification id the client saw); `Accept: text/event-stream`.
- **Response:** `200`, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`. The first
  line is `retry: 3000`, then the events:

| SSE `event:` | `id:` | `data:` (JSON) | Meaning |
|---|---|---|---|
| `notification` | the notification id | `NotificationView` (§3) | persistent: stored in the inbox, replayed after a reconnect |
| `signal` | none | `Signal` (below) | ephemeral "something changed — refetch". Never stored, never replayed |
| `session` | none | `{ "reason": "TOKEN_EXPIRED" \| "REVOKED" \| "REPLACED" }` | the server closes the stream right after it |
| *(comment)* `: hb` | — | — | heartbeat every 20 s (keeps proxies from closing an idle stream) |

- **Replay:**
  - With a valid `Last-Event-ID`, the server first sends the caller's notifications newer than that id, oldest
    first, at most 200. If more were missed, it then sends `signal` `RESYNC` and the client refetches everything.
  - An unknown or malformed `Last-Event-ID` gets no replay, just `RESYNC`. Without the header there is no replay.
  - Live events that arrive during the replay are queued and sent after it, without duplicates.
- **Lifetime:**
  - The server ends the stream when the access token expires (`session` `TOKEN_EXPIRED`); the client reconnects with
    a fresh token and its `Last-Event-ID`.
  - An account suspension (UserSuspended / VendorSuspended / RestaurantSuspended / DeliveryPartnerSuspended) ends
    the owner's streams on every replica (`session` `REVOKED`); the client signs out.
  - At most 5 streams per user (tabs, devices). A 6th closes the oldest (`session` `REPLACED`).
- **Audiences of a connection** are computed on the server from the token, never chosen by the client:
  `user:<sub>`, `role:<ROLE>` for each role in the token, and `public`.

```jsonc
// Signal
{ "type": "ORDER_UPDATED", "data": { "orderId": "…", "status": "CONFIRMED" }, "at": "2026-11-11T10:00:00.123Z" }
```

| Signal type | `data` | Sent to | Client refetches |
|---|---|---|---|
| `ORDER_UPDATED` | `orderId`, `status` (when known), `event` (the Kafka event type) | the order's customer; its store owner once the order is CONFIRMED or later | the order, the order lists |
| `ADMIN_ORDERS_CHANGED` | `orderId`, `event` | all admin roles | admin order and payment monitors, admin stats |
| `STOCK_CHANGED` | `productId`, `vendorId`, `inStock` | `public` (everyone connected) — in ↔ out of stock transitions only, at most 1 per product per 2 s | product, search, cart |
| `PRICE_CHANGED` | `productId`, `vendorId` | `public`, at most 1 per product per 2 s | product, search, cart |
| `RESTAURANT_UPDATED` | `restaurantId`, `acceptingOrders`, `status` | `public`, at most 1 per restaurant per 2 s | restaurant pages, search, cart |
| `INVENTORY_UPDATED` | `productId` | the vendor's owner | vendor inventory |
| `PRICING_UPDATED` | `productId`, `requestId` (optional) | the vendor's owner | vendor pricing |
| `APPROVALS_CHANGED` | `kind`: `VENDOR` \| `RESTAURANT` \| `DELIVERY_PARTNER` \| `PRICE_CHANGE` | the admin roles that handle that kind | admin queues |
| `ACCOUNT_UPDATED` | `kind`: `VENDOR` \| `RESTAURANT` \| `DELIVERY_PARTNER` \| `USER`, `status` | the account owner | own profile (and a token refresh: an approval adds the `vendor_id` / `restaurant_id` claim) |
| `RESYNC` | `{}` | one connection | everything (inbox and every cached query) |

## 3. Inbox (role: any authenticated user; only the caller's own notifications)

| Method & path | Body → Response |
|---|---|
| `GET /api/notifications?unread=&page=&size=` | → `PageResponse<NotificationView>`, newest first. `unread=true` → only unread. `size` ≤ 50 |
| `GET /api/notifications/unread-count` | → `{ "count": 3 }` |
| `POST /api/notifications/{id}/read` | → 204. Idempotent. Somebody else's or unknown id → 404 `NOTIFICATION_NOT_FOUND` |
| `POST /api/notifications/read-all` | → `{ "updated": 7 }` |

`data` always carries the ids the app needs to refresh the right screens:
- `orderId` and `orderNumber` on every ORDER and PAYMENT notification, `REFUND_FAILED` and `NEW_ORDER` included;
- `kind` (`VENDOR` | `RESTAURANT` | `DELIVERY_PARTNER` | `PRICE_CHANGE` | `USER`) on `APPROVAL_REQUESTED`,
  `PRICE_APPROVAL_REQUESTED` and every `ACCOUNT_*`;
- `productId` (and `requestId` for price changes) on STOCK and PRICING notifications.

Only `notification` events carry an SSE `id:`; signals and session events never do, so they cannot disturb
`Last-Event-ID`.

"The caller's notifications" are those addressed to `user:<sub>` or to any `role:<ROLE>` in the caller's token.
Read state is per user, so admin A reading a notification sent to `role:OPS_ADMIN` does not mark it read for admin B.

```jsonc
// NotificationView
{ "id": "…",                       // UUIDv7 = the SSE id; newer ids sort later
  "type": "ORDER_CONFIRMED",       // see §4
  "category": "ORDER",             // ORDER | PAYMENT | STOCK | PRICING | ACCOUNT | APPROVAL | REVIEW (Phase 12)
  "severity": "SUCCESS",           // INFO | SUCCESS | WARNING | ERROR
  "title": "Order GA-7K3F9Q2M confirmed",
  "body": "Paid ₹245.00. Green Basket will start preparing it soon.",
  "link": "/orders/01a1…",         // app route, or null
  "data": { "orderId": "…", "orderNumber": "GA-7K3F9Q2M" },
  "read": false,
  "createdAt": "…" }
```

## 4. Which event notifies whom

Persistent notifications (stored, shown in the bell and inbox, toasted). Money is formatted `₹1,234.50`. A missing
name falls back to a neutral word ("your store", "a product").

| Kafka event | Recipient (audience) | `type` · severity | Title · body · link |
|---|---|---|---|
| OrderConfirmed | customer | `ORDER_CONFIRMED` · SUCCESS | "Order {no} confirmed" · "Paid {total}. {storeName} will start preparing it soon." · `/orders/{id}` |
| OrderConfirmed (**until Phase 8**; from Phase 8 on, NEW_ORDER is sent on VendorOrderReceived / RestaurantOrderReceived — see the Phase 8 contract §6) | store owner (vendor for GROCERY, restaurant for RESTAURANT) | `NEW_ORDER` · INFO | "New order {no}" · "{itemCount} items · {total}" · `/vendor` or `/restaurant` |
| OrderRejected | customer | `ORDER_REJECTED` · ERROR | "Order {no} could not be placed" · "Some items ran out of stock. You have not been charged." · `/orders/{id}` |
| OrderCancelled | customer | `ORDER_CANCELLED` · WARNING | "Order {no} cancelled" · by reason: PAYMENT_TIMEOUT "Payment was not completed in time." · CUSTOMER_CANCELLED "You cancelled this order." · ADMIN_CANCELLED "Cancelled by our support team." (+ " {note}") · RESERVATION_EXPIRED "The items could not be held any longer."; + " A full refund is on its way." when previousStatus was CONFIRMED · `/orders/{id}` |
| OrderCancelled (previousStatus CONFIRMED) | store owner | `ORDER_CANCELLED` · WARNING | "Order {no} cancelled" · "The customer's order was cancelled; do not prepare it." · `/vendor` or `/restaurant` |
| PaymentFailed (`retryable`) | customer | `PAYMENT_FAILED` · ERROR | "Payment declined for order {no}" · "{errorDescription or 'Your bank declined the payment'}. You can try again before the payment window closes." · `/orders/{id}` |
| RefundCompleted | customer | `REFUND_COMPLETED` · SUCCESS | "Refund of {amount} processed" · "For order {no}. Banks usually take 5–7 working days to show it." · `/orders/{id}` |
| RefundFailed | `role:SUPER_ADMIN`, `role:FINANCE_ADMIN` | `REFUND_FAILED` · ERROR | "Refund failed for order {no}" · "{amount} ({reason}) — {errorCode}. Retry it from the payment monitor." · `/admin/payments` |
| OrderStuckDetected (Phase 10) | `role:SUPER_ADMIN`, `role:OPS_ADMIN`, `role:SUPPORT` | `STUCK_ORDER` · WARNING | "Order {no} is stuck: {reason label}" · "{status} since {HH:mm}." (India time) · `/admin/orders` (data: orderId, orderNumber, status, reason) |
| LowStockDetected | vendor owner | `LOW_STOCK` · WARNING | "Low stock: {productName}" · "{available} left (alert at {threshold})." · `/vendor/inventory` |
| PriceChangeRequested | `role:SUPER_ADMIN`, `role:OPS_ADMIN`, `role:FINANCE_ADMIN` | `PRICE_APPROVAL_REQUESTED` · INFO | "Price change waiting: {productName}" · "{changePercent}% — {oldSellingPrice} → {newSellingPrice}" · `/admin/price-approvals` |
| PriceChangeApproved | vendor owner | `PRICE_CHANGE_APPROVED` · SUCCESS | "Price change approved" · "The new price is live." · `/vendor/pricing` |
| PriceChangeRejected | vendor owner | `PRICE_CHANGE_REJECTED` · WARNING | "Price change rejected" · "{reason}" · `/vendor/pricing` |
| VendorSubmitted · RestaurantSubmitted | `role:SUPER_ADMIN`, `role:OPS_ADMIN` | `APPROVAL_REQUESTED` · INFO | "{name} is waiting for approval" · "New vendor" / "New restaurant" · `/admin/approvals` |
| DeliveryPartnerSubmitted | `role:SUPER_ADMIN`, `role:OPS_ADMIN`, `role:DELIVERY_MANAGER` | `APPROVAL_REQUESTED` · INFO | "{fullName} is waiting for approval" · "New delivery partner" · `/admin/approvals` |
| Vendor/Restaurant/DeliveryPartner Approved | owner (`ownerUserId`; the partnerId is the user id) | `ACCOUNT_APPROVED` · SUCCESS | "Your {store/restaurant/partner account} is approved" · "You can start now." · `/vendor` / `/restaurant` / `/delivery` |
| … Rejected | owner | `ACCOUNT_REJECTED` · WARNING | "Your application needs changes" · "{reason}" · same |
| … Suspended | owner | `ACCOUNT_SUSPENDED` · ERROR | "Your account is suspended" · "{reason}" · null — also ends their streams (`REVOKED`) |
| … Reinstated, UserReactivated | owner / user | `ACCOUNT_REINSTATED` · SUCCESS | "Your account is active again" · "" · role home |
| UserSuspended | — | no notification | ends the user's streams (`REVOKED`) |
| ReviewSubmitted `PUBLISHED` (Phase 12, review.events) | owner of the review's store (`storeId` → store owner projection); **not** for `DELIVERY_PARTNER` reviews | `NEW_REVIEW` · INFO · category `REVIEW` | "New {rating}★ review" · "{targetName}" (+ " (edited)") · `/vendor/reviews` or `/restaurant/reviews` (data: reviewId, targetType, targetId, rating) |
| ReviewSubmitted `FLAGGED` (Phase 12) | `role:SUPER_ADMIN`, `role:OPS_ADMIN`, `role:SUPPORT` | `REVIEW_FLAGGED` · WARNING · `REVIEW` | "A {rating}★ review needs moderation" · "{targetName} · {flag reason label}" · `/admin/reviews` (data + flagReason) |
| ReviewModerated `REJECTED` / `REMOVED` (Phase 12) | customer | `REVIEW_NOT_PUBLISHED` · WARNING · `REVIEW` | "Your review of {targetName} was not published" / "… was removed" · "{reason}" · `/account/reviews` (data + decision) |
| ReviewReplied (Phase 12) | customer | `REVIEW_REPLY` · INFO · `REVIEW` | "{storeName} replied to your review" · "Your review of {targetName}." · `/account/reviews` |
| ReturnRequested (Phase 12, order.events) | owner of the order's store | `RETURN_REQUESTED` · WARNING | "Return requested for order {no}" · "{n} items · {refund} · decide by {HH:mm}" · `/vendor/returns` or `/restaurant/returns` (data: orderId, orderNumber, returnId, returnNumber, decideBy) |
| ReturnEscalated (Phase 12) | `role:SUPER_ADMIN`, `role:OPS_ADMIN`, `role:SUPPORT` | `RETURN_ESCALATED` · WARNING | "Return {RT} needs a decision" · "Order {no} · {refund} · the store did not decide in time." · `/admin/returns` |
| ReturnApproved (Phase 12) | customer | `RETURN_APPROVED` · SUCCESS | "Return {RT} approved" · "{refund} will be refunded." · `/orders/{id}` (data + refundAmount) |
| ReturnRejected (Phase 12) | customer | `RETURN_REJECTED` · WARNING | "Return {RT} rejected" · "{comment}" · `/orders/{id}` |

Phase 12 names: `{targetName}` = the product / dish name (from the order lines notification-service has seen), the store
name, or "your product" / "your dish" / "your store" / "your restaurant" (store owner), "a product" / "the store" …
(customer, admins). Review events carry neither the review's title nor its comment, so those are not shown. Return and
review notifications carry `returnId` / `reviewId` in `data`; `REFUND_COMPLETED` of a return also carries `returnId`.

Signals (§2) are sent in addition:
- **ORDER_UPDATED:** to the customer for every order event and every payment event of the order; to the store owner
  for OrderConfirmed and OrderCancelled after confirmation.
- **ADMIN_ORDERS_CHANGED:** for every order and payment event.
- **STOCK_CHANGED:** from ProductAvailabilityChanged. **PRICE_CHANGED:** from PriceChanged.
  **RESTAURANT_UPDATED:** from RestaurantListingUpdated.
- **INVENTORY_UPDATED:** from StockAdjusted / LowStockDetected / ProductAvailabilityChanged.
- **PRICING_UPDATED:** from the vendor's PriceChanged and PriceChange*.
- **APPROVALS_CHANGED:** from every Submitted / Approved / Rejected / Suspended / Reinstated and PriceChange*.
- **ACCOUNT_UPDATED:** to the owner, from every lifecycle event.
- **Phase 12 reviews:** `REVIEW_UPDATED {reviewId, status, event}` → the customer, and `ADMIN_REVIEWS_CHANGED {reviewId,
  status, event}` → all admin roles, on every review event; `STORE_REVIEWS_CHANGED {reviewId, targetType, targetId,
  event}` → the store owner when the review appears on, leaves or changes on the store's page (Submitted PUBLISHED,
  Moderated PUBLISHED / REMOVED, Replied; never for delivery-partner reviews).
- **Phase 12 returns** (every return event): `ORDER_UPDATED {orderId, status, event, returnId}` → the customer;
  `STORE_RETURNS_CHANGED {returnId, orderId, status}` → the store owner; `ADMIN_RETURNS_CHANGED {returnId, orderId,
  status}` and `ADMIN_ORDERS_CHANGED {orderId, event}` → all admin roles (`status` of the return: REQUESTED, ESCALATED,
  APPROVED, REJECTED).
- **Phase 12 email** ([contract §5](phase-12-post-mvp-features.md#5-email-notifications-notification-service-mailpit)):
  `ORDER_CONFIRMED`, `ORDER_CANCELLED`, `ORDER_DELIVERED`, `REFUND_COMPLETED`, `REFUND_FAILED`, `RETURN_APPROVED`,
  `RETURN_REJECTED`, `ACCOUNT_APPROVED`, `ACCOUNT_REJECTED`, `ACCOUNT_SUSPENDED` and `AI_APPROVAL_REQUESTED` are also
  emailed to the same recipients (role audiences: every active user of the role), unless the user turned the category
  off.

**Privacy rules (acceptance criteria):**
- A notification or signal is addressed only to the audiences above.
- Recipients come from the server's own projections, never from a request parameter.
- A vendor never sees another vendor's notifications or signals. `public` signals carry only ids and
  in-stock / price-change facts that are already public in search.

## 5. Projections and event age

- **Store owners:** `vendor.events` give `vendorId → ownerUserId` (+ business name), and `restaurant.events` give
  `restaurantId → ownerUserId` (+ name). Every lifecycle and listing event refreshes them.
- **Orders:** `OrderCreated` stores `orderId → orderNumber, orderType, customerId, storeId, storeName, total`, because
  payment events carry only the `orderId`. Order events update the stored status.
- **Order and missing owners:**
  - A payment event whose order is unknown yet throws a **transient** error, so it is retried until OrderCreated has
    been processed (different topics, no ordering guarantee).
  - An order event whose store owner is unknown still notifies the customer; the store notification is skipped and
    logged.
- **Event age:** an event older than `grocery.notification.max-event-age` (default **15 min**, by the `occurredAt`
  header) only updates projections. It creates no notification and no signal. So a new replica reading the topics
  from the start, or a DLT replay, never spams people with old news.
- **Idempotency:** `processed_event` (consumer `notification-service.dispatch`) is written in the same transaction as
  the notifications. The Redis publish happens after commit; if Redis is down at that moment, the notification is
  still in the inbox and the client sees it on its next list or reconnect.
- **Retention:** notifications older than 90 days are deleted every night.

## 6. Health and gateway

- `GET /api/health/notification` (public).
- **Gateway routes:**
  - `/api/notifications/**` goes to notification-service. The stream route has **no response timeout**; the
    15 s default would cut every stream.
  - Rate limiting is per user, as for every route.
  - CORS already allows `Last-Event-ID`.
- **Local replicas:**
  - Two replicas run locally for the multi-replica check: `notification-service` (host port 8113, behind the gateway)
    and `notification-service-2` (host port 8123, direct only; profile `realtime-ha`).
  - In production a load balancer spreads streams over all replicas; no sticky sessions are needed.

## 7. Consumer group

`notification-service.dispatch` subscribes to `identity.events`, `vendor.events`, `restaurant.events`,
`delivery.events`, `order.events`, `payment.events`, `inventory.events` and `pricing.events` (Phase 11 adds `ai.events`,
Phase 12 `review.events`). `catalog.events` is not needed in this phase. Values arrive as JSON through grocery-common's Avro deserializer (ADR-0011), like every consumer.
