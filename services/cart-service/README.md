# cart-service

Each customer's cart (Phase 5): one store per cart, what the customer wants and the price they saw. Everything that
can change (price, stock, visibility, whether the store is open) is **re-checked live** from the owning service every
time the cart is shown, and the **checkout preview** adds the delivery address, delivery fee, totals and blocking
issues. Nothing is reserved here: inventory-service holds the stock when an order is created (Phase 6), and
`OrderCreated` empties the cart the order was placed from.

| | |
|---|---|
| Port | 8109 |
| Store | **Redis only** — `cart:v1:<customerId>` (no MySQL, no outbox, no Debezium connector) |
| Reads | pricing `POST /internal/prices/quote` and (Phase 12) `POST /internal/coupons/evaluate`, inventory `POST /internal/inventory/availability`, catalog-query `POST /internal/catalog/products/lookup`, vendor `GET /internal/vendors/{id}/store`, restaurant `POST /internal/restaurants/{id}/menu-quote`, customer `GET /api/customers/me/addresses[/{id}]` |
| Consumes | `order.events` → `OrderCreated` only (group `cart-service.order-events`, listener id `cart-order-events`) |
| Publishes | nothing |
| API | `docs/api/phase-5-cart-and-checkout-preview.md` §2, §3; order events: `docs/api/phase-6-orders-and-payments.md` §6 "Cart"; Phase 9 store location: `docs/api/phase-9-delivery.md` §7 |

## Endpoints (role `CUSTOMER`; customerId = the JWT subject)
| Method & path | Response |
|---|---|
| `GET /api/cart` | `CartView` (empty cart: `store: null, lines: []`) |
| `POST /api/cart/items` `{type, productId \| restaurantId+menuItemId, quantity, replaceCart?}` | `CartView` (quantities of the same item merge) |
| `PUT /api/cart/items/{itemId}` `{quantity}` (0 removes) | `CartView` · 404 `CART_ITEM_NOT_FOUND` |
| `DELETE /api/cart/items/{itemId}` | `CartView` (idempotent) |
| `DELETE /api/cart` | 204 |
| `GET /api/cart/preview?addressId=` | `CheckoutPreview` (default address without `addressId`) |
| `PUT /api/cart/coupon` `{code}` (Phase 12) | `CartView` · 422 `COUPON_NOT_APPLICABLE` (`reason`, `code`, `minOrderSubtotal`) |
| `DELETE /api/cart/coupon` (Phase 12) | `CartView` (idempotent) |

Errors: 409 `CART_STORE_CONFLICT` (`currentStoreId`, `currentStoreName`, `currentStoreType`), `ITEM_UNAVAILABLE`,
`OUT_OF_STOCK`, `INSUFFICIENT_STOCK` (`available`), `STORE_CLOSED`, `NOT_ACCEPTING_ORDERS`,
`CONCURRENT_MODIFICATION`; 422 `QUANTITY_LIMIT` (line > 20), `CART_LIMIT_REACHED` (> 30 lines); 404
`ADDRESS_NOT_FOUND`; 503 `DEPENDENCY_UNAVAILABLE` (`dependency`), `CART_UNAVAILABLE` (Redis); 400
`VALIDATION_FAILED` (missing ids, a PRODUCT with menu ids or vice versa, quantity < 1 / < 0).

## Storage (`store/CartStore.java`, `resources/redis/cart-compare-and-set.lua`)
- One hash per customer: `version` (integer, +1 per write) and `data` (JSON: store `{id, type, name}`, lines
  `{itemId, type, quantity, priceWhenAdded, name, unitLabel, imageUrl, foodType, addedAt}`, `updatedAt`). TTL
  `grocery.cart.ttl` (30 days) refreshed by every write. The display snapshot is shown only when live data is missing.
