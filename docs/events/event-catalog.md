# Event Catalog

This catalog lists every Kafka topic and event in the platform. It is the contract between services.
It grows phase by phase. An event is "live" once its phase is implemented.

## Conventions

### Topics
- One topic per domain: **`<domain>.events`** (e.g. `order.events`). The domain is the outbox `aggregate_type`.
- `delivery.location` is the only non-outbox topic. It carries best-effort telemetry with 1-hour retention.
- **Partitions:** 3 locally and 6–12 in production. **Production:** replication factor 3, `min.insync.replicas=2`.

### Key and ordering
- **Message key = aggregate ID** (e.g. `orderId`, `productId`, `vendorId`). All events of one aggregate go to one partition,
  in order.
- Ordering **across topics** is not guaranteed. Consumers use state-machine guards.

### Outbox table (every producing service)
| Column | Type | Becomes |
|---|---|---|
| `id` | `BINARY(16)` UUIDv7 | `eventId` header, used for de-duplication |
| `aggregate_type` | `VARCHAR` | Topic `<aggregate_type>.events` |
| `aggregate_id` | `VARCHAR` | Message key |
| `event_type` | `VARCHAR` | `eventType` header |
| `event_version` | `INT` | `eventVersion` header |
| `payload` | `JSON` | Kept for auditing and debugging (no longer the message value) |
| `payload_avro` | `LONGBLOB` | **Message value**: the same payload in Avro (Confluent wire format), validated against its central schema |
| `correlation_id` | `VARCHAR` | `correlationId` header (the business transaction, usually the order or request ID) |
| `causation_id` | `VARCHAR` | `causationId` header (the eventId or request that caused this event) |
| `traceparent` | `VARCHAR(55)` NULL | `traceparent` header (W3C trace context of the span that wrote the event; Phase 13) |
| `occurred_at` | `DATETIME(6)` UTC | `occurredAt` header |

### Headers on every event
`eventId`, `eventType`, `eventVersion`, `correlationId`, `causationId`, `occurredAt`, and `traceparent` when the event
was written inside a trace (Phase 13).
- The connector places it with `table.fields.additional.placement` `…,traceparent:header:traceparent`. Its
  `table.field.additional.missing = ignore` lets a connector registered before its service migrated still relay rows
  without the column (they get no header).
- The consumer's listener observation continues that trace: a Kafka consumer span is a child of the producer's span,
  so one business transaction is one trace across services.
- Consumers never require it. An event without it, from a replay, a scheduled job or an old row, starts a new trace.

### Contract tests
Every consumer service has an `EventConsumerContractTest` (Phase 13). For each event type it subscribes to, it feeds a
schema-generated sample into the listener: full and minimal variants, delivered and redelivered. The samples come from
the `grocery-event-schemas` test-jar (`com.grocery.events.contract`), and the subscribed types are read from the
listener code. A schema or listener change is therefore covered without editing a list.

### Payloads
- **Wire format: Apache Avro** (Confluent wire format: magic byte, schema id, binary), since ADR-0011. Every event has
  a central schema in [`libs/grocery-event-schemas`](../../libs/grocery-event-schemas/), at
  `avro/<topic>/<EventType>.avsc`. Each schema is registered in the Schema Registry under subject
  `<topic>-com.grocery.events.<domain>.<EventType>` with compatibility **BACKWARD_TRANSITIVE**. Producers publish only
  registered schemas, and the outbox rejects a payload that does not match its schema.
- **What consumers see:** grocery-common turns each Avro message back into the JSON the producer wrote. That JSON is
  camelCase, with IDs as UUID strings, money as numbers with 2 decimals in INR, and timestamps in ISO-8601 UTC
  (microsecond precision). Listeners therefore still receive a JSON string, and messages from before the switch
  (plain JSON) are still accepted.
- **Stakeholder IDs:** events carry the IDs consumers need (`customerId`, `vendorId`, `restaurantId`, `partnerId`), so
  consumers rarely need to call back.
- **Tolerant reader:** consumers ignore unknown fields. Each consumer defines its own DTO with only the fields it uses.
  No shared event classes.

### Versioning
- Additive, optional fields keep the same version. Each schema evolves in place and must stay BACKWARD_TRANSITIVE
  compatible. This is checked by the schema module's build, by the registration script and by the registry itself.
- A breaking change (rename, removal, meaning change) means a **new version** (`eventVersion=2`). It is published side by side
  with v1 until every consumer has migrated, then v1 is retired. Its schema is `<EventType>.v2.avsc` with record name
  `<EventType>V2`.
