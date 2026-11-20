# reporting-service

Admin reports (Phase 12 §7, [contract](../../docs/api/phase-12-post-mvp-features.md#7-reporting-reporting-service)):
a read model of **facts built only from events** — orders and their lines, payments, refunds, returns, deliveries,
reviews, ratings and goods receipts — and the `/api/admin/reports` API over it, as JSON, CSV, Excel or PDF. It calls no other
service and publishes nothing.

| | |
|---|---|
| Port | 8116 |
| Stores | MySQL `reporting_db` (user `reporting_user`) — `order_fact`, `order_line_fact`, `refund_fact`, `return_fact`, `return_line_fact`, `delivery_fact`, `review_fact`, `rating_fact`, `receipt_fact`, `receipt_line_fact`, `store_dim`, `item_dim`, `partner_dim`, the view `order_summary`, the audit trail (`audit_entry`, `audit_actor`), `processed_event` (no outbox) |
| Consumes | group `reporting-service.facts` (listener id `reporting-facts`, concurrency 3) on `order.events` (OrderCreated, OrderConfirmed, OrderCancelled, OrderRejected, OrderDelivered, OrderDeliveryFailed, ReturnApproved), `payment.events` (PaymentCaptured, RefundCompleted), `delivery.events` (DeliveryPartnerAssigned, DeliveryPickedUp, DeliveryCompleted), `review.events` (ReviewSubmitted, RatingSummaryUpdated), `vendor.events` (GoodsReceived). Every other type on those topics is skipped without a database write; group `reporting-service.audit` (listener id `reporting-audit`) on ten topics for the audit trail (see "Audit trail") |
| Publishes | nothing (no outbox, no Debezium connector; grocery-event-schemas 1.7.0 only as a test dependency) |
| Libraries | grocery-common 0.10.1 (Avro deserializer, `processed_event` guard, error handler + DLT, dead-letter API, housekeeping, security, ProblemDetail, the Excel / PDF exporter) |
| Database / user | `infrastructure/local/mysql/init/02-reporting-db.sql` (idempotent; applied by MySQL on a fresh volume and by the one-shot `mysql-init` on existing ones) |

```
order.events ─┐                                  one transaction per record
payment.events├─► group reporting-service.facts ─► processed_event (duplicate → stop) ─► idempotent upserts
delivery.events│   (Avro → JSON, grocery-common)                                          order_fact, *_line_fact,
review.events ─┤                                                                          refund/return/delivery/review/
vendor.events ─┘                                                                          rating/receipt facts, *_dim
                                                                   GET /api/admin/reports/** ─► aggregates, joined at
                                                                                                query time (JSON | CSV)
```

## Facts: any arrival order converges
Ordering is guaranteed only per topic partition (per order on `order.events`, `payment.events` and `delivery.events`,
which are all keyed by orderId), not across topics: a refund or a delivery can arrive before its order. So:

- **One row per aggregate, idempotent upserts.** Each event fills only the columns it knows (`OrderUpdate`): timestamps
  and the reasons that go with them keep the **first** value, descriptive fields (store, total, coupon …) take the
  latest non-null value (identical in every event of an order). A PaymentCaptured may create the order row first.
- **Derived, not stored:** an order's `status` (CANCELLED > REJECTED > DELIVERY_FAILED > DELIVERED > CONFIRMED >
  CREATED, from the timestamps known) and `placed_at` (`created_at`, else the first known time) are generated columns;
  its refunded and returned amounts come from `refund_fact` / `return_fact` at read time (view `order_summary`).
- **Separate facts** for refunds (unique per `refundId`), returns (per `returnId`), deliveries (per `deliveryId`: the
  first and the latest assignment, pickup, delivery, number of assignments), reviews (per `reviewId`: an edit wins only
  if newer), ratings (per target, **guarded by `summaryVersion`**), receipts (per `receiptId`). Names (stores from the
  orders, items from the lines, partners from DeliveryPartnerAssigned) are in `*_dim`, the newest event time wins.
- **Duplicates:** `processed_event` (consumer `reporting-service.facts`) in the same transaction; a re-publication
  under a new event id is still counted once because the facts are keyed by the business id.
- **Times:** the event's own field; RefundCompleted and OrderRejected carry none, so their `occurredAt` header
  (Debezium epoch microseconds; processing time if missing). UTC `DATETIME(6)` everywhere.
- **Failures** (grocery-common): unreadable payload or missing ids → `<topic>.DLT` at once (`InvalidEventException`);
  database down / deadlock → the same record is retried. Dead letters: `GET /api/admin/dead-letters` (grocery-common).

## API (any admin: SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER)
Common query parameters: `from`, `to` — **India dates** (`Asia/Kolkata`), both inclusive, at most **366** days; default
`to` = today in India, `from` = 29 days before `to` (30 days). `format=json` (default), `csv`, `xlsx` (or `excel`) or
`pdf` (below).

| Method & path | Extra parameters | Returns |
|---|---|---|
| `GET /api/admin/reports/overview` | — | object |
| `GET /api/admin/reports/sales/daily` | `type` (GROCERY/RESTAURANT), `storeId` | array, every date of the range |
| `GET /api/admin/reports/stores/top` | `metric=gmv\|orders` (gmv), `type`, `limit` (10, 1–100) | array |
| `GET /api/admin/reports/products/top` | `metric=units\|revenue` (units), `type`, `limit` | array |
| `GET /api/admin/reports/delivery/performance` | — | array, every date of the range |
| `GET /api/admin/reports/promotions` | — | array, largest discount given first |
| `GET /api/admin/reports/ratings` | `targetType` (PRODUCT/MENU_ITEM/VENDOR/RESTAURANT/DELIVERY_PARTNER), `limit`, `minCount` (1) | object |
| `GET /api/admin/reports/purchasing` | `vendorId` | array, per vendor and India date, in date order |
| `GET /actuator/health` | public | gateway: `GET /api/health/reporting` |

Errors (ProblemDetail): a malformed date, `from` after `to`, more than 366 days, an unknown `format` / `metric` /
`type` / `targetType`, `limit` outside 1–100, `minCount` < 1, a non-UUID id → **400 `INVALID_PARAMETER`** (the detail
says which, e.g. `A report covers at most 366 days (asked: 367)`); no token → 401; a non-admin role → 403.

Money is INR with 2 decimals (`741.00`), rates are fractions 0–1 with 4 decimals (`0.3333`), average minutes 1 decimal
(`null` when nothing was measured), dates `yyyy-MM-dd`, instants ISO-8601 UTC. The shapes are the ones the admin
Reports page codes against; fields after the agreed ones are additions.

```jsonc
// overview (object)
{ "from": "2026-10-05", "to": "2026-10-06", "timezone": "Asia/Kolkata", "currency": "INR",
  "grossSales": 741.00, "netSales": 446.00, "orders": 4, "averageOrderValue": 185.25, "cancellationRate": 0.3333,
  "refunds": 295.00,
  "couponDiscounts": { "platform": 9.00, "store": 40.00, "total": 69.00, "items": 29.00, "deliveryFee": 40.00 },
  "newCustomers": 2,
  "placedOrders": 6, "cancelledOrders": 2, "rejectedOrders": 1, "refundCount": 2, "couponOrders": 3,
  "returnsApproved": 1, "returnedAmount": 45.00 }

// sales/daily
[ { "date": "2026-10-05", "orders": 3, "gmv": 630.00, "discounts": 60.00, "refunds": 250.00, "net": 380.00 } ]

// stores/top
[ { "rank": 1, "storeId", "storeName": "Green Basket", "storeType": "GROCERY", "orders": 2, "gmv": 380.00,
    "averageOrderValue": 190.00 } ]

// products/top
[ { "rank": 1, "itemId", "itemType": "PRODUCT", "name": "Apples", "storeId", "storeName": "Green Basket",
    "units": 6, "revenue": 300.00, "orders": 2, "returnedUnits": 1 } ]

// delivery/performance
[ { "date": "2026-10-05", "deliveries": 2, "avgConfirmToAssignedMinutes": 15.0, "avgAssignedToPickedUpMinutes": 12.5,
    "avgPickedUpToDeliveredMinutes": 35.0, "failed": 0 } ]

// promotions
[ { "couponCode": "FREEDEL", "couponName": "Free delivery at Spice Route", "orders": 1, "discountGiven": 40.00,
    "gmv": 250.00, "fundedBy": "STORE", "discount": 0.00, "deliveryFeeDiscount": 40.00, "stores": 1 } ]

// ratings (object)
{ "from", "to", "timezone", "targetType": null, "limit": 10, "minCount": 1,
  "reviewsSubmitted": 3, "reviewsPublished": 1, "reviewsFlagged": 2,
  "lowest":  [ { "rank": 1, "targetType": "MENU_ITEM", "targetId", "targetName": "Biryani", "averageRating": 2.00,
                 "ratingCount": 1, "distribution": [0, 1, 0, 0, 0], "updatedAt": "2026-10-05T15:30:00Z" } ],
  "highest": [ … same shape … ] }

// purchasing
[ { "vendorId", "vendorName": "Green Basket", "date": "2026-10-05", "units": 20, "cost": 577.50, "receipts": 2,
    "purchaseOrders": 1 } ]
```

### Report definitions
- **Placed / confirmed.** An order counts on the India date it was **placed** (`OrderCreated.createdAt`). A
  **confirmed** order (`orders`) is one that was paid (OrderConfirmed), also if it was cancelled or failed later — its
  refund then lowers the net.
- **GMV (`grossSales`, `gmv`)** = the `total` of confirmed orders: what the customer paid (items + delivery fee −
  coupon discount − delivery-fee discount). **Net** = GMV − refunds. **AOV** = GMV / confirmed orders.
- **Refunds** count on the date they completed (RefundCompleted's `occurredAt`), whatever the order's date, so a day's
  net can be negative. `LATE_CAPTURE` refunds are excluded (they return money for orders that were never confirmed and
  so never in GMV). With a `type` / `storeId` filter, only refunds of matching known orders count.
- **Cancellation rate** = cancelled orders / placed orders (any cancellation reason, payment timeouts included;
  out-of-stock rejections are `rejectedOrders`, not cancellations).
- **Coupon discounts** (`couponDiscounts.total`) = `items` (the coupon discount on the item subtotal) + `deliveryFee`
  (FREE_DELIVERY) of confirmed orders; `couponOrders` = confirmed orders with a coupon.
- **Who funds a coupon** is its `scope` in pricing-service (`PLATFORM` or `STORE`). order-service takes it and the
  coupon's name from the redemption and sends them in `OrderCreated` / `OrderConfirmed` (`couponFundedBy`,
  `couponName`, schemas 1.7.0), stored as `order_fact.coupon_funded_by` / `coupon_name` (Flyway V3; latest non-null
  value wins, like the other descriptive columns). `couponDiscounts.platform` / `.store` = the coupon discounts (on items
  and delivery fees) of the confirmed orders funded that way. **Orders placed before that change have no funding and no
  name**: their events never carried them, so a rebuild cannot fill them either. They count in `total` but in neither
  `platform` nor `store`, so `platform + store` can be less than `total` for ranges that reach back that far.
  Promotions: each coupon's `couponName` and `fundedBy` are those of its newest order in the range that has them
  (`null` when all its orders are older). `stores` > 1 means the coupon was used at several stores, which only a
  PLATFORM coupon can be.
- **New customers** = customers whose first confirmed order (as far as the facts go back) was placed in the range.
- **Returns** (`returnsApproved`, `returnedAmount`): ReturnApproved in the range (approval date, approved amount).
- **Top products:** lines of confirmed orders placed in the range; `revenue` = line totals before the coupon
  discount; `returnedUnits` = units of those orders returned (approved). Ties: the other metric, then the id.
- **Delivery performance:** deliveries completed (DeliveryCompleted) per date; confirm → **first** assignment, **latest**
  assignment → pickup, pickup → delivered, averaged over the deliveries where both times are known and in order;
  `failed` = OrderDeliveryFailed per date.
- **Ratings:** the current published summaries (RatingSummaryUpdated, not limited to the range) of targets with at
  least `minCount` ratings, lowest and highest (ties: more ratings first); names from the orders and deliveries
  (`null` if never seen). `reviews*` = reviews **first submitted** in the range, by their status at their latest
  submission (moderation decisions are not consumed).
- **Purchasing:** GoodsReceived per vendor and date: receipts, distinct purchase orders, units, cost = Σ quantity ×
  unitCost; vendor names from grocery orders.

### CSV (`?format=csv`)
`text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="<report>_<from>_<to>.csv"` (`overview`,
`sales-daily`, `stores-top`, `products-top`, `delivery-performance`, `promotions`, `ratings`, `purchasing`). RFC 4180:
a header row with the JSON field names (nested ones dotted: `couponDiscounts.platform`), one row per array element
(the overview is one row; ratings: `list` = `lowest` / `highest`, the distribution as `stars1` … `stars5`), CRLF line
ends, commas / quotes / line breaks quoted and quotes doubled, `null` = empty field. Text starting with `=`, `+`, `-`, `@`, TAB or CR gets a
leading `'` (spreadsheet formula injection); numbers never do.

### Excel and PDF (`?format=xlsx`, `?format=pdf`)
The same rows for people, written by grocery-common's exporter (`ReportExports`, [data exports](../../docs/api/data-exports.md)):
headings in words (`Gross sales (INR)`, `Dish` rather than `MENU_ITEM`), real numbers and dates, the title and a
line with the period and the section's filters on top (`From: …, To: …, Store type: Grocery`), the column headers
highlighted (white on the brand green), frozen and filterable in Excel. The overview lists its figures downwards
(Figure, Value, Unit: `Cancellation rate … 33.33 %`), the coupon discounts funded by the platform and by stores
included; the promotions list has the coupon's name and `Funded by` (`Platform` / `Store`, empty for older orders).
`Content-Disposition: attachment;
filename="report-<report>-<yyyy-MM-dd-HHmm>.xlsx"`, `X-Export-Rows`, `Cache-Control: no-store`; a PDF of more than
5,000 rows is refused (422 `EXPORT_TOO_LARGE`: choose Excel). Any admin role, like the JSON.

## Audit trail
One search across the platform's audit logs (2026-11-21): **who did what, to what, when**, from every service, at
`GET /api/admin/audit` (contract: [docs/api/audit-trail.md](../../docs/api/audit-trail.md)). Built like the facts,
only from events, by its own consumer group `reporting-service.audit` (listener id `reporting-audit`), so neither
can hold the other up.

| Category | Events (topic) | Actor |
|---|---|---|
| Sign-in (`SECURITY`), accounts (`ACCOUNTS`) | `SecurityEventRecorded` (`identity.events`): every row of identity-service's `auth_audit` — sign-ins and failures, lockouts, sign-outs, password changes and resets, registrations, suspensions, new admins — with the client's IP and User-Agent | the admin who acted, else the account holder, else nobody (`ANONYMOUS`) |
| Settings | `PlatformSettingChanged` (`delivery`, `order`, `pricing`, `restaurant`, `review`, `vendor.events`): one per changed field, old → new | the admin (with their name) |
| Stores, delivery partners | `Vendor…`, `Restaurant…`, `DeliveryPartner…` `Submitted / Approved / Rejected / Suspended / Reinstated` | the admin; the owner for Submitted |
| Prices, stock | `PriceChangeRequested / Approved / Rejected / Cancelled`, `StockAdjusted` | the store user, or the admin |
| Orders, deliveries | `OrderCancelled` (by customer, admin, store or system), `ReturnApproved / Rejected`, `VendorOrderRejected`, `RestaurantOrderRejected`, `DeliveryPartnerUnassigned` | as the event says |
| Payments, reviews | `RefundCompleted / Failed`, `ReviewModerated` | system; the moderating admin |
| AI assistants | `ApprovalRequested / Decided / Executed / Failed` (`ai.events`) | the user who asked or decided |

- **One row per event** (`audit_entry`, id = the event id: a redelivery never adds a row). identity's
  `UserSuspended`, `UserReactivated`, `UserRegistered` and `PasswordChanged` are not used: the same actions arrive as
  `SecurityEventRecorded`, with the client's address.
- **Who someone is** (`audit_actor`): events say user ids; the account's e-mail comes from its sign-in events and an
  admin's name from their settings changes, so every action of that person shows them (newest event wins).
- **Labels the events do not carry** (a refund's order number, a product's name) come from the facts, best effort.
- **Kept** 180 days for sign-in events (`AUDIT_SIGN_IN_RETENTION`) and 730 days for everything else
  (`AUDIT_RETENTION`), purged daily at 03:40 India time. Kafka keeps the events only days (7 locally): the trail is
  the long-term record, and the facts' rebuild (below) never touches it. A new consumer group starts with whatever
  the topics still hold; nothing older can be recovered.
- **Who may read it:** SUPER_ADMIN and OPS_ADMIN (sign-in addresses and e-mails are personal data). Not covered (no
  events yet): coupon and complaint handling, catalog edits, and the assistants' individual tool calls (the AI audit
  page has those).