- **Every write** = read → change → atomic Lua compare-and-set on `version` (written only if nobody wrote since the
  read). On a conflict the change is re-applied to a fresh read (store conflict, limits and stock are re-checked),
  up to `grocery.cart.write-retries` (3) times with exponential full-jitter backoff (≤ 25/50/100 ms), then 409
  `CONCURRENT_MODIFICATION`. An emptied cart is stored empty (the key is never deleted by a write), so versions only
  grow while the cart exists: an old read can never match a newer cart (no ABA).
- Redis is not optional here: any Redis error or timeout (1 s) → 503 `CART_UNAVAILABLE`; the Redis health
  indicator stays on (the service is DOWN without its store).

## Emptying the cart after an order (`event/OrderEventsListener.java`, `CartService.clearIfUnchangedSince`)
order-service places an order from the checkout preview and copies the preview's `updatedAt` (ISO-8601, as this
service writes it, e.g. `2026-11-11T10:15:30.120Z`) into `OrderCreated.cartUpdatedAt`. On `OrderCreated` (grocery
and restaurant orders alike) the customer's cart is emptied **only if it has not changed since**:
- the stored cart's `updatedAt` must equal `cartUpdatedAt`, both parsed as instants and compared at the cart's own
  precision (milliseconds: `CartConfig`'s clock), so `…:30Z` and `…:30.000Z` are the same version;
- a cart edited after the order was placed (another tab, a new item) is kept; a missing or already empty cart is
  left alone (nothing is written, no key is created); an event without `cartUpdatedAt` keeps the cart (logged);
- check and clear go through the normal write path: read → check → the same Lua **compare-and-set on `version`**.
  An edit that lands between the read and the clear makes the compare-and-set fail; the check is then repeated on
  the edited cart, whose `updatedAt` no longer matches, so a racing edit is never lost. The emptied cart is stored
  empty (`version` + 1, `updatedAt` = now, TTL refreshed), exactly like `DELETE /api/cart`.
- **No `processed_event` table** (this service has no database): the effect is idempotent by nature — a redelivered
  `OrderCreated` finds the cart empty (or changed), its `updatedAt` no longer matches, nothing happens.
- Failures: unreadable payload, missing `orderId` / `customerId`, missing event headers → `InvalidEventException`
  (dead-letter topic `order.events.DLT` at once). Redis down or timing out (`CART_UNAVAILABLE`) and a cart that kept
  changing through every compare-and-set retry (`CONCURRENT_MODIFICATION`) → `TransientEventProcessingException`:
  grocery-common's Kafka error handler retries the same record with the outage backoff (up to 30 min) instead of
  dead-lettering it. Every other order event type is ignored.

## Events: Avro (ADR-0011)
- `order.events` payloads are **Avro on the wire** (order-service encodes them against the central schemas of
  `libs/grocery-event-schemas`; its outbox keeps the JSON `payload` column too). grocery-common 0.6.0 installs
  `ErrorHandlingDeserializer(AvroEventDeserializer)` on this service's consumer factory: it fetches the writer schema
  from the Schema Registry and hands the listener the same JSON as before (legacy JSON values pass through), so
  `OrderEventsListener` and its DTO are unchanged. `cartUpdatedAt` travels as `timestamp-micros` and comes back as
  `Instant.toString()` text, which the millisecond comparison above reads the same way.
- This service publishes nothing, so it needs no schemas at runtime (`grocery-event-schemas` is a test dependency
  only, for the Avro round-trip test).
- Registry: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (property `grocery.events.avro.schema-registry-url`,
  default `http://localhost:8081`; compose sets `http://schema-registry:8081`). An unreachable registry is a transient
  failure (the record is retried, never dead-lettered); undecodable bytes go to `order.events.DLT`.
- Rollback switch: `grocery.events.avro.enabled=false` (plain StringDeserializer, as before), only once every Avro
  record still on `order.events` has been consumed.

## Live pricing (`service/CartPricer.java`, `service/LiveDataFetcher.java`)
- **One batch call per dependency per request.** Grocery: lookup, quote, availability and the vendor's store run in
  parallel (virtual threads); restaurant: one menu quote. An add/update checks the item with the same batch that
  then prices the response (only the vendor store is fetched after the write).
- Calls forward the caller's own token (`Authorization: Bearer …` from the security context) and `X-Correlation-Id`.
- Timeouts: connect 1 s, read 2.5 s (`grocery.cart.http.*`). **Circuit breakers:** Spring Cloud CircuitBreaker +
  Resilience4j, one per dependency (`pricing`, `inventory`, `catalog`, `vendor`, `restaurant`, `customer`): count
  window 20, ≥ 10 calls, opens at 50 % failures for 10 s (`grocery.cart.circuit-breaker.*`). Only 5xx, timeouts and
  connection errors count; calls run on the request thread (no time limiter, no bulkhead thread pool). A 404 is
  "not found" only with the owner's error code (`VENDOR_NOT_FOUND`, `RESTAURANT_NOT_FOUND`, `ADDRESS_NOT_FOUND`);
  any other 404 (e.g. an endpoint that is not deployed) is a failure.
- **Adding / changing a quantity** needs the data: a failed dependency → 503 `DEPENDENCY_UNAVAILABLE` (the vendor
  store is not needed to add). **Viewing** never fails because of a dependency: affected lines get
  `PRICING_UNAVAILABLE` (no `unitPrice`) or `AVAILABILITY_UNKNOWN` (catalog/inventory down; the price is still shown);
  a vendor-service failure adds a cart-level `AVAILABILITY_UNKNOWN`.
- One issue per line, most important first: `ITEM_UNAVAILABLE` → `PRICING_UNAVAILABLE` → `AVAILABILITY_UNKNOWN` →
  `OUT_OF_STOCK` → `INSUFFICIENT_STOCK` → `PRICE_CHANGED` (the only non-blocking code). Store problems
  (`STORE_UNAVAILABLE`, `STORE_CLOSED`, `NOT_ACCEPTING_ORDERS`) are cart-level issues (`itemId: null`).
  Unavailable/unpriced lines have `unitPrice: null` and are not in the subtotal; out-of-stock lines keep their price.
- `subtotal` = Σ lineTotal; grocery `mrpTotal` = Σ mrp × qty, `savings` = mrpTotal − subtotal; restaurant
  `mrpTotal` = subtotal, `savings` 0. Money: BigDecimal, 2 decimals, HALF_UP.

## Add rules (`service/CartService.java`)
Order of checks: the item is orderable (grocery: visible → priced → in stock; restaurant: approved, on this menu
and available → open now → accepting orders), then the store conflict (`replaceCart: true` empties a cart of
another store first), then the limits (line ≤ 20, ≤ 30 lines), then stock for the **merged** line quantity.
`priceWhenAdded` = the live price when the line is created or its quantity changes (also on a merge). `PUT` with a
quantity > 0 runs the same live checks for the new quantity.

## Store location (Phase 9)
`CartView.store` and `CheckoutPreview.store` are `{ id, type, name, city, latitude, longitude }`. The three new
fields (additive) come from the data the cart already reads: the vendor store (`GET /internal/vendors/{id}/store`)
for a grocery cart, the menu quote for a restaurant cart. They are **null when unknown**: the store has no location
set (both coordinates are needed, a half-set location is reported as null), or its owner could not be reached.
order-service snapshots them at order creation as the delivery's pickup point (`OrderConfirmed.storeLocation`).

## Checkout preview (`service/CheckoutPreviewBuilder.java`, `service/DeliveryCalculator.java`)
`distanceKm` = haversine(store, address) × 1.3, 1 decimal; `fee` = base + ⌈distanceKm − included⌉ × per-km, free
when `subtotal ≥ freeAbove`; `total = subtotal + fee`. Store location: vendor store / menu quote coordinates.

| `grocery.cart.delivery.<type>.*` | included-km | base-fee | per-extra-km | free-above | max-distance-km | minimum-order |
|---|---|---|---|---|---|---|
| `grocery` | 3 | 25.00 | 8.00 | 499.00 | 15 | 99.00 |
| `restaurant` | 3 | 30.00 | 10.00 | — | 12 | — |

**The fees are admin platform settings** (area `delivery-fees`, owned by pricing-service; contract
`docs/api/platform-settings.md`). The preview charges the base fee, included km, per-km fee, free-above and minimum
order that pricing-service's `GET /internal/delivery-fees` answers (`service/DeliveryFeeRules.java`, with the
customer's token, fetched in parallel with the live data):
- the answer is kept for 15 s (`grocery.cart.delivery.settings-cache-ttl`, env `DELIVERY_FEES_CACHE_TTL`), so a change
  reaches checkout within about 30 s (pricing's own 15 s cache plus this one); placed orders keep their fee;
- **checkout never breaks because of it:** when pricing-service cannot be reached (or has no such endpoint), a warning
  is logged and the last fees it gave stay in use, before its first answer the table above; the next attempt is 15 s
  later. The lookup has its own circuit breaker (`pricing-fees`), so it never opens the quote's;
- the table above is that fallback only: keep it equal to pricing-service's `grocery.pricing.delivery-fees.*`. The
  road factor and `max-distance-km` always come from here (vendor-service's "stores near you" uses the same numbers).

Tests: `DeliveryFeeSettingsIntegrationTest` (the fees in force, one lookup per interval with the customer's token, the
last fees while pricing-service is down, the configured fees before its first answer) and the Pact interaction
"the delivery fees are the configured ones".

Preview issues: `EMPTY_CART`, `ADDRESS_REQUIRED` (no address / no default), `ADDRESS_LOCATION_MISSING` (no lat/lng),
`OUT_OF_DELIVERY_AREA` (distance > max; `delivery` still shows the distance), `BELOW_MINIMUM_ORDER` (not checked
while prices are unavailable). `delivery: null` without a usable address, for an empty cart, or when the store's
location is unknown; a store that answered **without coordinates** gets `STORE_UNAVAILABLE`. An `addressId` that is
not the caller's → 404 `ADDRESS_NOT_FOUND`; customer-service unreachable → 503 `DEPENDENCY_UNAVAILABLE`.

## Coupon (Phase 12 §2; `service/CouponChecker.java`, `CartService.applyCoupon` / `removeCoupon`)
One coupon per cart, stored with the cart (`data.coupon = { code, scope, storeId, appliedAt }`; carts stored before have
none). pricing-service decides: the coupon is **evaluated live** (`POST /internal/coupons/evaluate` with the customer's
token: `{ code, customerId, storeId, orderType, subtotal, deliveryFee }`) every time the cart or the preview is shown —
one call after the cart is priced, through the `pricing` circuit breaker and the same timeouts.
- **`PUT /api/cart/coupon {code}`** (any case; stored upper-case): the cart is priced live and the coupon evaluated for
  its store and subtotal (no delivery fee yet). Not usable → 422 `COUPON_NOT_APPLICABLE` with `reason`
  (pricing-service's), `code`, `minOrderSubtotal` (COUPON_MIN_ORDER) and a readable `detail`; nothing is stored. An empty
  cart → 422 with `reason: EMPTY_CART`. Prices or pricing-service unavailable → 503 `DEPENDENCY_UNAVAILABLE` (like
  adding). A second PUT replaces the coupon. **`DELETE /api/cart/coupon`** removes it (idempotent).
- **`CartView.coupon`** and **`CheckoutPreview.coupon`**: `{ code, valid, discount, deliveryFeeDiscount, reason?,
  message?, minOrderSubtotal? }` (null without a coupon). The preview adds `discount` and `deliveryFeeDiscount` (0.00
  without a usable coupon) and `total = subtotal + delivery fee − discount − deliveryFeeDiscount`, never below ₹1.00
  (pricing-service's rule, re-applied here: the discount is cut first). The cart view has no delivery fee, so a
  FREE_DELIVERY coupon shows `deliveryFeeDiscount: 0.00` there.
- A coupon that cannot be used (any more) **stays visible** with `valid: false`, 0.00 amounts, its `reason` and
  `message`, and the **blocking** cart-level issue `COUPON_NOT_APPLICABLE` (`canCheckout: false`) — the customer removes
  it or fixes the cart. pricing-service unreachable, or the cart's own prices unavailable (then evaluate is not even
  called) → `reason: COUPON_UNAVAILABLE`, same blocking issue; never a 500. A coupon on an emptied cart →
  `reason: EMPTY_CART`.
- **Store change:** adding another store's items (`replaceCart`, or an emptied cart filled from another store) drops a
  `STORE` coupon of the old store; a `PLATFORM` coupon stays (and is evaluated for the new store). `DELETE /api/cart` and
  the clear after `OrderCreated` remove the coupon with the lines.
- order-service reads `coupon.code` from the preview at placement and redeems it in pricing-service.

## Package layout
```
client/      one RestClient per dependency, Downstream (timeouts, circuit breakers, Outcome), DownstreamDtos,
             CallContext (caller's token + correlation id)
config/      CartProperties (grocery.cart.*), CartConfig (Clock, circuit breaker defaults)
controller/  CartController
dto/         CartDtos (requests, CartView, CheckoutPreview, CouponView)
event/       OrderEventsListener (order.events → OrderCreated empties the cart)
model/       StoredCart, StoredLine, StoredCoupon, Store, ItemType, StoreType
service/     CartService (use cases, CAS write loop, clear after an order, coupon), LiveDataFetcher, CartPricer,
             CheckoutPreviewBuilder, CouponChecker, DeliveryCalculator, DeliveryFeeRules (the fees in force), IssueCode,
             Money
store/       CartStore (Redis hash + Lua compare-and-set)
web/         CartErrors (ProblemDetail errors)
```

## Run / test
```bash
cd infrastructure/local && docker compose --profile cart up -d --build cart-service
# tests (from repo root, Git Bash) — Testcontainers Redis 8.8 + an in-process stub of all six dependencies
MSYS_NO_PATHCONV=1 docker run --rm --memory 3g -e MAVEN_OPTS=-Xmx384m -v "$PWD":/workspace -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -B -f services/cart-service/pom.xml verify
```
Tests need no Kafka broker: `spring.kafka.listener.auto-startup=false`, and the order-event tests hand records (with
the outbox router's headers) to the listener directly. `OrderCreatedIntegrationTest`: cleared when unchanged (with
the preview's own `updatedAt`), kept when edited after the order, millisecond precision, missing / empty cart, no
`cartUpdatedAt`, other event types, malformed events, an edit racing the clear, a cart that never stops changing →
transient, and an `OrderCreated` that went through the real Avro path (grocery-common's encoder with the real schema,
then the consumer factory's deserializer: whole seconds, sub-millisecond digits, 1 ms off, null);
`RedisUnavailableIntegrationTest`: Redis down → transient (retried, not dead-lettered). Both contexts decode Avro
against Confluent's in-memory registry (`grocery.events.avro.schema-registry-url=mock://cart-service`).
`CouponCartIntegrationTest` (Phase 12; the stub plays pricing-service's evaluate): apply / remove, the evaluate request,
preview discount lines and totals (PERCENT, FREE_DELIVERY), 422 at apply (unknown, minimum order, wrong store, empty
cart), a coupon turning invalid (blocking issue, `canCheckout: false`), pricing down (`COUPON_UNAVAILABLE`, 503 on PUT),
store change and clearing.

Environment: `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP_SERVERS` (docker profile `kafka:29092`),
`KAFKA_LISTENERS_ENABLED` (default `true`), `IDENTITY_JWKS_URI`, `JWT_ISSUER`, `CART_TTL`, `PRICING_SERVICE_URL`,
`INVENTORY_SERVICE_URL`, `CATALOG_QUERY_SERVICE_URL`, `VENDOR_SERVICE_URL`, `RESTAURANT_SERVICE_URL`,
`CUSTOMER_SERVICE_URL`, `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (profile `docker` defaults to the compose service
names).