- This catalog records every version.
- **Schema module versions** (`grocery-event-schemas`): a minor version adds event types or optional fields; it never
  changes what an existing producer writes. 1.0.0 = Phases 2–6, 1.1.0 = Phase 8, 1.2.0 = Phase 9, 1.3.0 = Phase 10
  (`OrderStuckDetected`), 1.4.0 = Phase 11 (`ai.events`), 1.5.0 = Phase 12 (reviews, returns, goods receipts,
  coupon fields), 1.6.0 = password management (`PasswordResetRequested`, `PasswordChanged`) and
  `CustomerProfileUpdated`, 1.7.0 = the coupon's name and funding on `OrderCreated` / `OrderConfirmed`
  (`couponName`, `couponFundedBy`). Every service pins the current version (1.7.0): there is no artifact repository
  yet, so only the version built from the current sources can be resolved (ADR-0011). (Between 1.6.0 and 1.7.0 only
  identity, customer and notification pinned 1.6.0 and the others 1.5.0; 1.7.0 moved every service again.)
- **How a new event or field ships:** (1) add or extend the `.avsc` (with `doc` on every field) and its v1 baseline,
  update the module's manifest test, and bump the minor version; (2) `schema-init` registers it, and the registry
  refuses anything that is not BACKWARD_TRANSITIVE compatible; (3) consumers deploy first if they must act on it (a
  tolerant reader ignores unknown event types and fields anyway); (4) the producer deploys. Never delete or rename a
  field in place, and never reuse an event type name with a different meaning.
- **Retention vs. replay:** a dead letter can be replayed for the DLT retention (30 days). `processed_event` rows are
  kept 35 days, so a replay can never apply an event twice. Old payload versions must therefore stay readable for at
  least 30 days after their last producer is gone.

### Consumers
- **Consumer group names:** `<service>.<purpose>`, e.g. `order-service.saga`, `catalog-query.indexer`,
  `notification-service.dispatch`.
- **Idempotency:** `processed_event(event_id, consumer)` is inserted in the same transaction as the business change.
  Duplicates are skipped.
- **Retries (reviewed in Phase 10, configurable with `grocery.kafka.retry.*`):** every consumer uses **blocking**
  retries, so per-key order is kept. Non-blocking retry topics are not used: notifications are cheap to retry in place
  and ordering matters for their projections too.

  | Failure | Policy | Settings (default) |
  |---|---|---|
  | Unreadable or invalid event (`InvalidEventException`, undecodable bytes) | straight to `<topic>.DLT` | — |
  | Processing error | 4 retries (1, 2, 4, 8 s), then the DLT | `max-attempts` (4), `initial-interval` (1s), `multiplier` (2.0), `max-interval` (10s) |
  | Outage (database, search cluster, Schema Registry or Kafka unreachable, lock timeouts, I/O — grocery-common `TransientFailures`) | the same record again, ≤ 30 s apart, for up to 30 minutes, then the DLT | `outage-max-interval` (30s), `outage-max-elapsed` (30m) |
  | `delivery.location` (best effort) | dropped and logged; never retried or dead-lettered | — |

  A restart or a short outage therefore never dead-letters a valid event, and a poison message never blocks its
  partition (the next event of the same key is processed).