## Rebuild
The read model can always be rebuilt from the topics (only as far back as their retention: **7 days** locally):
1. Stop every reporting-service replica (`docker compose stop reporting-service`).
2. Truncate the facts: `deploy/truncate-facts.sql`, e.g.
   `docker compose exec -T mysql mysql -ureporting_user -preporting_pass reporting_db < ../../services/reporting-service/deploy/truncate-facts.sql`
3. Reset the consumer group to the beginning:
   `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --group reporting-service.facts --reset-offsets --to-earliest --topic order.events --topic payment.events --topic delivery.events --topic review.events --topic vendor.events --execute`
   (or delete the group: `--delete --group reporting-service.facts`; `auto-offset-reset: earliest` then starts from the
   beginning too).
4. Start the service; it replays everything still on the topics. Duplicates and arrival order do not matter.

## Configuration
| Setting | Default (`local`) | Docker |
|---|---|---|
| `server.port` | 8116 | 8116 |
| `DB_HOST` / `DB_PORT` | localhost / 3307 | mysql / 3306 |
| `DB_USERNAME` / `DB_PASSWORD` | `reporting_user` / `reporting_pass` | same (`REPORTING_DB_PASSWORD` in compose) |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | kafka:29092 |
| `KAFKA_LISTENERS_ENABLED` | true | true |
| `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` | http://localhost:8081 | http://schema-registry:8081 (x-java-env) |
| `IDENTITY_JWKS_URI` / `JWT_ISSUER` | localhost:8101 JWKS / `https://identity.grocery.local` | identity-service:8101 |

