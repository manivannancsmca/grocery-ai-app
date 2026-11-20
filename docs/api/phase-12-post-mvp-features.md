# Phase 12 contract — Reviews, returns, promotions, purchasing, email, more assistants, reporting

Post-MVP features, chosen by the product owner. Public paths go through the gateway (`http://localhost:8080`);
conventions are unchanged ([Phase 2](phase-2-identity-and-stakeholders.md#1-conventions)). Conflict errors carry
`currentStatus`, money is `DECIMAL(12,2)` INR, and time is UTC (India time for day boundaries in reports). Events
are Avro, schemas in `libs/grocery-event-schemas` **1.5.0** (already written). Section 0 lists who owns what.

## 0. Ownership, ports, events

| Feature | Owner (builds it) | Touches |
|---|---|---|
| Reviews & ratings (§1) | **review-service** (new, port **8114**, `review_db`, user `review_user`/`review_pass`) | catalog-query (rating in search), notification |
| Promotions & coupons (§2) | **pricing-service** (coupons), **cart-service** (apply), **order-service** (redeem at placement) | payment (prorated refunds) |
| Returns & partial refunds (§3) | **order-service** (return requests, decisions) | payment (refund), inventory (restock), notification |
| Purchasing & supply (§4) | **vendor-service** (suppliers, purchase orders, receipts) | inventory (stock in) |
| Email (§5) | **notification-service** + **Mailpit** (local SMTP catcher) | identity events (emails) |
| Vendor, restaurant, delivery assistants (§6) | **ai-orchestrator-service** | inventory, pricing, vendor, restaurant, delivery (approval filter) |
| Reporting (§7) | **reporting-service** (new, port **8116**, `reporting_db`, user `reporting_user`/`reporting_pass`) | consumes events |

**New events (schemas 1.5.0):**
- `review.events`: ReviewSubmitted, ReviewModerated, ReviewReplied (key reviewId), RatingSummaryUpdated (key targetId).
- `order.events`: ReturnRequested, ReturnApproved, ReturnRejected, ReturnEscalated (key orderId).
- `vendor.events`: GoodsReceived (key vendorId).
- **Optional fields added:**
  - `OrderCreated` and `OrderConfirmed` gain `couponCode`, `discount` and `deliveryFeeDiscount`.
  - `RefundCompleted` and `RefundFailed` gain `returnId`.
  - The ai.events docs list the new agents and actions.
- Exact fields: the `.avsc` files and [the event catalog](../events/event-catalog.md).

**New notification types** (notification-service, Phase 7 style; texts below):
- `NEW_REVIEW`, `REVIEW_FLAGGED`, `REVIEW_NOT_PUBLISHED`, `REVIEW_REPLY`.
- `RETURN_REQUESTED`, `RETURN_ESCALATED`, `RETURN_APPROVED`, `RETURN_REJECTED`.

## 1. Reviews & ratings (review-service)

- **What can be reviewed.** After an order is **DELIVERED** the customer may review, for **30 days**
  (`REVIEW_WINDOW`; an admin platform setting, [platform-settings.md](platform-settings.md)):
  - each product (`PRODUCT`) or dish (`MENU_ITEM`) of the order;
  - the store (`VENDOR` or `RESTAURANT`);
  - the delivery partner (`DELIVERY_PARTNER`).

  There is one review per customer, order and target. Eligibility comes from `OrderConfirmed` (items, store, type)
  and `OrderDelivered` (partner, time), which review-service consumes into its own projection.
- **Review fields:** `rating` 1–5 (required), `title` ≤ 80, `comment` ≤ 1000. The customer may edit it for 7 days
  (it is re-moderated) and delete it at any time.
- **Automatic moderation.**
  - A review with a link, a phone number or an email address, a word from the profanity list (`moderation/words.txt`),
    or more than 70 % upper-case letters is `FLAGGED` and stays hidden until an admin decides.
  - Anything else is `PUBLISHED` at once.
  - Admins can `REMOVED` a published review, and `PUBLISHED` or `REJECTED` a flagged one.
- **Store reply.** The vendor or restaurant owner whose store the review belongs to may write one public reply
  (≤ 500, editable).
- **Rating summary.** For each target: the average of the published ratings (2 decimals), the count, and the
  distribution 1–5. Every change publishes `RatingSummaryUpdated` with an increasing `summaryVersion`.
- **Shown publicly** (in reviews and their replies): the customer's first name and last initial, e.g. "Asha S."

| Method & path | Roles | Body → Response |
|---|---|---|
| `GET /api/reviews/eligible?orderId=` | CUSTOMER | → `[ { orderId, orderNumber, deliveredAt, targetType, targetId, targetName, review: ReviewView \| null } ]` (without `orderId`: every delivered order still in the window, newest first) |
| `POST /api/reviews` | CUSTOMER | `{ orderId, targetType, targetId, rating, title?, comment? }` → 201 `ReviewView` · 422 `REVIEW_NOT_ELIGIBLE` (not delivered, not in that order, window over) · 409 `ALREADY_REVIEWED` |
| `PUT /api/reviews/{id}` | owner | `{ rating, title?, comment? }` → `ReviewView` · 422 `REVIEW_EDIT_WINDOW_OVER` |
| `DELETE /api/reviews/{id}` | owner | → 204 |
| `GET /api/reviews/mine?page=&size=` | CUSTOMER | → `PageResponse<ReviewView>` (all statuses) |
| `GET /api/reviews?targetType=&targetId=&sort=newest\|highest\|lowest&page=&size=` | public | → `PageResponse<PublicReview>` (published only) |
| `GET /api/reviews/summary?targetType=&targetId=` | public | → `{ targetType, targetId, averageRating, ratingCount, distribution: [5 ints] }` |
| `GET /api/store/reviews?targetType=&rating=&page=&size=` | VENDOR, RESTAURANT_OWNER | → reviews of the caller's store and of its products or dishes (published only) |
| `PUT /api/store/reviews/{id}/reply` | VENDOR, RESTAURANT_OWNER | `{ text }` → `ReviewView` · 404 if not their store's review |
| `GET /api/admin/reviews?status=&targetType=&q=&page=&size=` | any admin | → `PageResponse<ReviewView>` (default `status=FLAGGED`) |
| `POST /api/admin/reviews/{id}/publish` · `/reject` · `/remove` | SUPER_ADMIN, OPS_ADMIN, SUPPORT | reject and remove need `{ reason }` (≤ 300) → `ReviewView` · 409 `REVIEW_NOT_MODERATABLE` (`currentStatus`) |

- **catalog-query** consumes `RatingSummaryUpdated`:
  - `PRODUCT` → `products` docs get `rating` and `ratingCount`;
  - `RESTAURANT` → `restaurants` docs get them too;
  - the version is guarded with `summaryVersion`; other target types are ignored.
  - **Search:** products and restaurants show `rating`/`ratingCount`, accept `sort=rating` (highest first; unrated
    last) and `minRating` (restaurants).
  - Product detail and restaurant detail include them.
- **Notifications:**

| Trigger | Notification and recipients |
|---|---|
| ReviewSubmitted `PUBLISHED` | `NEW_REVIEW` INFO to the store owner: "New {rating}★ review" · "{targetName}: {title or first 80 chars}" · `/vendor/reviews` or `/restaurant/reviews` |
| ReviewSubmitted `FLAGGED` | `REVIEW_FLAGGED` WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT · `/admin/reviews` |
| ReviewModerated `REJECTED` / `REMOVED` | `REVIEW_NOT_PUBLISHED` to the customer · `/account/reviews` |
| ReviewReplied | `REVIEW_REPLY` INFO to the customer · `/account/reviews` |

  Names come from notification-service's projections, or "your product".

## 2. Promotions & coupons (pricing-service, cart-service, order-service)

**Coupon** (pricing-service):

| Field | Rule |
|---|---|
| `code` | Unique, `^[A-Z0-9-]{4,20}$`, stored upper-case |
| `name`, `description` | `name` ≤ 80, `description` ≤ 300 |
| `scope` | `PLATFORM` (admins; any store, optional `storeType` `GROCERY`/`RESTAURANT`/`ANY`) or `STORE` (a vendor or restaurant owner; only their `storeId`) |
| `discountType` | `PERCENT` (`value` 1–90, optional `maxDiscount`), `FLAT` (`value` ≤ subtotal), or `FREE_DELIVERY` |
| Limits | `minOrderSubtotal` (default 0), `validFrom`, `validUntil` (required), `totalUsageLimit` (optional), `perCustomerLimit` (default 1) |
| Status | `active` (pause or resume); `public` (listed to customers as an offer) |

- **Funding:** the platform funds `PLATFORM` coupons; the store funds `STORE` coupons (shown in reports).
- **One coupon per cart.** The discount applies to the item subtotal; `FREE_DELIVERY` removes the delivery fee
  instead.
- **Redemptions:**
  - `coupon_redemption(orderId unique, couponId, customerId, discount, deliveryFeeDiscount, status RESERVED |
    CONFIRMED | RELEASED)`.
  - Usage limits count RESERVED and CONFIRMED.
  - A redemption becomes CONFIRMED on `OrderCreated` and RELEASED on `OrderCancelled` or `OrderRejected`
    (pricing-service consumes `order.events` in group `pricing-service.order-events`).
  - A RESERVED redemption with no `OrderCreated` after 30 minutes is released by a sweeper (the order was never
    saved).

| Method & path | Roles | Body → Response |
|---|---|---|
| `GET/POST /api/admin/coupons`, `GET/PUT /api/admin/coupons/{id}`, `POST /api/admin/coupons/{id}/pause` · `/resume`, `GET /api/admin/coupons/{id}/usage` | SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN (read: any admin) | `CouponRequest` → `CouponView` (with `usedCount`, `discountGiven`). 409 `COUPON_CODE_TAKEN`. `scope` is chosen here |
| same under `/api/vendor/coupons` and `/api/restaurant/coupons` | VENDOR / RESTAURANT_OWNER | scope forced to `STORE` with their store id |
| `GET /api/coupons?storeId=` | signed-in CUSTOMER | → active, public coupons usable at that store: `[ { code, name, description, discountType, value, maxDiscount, minOrderSubtotal, validUntil } ]` |
| `POST /internal/coupons/evaluate` | internal (cart-service) | `{ code, customerId, storeId, orderType, subtotal, deliveryFee }` → `{ valid, discount, deliveryFeeDiscount, reason? , minOrderSubtotal? }` |
| `POST /internal/coupons/redeem` | internal (order-service) | `{ code, orderId, customerId, storeId, orderType, subtotal, deliveryFee }` → `{ redemptionId, discount, deliveryFeeDiscount, couponName, fundedBy }` (the last two added with schemas 1.7.0, for the reports) · 409 with `reason` · idempotent per `orderId` |

**Reasons:** `COUPON_NOT_FOUND`, `COUPON_PAUSED`, `COUPON_NOT_STARTED`, `COUPON_EXPIRED`, `COUPON_WRONG_STORE`,
`COUPON_MIN_ORDER` (+ `minOrderSubtotal`), `COUPON_USAGE_LIMIT`, `COUPON_CUSTOMER_LIMIT`.

- **cart-service:**
  - `PUT /api/cart/coupon { code }` → `CartView`. The coupon is validated through `evaluate`; 422
    `COUPON_NOT_APPLICABLE` with `reason` (and `minOrderSubtotal`).
  - `DELETE /api/cart/coupon` → `CartView`.
  - The preview gains `coupon: { code, valid, discount, deliveryFeeDiscount, reason?, message? }`, `discount` and
    `deliveryFeeDiscount`, with `total = subtotal + deliveryFee − discount − deliveryFeeDiscount`.
  - A coupon that is no longer valid stays visible with `valid: false` and the **blocking** issue
    `COUPON_NOT_APPLICABLE`, so the customer removes it or fixes the cart.
  - Changing to another store's items clears a `STORE` coupon of the old store.
- **order-service:**
  - Placement reads the coupon from the cart preview and calls `redeem` with the new order id before saving the order.
  - A failed `redeem` → 409 `COUPON_NOT_APPLICABLE` (`reason`), and no order is placed.
  - `expectedTotal` is compared with the discounted total.
  - `OrderView` and `OrderSummary` gain `couponCode`, `discount` and `deliveryFeeDiscount`.
  - `OrderCreated` and `OrderConfirmed` carry them. The payment amount is the discounted total, unchanged in
    payment-service.

## 3. Returns & partial refunds (order-service)

- **Who may request.** The customer of a **DELIVERED** order, within **48 h** of delivery (`ORDER_RETURN_WINDOW`; like the
  store's 24 h, an admin platform setting, [platform-settings.md](platform-settings.md)).
  - It covers any lines and quantities not yet returned or under an open request.
  - Only one open request (REQUESTED or ESCALATED) per order at a time.
  - `RESTAURANT` orders use the same flow ("report a problem"), always without restock.
- **Refund per line:** `lineTotal × quantity / orderedQuantity × (1 − discount / subtotal)`, rounded to 0.01.
  - When the request returns **every** remaining unit of the order, the paid delivery fee (`deliveryFee −
    deliveryFeeDiscount`) is added.
  - Computed by the server, never by the client.
- **Lifecycle:** `REQUESTED → APPROVED → REFUNDED`; `REQUESTED → REJECTED`; `REQUESTED → ESCALATED` (the store did not
  decide within **24 h**, `RETURN_DECISION_TIMEOUT`; a poller) `→ APPROVED / REJECTED` by an admin. Admins may also
  decide `REQUESTED` ones.
  - `APPROVED` publishes `ReturnApproved`.
  - payment-service refunds with reason `RETURN` (idempotent per `returnId`) and publishes `RefundCompleted` with
    `returnId`. order-service then marks it `REFUNDED`.
  - When `restock` is true (GROCERY only), inventory-service adds the quantities back (adjustment `INCREASE`, reason
    `RETURN`, idempotent per event).
- **Physical return:** the store states at approval whether the goods came back sellable (`restock`). Pickup logistics
  are outside this phase.

| Method & path | Roles | Body → Response |
|---|---|---|
| `POST /api/orders/{id}/returns` | CUSTOMER (own order) | `{ items: [ { itemId, quantity } ], reason: DAMAGED\|WRONG_ITEM\|MISSING\|QUALITY\|OTHER, note? (≤ 500) }` → 201 `ReturnView` · 422 `RETURN_NOT_ALLOWED` (state, window, open request) · 422 `RETURN_QUANTITY_EXCEEDED` |
| `GET /api/orders/{id}/returns` · `GET /api/returns?page=&size=` | CUSTOMER | → `ReturnView[]` / page (own) |
| `GET /api/store/returns?status=&page=&size=` | VENDOR, RESTAURANT_OWNER | → the caller's store's returns |
| `POST /api/store/returns/{id}/approve` | VENDOR, RESTAURANT_OWNER | `{ restock: true\|false }` (forced false for RESTAURANT) → `ReturnView` · 409 `RETURN_NOT_PENDING` (`currentStatus`) |
| `POST /api/store/returns/{id}/reject` | VENDOR, RESTAURANT_OWNER | `{ comment }` (required ≤ 300) → `ReturnView` |
| `GET /api/admin/returns?status=&storeId=&page=&size=` | any admin | → `PageResponse<ReturnView>` (default REQUESTED + ESCALATED) |
| `POST /api/admin/returns/{id}/approve` · `/reject` | SUPER_ADMIN, OPS_ADMIN, SUPPORT | same bodies → `ReturnView` |

```jsonc
// ReturnView
{ "id", "returnNumber": "RT-7K3M9QX2", "orderId", "orderNumber", "orderType", "storeId", "storeName",
  "status": "REQUESTED", "reason": "DAMAGED", "note",
  "items": [ { "itemId", "name", "quantity", "refundAmount" } ], "refundAmount": 95.00, "restock": null,
  "decideBy", "requestedAt", "decidedAt", "decidedByRole", "comment", "refundedAt" }
```

- `OrderView` gains `returnable` (boolean, now) and `returns` (summaries).
- **Notifications:**

| Trigger | Notification and recipients |
|---|---|
| ReturnRequested | `RETURN_REQUESTED` WARNING to the store owner: "Return requested for order {no}" · "{n} items · ₹{refund} · decide by {HH:mm}" · `/vendor/returns` or `/restaurant/returns` |
| ReturnEscalated | `RETURN_ESCALATED` WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT · `/admin/returns` |
| ReturnApproved | `RETURN_APPROVED` SUCCESS to the customer: "Return {RT} approved" · "₹{refund} will be refunded." |
| ReturnRejected | `RETURN_REJECTED` WARNING to the customer, with the comment |

## 4. Vendor purchasing & supply (vendor-service, inventory-service)

- **Supplier** (per vendor): `name` (required ≤ 120), `contactName`, `phone`, `email`, `gstin`, `address`
  (free text ≤ 300), `leadTimeDays` (0–90), `notes` (≤ 500), `active`.
- **Purchase order:**
  - `number` PO-XXXXXXXX, `supplierId`, `expectedAt`, `notes`;
  - lines `{ productId, productName (snapshot), orderedQuantity 1–100000, unitCost ≥ 0, receivedQuantity }`;
  - totals.
  - Status: `DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED`; `DRAFT`/`SENT → CANCELLED`; `PARTIALLY_RECEIVED → CLOSED`
    (short-closed).
  - Only `DRAFT` is editable.
  - Products must be the vendor's own: vendor-service keeps a projection of the vendor's products from
    `catalog.events` (`ProductUpserted`), consumer group `vendor-service.catalog-events`.
- **Receiving:** `POST …/{id}/receive` with `Idempotency-Key` and `{ lines: [ { productId, quantity } ], note? }`.
  - More than what is still open → 422 `RECEIPT_EXCEEDS_ORDERED`.
  - It creates a receipt and publishes `GoodsReceived`.
  - inventory-service consumes it (group `inventory-service.vendor-events`) and increases stock per line
    (adjustment `INCREASE`, reason `RESTOCK`, note "PO-…"), idempotent per event.

| Method & path | Roles | Body → Response |
|---|---|---|
| `GET/POST /api/vendor/suppliers`, `GET/PUT/DELETE /api/vendor/suppliers/{id}` | VENDOR | delete = deactivate (409 `SUPPLIER_IN_USE` when open POs exist) |
| `GET /api/vendor/purchase-orders?status=&supplierId=&page=&size=`, `POST /api/vendor/purchase-orders`, `GET/PUT /api/vendor/purchase-orders/{id}` | VENDOR | `PurchaseOrderRequest` → `PurchaseOrderView` |
| `POST /api/vendor/purchase-orders/{id}/send` · `/cancel` · `/close` | VENDOR | → `PurchaseOrderView` · 409 `INVALID_STATUS_TRANSITION` (`currentStatus`) |
| `POST /api/vendor/purchase-orders/{id}/receive` | VENDOR | see above → `PurchaseOrderView` (with `receipts`) |
| `GET /api/admin/purchase-orders?vendorId=&status=&page=&size=` | any admin | read-only |

## 5. Email notifications (notification-service, Mailpit)

- **Mailpit** (`axllent/mailpit`) is a local SMTP catcher.
  - SMTP on `mailpit:1025`; web UI at http://localhost:8025 (`MAILPIT_UI_PORT`).
  - Compose profiles `realtime`, `realtime-ha` and `all`.
  - Production: a real SMTP or email provider, configured by `SPRING_MAIL_*`.
- **Addresses:** a projection from `identity.events` `UserRegistered` (userId → email, full name, role), kept up to
  date. Role audiences (`role:FINANCE_ADMIN` …) are sent to every active user with that role.
- **Which types are emailed** (`grocery.notification.email.types`):
  - `ORDER_CONFIRMED`, `ORDER_CANCELLED`, `ORDER_DELIVERED` (if the type exists, else the Phase 9 equivalent);
  - `REFUND_COMPLETED`, `REFUND_FAILED`, `RETURN_APPROVED`, `RETURN_REJECTED`;
  - `ACCOUNT_APPROVED`, `ACCOUNT_REJECTED`, `ACCOUNT_SUSPENDED`;
  - `AI_APPROVAL_REQUESTED`.
  - Other types stay in-app only.
- **Format:** plain text and simple HTML from the same title, body and link (absolute link from `APP_BASE_URL`,
  default `http://localhost:6420`).
  - From: `Grocery <no-reply@grocery.local>`; subject = the notification title.
- **Reliability:**
  - An `email_outbox` row is written in the same transaction as the notification.
  - A sender job (`SKIP LOCKED`, safe with replicas) sends it, retrying after 1 min, 5 min and 30 min, then `FAILED`.
  - Each email is sent once (idempotent per notification and recipient).
  - SMTP down does not affect the in-app notification.
- **Preferences:** `GET/PUT /api/notifications/preferences` →
  `{ "email": { "orders": true, "account": true, "approvals": true } }` (default all true). The categories map the
  types above (orders, refunds and returns → `orders`; account → `account`; AI approvals → `approvals`).
- **Admin:** `GET /api/admin/notifications/emails?status=PENDING|SENT|FAILED&to=&page=&size=` (any admin) → the email
  log (`to`, `subject`, `type`, `status`, `attempts`, `lastError`, `createdAt`, `sentAt`).
- **SMS and push:** later. The channel interface allows them.

## 6. Vendor, restaurant and delivery assistants (ai-orchestrator-service)

These use the same framework as Phase 11 ([contract](phase-11-agentic-ai-and-approvals.md)): tools through the
gateway with the user's JWT, ToolGuard, approvals and the signed approval token. **New agents:**

| Agent id | Roles | READ / LOW_RISK tools | SENSITIVE tools → action (decided by) |
|---|---|---|---|
| `vendor` | VENDOR | `getSalesReport(from?, to?)`, `listInventory(filter?, query?)` (LOW_STOCK / OUT_OF_STOCK), `getStock(productId)`, `listProducts(query?)`, `getPrice(productId)`, `listStoreOrders(status?)`, `listPurchaseOrders(status?)`, `listSuppliers()`, `listReturns(status?)`, `listReviews()`, `draftPurchaseOrder(supplierId, lines[{productId, quantity, unitCost}])` (LOW_RISK: creates a DRAFT) | `adjustStock(productId, type, quantity, reason)` → `ADJUST_STOCK` (the vendor); `changePrice(productId, sellingPrice, mrp?)` → `CHANGE_PRICE` (the vendor; a change above the price threshold still goes to admin approval in pricing-service and the result says so); `sendPurchaseOrder(purchaseOrderNumber)` → `SEND_PURCHASE_ORDER` (the vendor) |
| `restaurant` | RESTAURANT_OWNER | `getOrderQueue(status?)`, `getSalesReport(from?, to?)`, `getMenu()`, `listReturns(status?)`, `listReviews()` | `setMenuItemAvailability(menuItemId, available)` → `SET_MENU_ITEM_AVAILABILITY`; `acceptOrder(orderNumber, readyInMinutes)` → `ACCEPT_STORE_ORDER`; `rejectOrder(orderNumber, reason)` → `REJECT_STORE_ORDER` (all decided by the owner) |
| `delivery` | DELIVERY_PARTNER, DELIVERY_MANAGER | partner: `getActiveDelivery()`, `getMyOffers()`, `getMyHistory()`, `getMySummary()`; manager: `listDeliveries(status?, assignmentFailed?)`, `getDelivery(orderNumber)` | manager: `reassignDelivery(orderNumber, partnerId?, reason?)` → `REASSIGN_DELIVERY` (DELIVERY_MANAGER, OPS_ADMIN, SUPER_ADMIN; four-eyes off) |

- **Shopping assistant:** gains `requestReturn(orderNumber, items[{name or itemId, quantity}], reason, note?)` →
  `REQUEST_RETURN` (the customer), target `POST /api/orders/{id}/returns`.
- **Approval-token targets** (grocery-common 0.8.0 filter, `consumed_approval`): inventory-, pricing- and
  delivery-service are added, alongside order, payment, vendor and restaurant. The rollout step enables them.
- **Self-decided actions** (the vendor, owner or customer is the decider): `approverUserId` = requester, as for
  `PLACE_ORDER`.
- **Notification links for the deciding user:** customer `/assistant/approvals`, vendor `/vendor/assistant`,
  restaurant `/restaurant/assistant`, delivery `/delivery/assistant`, admins `/admin/ai/approvals`. All with `?id=`.
- **The scripted model** gains rules per agent:
  - vendor: `low stock`, `sales`, `set stock <product words> to <n>`, `price <product words> <amount>`;
  - restaurant: `orders`, `mark <dish words> sold out`, `accept <ORDER-NUMBER> in <n> min`;
  - delivery: `active delivery`, `deliveries`, `reassign <ORDER-NUMBER>`;
  - shopping: `return <n> <item words> from <ORDER-NUMBER> because <reason>`.

## 7. Reporting (reporting-service)

- **Inputs:** a read model built only from events (group `reporting-service.facts`, idempotent with
  `processed_event`):
  - `order.events`: OrderCreated, OrderConfirmed, OrderCancelled, OrderRejected, OrderDelivered,
    OrderDeliveryFailed, ReturnApproved;
  - `payment.events`: PaymentCaptured, RefundCompleted;
  - `delivery.events`: DeliveryPartnerAssigned, DeliveryPickedUp, DeliveryCompleted;
  - `review.events`: RatingSummaryUpdated (and ReviewSubmitted for counts);
  - `vendor.events`: GoodsReceived.
- **Tables** (one row per order and per line, plus deliveries, refunds, receipts and ratings): store, type,
  timestamps, subtotal, discount, delivery fee, total, coupon, status, refunded amount.
- **Rebuild** = truncate and replay the topics from the start (documented).
- **API** (any admin; `from`/`to` are India dates, inclusive, ≤ 366 days; `?format=csv` returns CSV with a header row;
  `?format=xlsx` / `pdf` the same rows as a file for people — see [data exports](data-exports.md#admin-every-admin-role-unless-noted)):

| Path | Returns |
|---|---|
| `GET /api/admin/reports/overview` | gross sales (GMV of confirmed orders), net sales (GMV − refunds), orders, average order value, cancellation rate, refunds, coupon discounts (platform vs store), new customers (first order) |
| `GET /api/admin/reports/sales/daily?type=&storeId=` | per day: orders, GMV, discounts, refunds, net |
| `GET /api/admin/reports/stores/top?metric=gmv\|orders&limit=` | top stores |
| `GET /api/admin/reports/products/top?limit=` | top products / dishes by units and revenue |
| `GET /api/admin/reports/delivery/performance` | per day: deliveries, average minutes confirm→assigned, assigned→picked up, picked up→delivered; failed count |
| `GET /api/admin/reports/promotions` | per coupon: orders, discount given, GMV of those orders, funded by |
| `GET /api/admin/reports/ratings?targetType=&limit=` | lowest and highest rated targets with counts |
| `GET /api/admin/reports/purchasing?vendorId=` | receipts per vendor and day: units and cost |

## 8. Gateway, compose, frontend

- **Gateway routes** (more specific before general; the existing `/api/restaurant/**` and `/api/vendor/**` routes must
  not swallow the new ones):

| Path | Target |
|---|---|
| `/api/reviews/**`, `/api/store/reviews/**`, `/api/admin/reviews/**` | review-service |
| `/api/admin/coupons/**`, `/api/vendor/coupons/**`, `/api/restaurant/coupons/**`, `/api/coupons/**` | pricing-service |
| `/api/returns/**`, `/api/store/returns/**`, `/api/admin/returns/**` | order-service (`/api/orders/**` already routes there) |
| `/api/vendor/suppliers/**`, `/api/vendor/purchase-orders/**`, `/api/admin/purchase-orders/**` | vendor-service |
| `/api/notifications/preferences`, `/api/admin/notifications/**` | notification-service |
| `/api/admin/reports/**` | reporting-service |

  Plus `GET /api/health/review` and `/api/health/reporting`.
- **Compose:**
  - `review-service` (profile `reviews`, `all`), `reporting-service` (profile `reporting`, `all`), `mailpit`;
  - a one-shot `mysql-init` that applies the idempotent init SQL (it creates `reporting_db` and `reporting_user` on
    existing volumes; MySQL runs `/docker-entrypoint-initdb.d` only on a fresh volume);
  - the `review-outbox` connector (server id 5114);
  - the new env settings.
- **Frontend:**
  - **customer:** rate a delivered order (stars per item, store and partner), my reviews, reviews and ratings on
    product and restaurant pages, sort by rating, coupon in the cart (offers list, apply/remove, discount lines),
    returns (request from the order page, my returns), email preferences.
  - **vendor:** reviews (reply), returns (approve or reject), coupons, suppliers, purchase orders (create, send,
    receive, close), the vendor assistant.
  - **restaurant:** reviews, returns, coupons, the restaurant assistant.
  - **delivery:** the delivery assistant.
  - **admin:** review moderation, returns, coupons, purchase orders (read), email log, the **Reports** page (KPIs,
    daily chart, tables, CSV download).
  - The new notification types.

## 9. Acceptance

- **Reviews:** a customer can review only what they received. A flagged review is hidden until an admin publishes it.
  The store can reply, and the product's rating appears in search within seconds.
- **Coupons:** the coupon shows the discount in the preview and the order total. Its usage limit holds under
  concurrent orders, and a cancelled order frees the use.
- **Returns:** a partial return refunds exactly the prorated amount and restocks when the store says so. An
  undecided return escalates to admins.
- **Purchasing:** receiving a purchase order increases stock once, even if the request is retried.
- **Email:** an order confirmation and a refund email arrive in Mailpit, and a disabled category is not emailed.
- **Assistants:** the vendor assistant changes a stock level only after the vendor approves. The restaurant assistant
  marks a dish sold out only after approval. A delivery manager's reassignment runs only after approval.
- **Reports:** the reporting overview matches the orders the smoke test created.
