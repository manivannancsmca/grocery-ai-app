# notification-service

Real-time updates (Phase 7, [contract](../../docs/api/phase-7-real-time-updates.md),
[ADR-0005](../../docs/adr/0005-sse-for-real-time.md); store fulfillment in Phase 8,
[contract §6](../../docs/api/phase-8-vendor-and-restaurant-fulfillment.md); delivery and the live partner location in
Phase 9, [contract §7](../../docs/api/phase-9-delivery.md); stuck orders in Phase 10,
[contract §4](../../docs/api/phase-10-event-driven-hardening.md); assistant approvals in Phase 11; reviews, returns,
**email** and the new assistants' links in Phase 12, [contract §1, §3, §5, §6](../../docs/api/phase-12-post-mvp-features.md)):
consumes the domain events, resolves **on the server** who
must hear about each one (from its own projections, never from a request parameter), keeps the notification
**inbox** and pushes notifications and "refetch" signals to every connected browser over **Server-Sent Events**.
Any number of replicas: Kafka gives each event to one replica, **Redis pub/sub** brings the result to the streams
of every replica. It publishes no Kafka events. Phase 12: the important notifications are also **emailed** through a
transactional `email_outbox` and a sender job (SMTP; Mailpit locally).

| | |
|---|---|
| Port | 8113 |
| Stores | MySQL `notification_db` (user `notification_user`) — tables `notification`, `notification_audience`, `notification_read`, `store_owner`, `order_ref`, `pending_event`, `ai_approval_ref`, `processed_event` (no Kafka outbox); Phase 12: `email_outbox`, `email_address`, `email_address_role`, `email_preference`, `item_name`; Redis pub/sub channel `grocery:realtime` (no keys stored) |
| Consumes | group `notification-service.dispatch` (listener id `notification-dispatch`, concurrency 3) on `identity.events`, `vendor.events`, `restaurant.events`, `delivery.events`, `order.events`, `payment.events`, `inventory.events`, `pricing.events`, `ai.events` (Phase 11), `review.events` (Phase 12), `customer.events` (CustomerProfileUpdated: the email address projection); Phase 9: group `notification-service.location` (listener id `notification-location`, **its own container**) on `delivery.location` |
| Publishes | nothing on Kafka (no outbox, no Debezium connector; grocery-event-schemas only as a test dependency); Phase 12: emails over SMTP (`spring.mail.*`) |
| API | `docs/api/phase-7-real-time-updates.md` §2 (stream), §3 (inbox), §4 (who is notified), §5 (projections, event age); `docs/api/phase-8-vendor-and-restaurant-fulfillment.md` §6 (store orders); `docs/api/phase-9-delivery.md` §7 (delivery, live location); `docs/api/phase-10-event-driven-hardening.md` §4 (stuck orders); `docs/api/phase-11-agentic-ai-and-approvals.md` §8; `docs/api/phase-12-post-mvp-features.md` §1 / §3 (review and return notifications), §5 (email), §6 (approval links) |

```
Kafka (8 topics) ──► the replica that owns the partition ── one transaction ──────────────────────────────┐
     processed_event ─► projections (store_owner, order_ref) ─► [event ≤ 15 min old] notifications + audiences │
                                                                                                            ▼
                    after commit: PUBLISH grocery:realtime  { kind, audiences, payload }  (one per notification / signal / session)
delivery.location (Phase 9) ──► group notification-service.location (own container) ─► ≤ 30 s old? order_ref → customer?
                    1 per order per 2 s? ─► PUBLISH DELIVERY_LOCATION → user:<customer> (nothing stored)
Redis ──► EVERY replica ──► fan-out thread ──► each LOCAL stream whose audiences intersect ──► per-stream send queue ──► SSE
```

## Endpoints
| Method & path | Who | |
|---|---|---|
| `GET /api/notifications/stream` | any authenticated user | `text/event-stream` (header `Last-Event-ID` optional); see "The stream" |
| `GET /api/notifications?unread=&page=&size=` | 〃 | `PageResponse<NotificationView>`, newest first; `unread=true` → only unread; `size` 1–50 (clamped, default 20) |
| `GET /api/notifications/unread-count` | 〃 | `{ "count": n }` |
| `POST /api/notifications/{id}/read` | 〃 | 204, idempotent; somebody else's, unknown or malformed id → 404 `NOTIFICATION_NOT_FOUND` |
| `POST /api/notifications/read-all` | 〃 | `{ "updated": n }` (how many were unread) |
| `GET /api/notifications/preferences` | 〃 (Phase 12) | `{ "email": { "orders": true, "account": true, "approvals": true } }` — the caller's own; default all `true` |
| `PUT /api/notifications/preferences` | 〃 (Phase 12) | same body → the saved preferences; a missing category keeps its value; no `email` object → 400 `VALIDATION_FAILED` |
| `GET /api/admin/notifications/emails?status=PENDING\|SENT\|FAILED&to=&page=&size=` | any admin (Phase 12) | `PageResponse<{ id, notificationId, to, subject, type, status, attempts, lastError, createdAt, sentAt }>`, newest first; `to` = part of the address, any case; `size` 1–100 (default 20); unknown status → 400 `INVALID_PARAMETER` |
| `GET /actuator/health` | public | gateway: `GET /api/health/notification` (SMTP is **not** part of health: `management.health.mail.enabled=false`) |

"The caller's notifications" = those with an audience row for `user:<sub>` or `role:<R>` of a role in the token.
Read state is a `(user_id, notification_id)` row: admin A reading a `role:OPS_ADMIN` notification does not mark it
read for admin B. A notification for several roles is one row with several audience rows, so an admin holding two
of those roles sees (and counts) it once. No token → 401 `UNAUTHENTICATED`; `unread=maybe` → 400 `INVALID_PARAMETER`.

`NotificationView`: `{ id (UUIDv7 = the SSE id), type, category, severity, title, body, link, data, read, createdAt }`.
`data` carries the ids the app needs: `orderId` + `orderNumber` on every ORDER / PAYMENT notification (+ `acceptBy`
on `NEW_ORDER`); `kind` on
`APPROVAL_REQUESTED`, `PRICE_APPROVAL_REQUESTED` (`PRICE_CHANGE`) and every `ACCOUNT_*`; `productId` (+ `requestId`
for price changes) on STOCK / PRICING ones.

## The stream (`service/StreamService.java`, `realtime/*`)
```
retry: 3000                                   ← always first

event: notification                           ← persistent: stored, replayed after a reconnect
id: 019a6c2e-…                                ← only notification events carry an id
data: {"id":"019a6c2e-…","type":"ORDER_CONFIRMED",…,"read":false,"createdAt":"…"}

event: signal                                 ← ephemeral "refetch": never stored, never replayed
data: {"type":"ORDER_UPDATED","data":{"orderId":"…","status":"CONFIRMED","event":"OrderConfirmed"},"at":"…"}

: hb                                          ← heartbeat comment every 20 s

event: session                                ← the server closes the stream right after it
data: {"reason":"TOKEN_EXPIRED" | "REVOKED" | "REPLACED"}
```
- **Response:** `200`, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`. Frames
  are written as `field: value` lines (one space after the colon), JSON on one `data:` line.
- **Audiences** come from the validated token only: `user:<sub>`, `role:<R>` per role, `public`.
- **Opening order:** `retry` → register the connection (live frames are **held** from now on) → replay → send the
  held frames → live. Registering before the replay query means nothing committed meanwhile is missed.
- **Replay** (`Last-Event-ID`): the caller's notifications with a greater id, oldest first, at most `replay-limit`
  (200); more → then `signal RESYNC` (`data: {}`). An unknown, foreign or malformed id → only `RESYNC`. No header →
  no replay. A connection remembers the ids it replayed (≤ 200) and drops their live copies, held or arriving later,
  so a notification is never sent twice.
- **Lifetime:** `session TOKEN_EXPIRED` at the token's `exp` (scheduled per connection). The emitter timeout is set
  explicitly to the token's remaining lifetime + 1 minute, so Tomcat's default async timeout never cuts a stream.
  A token that the JWT validator still accepts (60 s clock skew) but whose `exp` has passed gets `retry` +
  `TOKEN_EXPIRED` at once. Suspensions (UserSuspended / VendorSuspended / RestaurantSuspended /
  DeliveryPartnerSuspended) publish `session REVOKED` for `user:<owner>`: every replica sends it to that user's
  streams (after the notification and signals of the same event) and closes them.
- **At most 5 streams per user per replica** (`max-streams-per-user`): one more closes the oldest with
  `session REPLACED`. The registry is local to each replica (no shared connection registry, ADR-0005), so with N
  replicas a user can hold up to 5 × N streams.
- **Serialised sends, no leaks:** every connection has one FIFO send queue drained by at most one virtual thread, so
  writes never overlap and keep their order; the Redis fan-out thread and the schedulers only enqueue. The first
  failed write (client gone — the 20 s heartbeat finds it), the emitter's completion / timeout / error callbacks, a
  session close and shutdown all remove the connection (`StreamConnection.terminate()`, idempotent). A client more
  than 1,000 frames behind is disconnected (it reconnects with its Last-Event-ID).
- **Shutdown:** the registry is a `SmartLifecycle` that stops first and completes every stream, so graceful shutdown
  does not wait for open streams; clients reconnect (to another replica) and replay.

## Multi-replica fan-out (`realtime/RealtimePublisher.java`, `RealtimeSubscriber.java`, `ConnectionRegistry.java`)
- One consumer group: each event is processed **once**, by the replica owning its partition.
- After the event's transaction committed, that replica publishes one message per notification / signal / session
  on `grocery:realtime`: `{ "kind": "notification" | "signal" | "session", "audiences": ["user:…", "role:…",
  "public"], "payload": { …the SSE data… } }`, in order (notifications, signals, sessions), from one background
  thread (Kafka consumer threads never wait for Redis; queue bounded at 10,000).
- **Every** replica (the publisher included) is subscribed. Its single fan-out thread turns each message into the SSE
  frame once and writes it to every **local** connection whose audiences intersect the message's — once per
  connection, even when several audiences match (an index by audience key, deduplicated). There is no shared
  connection registry and no sticky session.
- Redis down at publish time: the message is dropped and logged; the notification is already in the inbox, so the
  client sees it on its next list or reconnect. Redis back: the listener container re-subscribes (every 2 s).
- Two replicas locally: `notification-service` (8113, behind the gateway) and `notification-service-2` (8123,
  profile `realtime-ha`). `MultiReplicaIntegrationTest` starts a second application context on the same MySQL and
  Redis and checks both directions.

## Dispatch (`service/DispatchService.java`, `dispatch/*EventHandler.java`, `dispatch/NotificationTemplates.java`)
One transaction per record: `processed_event` (a duplicate stops here) → the handler updates its projections (or
parks a payment event whose order is unknown, see below) → if the event is **fresh** (its `occurredAt` header —
Debezium's epoch microseconds; ISO-8601 and epoch ms accepted; missing or unreadable = new — is not older than
`max-event-age`, 15 min) it plans notifications, signals and session closes → notifications and audiences are
inserted → if the event made an order known, that order's parked payment events are applied → after commit,
published. Old events (a new consumer group reading from the start, a DLT replay) **only update projections**: no
notification, no signal, no session.

| Kafka event | Persistent notification → audience | Signals |
|---|---|---|
| OrderCreated / OrderAwaitingPayment | — | `ORDER_UPDATED` → customer; `ADMIN_ORDERS_CHANGED` → all admin roles |
| OrderConfirmed | `ORDER_CONFIRMED` → customer (Phase 8: **no** `NEW_ORDER` any more, see VendorOrderReceived) | `ORDER_UPDATED` → customer + store owner; `ADMIN_ORDERS_CHANGED` |
| OrderAccepted (Phase 8) | `ORDER_ACCEPTED` · SUCCESS → customer: "{storeName} accepted order {no}" · "Ready in about {readyInMinutes} min." | `ORDER_UPDATED` → customer; `ADMIN_ORDERS_CHANGED` |
| OrderPreparing (Phase 8) | — | 〃 |
| OrderReadyForPickup (Phase 8) | `ORDER_READY` · INFO → customer: "Order {no} is ready" · grocery "Packed and waiting for a delivery partner." / restaurant "Your food is ready and waiting for a delivery partner."; Phase 9: + `PICKUP_READY` · INFO → the assigned partner (from `order_ref`): "Order {no} is ready at {store}" · `/delivery` | 〃 |
| OrderOutForDelivery (Phase 9) | `OUT_FOR_DELIVERY` · INFO → customer: "Order {no} is on the way" · `/orders/{id}` | `ORDER_UPDATED` → customer; `ADMIN_ORDERS_CHANGED`; `STORE_ORDERS_CHANGED {orderId, PICKED_UP}` → store owner |
| OrderDelivered (Phase 9) | `ORDER_DELIVERED` · SUCCESS → customer: "Order {no} delivered" · "Enjoy!" · `/orders/{id}` | `ORDER_UPDATED` → customer; `ADMIN_ORDERS_CHANGED` |
| OrderDeliveryFailed (Phase 9) | `DELIVERY_FAILED` · ERROR → customer: "We could not deliver order {no}" · "Our support team will contact you about a refund." · `/orders/{id}`; `DELIVERY_FAILED_ADMIN` · ERROR → SUPER_ADMIN, OPS_ADMIN, SUPPORT, DELIVERY_MANAGER (one row): "Delivery failed for order {no}" · "{reason}. Decide on a refund in the payment monitor." · `/admin/orders` | 〃 |
| OrderStuckDetected (Phase 10) | `STUCK_ORDER` · WARNING → SUPER_ADMIN, OPS_ADMIN, SUPPORT (one row): "Order {no} is stuck: {reason label}" · "{status} since {HH:mm}." · `/admin/orders` | `ADMIN_ORDERS_CHANGED {orderId, event}` → all admin roles (no `ORDER_UPDATED`: the order did not change) |
| DeliveryOfferCreated (Phase 9) | `DELIVERY_OFFER` · INFO → the partner: "New delivery from {store}" · "{distanceKm} km away · respond within {s} s" · `/delivery` (not sent when the offer has already expired) | `DELIVERY_OFFERS_CHANGED {deliveryId, offerId}` → partner; `ADMIN_DELIVERIES_CHANGED` |
| DeliveryOfferClosed (Phase 9) | — | `DELIVERY_OFFERS_CHANGED {deliveryId, offerId}` → partner; `ADMIN_DELIVERIES_CHANGED` |
| DeliveryPartnerAssigned (Phase 9) | `PARTNER_ASSIGNED` · INFO → customer: "{partnerFirstName} will deliver order {no}" (no name: "A delivery partner …") · `/orders/{id}` | `DELIVERY_UPDATED {deliveryId, orderId}` → partner; `STORE_ORDERS_CHANGED {orderId, status}` → store owner; `ADMIN_DELIVERIES_CHANGED` |
| DeliveryPartnerUnassigned (Phase 9) | `DELIVERY_REASSIGNED` · WARNING → the partner: "Order {no} was reassigned" · `/delivery` | `ADMIN_DELIVERIES_CHANGED` |
| DeliveryAssignmentFailed (Phase 9) | `NO_DELIVERY_PARTNER` · WARNING → SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER (one row): "No delivery partner for order {no}" · "Assign one by hand." · `/admin/deliveries` | `ADMIN_DELIVERIES_CHANGED` |
| DeliveryCancelled (Phase 9) | with a partner: `DELIVERY_CANCELLED` · WARNING → the partner: "Order {no} was cancelled" · "Do not pick it up." · `/delivery` | `ADMIN_DELIVERIES_CHANGED` |
| DeliveryPickedUp / DeliveryCompleted / DeliveryFailed (Phase 9) | — (the customer and the store hear it from the order events) | `ADMIN_DELIVERIES_CHANGED {deliveryId, orderId}` → SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER |
| DeliveryLocationUpdated (Phase 9, delivery.location) | — | `DELIVERY_LOCATION {orderId, latitude, longitude, heading, at}` → the order's customer only (see "Live location") |
| OrderRejected | `ORDER_REJECTED` → customer | `ORDER_UPDATED` → customer; `ADMIN_ORDERS_CHANGED` |
| OrderCancelled | `ORDER_CANCELLED` → customer (STORE_REJECTED: "{storeName} could not accept your order ({reason label}{: note}). A full refund is on its way."; STORE_NO_RESPONSE: "The store did not respond in time. A full refund is on its way."); + store owner ("do not prepare it") when previousStatus was CONFIRMED or later and the store did not reject it itself | `ORDER_UPDATED` → customer (+ store owner when previousStatus was CONFIRMED or later); `ADMIN_ORDERS_CHANGED`; `STORE_ORDERS_CHANGED {orderId, CANCELLED}` → store owner when previousStatus was CONFIRMED or later |
| VendorOrderReceived / RestaurantOrderReceived (Phase 8) | `NEW_ORDER` · INFO → the event's `ownerUserId`: "New order {no}" · "{n} items · {total} · accept by {HH:mm India time}" · `/vendor/orders` / `/restaurant/orders`, `data` + `acceptBy` | `STORE_ORDERS_CHANGED {orderId, NEW}` → store owner |
| …OrderAccepted / …OrderPreparing / …OrderReadyForPickup (Phase 8) | — | `STORE_ORDERS_CHANGED {orderId, ACCEPTED / PREPARING / READY_FOR_PICKUP}` → store owner |
| …OrderRejected (Phase 8) | with `auto: true`: `ORDER_AUTO_REJECTED` · WARNING → store owner: "Order {no} was auto-rejected" · "It was not accepted in time. The customer has been refunded." · board link | `STORE_ORDERS_CHANGED {orderId, REJECTED}` → store owner |
| PaymentFailed (`retryable`) | `PAYMENT_FAILED` → customer | every payment event: `ORDER_UPDATED` → customer (status = the order's last known status); `ADMIN_ORDERS_CHANGED` |
| RefundCompleted | `REFUND_COMPLETED` → customer | 〃 |
| RefundFailed | `REFUND_FAILED` → `role:SUPER_ADMIN` + `role:FINANCE_ADMIN` (one row) | 〃 |
| PaymentInitiated / Captured / Expired, PaymentFailed not retryable | — | 〃 |
| LowStockDetected | `LOW_STOCK` → vendor owner | `INVENTORY_UPDATED` → vendor owner |
| StockAdjusted | — | `INVENTORY_UPDATED` → vendor owner |
| ProductAvailabilityChanged | — | `STOCK_CHANGED` → `public` (throttled); `INVENTORY_UPDATED` → vendor owner |
| PriceChanged | — | `PRICE_CHANGED` → `public` (throttled); `PRICING_UPDATED` → vendor owner |
| PriceChangeRequested | `PRICE_APPROVAL_REQUESTED` → SUPER / OPS / FINANCE admin (one row) | `APPROVALS_CHANGED {PRICE_CHANGE}` → same roles; `PRICING_UPDATED` → vendor owner |
| PriceChangeApproved / Rejected | `PRICE_CHANGE_APPROVED` / `_REJECTED` → vendor owner | 〃 |
| PriceChangeCancelled | — | 〃 |
| Vendor/Restaurant/DeliveryPartner Registered | — | `ACCOUNT_UPDATED {kind, DRAFT}` → owner |
| … Submitted | `APPROVAL_REQUESTED` → SUPER + OPS admin (+ DELIVERY_MANAGER for partners) | `APPROVALS_CHANGED {kind}` → those roles; `ACCOUNT_UPDATED` → owner |
| … Approved / Rejected / Reinstated | `ACCOUNT_APPROVED` / `_REJECTED` / `_REINSTATED` → owner | 〃 |
| … Suspended | `ACCOUNT_SUSPENDED` → owner | 〃 + `session REVOKED` → owner |
| UserSuspended | — | `ACCOUNT_UPDATED {USER, SUSPENDED}` → user + `session REVOKED` |
| UserReactivated | `ACCOUNT_REINSTATED` → user (link `/`) | `ACCOUNT_UPDATED {USER, ACTIVE}` → user |
| RestaurantListingUpdated | — | `RESTAURANT_UPDATED` → `public` (throttled) |
| UserRegistered (Phase 12) | — (email address projection only) | — |
| PasswordResetRequested | — (**email only**, see "Security emails") | — |
| PasswordChanged | — (**email only**, see "Security emails") | — |
| CustomerProfileUpdated (customer.events) | — (the customer's name in the email address projection; old events too) | — |
| ReviewSubmitted PUBLISHED (Phase 12) | `NEW_REVIEW` · INFO · REVIEW → owner of the review's store (not for DELIVERY_PARTNER reviews): "New {rating}★ review" · "{targetName}" (+ " (edited)") · `/vendor/reviews` / `/restaurant/reviews` | `STORE_REVIEWS_CHANGED` → store owner; `REVIEW_UPDATED` → customer; `ADMIN_REVIEWS_CHANGED` → all admin roles |
| ReviewSubmitted FLAGGED (Phase 12) | `REVIEW_FLAGGED` · WARNING · REVIEW → SUPER_ADMIN, OPS_ADMIN, SUPPORT (one row): "A {rating}★ review needs moderation" · "{targetName} · {flag label}" · `/admin/reviews` | `REVIEW_UPDATED`; `ADMIN_REVIEWS_CHANGED` |
| ReviewModerated (Phase 12) | REJECTED / REMOVED: `REVIEW_NOT_PUBLISHED` · WARNING · REVIEW → customer: "Your review of {targetName} was not published" / "… was removed" · "{reason}" · `/account/reviews`; PUBLISHED: — | `REVIEW_UPDATED`; `ADMIN_REVIEWS_CHANGED`; PUBLISHED / REMOVED: `STORE_REVIEWS_CHANGED` |
| ReviewReplied (Phase 12) | `REVIEW_REPLY` · INFO · REVIEW → customer: "{storeName} replied to your review" · "Your review of {targetName}." · `/account/reviews` | `STORE_REVIEWS_CHANGED`; `REVIEW_UPDATED`; `ADMIN_REVIEWS_CHANGED` |
| RatingSummaryUpdated (Phase 12) | — (ignored, no `processed_event` row) | — |
| ReturnRequested (Phase 12) | `RETURN_REQUESTED` · WARNING · ORDER → owner of the order's store: "Return requested for order {no}" · "{n} items · {refund} · decide by {HH:mm}" · `/vendor/returns` / `/restaurant/returns` | every return event: `ORDER_UPDATED {orderId, status, event, returnId}` → customer; `STORE_RETURNS_CHANGED {returnId, orderId, status}` → store owner; `ADMIN_RETURNS_CHANGED {returnId, orderId, status}` + `ADMIN_ORDERS_CHANGED` → all admin roles |
| ReturnEscalated (Phase 12) | `RETURN_ESCALATED` · WARNING → SUPER_ADMIN, OPS_ADMIN, SUPPORT (one row): "Return {RT} needs a decision" · "Order {no} · {refund} · the store did not decide in time." · `/admin/returns` | 〃 |
| ReturnApproved (Phase 12) | `RETURN_APPROVED` · SUCCESS → customer: "Return {RT} approved" · "{refund} will be refunded." · `/orders/{id}` | 〃 |
| ReturnRejected (Phase 12) | `RETURN_REJECTED` · WARNING → customer: "Return {RT} rejected" · "{comment}" · `/orders/{id}` | 〃 |

Texts, links and severities: `NotificationTemplates` (one place). Money `₹1,234.50` with Indian grouping
(`₹12,34,567.00`, `dispatch/Money.java`); missing names → "The store", "a product", "A vendor"; `NEW_ORDER` counts the
ordered quantity ("1 item" / "3 items"); a positive `changePercent` shows its sign ("+12.50%"); times shown to people
are India time (`HH:mm`, Asia/Kolkata).

- **Phase 8 store orders** (`dispatch/StoreOrderEventHandler.java`): `VendorOrder*` on vendor.events and
  `RestaurantOrder*` on restaurant.events (Received, Accepted, Rejected, Preparing, ReadyForPickup); the store
  lifecycle events stay with `AccountEventHandler`. The NEW_ORDER recipient is the Received event's `ownerUserId`
  (it also fills a **missing** `store_owner` row; the lifecycle events stay the source of truth); the other store
  events find the owner in `store_owner` — unknown → nothing is sent (logged). The `STORE_ORDERS_CHANGED` status is
  the store order's (NEW … REJECTED; CANCELLED from OrderCancelled). Store events never touch `order_ref`; the
  order's status follows order.events (now also ACCEPTED, PREPARING, READY_FOR_PICKUP).
- **Rejection reason labels** (STORE_REJECTED, from the OrderCancelled note "&lt;reason&gt;: &lt;note&gt;" that
  order-service writes): OUT_OF_STOCK "items out of stock", STORE_CLOSED "store closed", TOO_BUSY "too busy right
  now", OTHER "" (just the note; nothing at all → no parentheses); a note without a known reason is shown as is.
- **Refund sentence:** " A full refund is on its way." is added when previousStatus was CONFIRMED **or later**
  (ACCEPTED, PREPARING, READY_FOR_PICKUP — paid, admin-cancellable since Phase 8); the store-decision texts include it.

- **Phase 9 delivery** (`dispatch/DeliveryEventHandler.java`; the partner lifecycle events on delivery.events stay
  with `AccountEventHandler`):
  - `data` carries `orderId`, `orderNumber` and `deliveryId` / `offerId` where relevant (`DELIVERY_FAILED_ADMIN`
    also the `reason` code). Every delivery notification has category ORDER.
  - **Privacy:** an offered partner gets only what their OfferView shows — the store's name, the distance, the time
    to respond and the ids (no customer name, address or phone). The assigned partner hears about the order
    (`PICKUP_READY`, `DELIVERY_UPDATED`), never the customer's details; customers see the partner's **first name**
    only.
  - `{s}` = whole seconds from now until the offer's `expiresAt`, rounded up; an offer already expired when handled
    notifies nobody (its `DELIVERY_OFFERS_CHANGED` still refreshes the list). `{distanceKm}` with one decimal;
    `{store}` = the event's store name, else the order's, else "a store".
  - `{reason}` of `DELIVERY_FAILED_ADMIN`: CUSTOMER_UNREACHABLE "Customer unreachable", ADDRESS_NOT_FOUND "Address
    not found", CUSTOMER_REFUSED "Customer refused the order", OTHER "Other reason", + ": {note}" when the partner left
    one.
  - **Partner projection:** `order_ref.partner_id` / `delivery_id` (Flyway V4) are set by DeliveryPartnerAssigned
    (also from an old event) and cleared by DeliveryPartnerUnassigned and DeliveryCancelled — only when it is still
    the recorded partner, so a stale unassignment never removes a newer partner. OrderReadyForPickup tells that
    partner (`PICKUP_READY`); nobody recorded → nobody told.
  - A **DeliveryPartnerAssigned whose order is unknown** is parked in `pending_event` exactly like a payment event
    (it needs the order's customer and store) and applied when the order becomes known.
  - `STORE_ORDERS_CHANGED` on an assignment carries the order's current status from `order_ref`; on
    OrderOutForDelivery the store order's new status `PICKED_UP`.
- **Phase 10 stuck orders** (`dispatch/StuckOrderEventHandler.java`): order-service's watchdog publishes
  `OrderStuckDetected` once per order and status. `{reason label}`: STOCK_CHECK_OVERDUE "stock check overdue",
  PAYMENT_EXPIRY_OVERDUE "payment expiry overdue", STORE_RESPONSE_OVERDUE "store response overdue",
  PREPARATION_OVERDUE "preparation overdue", PICKUP_OVERDUE "pickup overdue", DELIVERY_OVERDUE "delivery overdue"
  (anything else as it is); `{status}` = the order status as published (e.g. `CONFIRMED since 14:35.`), `{HH:mm}` =
  `since` in India time. `data` = `orderId`, `orderNumber`, `status`, `reason`; category ORDER. The `order_ref`
  projection is not touched and the customer hears nothing; missing `orderId` / `orderNumber` / `status` / `reason` →
  the DLT.
- **Live location** (`event/LocationListener.java`, `service/LocationService.java`, `realtime/LocationThrottle.java`):
  a second consumer group, `notification-service.location`, on `delivery.location` — its own listener container (own
  consumer threads), so the high-frequency positions never delay the domain events; a new group starts at the latest
  offset. For each `DeliveryLocationUpdated` (the outbox headers are optional: delivery-service produces directly;
  a record whose `eventType` header names another type is ignored):
  - recorded (`recordedAt`; else the `occurredAt` header; else the record timestamp) longer ago than
    `location-max-age` (30 s) → dropped;
  - the customer is resolved through `order_ref` (never from the event): unknown order → dropped; an order that has
    ended (DELIVERED, DELIVERY_FAILED, CANCELLED, REJECTED) → dropped;
  - at most one per order per `location-throttle` (2 s): the first of a window is sent, the others are dropped (no
    trailing send — the next position follows within seconds). In memory, per replica (the topic is keyed by
    orderId);
  - → signal `DELIVERY_LOCATION {orderId, latitude, longitude, heading, at}` to `user:<customerId>` only, published
    at once. Nothing is stored (no notification, no `processed_event`); a failure is logged and the position dropped
    (never retried or dead-lettered — best effort).
- **Public throttle** (`realtime/PublicSignalThrottle.java`): at most one `STOCK_CHANGED` / `PRICE_CHANGED` per
  product and one `RESTAURANT_UPDATED` per restaurant per `public-signal-throttle` (2 s). The first goes at once;
  later ones in the window collapse into one trailing signal with the latest state at the end of the window. In
  memory: all events of one product / restaurant are keyed to one partition, hence one replica.
- **Projections:** `store_owner` from every vendor / restaurant lifecycle event and RestaurantListingUpdated (a
  missing name keeps the known one); `order_ref` from OrderCreated (status never moved back by a replay) and every
  order event (status + fields still unknown).
- **Payment before its order — parking** (`repository/PendingEventRepository.java`, Flyway V3): payment events
  carry only the orderId, and OrderCreated is on another topic (no ordering guarantee). A payment event whose
  `order_ref` is missing is **parked** in `pending_event` (event id, order id, topic, type, version, its original
  `occurredAt`, the payload JSON as received, `received_at`) in the same transaction as its `processed_event` mark,
  and the record is done: **no exception, the partition is never blocked**. (A blocking retry would be dangerous:
  `order.events` and `payment.events` are co-partitioned by orderId and the default RangeAssignor gives partition N
  of both to the same consumer thread, so the retry could starve the very OrderCreated it waits for — e.g. on a
  backlog after downtime — and finally dead-letter a valid event.)
  - The transaction of the order event that makes the order known (OrderCreated, or the first order event seen for
    it) applies the order's parked events after its own work: in arrival order, each judged by its **original**
    `occurredAt` for the max-event-age rule (one that has grown older meanwhile notifies nobody), then deletes them.
    Their messages are published after the order event's own. This also works when the OrderCreated itself is old.
  - Idempotent: a redelivered payment event stops at its processed mark (never parked or applied twice; the event id
    is also unique in `pending_event`); a redelivered OrderCreated stops at its own, and the parked rows are gone.
  - Concurrency: the payment handler reads `order_ref` with a locking read (`FOR SHARE`), the drain takes the parked
    rows `FOR UPDATE`. Under InnoDB's default REPEATABLE READ, a payment event either waits for a concurrent
    uncommitted OrderCreated (and then applies directly), or gap-locks the missing row so that OrderCreated waits
    until the parked row is committed — and then finds it. Deadlock victims are transient and retried.
  - An old payment event (older than max-event-age) of an unknown order is neither parked nor kept waiting: it
    notifies nobody anyway.
  - The nightly cleanup drops parked events older than `pending-event-max-age` (24 h), each with a WARN: they can only
    belong to orders this service never heard of.
- **Missing store owner:** the customer is still notified; the store's notification and signal are skipped and
  logged. Same for vendor-owned stock / pricing news of an unknown vendor.
- **Errors:** unreadable payloads, missing headers or required ids → `InvalidEventException` (`<topic>.DLT` at once).
  Database outages propagate as transient → the same record is retried. Event types this service does not use
  (reservations, StockCommitted, RatingSummaryUpdated, …) are ignored without a `processed_event` row (Phase 12:
  UserRegistered is used now, for the email address projection).
- **Phase 12 reviews and returns** (`dispatch/ReviewEventHandler.java`, `dispatch/ReturnEventHandler.java`):
  - **Privacy:** the store's notification and `STORE_*_CHANGED` signal go to the owner the `store_owner` projection
    knows for the event's own store (`storeId`; reviews: the store type from `targetType` — PRODUCT / VENDOR →
    vendor, MENU_ITEM / RESTAURANT → restaurant; returns: from `orderType`), so a store never hears about another
    store's reviews or returns. DELIVERY_PARTNER reviews are not the store's business: no store notification or signal.
  - **Names** (`{targetName}`): products and dishes from the new `item_name` projection (Flyway V7: the lines of
    OrderCreated / OrderConfirmed — a review is always about an item of a delivered order), stores from `store_owner`;
    fallbacks per reader: store owner "your product" / "your dish" / "your store" / "your restaurant", customer and
    admins "a product" / "a dish" / "the store" (customer) / "a store" (admins) …, "your / a delivery partner".
    review.events carry neither the review's title nor its comment, so NEW_REVIEW shows the name only (contract:
    "{targetName}: {title or first 80 chars}").
  - **Flag reason labels** (REVIEW_FLAGGED): LINK / URL "contains a link", PHONE "contains a phone number", EMAIL
    "contains an email address", PROFANITY "contains a blocked word", UPPERCASE / CAPS / SHOUTING "mostly capital
    letters", other codes in lower case, several joined with ", ", none "flagged by automatic moderation".
  - Neither handler writes a projection: events older than max-event-age do nothing; returns need no known order
    (everything is in the event; `ORDER_UPDATED`'s `status` is the order's last known one, left out when unknown).
  - `RefundCompleted` of a return (optional `returnId`, schemas 1.5.0) keeps its texts; `data` gains `returnId`.
- **Retention:** every night (`retention-cron`, 03:30 India time) notifications older than `retention` (90 d) are
  deleted in batches of 1,000 (audience and read rows cascade), and parked events older than
  `pending-event-max-age` (24 h) are dropped with a WARN each.

## Events: Avro (ADR-0011)
Values on all eight topics are Avro (Confluent wire format) or legacy JSON; grocery-common 0.6.0's
`AvroEventDeserializer` hands the listener the JSON the producer wrote, so the payload records in
`event/EventPayloads.java` are plain tolerant DTOs (field names from `libs/grocery-event-schemas/src/main/avro`).
Decimals arrive at their schema scale (`245.00`). Registry: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL`; an
unreachable registry is transient (retried), undecodable bytes go to the DLT. This service publishes nothing, so it
needs no schemas at runtime; `grocery-event-schemas` (1.7.0) is a **test** dependency (a DeliveryLocationUpdated is
encoded as delivery-service's direct producer does; the ai, review and return events of the tests are encoded with the
real schemas), so the Dockerfile installs the module before `package`.

## Email (Phase 12 §5; `email/*`, `channel/NotificationChannel.java`)
```
dispatch transaction:  notification + audiences ─► EmailOutbox (a NotificationChannel): type emailed? ─► recipients
                       (user:<id> → that user; role:<R> → every active user holding R; active; preference on)
                       ─► email_outbox rows (PENDING, unique per notification + recipient)      ← same transaction
EmailSenderSchedule (every 5 s, any number of replicas)
   short tx: SELECT … FOR UPDATE SKIP LOCKED (≤ 20 due rows) + push their due time by the lease (10 min) ─► commit
   per email: render (text + HTML) ─► SMTP ─► SENT | retry after 1 m, 5 m, 30 m | then FAILED
```
- **Types** (`grocery.notification.email.types`): ORDER_CONFIRMED, ORDER_CANCELLED, ORDER_DELIVERED, REFUND_COMPLETED,
  REFUND_FAILED, RETURN_APPROVED, RETURN_REJECTED, ACCOUNT_APPROVED, ACCOUNT_REJECTED, ACCOUNT_SUSPENDED,
  AI_APPROVAL_REQUESTED; the rest stay in-app only. The channel is **off** unless `GROCERY_MAIL_ENABLED=true`
  (then no outbox rows and no sender at all); compose turns it on, with Mailpit.
- **Preference categories** (`email_preference`, no row = all on): orders / refunds / returns → `orders`, ACCOUNT_* →
  `account`, AI_APPROVAL_REQUESTED → `approvals`; a type added to the list later follows its notification category
  (APPROVAL → approvals, ACCOUNT → account, else orders).
- **Address projection** (`email_address` + `email_address_role`, Flyway V6):
  - `UserRegistered` (also old events) → address, full name, the registered role; UserSuspended / UserReactivated →
    `active` (a suspended user gets no email); an unusable address is skipped (WARN), never dead-lettered.
  - identity-service publishes **no event for admins** (`POST /api/admin/users`, the bootstrap SUPER_ADMIN), and none
    for later role changes. So the **validated access token** of every caller of the stream, the inbox list and the
    preferences API is remembered too (`AddressBook`: `email`, `name` and the token's current `roles`, which replace
    the known ones; written only when they changed, in its own short transaction, never failing the request). An admin
    is emailed for role audiences once they have used the web app (the bell opens the stream on every page).
  - Users registered before Phase 12 were consumed (and ignored) already: they become known the same way, or by
    replaying `identity.events` for the group (old events only update projections, nobody is notified):
    `kafka-consumer-groups --bootstrap-server kafka:29092 --group notification-service.dispatch --topic identity.events
    --reset-offsets --to-earliest --execute` (with the service stopped). UserSuspended / UserReactivated already marked
    in `processed_event` are not replayed (35-day housekeeping), so a still-suspended old user may show as active
    until their next suspension event; they cannot sign in anyway.
- **Format** (`EmailRenderer`): subject = the notification title (one line); a text part and a simple HTML part with
  the same title, body and an "Open in Grocery" link = `APP_BASE_URL` + the notification's route; From
  `Grocery <no-reply@grocery.local>`; header `X-Grocery-Notification-Id`. **Every** text in the HTML part is escaped
  (`Html.escape`: `& < > " '`, in content and in the `href`), so a store name, a reason or a comment can never add
  markup.
- **Reliability:** SMTP is only talked to by the sender job, after the claiming transaction committed — SMTP down
  never touches the in-app notification (nor the health endpoint). A run that takes longer than half the lease gives
  its unsent rows back; a replica that dies mid-run leaves its rows to the next claim after the lease. An address
  SMTP cannot parse fails at once (FAILED). SENT and FAILED rows are never taken again; the only possible repeat is a
  crash between the SMTP server accepting a message and the SENT update.
- **Retention:** `email_outbox` rows are deleted with their notification (FK cascade, 90 days).
- **Security emails** (`dispatch/PasswordEventHandler.java`, `EmailProperties.SECURITY_TYPES`): identity.events
  `PasswordResetRequested` → `PASSWORD_RESET_REQUESTED` "Reset your Grocery password" (greeting, the account's address,
  "It works once and expires in {n} minutes, at {HH:mm} India time", button **Choose a new password** →
  `APP_BASE_URL/reset-password?token=…`); `PasswordChanged` → `PASSWORD_CHANGED` "Your Grocery password was changed"
  (how — "in your account settings" / "with a password reset link" — and when, who was signed out, "If this wasn't you,
  reset your password now", button **Reset your password** → `APP_BASE_URL/forgot-password`).
  - Sent to the address **in the event**, whatever the type list and the preferences say, even when the address
    projection does not know the user (an admin who never opened the app); only `email.enabled` stops them. Their
    footer says so (no "turn it off").
  - **Never in an inbox:** the dispatch stores a notification row **without audience rows** (the email rows need one:
    foreign key, retention), so no list, unread count, stream or replay shows it, and no signal is sent. That row keeps
    neither the link nor the data; the reset link (with the raw token) exists only in the email's `email_outbox` row,
    which the admin email log and its export never show. The token is never logged.
  - Like every notification, an event older than `max-event-age` sends nothing (a replay never emails an old link
    again); a reset link that has already expired is not sent; an unusable address is logged and skipped.
- **Names:** `customer.events` `CustomerProfileUpdated` renames a known user in `email_address` (the name in the To
  header), so a customer who changed their name is greeted with it; an unknown user changes nothing.
- **SMS / push later:** another `NotificationChannel` bean is offered every stored notification in the same
  transaction.

## Package layout
```
channel/     NotificationChannel (Phase 12: channels besides the inbox, offered every stored notification in its transaction)
config/      NotificationProperties (grocery.notification.*), NotificationConfig (Clock µs, scheduling, Redis listener container)
controller/  NotificationController (inbox), NotificationStreamController (SSE), InvalidParameterAdvice,
             NotificationPreferencesController, AdminEmailController (Phase 12)
dispatch/    EventHandler + OrderEventHandler, StoreOrderEventHandler (Phase 8), DeliveryEventHandler (Phase 9),
             PaymentEventHandler, AccountEventHandler, StockEventHandler, PricingEventHandler (the §4 rules),
             StuckOrderEventHandler (Phase 10), AiApprovalEventHandler + ApprovalPage (Phase 11 / 12 §6),
             ReviewEventHandler, ReturnEventHandler (Phase 12), PasswordEventHandler (security emails),
             DispatchPlan, NotificationTemplates, Money, Audiences, SignalType, SessionReason
dto/         NotificationDtos (NotificationView, Signal, Session, UnreadCount, ReadAllResult), EmailDtos (Phase 12)
email/       EmailProperties, EmailCategory, EmailOutbox (the channel), AddressBook (token → address projection),
             EmailRenderer + Html (text / escaped HTML), EmailSender (SKIP LOCKED, retries), EmailSenderSchedule
entity/      NotificationType (category + severity), AccountKind, StoreType, StoreOwner, OrderRef, AiApprovalRef,
             ReviewTarget (Phase 12)
event/       DispatchListener (the domain-event consumer), LocationListener (Phase 9, delivery.location), EventPayloads
             (tolerant DTOs), OccurredAt (header parsing)
realtime/    RealtimeMessage, RealtimePublisher, RealtimeSubscriber, PublicSignalThrottle, LocationThrottle (Phase 9),
             ConnectionRegistry, StreamConnection (send queue, replay hold), SseFrame
repository/  NotificationRepository, StoreOwnerRepository, OrderRefRepository, PendingEventRepository, AiApprovalRefRepository,
             ItemNameRepository, EmailAddressRepository, EmailPreferenceRepository, EmailOutboxRepository (JdbcClient), UuidBytes
service/     DispatchService (the pipeline), LocationService (Phase 9 live location), StreamService (opening a stream),
             InboxService, NotificationRetention, EmailSettingsService (Phase 12: preferences, email log)
```
Plain JDBC (`JdbcClient`) instead of JPA: the inbox is audience joins, `INSERT IGNORE` and upserts.

## Configuration (`grocery.notification.*`)
| Property | Default | |
|---|---|---|
| `max-event-age` | `15m` (env `NOTIFICATION_MAX_EVENT_AGE`) | older events only update projections |
| `heartbeat-interval` | `20s` | `: hb` on every stream |
| `max-streams-per-user` | `5` | per replica; one more → oldest gets `REPLACED` |
| `replay-limit` | `200` | then `RESYNC` |
| `public-signal-throttle` | `2s` | per product / restaurant |
| `retention` / `retention-cron` | `90d` / `0 30 3 * * *` (Asia/Kolkata) | nightly cleanup |
| `pending-event-max-age` | `24h` | parked payment events whose order never arrived are dropped (WARN) |
| `location-max-age` | `30s` (env `NOTIFICATION_LOCATION_MAX_AGE`) | Phase 9: older positions are dropped |
| `location-throttle` | `2s` | Phase 9: at most one `DELIVERY_LOCATION` per order per window |
| `redis-channel` | `grocery:realtime` | |
| `instance-id` | env `NOTIFICATION_INSTANCE_ID`, else `HOSTNAME`, else the host name | logs only |
| `email.enabled` | `false` (env `GROCERY_MAIL_ENABLED`; compose: `true`) | Phase 12: off = no outbox rows, no sender |
| `email.sender-enabled` | `true` | the sender job (tests call it themselves) |
| `email.from` | `Grocery <no-reply@grocery.local>` | From header |
| `email.app-base-url` | `http://localhost:6420` (env `APP_BASE_URL`) | absolute links |
| `email.types` | the 11 types of contract §5 | which types are emailed |
| `email.poll-interval` / `email.batch-size` / `email.lease` | `5s` / `20` / `10m` | the sender job |
| `email.retry-delays` | `1m, 5m, 30m` | then FAILED |
| `spring.mail.host` / `spring.mail.port` | `localhost` / `1025` (docker: `mailpit` / `1025`; env `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`) | SMTP; any other `SPRING_MAIL_*` (username, password, `properties.mail.smtp.starttls.enable`, …) for a real provider; timeouts 5 s / 5 s / 10 s |

## AI approvals (Phase 11)

`notification-service.dispatch` also consumes `ai.events`:
- `ApprovalRequested` → `AI_APPROVAL_REQUESTED` to the deciding customer or to the approver roles.
- `ApprovalDecided` REJECTED / EXPIRED → `AI_APPROVAL_DECIDED`.
- `ApprovalExecuted` → `AI_ACTION_COMPLETED`.
- `ApprovalFailed` → `AI_ACTION_FAILED`.
- Each also sends the signal `AI_APPROVALS_CHANGED`.

How it works:
- **Links** (`dispatch/ApprovalPage.java`; all with `?id=`):
  - Phase 12 §6: the requester of the `vendor`, `restaurant` and `delivery` assistants — and so the self-deciding
    approver, who is the requester — gets `/vendor/assistant`, `/restaurant/assistant`, `/delivery/assistant`.
  - Approver roles get their role's page: VENDOR → `/vendor/assistant`, RESTAURANT_OWNER → `/restaurant/assistant`,
    DELIVERY_PARTNER and DELIVERY_MANAGER → `/delivery/assistant`, every other (admin) role → `/admin/ai/approvals` —
    one notification per page (REASSIGN_DELIVERY: one row for `role:DELIVERY_MANAGER` on the delivery page, one for
    `role:OPS_ADMIN` + `role:SUPER_ADMIN` on the admin page; a user holding both a delivery and an admin role sees both).
  - Unchanged (Phase 11): the customer the action is for gets `/assistant/approvals`; everyone else
    `/admin/ai/approvals`.
  - Agent labels: Shopping, Business insights, Vendor, Restaurant, Delivery.
- **Approver projection:** `ApprovalRequested` records who may decide in `ai_approval_ref` (Flyway V5), because the
  later events do not carry it. The later events use it to signal those approvers.
- Texts and recipients: [Phase 11 §8](../../docs/api/phase-11-agentic-ai-and-approvals.md#8-events-aievents-key-approvalid-schemas-140-and-notifications).

## Run / test
```bash
cd infrastructure/local && docker compose up -d --build notification-service
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4.13 + Redis 8.8.4 + Mailpit v1.32.0; real HTTP for SSE
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/notification-service/pom.xml clean verify
```
Tests run the real server on a random port with Kafka listeners off (records with the outbox router's headers are
handed to the listener), Avro against `mock://notification-service`, heartbeats every second and `replay-limit` 5.
Streams are read over real HTTP (JDK HttpClient, line by line) with tokens signed by a test RSA key and validated
against a stub JWKS; every Redis message is recorded. Phase 12: the email channel is on (outbox written), its
background sender off — email tests run senders of their own (real SMTP to Mailpit, read back through Mailpit's HTTP
API; or "SMTP down" = a closed port) on a hand-moved clock.

| Test class | Covers |
|---|---|
| `OrderNotificationsIntegrationTest` | every Phase 7 order row of §4 (texts, links, severities, exact audiences; vendor / restaurant owners; every cancel reason and the refund sentence), OrderConfirmed no longer sends NEW_ORDER, other customers / vendors / admins see nothing, unknown store owner, neutral store name |
| `StoreOrderNotificationsIntegrationTest` | Phase 8 §6: NEW_ORDER from Vendor/RestaurantOrderReceived (owner from the event, accept-by in India time, board link, `acceptBy`, privacy, duplicates), not from OrderConfirmed (v1.0 and v1.1 payloads); STORE_ORDERS_CHANGED for every store event and for OrderCancelled once the store had the order; ORDER_AUTO_REJECTED; ORDER_ACCEPTED (store-name fallbacks), OrderPreparing signals only, ORDER_READY per store type; STORE_REJECTED / STORE_NO_RESPONSE texts without a store "do not prepare" notification; admin cancel of ACCEPTED / PREPARING / READY_FOR_PICKUP; unknown store, old events, broken events |
| `DeliveryNotificationsIntegrationTest` | Phase 9 §7, every row: DELIVERY_OFFER (texts, OfferView-only data, privacy, expired offer → signal only, store-name fallback), DeliveryOfferClosed signals, PARTNER_ASSIGNED (first name, no name, partner DELIVERY_UPDATED, store STORE_ORDERS_CHANGED, projection; unknown order parked and applied), DELIVERY_REASSIGNED (stale unassignment keeps the newer partner), PICKUP_READY to the assigned partner only, OUT_FOR_DELIVERY (+ PICKED_UP board signal), ORDER_DELIVERED, DELIVERY_FAILED + DELIVERY_FAILED_ADMIN (one row, four roles, reason label + note), NO_DELIVERY_PARTNER, DELIVERY_CANCELLED (with / without partner), ADMIN_DELIVERIES_CHANGED for every delivery event, ORDER_UPDATED / ADMIN_ORDERS_CHANGED for the new order events, old events → projections only, idempotency, partner lifecycle unchanged, broken events |
| `StuckOrderNotificationsIntegrationTest` | Phase 10 §4: STUCK_ORDER (texts, India-time `since`, link, one row for the three roles, not for FINANCE_ADMIN / DELIVERY_MANAGER / the customer), every reason label, ADMIN_ORDERS_CHANGED to all admin roles and no ORDER_UPDATED, projection untouched, an order the projection never saw, old / redelivered events, broken events |
| `DeliveryLocationIntegrationTest` | Phase 9 live location: separate group and container, DELIVERY_LOCATION reaches only the order's customer (SSE streams of the partner, another customer, a vendor and admins get nothing), exact data, nothing stored; older than 30 s dropped; one per order per 2 s; unknown / ended order, foreign type, missing coordinates, unreadable → dropped (the listener never throws); no headers / no heading; a position that travelled as Avro (real schema 1.2.0) |
| `dispatch/StuckOrderTemplatesTest` | the STUCK_ORDER text per reason with India-time `since`, unknown reasons, the handler's plan (audiences, signal) and old events |
| `realtime/LocationThrottleTest`, `dispatch/DeliveryTemplatesTest` | the 2-second window per order on a controlled clock, idle cleanup; seconds left to respond, distance format, first names, failure reason labels |
| `PaymentNotificationsIntegrationTest` | PaymentFailed (retryable or not, fallback text), RefundCompleted (lakh grouping), RefundFailed as one row for two roles (seen once by an admin with both) |
| `ParkedPaymentEventsIntegrationTest` | a payment event before its order: parked (no exception, nothing sent), redelivered while parked (parked once), applied when OrderCreated arrives (after the order's own messages), redeliveries apply nothing twice; arrival order; a parked event grown older than max-event-age notifies nobody; an old OrderCreated or another order event also applies them; old payment events are not parked; the nightly cleanup; payment event and OrderCreated racing on two threads (15 runs: applied exactly once, nothing left) |
| `AccountNotificationsIntegrationTest` | Submitted → admin roles per kind, every Approved / Rejected / Suspended / Reinstated for vendors, restaurants and partners, UserReactivated / UserSuspended, registrations, neutral names |
| `StockAndPricingNotificationsIntegrationTest` | LowStockDetected, PriceChangeRequested (one row, signed percent), PriceChangeApproved / Rejected / Cancelled, unknown vendor |
| `SignalsIntegrationTest` | every §2 / §4 signal on the Redis channel (type, data, audiences), store owner only from CONFIRMED, notification and session messages, suspension order (notification → signal → REVOKED), public throttle with trailing latest state, old events publish nothing |
| `ProjectionsAndEventAgeIntegrationTest` | store_owner and order_ref projections, max-event-age, missing occurredAt, duplicates, broken / ignored events, retention |
| `InboxApiIntegrationTest` | list (newest first, pages, unread filter), unread count, read / read-all (idempotent), per-user read state for role notifications, 404 for foreign / unknown / malformed ids, size cap, 400, 401 |
| `StreamIntegrationTest` | retry line, headers, heartbeat, 401, live notification + signal, Last-Event-ID replay, live events racing the replay (no duplicates), RESYNC (unknown / foreign / malformed id, truncation), TOKEN_EXPIRED, REPLACED, REVOKED (vendor and user), vendor isolation, two roles → once, cleanup after a client disconnects |
| `MultiReplicaIntegrationTest` | a second application context: stream on A + event consumed by B (and the reverse), streams on both, replay from the other replica, REVOKED on both, stopping a replica closes its streams |
| `dispatch/MoneyAndTemplatesTest`, `event/OccurredAtTest` | money format, fallbacks, `data` ids per type, plan merging, audiences, the NEW_ORDER line with India-time deadlines, the rejection reason labels, the refund sentence; occurredAt header formats |
| `AiApprovalNotificationsIntegrationTest`, `dispatch/AiApprovalTemplatesTest` | Phase 11 §8, plus Phase 12 §6: the vendor / restaurant / delivery requester and self-decider on their assistant page, REASSIGN_DELIVERY split into a delivery-page row for DELIVERY_MANAGER and an admin-page row, role pages, agent labels; customer and admin links unchanged |
| `ReviewNotificationsIntegrationTest` | Phase 12 §1 (Avro, real schemas 1.5.0): NEW_REVIEW to the store owner only (another store, the customer, admins get nothing), product / dish / store names and fallbacks, edited reviews, no store part for DELIVERY_PARTNER reviews, REVIEW_FLAGGED to the three roles (one row), REVIEW_NOT_PUBLISHED (rejected / removed), REVIEW_REPLY, the three signals and their audiences, unknown store owner, old / duplicate / broken events, RatingSummaryUpdated ignored, review.events subscribed |
| `ReturnNotificationsIntegrationTest` | Phase 12 §3 (Avro): RETURN_REQUESTED to the order's store owner only (vendor / restaurant pages, item count, India-time deadline, lakh grouping), RETURN_ESCALATED (one row, three roles), RETURN_APPROVED / RETURN_REJECTED to the customer, ORDER_UPDATED / STORE_RETURNS_CHANGED / ADMIN_RETURNS_CHANGED / ADMIN_ORDERS_CHANGED, RefundCompleted with `returnId`, unknown store owner, old / duplicate / broken events |
| `EmailIntegrationTest` | Phase 12 §5: one outbox row per eligible notification and recipient in its transaction (fields; redelivery and a second enqueue add nothing; in-app-only types; unknown users), role audiences to every active user of the role once (admins known from their tokens, suspended / opted-out excluded, reactivation, new roles), UserRegistered projection (replay keeps a suspension, unusable address), the preferences API (defaults, partial PUT, 400, 401) and categories (orders off / account on / approvals on → off), the sender over real SMTP to Mailpit (From, To, Subject, text, escaped HTML, absolute links, sent once), user text never becomes HTML, SMTP down → retries after 1 / 5 / 30 min then FAILED with in-app delivery untouched, retry then success, SKIP LOCKED + lease between two "replicas", the admin email log (filters, 400, 403, 401) |
| `PasswordEmailsIntegrationTest` | Security emails: the reset link to the event's address with account emails turned off (no inbox entry, no signal, the stored row keeps no link, one email per redelivery), its text, link and footer over SMTP; PasswordChanged for CHANGE and RESET with the /forgot-password link; old events, expired links and unusable addresses send nothing; CustomerProfileUpdated renames a known user (old events too), not an unknown one |
| `dispatch/ReviewAndReturnTemplatesTest`, `email/EmailRendererTest`, `email/EmailOutboxTest` | the Phase 12 texts, fallbacks, flag labels and `data`; text / HTML parts, escaping of every text (also inside the link), one-line subjects, absolute links; which types and recipients are emailed, the default types and categories, usable addresses |

Environment: `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`,
`KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_LISTENERS_ENABLED`, `IDENTITY_JWKS_URI`, `JWT_ISSUER`,
`GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL`, `NOTIFICATION_MAX_EVENT_AGE`, `NOTIFICATION_INSTANCE_ID`, Phase 12:
`GROCERY_MAIL_ENABLED` (default `false`), `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `APP_BASE_URL` (profile `docker`
defaults to the compose service names: `mysql`, `redis`, `kafka:29092`, `identity-service`, `mailpit:1025`).