Recommended compose service (profile `reporting`, `all`):
```yaml
  reporting-service:                # admin reports: a read model of facts built only from events (Phase 12 §7)
    <<: *java-service
    profiles: ["reporting", "all"]
    build:
      context: ../..
      dockerfile: services/reporting-service/Dockerfile
    image: grocery/reporting-service:dev
    depends_on:
      mysql: { condition: service_healthy }
      mysql-init: { condition: service_completed_successfully }   # reporting_db / reporting_user on existing volumes
      kafka: { condition: service_healthy }
      schema-init: { condition: service_completed_successfully }  # Avro schemas registered (topics exist)
    environment:
      <<: *java-env
      DB_USERNAME: reporting_user
      DB_PASSWORD: ${REPORTING_DB_PASSWORD:-reporting_pass}
    ports:
      - "8116:8116"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8116/actuator/health | grep -q UP"]
      interval: 15s
      timeout: 5s
      retries: 12
      start_period: 60s
```
The gateway routes `/api/admin/reports/**` and `GET /api/health/reporting` here (contract §8).

## Build and test
Inside Docker (no local JDK), from the repository root; the tests start MySQL 8.4.13 and Kafka 4.3.2 with
Testcontainers:
```bash
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx512m -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace \
  maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/reporting-service/pom.xml clean verify
```
- `FactsIntegrationTest`: event → facts for every consumed type, redeliveries, re-publications, reversed and shuffled
  arrival, version and edit guards, unknown types / fields, poison events, the coupon's name and funding (and older
  order events without them).
- `ReportsIntegrationTest`: every report's numbers on the hand-computed dataset of `ReportDataset` (one coupon order
  placed before the funding was recorded).
- `ReportApiIntegrationTest`: India-date boundaries, the 366-day rule and defaults, 400s, CSV, Excel and PDF (headings,
  numbers, the period on top), roles in every format (401/403).
- `KafkaEndToEndIntegrationTest`: Avro records through the real broker and listener container to the report.
- Unit: `CsvTest`, `ReportRangeTest`, `ReportMathTest`.

Every test event is encoded with its real schema (strict Avro) and decoded by the service's own deserializer, so the
tests cannot drift from what the producers publish.