- **Dead letters:** go to **`<topic>.DLT`** (e.g. `order.events.DLT`, 1 partition, 30-day retention) with the
  **original bytes** (Avro or legacy JSON) and headers, so a replay is byte-identical. Each consuming service lists,
  replays (in process, to its own listener only — never re-published, so other consumer groups are not affected) and
  discards its own dead letters (`/api/admin/dead-letters`, gateway `/api/admin/ops/<service>/dead-letters`;
  [Phase 10 §1](../api/phase-10-event-driven-hardening.md#1-dead-letters--inspect-replay-discard-grocery-common-070-every-consuming-service)).
  Their status is kept in the compacted topic `_grocery.dead-letter-actions`.

---

## Real-time consumer (Phase 7 ✅)
`notification-service.dispatch` (notification-service, any number of replicas) consumes `identity`, `vendor`,
`restaurant`, `delivery`, `order`, `payment`, `inventory` and `pricing` events (later also `ai`, `review` and
`customer`). It turns them into persistent inbox
notifications and ephemeral signals for the right users only, and pushes them over Server-Sent Events. It publishes no
events. Events older than 15 minutes (by the `occurredAt` header) only update its projections, so a replay never
notifies anyone again. Who is notified of which event:
[docs/api/phase-7-real-time-updates.md §4](../api/phase-7-real-time-updates.md#4-which-event-notifies-whom).

## identity.events — producer: identity-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| UserRegistered | 1 | customer (role CUSTOMER), vendor (VENDOR), restaurant (RESTAURANT_OWNER), delivery (DELIVERY_PARTNER), notification | userId | userId, email, fullName, phone, role, registeredAt | 2 ✅ |
| UserSuspended | 1 | notification | userId | userId, reason, suspendedBy | 2 ✅ |
| UserReactivated | 1 | notification | userId | userId, reactivatedBy | 2 ✅ |
| PasswordResetRequested | 1 | notification (emails the reset link; never in the inbox) | userId | userId, email, fullName, **token** (the raw single-use reset token: only notification-service may use it, to build the link; never logged), expiresAt (30 min). Only for an ACTIVE account, at most 3 per account per hour | ✅ |
| PasswordChanged | 1 | notification (emails "your password was changed"; never in the inbox) | userId | userId, email, fullName, method (`CHANGE` signed in / `RESET` emailed link), changedAt. The account's other sessions (CHANGE) or all of them (RESET) were signed out | ✅ |
| SecurityEventRecorded | 1 | reporting (central audit trail) | recordId | recordId (= the `auth_audit` row id), type (`REGISTERED`, `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `LOGIN_LOCKED`, `LOGIN_SUSPENDED`, `REFRESH_REUSE_DETECTED`, `LOGOUT`, `USER_SUSPENDED`, `USER_REACTIVATED`, `ADMIN_CREATED`, `PASSWORD_CHANGED`, `PASSWORD_CHANGE_FAILURE`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_IGNORED`, `PASSWORD_RESET`, `PASSWORD_RESET_FAILURE`), userId (null = unknown e-mail), email, actorUserId (the admin for USER_SUSPENDED / USER_REACTIVATED / ADMIN_CREATED; null when the account holder or nobody acted), detail (never a password or token), ipAddress, userAgent (at most 255 characters), occurredAt. One per `auth_audit` row, written in the row's transaction (outbox aggregate `SecurityEvent`) | ✅ |

Implemented consumer groups (Phase 2): `customer-service.identity-events`, `vendor-service.identity-events`,
`restaurant-service.identity-events`, `delivery-service.identity-events`, `identity-service.business-events`
(vendor.events + restaurant.events → `vendor_id` / `restaurant_id` JWT claims). Later: `identity-service.customer-events`
(customer.events → the account's name and phone, see below).

## customer.events — producer: customer-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| CustomerProfileUpdated | 1 | identity (`identity-service.customer-events`: the account's name and phone, then the revoked-before marker so the next call refreshes into the new JWT `name`), notification (`notification-service.dispatch`: the name in the email address projection) | customerId | customerId (= userId), fullName, phone (null = none), updatedAt. Published by `PUT /api/customers/me` in the profile's transaction, only when the name or the phone changed. identity ignores an event older than the profile it applied (by `updatedAt`) | ✅ |

Outbox aggregate type `Customer`; Debezium connector `customer-outbox` (`services/customer-service/deploy/debezium-connector.json`, server id 5102).

## vendor.events — producer: vendor-service
All vendor lifecycle events share one payload: `{vendorId, ownerUserId, businessName, reason, actorUserId}`
(`reason`/`actorUserId` null when not applicable).

| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| VendorRegistered | 1 | identity, catalog-command (vendor projection), notification | vendorId | lifecycle payload (DRAFT created) | 2 ✅ |
| VendorSubmitted | 1 | identity, catalog-command, notification (admin review queue) | vendorId | lifecycle payload | 2 ✅ |
| VendorApproved | 1 | identity (adds `vendor_id` claim), catalog-command (vendor name on products), notification | vendorId | lifecycle payload, actorUserId = admin | 2 ✅ |
| VendorRejected | 1 | identity, catalog-command, notification | vendorId | lifecycle payload + reason | 2 ✅ |
| VendorSuspended | 1 | identity (removes claim), catalog-command (re-publishes the vendor's products as hidden), notification | vendorId | lifecycle payload + reason | 2 ✅ |
| VendorReinstated | 1 | identity, catalog-command (products visible again), notification | vendorId | lifecycle payload | 2 ✅ |
| VendorOrderReceived | 1 | notification (`NEW_ORDER` alert + board refresh); order ignores it | orderId | orderId, orderNumber, vendorId, ownerUserId, itemCount, total, acceptBy, receivedAt | 8 ✅ |
| VendorOrderAccepted | 1 | order (`order-service.vendor-events`), notification | orderId | orderId, orderNumber, vendorId, readyInMinutes, readyBy, acceptedAt, actorUserId | 8 ✅ |
| VendorOrderRejected | 1 | order, notification | orderId | orderId, orderNumber, vendorId, reason (`OUT_OF_STOCK` / `STORE_CLOSED` / `TOO_BUSY` / `OTHER` / `NO_RESPONSE`), note, `auto`, rejectedAt, actorUserId | 8 ✅ |
| VendorOrderPreparing | 1 | order, notification | orderId | orderId, orderNumber, vendorId, preparingAt, actorUserId (packing started) | 8 ✅ |
| VendorOrderReadyForPickup | 1 | order, notification | orderId | orderId, orderNumber, vendorId, readyAt, actorUserId | 8 ✅ |
| GoodsReceived | 1 | inventory (`inventory-service.vendor-events`: stock INCREASE, reason RESTOCK), reporting | vendorId | vendorId, purchaseOrderId, purchaseOrderNumber, receiptId, supplierId, lines[productId, quantity, unitCost], receivedAt | 12 |
| PlatformSettingChanged | 1 | reporting (central audit trail) | settingKey | settingKey (`<area>.<field>`, e.g. `grocery-stores.acceptanceTimeMinutes`), area, field, oldValue, newValue (the stored texts; null = the default), changedBy, changedByName, changedAt. One per changed field of an admin settings save ([platform settings](../api/platform-settings.md)), written with its `platform_setting_change` row (outbox aggregate `PlatformSetting`) | ✅ |

## restaurant.events — producer: restaurant-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| RestaurantRegistered / Submitted / Approved / Rejected / Suspended / Reinstated | 1 | identity (`restaurant_id` claim), notification | restaurantId | `{restaurantId, ownerUserId, name, reason, actorUserId}` | 2 ✅ |
| RestaurantListingUpdated | 1 | catalog-query (`catalog-query.restaurant-indexer`); identity ignores it | restaurantId | **full public listing**: profile, address + location, opening hours, `acceptingOrders`, status, full menu (categories → items with price, foodType, availability), `listingVersion` (strictly increasing). Published on approval/suspension/reinstatement, every menu change and the accepting-orders switch — only for restaurants that were ever approved | 3 ✅ |
| RestaurantOrderReceived / Accepted / Rejected / Preparing / ReadyForPickup | 1 | order (`order-service.restaurant-events`; ignores Received), notification | orderId | as the VendorOrder* events, with `restaurantId` (aggregate `RestaurantOrder`) | 8 ✅ |
| PlatformSettingChanged | 1 | reporting (central audit trail) | settingKey | settingKey (`<area>.<field>`, e.g. `restaurants.acceptanceTimeMinutes`), area, field, oldValue, newValue (the stored texts; null = the default), changedBy, changedByName, changedAt. One per changed field of an admin settings save ([platform settings](../api/platform-settings.md)), written with its `platform_setting_change` row (outbox aggregate `PlatformSetting`) | ✅ |

## catalog.events — producer: catalog-command-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| ProductUpserted | 1 | catalog-query (`catalog-query.product-indexer`), inventory (`inventory-service.catalog-events`), pricing (`pricing-service.catalog-events`); later cart | productId | **full snapshot**: productId, vendorId, vendorName, vendorActive, name, description, brand, category, categoryPath, categoryActive, unit, sku, barcode, foodType, tags, attributes, imageUrls, status (`DRAFT`/`PUBLISHED`/`UNPUBLISHED`/`ARCHIVED`), publishedAt, `productVersion` (strictly increasing). Published on every product change and on re-snapshots caused by category or vendor changes | 3 ✅ |
| CategoryChanged | 1 | catalog-query (`catalog-query.product-indexer`) | categoryId | full category: categoryId, parentId, name, description, imageUrl, sortOrder, depth, path, active, effectiveActive, deleted, `categoryVersion` | 3 ✅ |

Payload field lists for the Phase 3 events: [docs/api/phase-3-catalog-and-search.md §8](../api/phase-3-catalog-and-search.md#8-events-introduced-in-phase-3-see-docseventsevent-catalogmd).
*Design note:* the originally planned `ProductRemoved`, `MenuChanged` and `RestaurantStatusChanged` were replaced by
snapshot events: archiving is a `ProductUpserted` with status `ARCHIVED`, and a restaurant's menu and open/accepting
state travel together in `RestaurantListingUpdated`. One versioned snapshot per aggregate means the search side never
merges partial updates, and Elasticsearch external versioning drops any stale or replayed event.

Implemented consumer groups (Phase 3): `catalog-command.vendor-events` (vendor.events),
`catalog-query.product-indexer` (catalog.events), `catalog-query.restaurant-indexer` (restaurant.events).

## pricing.events — producer: pricing-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| PriceChanged | 1 | catalog-query (`catalog-query.price-indexer`) | productId | productId, vendorId, mrp, sellingPrice, currency, `priceVersion` (strictly increasing), changedAt. Every live-price change and republish | 4 ✅ |
| PriceChangeRequested | 1 | notification (Phase 7) | productId | requestId, productId, vendorId, productName, oldMrp, oldSellingPrice, newMrp, newSellingPrice, changePercent, requestedBy | 4 ✅ |
| PriceChangeApproved | 1 | notification (Phase 7) | productId | requestId, productId, vendorId, approvedBy | 4 ✅ |
| PriceChangeRejected | 1 | notification (Phase 7) | productId | requestId, productId, vendorId, rejectedBy, reason | 4 ✅ |
| PriceChangeCancelled | 1 | notification (Phase 7) | productId | requestId, productId, vendorId, cancelledBy (null = automatic, e.g. product archived), reason | 4 ✅ |
| PlatformSettingChanged | 1 | reporting (central audit trail) | settingKey | settingKey (`<area>.<field>`, e.g. `delivery-fees.groceryBaseFee`), area, field, oldValue, newValue (the stored texts; null = the default), changedBy, changedByName, changedAt. One per changed field of an admin settings save ([platform settings](../api/platform-settings.md)), written with its `platform_setting_change` row (outbox aggregate `PlatformSetting`) | ✅ |

Outbox aggregate type `Price`. Full payloads: [docs/api/phase-4-inventory-and-pricing.md §8](../api/phase-4-inventory-and-pricing.md#8-events-introduced-in-phase-4-see-docseventsevent-catalogmd).

## inventory.events — producer: inventory-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| StockAdjusted | 1 | — (audit/analytics later) | productId | productId, vendorId, movementId, type, delta, onHand, reserved, available, reason, actorUserId, stockVersion. Every vendor adjustment | 4 ✅ |
| ProductAvailabilityChanged | 1 | catalog-query (`catalog-query.stock-indexer`), notification later | productId | productId, vendorId, inStock, `stockVersion` (strictly increasing). Only on in ↔ out of stock transitions, and on republish | 4 ✅ |
| LowStockDetected | 1 | notification (Phase 7) | productId | productId, vendorId, productName, available, threshold. When available drops to the threshold or below | 4 ✅ |

Outbox aggregate type `Stock`. Reservations (reserve / release / commit) were built in Phase 4. Since Phase 6 the order
saga drives them, and they publish the reservation events below in the same transaction as the stock change.

Implemented consumer groups (Phase 4): `inventory-service.catalog-events` and `pricing-service.catalog-events`
(catalog.events → local `product_ref` copy), `catalog-query.price-indexer` (pricing.events), `catalog-query.stock-indexer`
(inventory.events). In search, one product document has three independently versioned parts (catalog snapshot,
price, stock); each event only updates its own part and only with a newer version.

Reservation events (Phase 6) use outbox aggregate type `Reservation` and are keyed by **orderId**:

| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| StockReserved | 1 | order (`order-service.inventory-events`) | orderId | orderId, reservationId, items[productId, quantity], expiresAt (20 min) | 6 ✅ |
| StockReservationFailed | 1 | order | orderId | orderId, unavailableItems[productId, requested, available]. Nothing is held | 6 ✅ |
| StockReservationExpired | 1 | order | orderId | orderId, reservationId. From the expiry poller; a confirmed order's reservation never expires | 6 ✅ |
| StockReleased | 1 | — (audit) | orderId | orderId, reservationId, reason (`ORDER_CANCELLED` / `RESERVATION_EXPIRED`) | 6 ✅ |
| StockCommitted | 1 | — (audit) | orderId | orderId, reservationId, items[productId, quantity], committedAt — at pickup (OrderOutForDelivery) | 9 ✅ |

Store-order events (Phase 8) share `vendor.events` / `restaurant.events` with the lifecycle events, keyed by
**orderId** (outbox aggregate types `VendorOrder` / `RestaurantOrder`). Lifecycle consumers (identity, catalog-command,
catalog-query) ignore them. Phase 8 consumer groups: `vendor-service.order-events` and
`restaurant-service.order-events` (OrderConfirmed of their store type → the store's board; OrderCancelled → off the
board), and `order-service.vendor-events` / `order-service.restaurant-events` (the stores' decisions → order status).
OrderConfirmed gained optional `subtotal` and `note`, and OrderCancelled gained reasons `STORE_REJECTED` and
`STORE_NO_RESPONSE` and actor `STORE`. Both are compatible schema evolutions (version 2 of their subjects).

Implemented consumer groups (Phase 6):
- `inventory-service.order-events` (order.events): `OrderCreated` grocery → reserve; `OrderConfirmed` → hold until
  pickup; `OrderCancelled` → release.
- `cart-service.order-events` (order.events): `OrderCreated` → empty the cart if unchanged. No `processed_event`
  table: the compare-and-set on `updatedAt` makes it idempotent.
- `order-service.inventory-events` and `order-service.payment-events`.
- `payment-service.order-events` (order.events): `OrderAwaitingPayment`, `OrderCancelled`.

## order.events — producer: order-service
Outbox aggregate type `Order`, key orderId. order-service applies inventory and payment events only in the order
states listed in [the Phase 6 contract](../api/phase-6-orders-and-payments.md) (state guards); everything else is ignored.

| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| OrderCreated | 1 | inventory (`inventory-service.order-events`, grocery only), cart (`cart-service.order-events`), notification | orderId | orderId, orderNumber, orderType (GROCERY/RESTAURANT), customerId, storeId (vendorId / restaurantId), storeName, items[itemId, type, name, quantity, unitPrice, lineTotal], subtotal, deliveryFee, total, currency, cartUpdatedAt, paymentDueAt, createdAt | 6 ✅ |
| OrderAwaitingPayment | 1 | payment (`payment-service.order-events`) | orderId | orderId, orderNumber, customerId, amount, currency, expiresAt (15 min after creation), contactPhone | 6 ✅ |
| OrderConfirmed | 1 | inventory (hold until pickup); vendor / restaurant (Phase 8), notification | orderId | orderId, orderNumber, orderType, customerId, storeId, items, total, deliveryAddress, confirmedAt | 6 ✅ |
| OrderRejected | 1 | notification | orderId | orderId, orderNumber, orderType, customerId, storeId, reason (`OUT_OF_STOCK`), unavailableItems | 6 ✅ |
| OrderCancelled | 1 | inventory (release), payment (cancel / refund); vendor, restaurant, delivery, notification later | orderId | orderId, orderNumber, orderType, customerId, storeId, previousStatus, reason (`CUSTOMER_CANCELLED`, `ADMIN_CANCELLED`, `PAYMENT_TIMEOUT`, `RESERVATION_EXPIRED`), cancelledBy (CUSTOMER/ADMIN/SYSTEM), actorUserId, note, cancelledAt. **The single compensation trigger** | 6 ✅ |
| OrderAccepted | 1 | notification; delivery (Phase 9) | orderId | orderId, orderNumber, orderType, customerId, storeId, readyInMinutes, readyBy, acceptedAt | 8 ✅ |
| OrderPreparing | 1 | notification | orderId | orderId, orderNumber, orderType, customerId, storeId, preparingAt | 8 ✅ |
| OrderReadyForPickup | 1 | notification; delivery (Phase 9) | orderId | orderId, orderNumber, orderType, customerId, storeId, readyAt | 8 ✅ |
| OrderOutForDelivery | 1 | inventory (commit), vendor / restaurant (store order PICKED_UP), notification | orderId | orderId, orderNumber, orderType, customerId, storeId, partnerId, pickedUpAt | 9 ✅ |
| OrderDelivered | 1 | notification; review (Phase 12) | orderId | orderId, orderNumber, orderType, customerId, storeId, partnerId, deliveredAt | 9 ✅ |
| OrderDeliveryFailed | 1 | notification (customer + admins decide a refund) | orderId | orderId, orderNumber, orderType, customerId, storeId, partnerId, reason, note, failedAt | 9 ✅ |
| OrderStuckDetected | 1 | notification (`STUCK_ORDER` to SUPER_ADMIN, OPS_ADMIN, SUPPORT) | orderId | orderId, orderNumber, orderType, customerId, storeId, status, reason (`STOCK_CHECK_OVERDUE`, `PAYMENT_EXPIRY_OVERDUE`, `STORE_RESPONSE_OVERDUE`, `PREPARATION_OVERDUE`, `PICKUP_OVERDUE`, `DELIVERY_OVERDUE`), since, detectedAt. Published once per detection by the stuck-order watchdog ([Phase 10 §4](../api/phase-10-event-driven-hardening.md#4-stuck-order-watchdog-order-service)); the order itself is not changed | 10 ✅ |
| ReturnRequested | 1 | notification (`RETURN_REQUESTED` to the store owner) | orderId | returnId, returnNumber, orderId, orderNumber, orderType, customerId, storeId, items[itemId, name, quantity, refundAmount], refundAmount, reason, note, decideBy, requestedAt | 12 |
| ReturnApproved | 1 | payment (refund, reason RETURN), inventory (restock when `restock`), notification, reporting | orderId | … + items, refundAmount, restock, decidedBy, decidedByRole (STORE/ADMIN), decidedAt | 12 |
| ReturnRejected | 1 | notification | orderId | … + comment, decidedBy, decidedByRole, decidedAt | 12 |
| ReturnEscalated | 1 | notification (`RETURN_ESCALATED` to admins) | orderId | … + refundAmount, requestedAt, escalatedAt | 12 |
| PlatformSettingChanged | 1 | reporting (central audit trail) | settingKey | settingKey (`<area>.<field>`, e.g. `returns.returnWindowHours`), area, field, oldValue, newValue (the stored texts; null = the default), changedBy, changedByName, changedAt. One per changed field of an admin settings save ([platform settings](../api/platform-settings.md)), written with its `platform_setting_change` row (outbox aggregate `PlatformSetting`) | ✅ |

Phase 12: `OrderCreated` and `OrderConfirmed` gain the optional `couponCode`, `discount` and `deliveryFeeDiscount`
(schemas 1.5.0). pricing-service consumes `OrderCreated`, `OrderCancelled` and `OrderRejected` to confirm or release
coupon redemptions; review-service consumes `OrderConfirmed` and `OrderDelivered` for review eligibility;
reporting-service consumes most order events.

Schemas 1.7.0: `OrderCreated` and `OrderConfirmed` also gain the optional `couponName` and `couponFundedBy`
(`PLATFORM` / `STORE`, the coupon's scope at pricing-service). order-service takes both from the redeem answer and
keeps them on the order; reporting-service uses them for the coupon names and the platform / store split of the coupon
discounts. Null without a coupon, and in every event of an order placed before 1.7.0 (there is no backfill).

## payment.events — producer: payment-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| PaymentInitiated | 1 | order (`order-service.payment-events`), notification | orderId | orderId, paymentId, razorpayOrderId, amount, currency, expiresAt | 6 ✅ |
| PaymentCaptured | 1 | order, notification | orderId | orderId, paymentId, razorpayPaymentId, amount, method, capturedAt, `late` (true = captured after the payment expired / the order was cancelled → refunded automatically) | 6 ✅ |
| PaymentFailed | 1 | order, notification | orderId | orderId, paymentId, razorpayPaymentId, errorCode, errorDescription, retryable | 6 ✅ |
| PaymentExpired | 1 | order, notification | orderId | orderId, paymentId | 6 ✅ |
| RefundCompleted | 1 | order, notification | orderId | orderId, paymentId, refundId, amount, reason (`ORDER_CANCELLED` / `LATE_CAPTURE` / `ADMIN`), totalRefunded, fullyRefunded | 6 ✅ |
| RefundFailed | 1 | order, notification | orderId | orderId, paymentId, refundId, amount, reason, errorCode | 6 ✅

Phase 12: `RefundCompleted` and `RefundFailed` gain an optional `returnId` (reason `RETURN`, from `ReturnApproved`); order-service marks the return REFUNDED. |

Outbox aggregate type `Payment`, key orderId. Payloads in full:
[docs/api/phase-6-orders-and-payments.md §6](../api/phase-6-orders-and-payments.md#6-events-introduced-in-phase-6-see-docseventsevent-catalogmd).

## delivery.events — producer: delivery-service
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| DeliveryPartnerRegistered / Submitted / Approved / Rejected / Suspended / Reinstated | 1 | notification (Phase 7) | partnerId | `{partnerId (= userId), fullName, reason, actorUserId}` — partner status is enforced by delivery-service itself, so identity does not consume these | 2 ✅ |
| DeliveryOfferCreated | 1 | notification (partner) | orderId | deliveryId, orderId, orderNumber, offerId, partnerId, storeName, distanceKm, tripKm, expiresAt | 9 ✅ |
| DeliveryOfferClosed | 1 | notification (partner's offer list) | orderId | deliveryId, orderId, orderNumber, offerId, partnerId, outcome (`EXPIRED` / `REJECTED` / `WITHDRAWN`), closedAt | 9 ✅ |
| DeliveryPartnerAssigned | 1 | order (`order-service.delivery-events`), notification | orderId | deliveryId, orderId, orderNumber, partnerId, partnerName, vehicleType, manual, assignedAt | 9 ✅ |
| DeliveryPartnerUnassigned | 1 | order, notification | orderId | deliveryId, orderId, orderNumber, partnerId, reason, actorUserId, unassignedAt (admin reassignment) | 9 ✅ |
| DeliveryPickedUp | 1 | order (→ OUT_FOR_DELIVERY), notification | orderId | deliveryId, orderId, orderNumber, partnerId, pickedUpAt | 9 ✅ |
| DeliveryCompleted | 1 | order (→ DELIVERED), notification | orderId | deliveryId, orderId, orderNumber, partnerId, deliveredAt | 9 ✅ |
| DeliveryFailed | 1 | order (→ DELIVERY_FAILED), notification | orderId | deliveryId, orderId, orderNumber, partnerId, reason, note, failedAt | 9 ✅ |
| DeliveryAssignmentFailed | 1 | notification (delivery managers, ops) | orderId | deliveryId, orderId, orderNumber, attempts, radiusKm, failedAt — once per delivery; searching continues | 9 ✅ |
| DeliveryCancelled | 1 | notification (partner) | orderId | deliveryId, orderId, orderNumber, partnerId (nullable), cancelledAt | 9 ✅ |
| PlatformSettingChanged | 1 | reporting (central audit trail) | settingKey | settingKey (`<area>.<field>`, e.g. `delivery.offersPerWave`), area, field, oldValue, newValue (the stored texts; null = the default), changedBy, changedByName, changedAt. One per changed field of an admin settings save ([platform settings](../api/platform-settings.md)), written with its `platform_setting_change` row (outbox aggregate `PlatformSetting`) | ✅ |

Delivery events (Phase 9) use outbox aggregate type `Delivery` and are keyed by **orderId**. The partner lifecycle events
stay keyed by partnerId. delivery-service consumes `order.events` in group `delivery-service.order-events`:
OrderConfirmed creates the delivery, OrderAccepted starts the search, OrderReadyForPickup allows pickup, and
OrderCancelled cancels it before pickup.

## delivery.location — producer: delivery-service (best-effort, no outbox)
| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| DeliveryLocationUpdated | 1 | notification (`notification-service.location`: the order's customer only; older than 30 s dropped; ≤ 1 per order per 2 s) | orderId | deliveryId, orderId, partnerId, latitude, longitude, heading, speedKmh, accuracyMeters, recordedAt | 9 ✅ |

Published directly with grocery-common's Avro encoder (same wire format and headers as the outbox), at most once per
partner per 2 s, only while the partner has an ASSIGNED or PICKED_UP delivery. A lost message is fine: the next
position replaces it.

## review.events — producer: review-service
Outbox aggregates `Review` (key reviewId) and `RatingSummary` (key targetId). Target types: `PRODUCT`, `MENU_ITEM`,
`VENDOR`, `RESTAURANT`, `DELIVERY_PARTNER` ([Phase 12 §1](../api/phase-12-post-mvp-features.md#1-reviews--ratings-review-service)).

| Event | v | Consumers | Key | Main payload | Phase |
|---|---|---|---|---|---|
| ReviewSubmitted | 1 | notification (`NEW_REVIEW` to the store, `REVIEW_FLAGGED` to admins), reporting | reviewId | reviewId, targetType, targetId, storeId, customerId, orderId, rating, status (PUBLISHED/FLAGGED), flagReason, edited, submittedAt | 12 |
| ReviewModerated | 1 | notification (`REVIEW_NOT_PUBLISHED` to the customer) | reviewId | … + decision (PUBLISHED/REJECTED/REMOVED), reason, moderatorId, moderatedAt | 12 |
| ReviewReplied | 1 | notification (`REVIEW_REPLY` to the customer) | reviewId | … + repliedBy, repliedAt | 12 |
| RatingSummaryUpdated | 1 | catalog-query (`rating`, `ratingCount` in search; version-guarded), reporting | targetId | targetType, targetId, averageRating (2 decimals), ratingCount, ratingDistribution[5], summaryVersion, updatedAt | 12 |
| PlatformSettingChanged | 1 | reporting (central audit trail) | settingKey | settingKey (`<area>.<field>`, e.g. `reviews.reviewWindowDays`), area, field, oldValue, newValue (the stored texts; null = the default), changedBy, changedByName, changedAt. One per changed field of an admin settings save ([platform settings](../api/platform-settings.md)), written with its `platform_setting_change` row (outbox aggregate `PlatformSetting`) | ✅ |

## ai.events — producer: ai-orchestrator-service
Outbox aggregate `Approval`, key approvalId. The lifecycle of an action an AI assistant proposed and a human must
decide ([Phase 11 contract](../api/phase-11-agentic-ai-and-approvals.md)). Every event carries approvalId, action
(Phase 11: `PLACE_ORDER`, `CANCEL_ORDER`, `REFUND_PAYMENT`, `ADMIN_CANCEL_ORDER`, `SUSPEND_VENDOR`,
`SUSPEND_RESTAURANT`; Phase 12: `REQUEST_RETURN`, `ADJUST_STOCK`, `CHANGE_PRICE`, `SEND_PURCHASE_ORDER`,
`SET_MENU_ITEM_AVAILABILITY`, `ACCEPT_STORE_ORDER`, `REJECT_STORE_ORDER`, `REASSIGN_DELIVERY`), agent (`shopping`,
`admin-bi`, `vendor`, `restaurant`, `delivery`), requestedBy, onBehalfOf (the customer, or null) and a server-rendered title.

| Event | v | Consumers | Key | Main payload (besides the common fields) | Phase |
|---|---|---|---|---|---|
| ApprovalRequested | 1 | notification (`AI_APPROVAL_REQUESTED` to the deciding customer or roles) | approvalId | approverUserId, approverRoles, fourEyes, amount, currency, expiresAt, requestedAt | 11 ✅ |
| ApprovalDecided | 1 | notification (`AI_APPROVAL_DECIDED` for REJECTED and EXPIRED) | approvalId | decision (APPROVED, REJECTED, EXPIRED, CANCELLED), decidedBy, comment, decidedAt | 11 ✅ |
| ApprovalExecuted | 1 | notification (`AI_ACTION_COMPLETED`) | approvalId | decidedBy, resultReference (e.g. the order number), executedAt | 11 ✅ |
| ApprovalFailed | 1 | notification (`AI_ACTION_FAILED`) | approvalId | decidedBy, errorCode, errorMessage, httpStatus, failedAt | 11 ✅ |
