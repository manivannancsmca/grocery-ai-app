# pricing-service

Grocery prices (Phase 4): the live price of every product (MRP + selling price, INR), its history, price-change
requests above the approval threshold and their admin approval, and the Redis-cached **price quote** that cart and
order use from Phase 5. Products are known from `catalog.events`; every price event leaves through the outbox.
**Phase 12:** promotions & coupons (admin, vendor and restaurant coupons, customer offers, the internal `evaluate` for
the cart and `redeem` for order placement, the redemption lifecycle) and approval-token verification (the vendor
assistant's `CHANGE_PRICE`).

| | |
|---|---|
| Port | 8108 |
| Database | `pricing_db` (user `pricing_user`) — tables `product_ref`, `product_price`, `price_history`, `price_change_request`, `coupon`, `coupon_redemption`, `consumed_approval`, `outbox_event`, `processed_event`, `platform_setting`, `platform_setting_change` (admin platform settings) |
| Cache | Redis `price:v1:<productId>` (optional: MySQL is read when Redis is down) |
| Publishes | `pricing.events` (aggregate `Price`, key productId): `PriceChanged`, `PriceChangeRequested`, `PriceChangeApproved`, `PriceChangeRejected`, `PriceChangeCancelled` (coupons publish nothing) |
| Consumes | `catalog.events` → `ProductUpserted` → `product_ref` projection (group `pricing-service.catalog-events`); Phase 12: `order.events` → `OrderCreated` / `OrderCancelled` / `OrderRejected` → coupon redemptions (group `pricing-service.order-events`, listener id `pricing-order-events`) |
| API | `docs/api/phase-4-inventory-and-pricing.md` §3, §4, §5 (events §8); quote path: `docs/api/phase-5-cart-and-checkout-preview.md` §4; coupons: `docs/api/phase-12-post-mvp-features.md` §2; approval tokens: Phase 12 §6 / Phase 11 §5 |
| Debezium | `deploy/debezium-connector.json` (connector `pricing-outbox`, server id 5108) |

## Endpoints
| Method & path | Who |
|---|---|
| `GET /api/vendor/prices?q=&filter=&page=&size=`, `GET /api/vendor/prices/summary`, `GET/PUT /api/vendor/prices/{productId}`, `GET /api/vendor/prices/{productId}/history`, `GET /api/vendor/price-change-requests?status=`, `POST /api/vendor/price-change-requests/{id}/cancel` | `VENDOR` with a `vendor_id` claim (approved), else 403 `VENDOR_NOT_ACTIVE` |
| `GET /api/admin/price-change-requests?status=&vendorId=`, `GET /api/admin/price-change-requests/{id}` | any admin role |
| `POST /api/admin/price-change-requests/{id}/approve` (optional `{note}`), `POST …/{id}/reject` (`{reason}` required, ≤ 300) | `SUPER_ADMIN`, `OPS_ADMIN`, `FINANCE_ADMIN` |
| `POST /api/admin/pricing/republish` → `{ "prices": n }` | `SUPER_ADMIN`, `OPS_ADMIN` |
| `POST /internal/prices/quote` `{ "productIds": [1–100] }` → `{ items, missing }` | any authenticated caller, no role needed (internal: the gateway never routes `/internal/**`; the JWT is still validated here) |
| `GET/POST /api/admin/coupons`, `GET/PUT /api/admin/coupons/{id}`, `POST …/{id}/pause` · `/resume`, `GET …/{id}/usage` | read: any admin role; write: `SUPER_ADMIN`, `OPS_ADMIN`, `FINANCE_ADMIN` (Phase 12) |
| the same under `/api/vendor/coupons` and `/api/restaurant/coupons` | `VENDOR` with `vendor_id` / `RESTAURANT_OWNER` with `restaurant_id` (else 403 `VENDOR_NOT_ACTIVE` / `RESTAURANT_NOT_ACTIVE`): only their store's STORE coupons |
| `GET /api/coupons?storeId=&storeType=` → `[ CouponOffer ]` | `CUSTOMER` |
| `POST /internal/coupons/evaluate`, `POST /internal/coupons/redeem` | any authenticated caller (cart-service / order-service with the customer's token); `customerId` must be the caller |
| `GET /api/admin/settings/delivery-fees`, `PUT …`, `GET …/history?page=&size=` (admin platform settings, below) | read and history: any admin role; save: `SUPER_ADMIN`, `OPS_ADMIN` |
| `GET /internal/delivery-fees` → the eight delivery fees in force | any authenticated caller (cart-service with the customer's token) |

**Phase 5:** the quote moved from Phase 4's `POST /api/prices/quote` to `POST /internal/prices/quote`
(`docs/api/phase-5-cart-and-checkout-preview.md` §1, §4) — same body, response, validation and cache. The old path
now answers 404. cart-service calls it with the customer's forwarded token.

Vendor lists are sorted by product name, leave archived products out, `q` = name contains (case-insensitive) or
SKU equals, `filter` = `PRICED` | `UNPRICED` | `PENDING_APPROVAL`. History and the vendor's requests are newest
first. The admin queue defaults to `status=PENDING`, oldest first; other statuses newest first, with `vendorName`.

## Delivery fees: admin platform settings (area `delivery-fees`)
This service owns the money rules, so it keeps the **delivery fees** of the checkout preview, which admins change on
**Admin → Platform settings** (contract `docs/api/platform-settings.md`). cart-service charges them: it reads
`GET /internal/delivery-fees` for its previews (cached 15 s there) and falls back to the last fees it got, then to its
own config, when this service cannot be reached.
- **Defaults:** `grocery.pricing.delivery-fees.*` (`config/DeliveryFeeProperties`): grocery base fee 25.00, 3 km
  included, 8.00 per started extra km, free from 499.00, minimum order 99.00; restaurant 30.00, 3 km, 10.00. Keep them
  equal to cart-service's `grocery.cart.delivery.*` (its fallback). Road factor and maximum distance are not here: they
  stay in cart-service's config.
- **Fields and bounds:** `groceryBaseFee` 0–500, `groceryIncludedKm` 0–20 (one decimal), `groceryPerExtraKm` 0–100,
  `groceryFreeAbove` 0–10,000, `groceryMinimumOrder` 0–5,000, `restaurantBaseFee` 0–500, `restaurantIncludedKm` 0–20,
  `restaurantPerExtraKm` 0–100; rupees with at most two decimals.
- **Storage:** `platform_setting` (Flyway `V7__platform_settings.sql`, audit rows in `platform_setting_change`); a saved
  value overrides the default. Each instance re-reads the overrides every 15 s (`grocery.settings.cache-ttl`, env
  `PLATFORM_SETTINGS_CACHE_TTL`), so cart-service charges a change within about 30 s; placed orders keep their fee.
- **History:** `GET /api/admin/settings/delivery-fees/history?page=&size=` (any admin): the audit rows as a
  `PageResponse`, newest first, `{ field, oldValue, newValue, changedAt, changedBy: { id, name } }`, rupees with two
  decimals and km as in `GET`, `null` = the default (contract §5).
- **Tests:** `PlatformSettingsIntegrationTest` (defaults, overrides as cart-service reads them, reset, 409, validation,
  roles, audit rows, the history: order, values, paging, roles) and the Pact interaction "the delivery fees are the
  configured ones".

## Price rules (`service/PriceRules.java`, `service/VendorPriceService.java`)
- `1.00 ≤ sellingPrice ≤ mrp ≤ 1000000.00`, at most 2 decimals (400 `VALIDATION_FAILED`); `sellingPrice > mrp` →
  422 `SELLING_PRICE_ABOVE_MRP` (field `sellingPrice`).
- `changePercent = (new − old) / old × 100` on the selling price, signed, 2 decimals HALF_UP.
  `discountPercent = round((mrp − sellingPrice) / mrp × 100)` HALF_UP, 0–99.
- **The first price applies immediately** (200, `outcome APPLIED`, `priceVersion` 1).
- **A later change applies immediately when |changePercent| ≤ the threshold** (`grocery.pricing.approval-threshold-percent`,
  env `PRICING_APPROVAL_THRESHOLD_PERCENT`, default **20**; exactly 20.00 % applies; the rounded value is compared).
  A larger change creates a `PENDING` request and answers **202** (`outcome PENDING_APPROVAL`); the live price stays.
- While a request is pending every `PUT` is 409 `PRICE_CHANGE_PENDING` (cancel first); the same values as the live
  price → 409 `PRICE_UNCHANGED`. Unknown / another vendor's product → 404 `PRODUCT_NOT_FOUND`; archived → 409
  `PRODUCT_ARCHIVED`.
- Approve: the requested price becomes live (history `source APPROVED_REQUEST`, next `priceVersion`). Not pending →
  409 `PRICE_CHANGE_NOT_PENDING`; product archived meanwhile → 409 `PRODUCT_ARCHIVED`. A decision's note / reason is
  stored as `decisionReason`; a vendor cancel stores "Cancelled by vendor".
- Archiving a product (a `ProductUpserted` with status `ARCHIVED`) cancels its pending request automatically
  (`decisionReason` "Product archived", `PriceChangeCancelled` with `cancelledBy: null`).

## Locking and versioning (`service/PriceWriter.java`)
Consumers keep a price only when its `priceVersion` is newer than what they have, so **a higher version must never
carry older data**. Every transaction that changes a live price or a price-change request:
1. **first** locks the product's `product_ref` row (`SELECT … FOR UPDATE`) — before reading anything else. The row
   comes from the catalog and exists before the first price, so there is always something to lock. Commands
   addressed by request id look up the request's (immutable) product id before the transaction starts;
2. then reads price and pending request, checks the rules, changes them and increments `price_version`;
3. writes `price_history` and the outbox events in the same transaction and registers the cache update for after
   the commit.

Two writers of one product are serialised; the later one reads the committed state and produces the higher
version, and of two admins approving the same request at once the second gets 409 `PRICE_CHANGE_NOT_PENDING`.
Backstops: the unique generated `pending_key` column (one PENDING request per product) and a CHECK constraint on
`product_price`. Lock timeouts / deadlocks → 409 `CONCURRENT_MODIFICATION` (grocery-common) or a Kafka retry.
**Republish** re-sends every live price with its current version (no increments; batches of 500, one transaction each).

| Trigger | Events |
|---|---|
| first price, change within the threshold, approval | `PriceChanged` (priceVersion + 1) (+ `PriceChangeApproved`) |
| change beyond the threshold | `PriceChangeRequested` |
| reject / vendor cancel / product archived | `PriceChangeRejected` / `PriceChangeCancelled` |
| `POST /api/admin/pricing/republish` | `PriceChanged` for every live price, **same versions** |

## Events: Avro ([ADR-0011](../../docs/adr/0011-avro-and-schema-registry.md))
- `pricing.events` payloads travel as **Avro** (Confluent wire format), validated against the central schemas in
  `libs/grocery-event-schemas` (`avro/pricing.events/*.avsc`, money as `decimal(12,2)`; dependency
  `grocery-event-schemas:1.7.0`). Subjects: `pricing.events-com.grocery.events.pricing.<EventType>`.
- The outbox (grocery-common 0.6.0) writes both columns in the business transaction: `payload` (JSON, kept for
  auditing and the tests) and `payload_avro` (Flyway `V3`), which Debezium publishes byte for byte
  (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- `catalog.events` is read through grocery-common's Avro deserializer: the listener still gets the same JSON
  (decimals at their schema scale, e.g. `unitValue` `500.000`, which this service does not read).
- Schema Registry URL: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`);
  tests use the in-memory `mock://pricing-service`.
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only; switch the connector back to `payload` first).

## Quote cache (`cache/PriceQuoteCache.java`)
- Read-through: one `MGET` for all requested ids; misses are read from MySQL in one query and written back.
  Entries are JSON (`productId, vendorId, mrp, sellingPrice, currency, priceVersion, productVersion, archived`),
  TTL `grocery.pricing.quote-cache-ttl` (env `PRICING_QUOTE_CACHE_TTL`, default 10 min).
- **Only newer entries win.** Every write (fill on a miss, and after a price change commits) goes through one atomic
  Lua compare-and-set (`resources/redis/price-put-if-newer.lua`): an entry is replaced only by a higher
  `priceVersion` — or, for the same price, a higher `productVersion`, which is how an archived product's entry
  becomes a tombstone (quoted as `missing`). A slow reader can never put an older price back.
- **Redis is optional.** No Redis error or timeout fails a request: the quote reads MySQL, writes are skipped, the
  first failure is logged once (warning), and quote reads bypass Redis for 5 s before trying again. If an
  after-commit write fails, the previous entry may be served until it expires; `priceVersion` in the quote shows it.
- `missing` = unknown, archived or unpriced products. DRAFT / UNPUBLISHED products with a price are quoted
  (visibility is the catalog's business).

## Coupons (Phase 12 §2; `service/CouponService.java`, `CouponRedemptionService.java`, `CouponRules.java`)
Flyway `V4__coupons.sql` (`coupon`, `coupon_redemption`).

**Coupon fields.** `code` unique, `^[A-Z0-9-]{4,20}$` (any case in, stored upper-case; 409 `COUPON_CODE_TAKEN`);
`name` ≤ 80; `description` ≤ 300; `scope` `PLATFORM` (admins; `storeType` `GROCERY` / `RESTAURANT` / `ANY`, default
`ANY`) or `STORE` (`storeId`; `storeType` `GROCERY` / `RESTAURANT` may be left out: a vendor known from the catalog
projection is GROCERY, any other id RESTAURANT — a STORE coupon is matched on its `storeId` alone, so this only labels
it); `discountType` `PERCENT` (`value` 1–90, optional
`maxDiscount`), `FLAT` (`value`), `FREE_DELIVERY` (no value; fields that do not apply are ignored);
`minOrderSubtotal` (default 0); `validFrom` (default now) < `validUntil` (required, exclusive); `totalUsageLimit`
(optional); `perCustomerLimit` (default 1); `active` (default true; pause / resume, idempotent); `public` (default
false; listed to customers). Cross-field problems → 400 `VALIDATION_FAILED` with `errors[{field, message}]`.
- **Store owners:** under `/api/vendor/coupons` (`storeType` GROCERY, store = `vendor_id`) and `/api/restaurant/coupons`
  (RESTAURANT, `restaurant_id`) the scope is forced to `STORE` with their store; `scope`, `storeId`, `storeType` may be
  left out, anything else (e.g. `PLATFORM`, another store) → 403 `COUPON_SCOPE_NOT_ALLOWED`. Another store's coupon (or
  a PLATFORM one) → 404 `COUPON_NOT_FOUND`; their lists show only their store's coupons. Admins see and manage all.
- **PUT** replaces the terms; fields with a default (`scope`, `storeType`, `storeId`, `validFrom`, `minOrderSubtotal`,
  `perCustomerLimit`, `active`, `public`) keep their value when left out; the code may change (uniqueness checked).
- **`CouponView`:** `{ id, code, name, description, scope, storeType, storeId, storeName (vendors: from the catalog
  projection; else null), fundedBy (PLATFORM|STORE), discountType,
  value, maxDiscount, minOrderSubtotal, validFrom, validUntil, totalUsageLimit, perCustomerLimit, active, public,
  status (ACTIVE|PAUSED|SCHEDULED|EXPIRED), usedCount, discountGiven, createdBy, createdAt, updatedBy, updatedAt }`;
  `usedCount` = RESERVED + CONFIRMED redemptions, `discountGiven` = Σ discount + deliveryFeeDiscount of CONFIRMED ones.
  Lists: newest first, `?status=&q=&page=&size=` (admins also `scope`, `storeId`); `q` = code prefix / name contains.
- **Usage** (`GET …/{id}/usage?status=&page=&size=`): `{ couponId, code, usedCount, reservedCount, confirmedCount,
  releasedCount, discountGiven, deliveryFeeDiscountGiven, redemptions: [ { id, orderId, orderNumber, customerId,
  storeId, orderType, subtotal, deliveryFee, discount, deliveryFeeDiscount, status, createdAt, reservedAt, confirmedAt,
  releasedAt, releaseReason } ], page, size, totalElements, totalPages }` — one page of redemptions, newest first;
  `orderNumber` is learnt from the order events (null until one arrived), `createdAt` = `reservedAt`.
- **Customer offers** (`GET /api/coupons?storeId=`): active, public coupons valid now that the caller can still use at
  the store (PLATFORM of its store type or ANY, plus the store's own STORE coupons; total and the caller's limit not
  reached), ending soonest first: `[ { code, name, description, discountType, value, maxDiscount, minOrderSubtotal,
  validUntil } ]`. The store type is `storeType` when given, else GROCERY for a vendor known from the catalog projection
  and RESTAURANT otherwise; without `storeId` every PLATFORM offer.

**Evaluate / redeem.** Reasons in check order: `COUPON_NOT_FOUND`, `COUPON_PAUSED`, `COUPON_NOT_STARTED`,
`COUPON_EXPIRED`, `COUPON_WRONG_STORE`, `COUPON_MIN_ORDER` (+ `minOrderSubtotal`), `COUPON_USAGE_LIMIT`,
`COUPON_CUSTOMER_LIMIT`. Amounts (2 decimals, HALF_UP): PERCENT = subtotal × value / 100, at most `maxDiscount` and the
subtotal; FLAT = value, at most the subtotal; FREE_DELIVERY = no discount, `deliveryFeeDiscount` = the delivery fee.
**At least ₹1.00 stays payable** (payment-service cannot take ₹0): when `subtotal + deliveryFee − discount −
deliveryFeeDiscount` would be lower, `discount` is cut first, then `deliveryFeeDiscount` (a FLAT coupon equal to the
subtotal on an order with free delivery leaves ₹1.00). Same in `evaluate` and `redeem`.
```jsonc
// POST /internal/coupons/evaluate  { code, customerId?, storeId, orderType: GROCERY|RESTAURANT, subtotal, deliveryFee? }
{ "valid": true, "discount": 15.00, "deliveryFeeDiscount": 0.00,
  "code": "SAVE10", "scope": "PLATFORM", "discountType": "PERCENT" }            // + "storeId" for STORE coupons
{ "valid": false, "discount": 0.00, "deliveryFeeDiscount": 0.00, "reason": "COUPON_MIN_ORDER", "minOrderSubtotal": 199.00,
  "code": "SAVE10", "scope": "PLATFORM", "discountType": "PERCENT" }           // COUPON_NOT_FOUND: no code/scope/type
// POST /internal/coupons/redeem    { code, orderId, customerId?, storeId, orderType, subtotal, deliveryFee? }
{ "redemptionId": "…", "discount": 15.00, "deliveryFeeDiscount": 0.00, "couponName": "Save 10%", "fundedBy": "PLATFORM" }
// 409 { "errorCode": "COUPON_NOT_APPLICABLE", "reason": "COUPON_USAGE_LIMIT", "detail": "…" }   (+ minOrderSubtotal)
// 409 { "errorCode": "COUPON_REDEMPTION_CONFLICT" }  the order already redeemed another coupon / customer, or released
```
`customerId` defaults to the caller (the forwarded customer token) and must be the caller (403 `CUSTOMER_MISMATCH`).
`evaluate` never fails for a coupon problem; it only reads. `redeem` is **idempotent per `orderId`**: the same order,
coupon and customer get the same redemption back (same id and amounts) while RESERVED or CONFIRMED.
`couponName` and `fundedBy` (`PLATFORM` | `STORE`: the coupon's scope, as in `CouponView`) describe the coupon as it
is at that moment. order-service keeps them on the order and publishes them in `OrderCreated` / `OrderConfirmed`
(schemas 1.7.0), which is how reporting-service learns a coupon's name and who funds it; coupons themselves still
publish nothing.

**Concurrency.** `redeem` runs in a READ COMMITTED transaction whose first statement locks the coupon row
(`SELECT … FOR UPDATE` by code); counting RESERVED + CONFIRMED uses (total and the customer's), checking the limits and
inserting the RESERVED row all happen under that lock, so concurrent redeems of a coupon are serialised and a limit of
N lets exactly N through. Releases only lower the counts and don't take the coupon lock. The unique `order_id` is the
backstop against a concurrent redeem of the same order with another coupon.

**Lifecycle.** RESERVED (redeem) → CONFIRMED on `OrderCreated`; → RELEASED on `OrderCancelled` (`ORDER_CANCELLED`) or
`OrderRejected` (`ORDER_REJECTED`). Consumer group `pricing-service.order-events`: only orders with a redemption are
handled (lock the redemption row, `processed_event` per event id, and the state machine: duplicates and replays change
nothing; a cancelled one is never revived). The **sweeper** (`CouponReservationSweeper`, every
`COUPON_SWEEP_INTERVAL` = 1 min, fixed delay) releases RESERVED redemptions older than `COUPON_RESERVATION_TTL`
(30 min) with reason `RESERVATION_EXPIRED` — batches of 100 claimed with `FOR UPDATE SKIP LOCKED`, safe in every
replica. An `OrderCreated` arriving after the sweep confirms the redemption again (the order exists).

| Setting | Env | Default |
|---|---|---|
| `grocery.pricing.coupons.reservation-ttl` | `COUPON_RESERVATION_TTL` | `30m` |
| `grocery.pricing.coupons.sweep-interval` | `COUPON_SWEEP_INTERVAL` | `1m` |
| `grocery.pricing.coupons.sweep-batch-size` | — | `100` |
| `grocery.pricing.coupons.sweeper-enabled` | `COUPON_SWEEPER_ENABLED` | `true` (tests: `false`, run by hand) |

## Approval tokens (Phase 12 §6)
`grocery.approvals.enabled: true` (grocery-common 0.8.0): a request carrying `X-Approval-Token` (the vendor assistant's
`CHANGE_PRICE` → `PUT /api/vendor/prices/{productId}`) is verified against the orchestrator's JWKS (`APPROVAL_JWKS_URI`,
default `http://localhost:8115/.well-known/approval-jwks.json`; audience `pricing-service`) and consumed once
(`consumed_approval`, Flyway `V5`); requests without the header are untouched. A change above the price threshold still
answers 202 `PENDING_APPROVAL` (admin approval in this service).

## Package layout
```
cache/        PriceQuoteCache (MGET + Lua compare-and-set, fallback), CachedPrice
config/       PricingProperties (threshold, cache TTL), CouponProperties, Clock (µs = DATETIME(6)), scheduling
controller/   VendorPriceController, AdminPricingController, PriceQuoteController,
              AdminCouponController, VendorCouponController, RestaurantCouponController, CouponController
dto/          request/response records + validation (PriceDtos, CouponDtos)
entity/       ProductRef, ProductPrice, PriceHistory, PriceChangeRequest, Coupon, CouponTerms, CouponRedemption, enums
event/        PricingEvents (published payloads), CatalogEventsListener, OrderEventsListener (Kafka consumers)
mapper/       PriceMapper (plain Java)
repository/   Spring Data JPA (lock queries, Specifications for lists, SKIP LOCKED claim)
service/      PriceWriter (locking rule), VendorPriceService, PriceChangeDecisionService, PriceQueryService,
              CatalogProjectionService, RepublishService, PriceQuoteService, PriceRules,
              CouponService, CouponRedemptionService, CouponReservationSweeper, CouponRules, CouponOwner, CouponException
settings/     admin platform settings (area delivery-fees): PlatformSettingStore (table + 15 s cache, history),
              DeliveryFeeSettingsService, AdminSettingsController, InternalDeliveryFeeController,
              SettingChange (history entry)
```

## Run / test
```bash
# (once infrastructure/local/docker-compose.yml has a pricing-service entry: DB pricing_db, REDIS_HOST=redis)
cd infrastructure/local && docker compose up -d --build pricing-service
# tests (from repo root, Git Bash) — Testcontainers MySQL 8.4 + Redis 8.8
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx384m -v "$PWD":/workspace -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/pricing-service/pom.xml verify
```
Phase 12 tests: `CouponRulesTest` (amounts, reasons and their order), `CouponManagementIntegrationTest` (CRUD,
validation, roles, scopes, vendor A vs B, restaurant owners, customer offers), `CouponEvaluateIntegrationTest` (every
reason and discount type), `CouponRedeemIntegrationTest` (idempotency, 409 reasons, total / per-customer limits under 5–6
concurrent redeems), `CouponLifecycleIntegrationTest` (order events, duplicates, sweeper, SKIP LOCKED),
`ApprovalTokenTargetIntegrationTest`.

Environment (Phase 12 additions): `COUPON_RESERVATION_TTL`, `COUPON_SWEEP_INTERVAL`, `COUPON_SWEEPER_ENABLED`,
`APPROVAL_JWKS_URI` (compose: the orchestrator's `/.well-known/approval-jwks.json`).
