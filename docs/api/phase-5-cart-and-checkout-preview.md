# Phase 5 API contract — Cart & checkout preview

Single source of truth for the Phase 5 backend and the React app. Public paths go through the API gateway
(`http://localhost:8080`). Conventions (auth header, ProblemDetail errors with `errorCode`, UUIDv7 ids, ISO-8601 UTC
timestamps, correlation IDs, money as JSON numbers in INR with 2 decimals) are unchanged from
[Phase 2](phase-2-identity-and-stakeholders.md#1-conventions).

## 1. Who owns what

| Service | Port | Store | Owns |
|---|---|---|---|
| **cart-service** (new) | 8109 | Redis only | each customer's cart, cart pricing and the checkout preview (delivery fee, totals, blocking issues) |

The cart stores **what** the customer wants (item, quantity, the price seen when it was added). Everything that can
change — price, stock, whether the store is open — is **re-checked live** from the owning service every time the cart
is shown. Nothing is reserved yet: stock is held only when an order is created (Phase 6).

```
customer ─► gateway ─► cart-service ──(user's JWT)──► pricing      /internal/prices/quote
                          │                         ├► inventory    /internal/inventory/availability
                          │                         ├► catalog-query /internal/catalog/products/lookup
                          │                         ├► vendor       /internal/vendors/{id}/store
                          │                         ├► restaurant   /internal/restaurants/{id}/menu-quote
                          ▼                         └► customer     /api/customers/me/addresses/{id}
                        Redis  cart:v1:<customerId>  (30 days after the last change)
```

### Internal endpoints (new convention)
Service-to-service endpoints live under **`/internal/**`**. The gateway never routes `/internal/**` (404 from outside),
but each service still validates the caller's JWT (zero trust). Calls made for a user forward that user's token.
Phase 4's `POST /api/prices/quote` moves to **`POST /internal/prices/quote`** (same body and response).

## 2. Cart (cart-service, role `CUSTOMER`)

| Method & path | Body → Response |
|---|---|
| `GET /api/cart` | → `CartView` (live prices and availability; an empty cart is `store: null, lines: []`) |
| `POST /api/cart/items` | `AddItemRequest` → `CartView`. Adds the quantity to an existing line |
| `PUT /api/cart/items/{itemId}` | `{ "quantity": 3 }` (0–20; 0 removes the line) → `CartView` · 404 `CART_ITEM_NOT_FOUND` |
| `DELETE /api/cart/items/{itemId}` | → `CartView` |
| `DELETE /api/cart` | → 204 (empty cart) |
| `GET /api/cart/preview?addressId=` | → `CheckoutPreview`. Without `addressId` the customer's default address is used; no address at all → issue `ADDRESS_REQUIRED` |

```jsonc
// AddItemRequest — exactly one kind of item
{ "type": "PRODUCT", "productId": "…", "quantity": 2 }
{ "type": "MENU_ITEM", "restaurantId": "…", "menuItemId": "…", "quantity": 1 }
// optional on both: "replaceCart": true  → empties a cart from another store first
```

**Rules when adding or changing quantities**
- **One store per cart.** Adding an item from another store → 409 `CART_STORE_CONFLICT` with properties
  `currentStoreId`, `currentStoreName`, `currentStoreType`. The client asks "Start a new cart?" and repeats the
  request with `"replaceCart": true`.
- The item must be orderable right now, otherwise nothing changes:
  - grocery: product visible in the catalog and priced → else 409 `ITEM_UNAVAILABLE`; out of stock → 409 `OUT_OF_STOCK`;
    the new line quantity above available stock → 409 `INSUFFICIENT_STOCK` (property `available`);
  - restaurant: restaurant approved, menu item exists and is available → else 409 `ITEM_UNAVAILABLE`; restaurant closed
    now → 409 `STORE_CLOSED`; not accepting orders → 409 `NOT_ACCEPTING_ORDERS`.
- Limits: 1–20 per line (a line above 20 → 422 `QUANTITY_LIMIT`), at most 30 lines → 422 `CART_LIMIT_REACHED`.
- `priceWhenAdded` is the unit price at the moment the line was created or its quantity last changed.
- Two tabs changing the cart at the same time are safe: every write is an atomic compare-and-set on the cart's version
  (retried; if it still conflicts → 409 `CONCURRENT_MODIFICATION`).
- If a dependency (pricing, inventory, catalog, restaurant) is down while **adding**, the add fails with
  503 `DEPENDENCY_UNAVAILABLE`. While **viewing**, the cart is still shown: affected lines have `unitPrice: null` and the
  blocking issue `PRICING_UNAVAILABLE` / `AVAILABILITY_UNKNOWN`.
- If Redis is down: 503 `CART_UNAVAILABLE`.
- *As built:*
  - **Removing a line:** `DELETE /api/cart/items/{itemId}` is idempotent (removing a missing line is not an error).
  - **Changing a quantity:** a `PUT` with quantity > 0 runs the same checks as adding.
  - **Issues:** each line carries at most one issue, the most severe.
  - **Error properties:** 503 errors carry a `dependency` property; the 422 limit errors carry `max`.
  - **Concurrent writes:** under heavy parallel writes to one cart, a write may still end in 409
    `CONCURRENT_MODIFICATION` after its retries. Nothing is lost; the client retries.
  - **Circuit breakers:** every downstream call goes through a Resilience4j circuit breaker (Spring Cloud CircuitBreaker),
    so a dead dependency fails fast.

```jsonc
// CartView
{ "store": { "id": "…", "type": "GROCERY", "name": "Green Basket" } | null,      // type GROCERY | RESTAURANT
  "lines": [ CartLine ],
  "itemCount": 3,                      // sum of quantities
  "subtotal": 248.00,                  // sum of lineTotal of priced lines
  "mrpTotal": 300.00, "savings": 52.00,   // grocery: MRP-based savings; restaurant: mrpTotal = subtotal, savings 0
  "currency": "INR",
  "issues": [ Issue ],                 // see below; empty when everything is fine
  "updatedAt": "…" | null }            // null for a cart that was never written

// CartLine
{ "itemId": "…",                       // productId or menuItemId
  "type": "PRODUCT",                   // PRODUCT | MENU_ITEM
  "name": "Basmati Rice", "unitLabel": "1 kg" | null, "imageUrl": null, "foodType": "VEG" | null,
  "quantity": 2,
  "unitPrice": 50.00 | null,           // live selling price (null when it can't be priced now)
  "mrp": 120.00 | null,                // grocery only
  "lineTotal": 100.00 | null,
  "priceWhenAdded": 90.00,
  "availableQuantity": 7 | null,       // grocery only: live available stock
  "issue": null }                      // the line's issue code, if any (same codes as Issue)

// Issue
{ "code": "PRICE_CHANGED", "itemId": "…" | null, "blocking": false,
  "message": "Basmati Rice now costs ₹50.00 (was ₹90.00)" }
```

**Issue codes**

| Code | Blocks checkout | When |
|---|---|---|
| `PRICE_CHANGED` | no | the live unit price differs from `priceWhenAdded` |
| `ITEM_UNAVAILABLE` | yes | product hidden/unpriced/archived, or menu item removed/unavailable |
| `OUT_OF_STOCK` | yes | grocery product has no available stock |
| `INSUFFICIENT_STOCK` | yes | line quantity > available (message says how many are left) |
| `STORE_UNAVAILABLE` | yes | vendor suspended / restaurant not approved |
| `STORE_CLOSED` | yes | restaurant closed now (opening hours, India time) |
| `NOT_ACCEPTING_ORDERS` | yes | restaurant paused orders |
| `PRICING_UNAVAILABLE`, `AVAILABILITY_UNKNOWN` | yes | a dependency could not be reached |
| `ADDRESS_REQUIRED` | yes | preview only: no address given and no default address |
| `ADDRESS_LOCATION_MISSING` | yes | preview only: the address has no latitude/longitude |
| `OUT_OF_DELIVERY_AREA` | yes | preview only: further than the store type's maximum distance |
| `BELOW_MINIMUM_ORDER` | yes | preview only: subtotal below the store type's minimum order |
| `EMPTY_CART` | yes | preview only |

## 3. Checkout preview

`GET /api/cart/preview?addressId=` → everything in `CartView` plus:

```jsonc
{ …CartView,
  "address": { "id", "label", "recipientName", "phone", "line1", "line2", "landmark", "city", "state",
               "postalCode", "latitude", "longitude" } | null,
  "delivery": { "distanceKm": 4.6, "fee": 37.00, "freeAbove": 499.00 | null, "maxDistanceKm": 15,
                "minimumOrder": 99.00 } | null,            // null when there is no usable address or the cart is empty
  "total": 285.00,                                          // subtotal + delivery fee (all prices include taxes)
  "canCheckout": true,                                      // no blocking issue
  "pricedAt": "…" }
```

An `addressId` that is not the caller's → 404 `ADDRESS_NOT_FOUND` (from customer-service). customer-service unreachable →
503 `DEPENDENCY_UNAVAILABLE`. Delivery is free when `subtotal ≥ freeAbove`. An out-of-area address still returns
`delivery` (with the distance) next to the `OUT_OF_DELIVERY_AREA` issue. The minimum order is not checked while
prices are unavailable.

**Delivery fee** (configuration `grocery.cart.delivery.*`, defaults below). Since the platform settings the fees are
admin settings owned by pricing-service; road factor and maximum distance stay here
([platform-settings.md](platform-settings.md) §4):

| Store type | Included distance | Base fee | Per extra km (started) | Free above subtotal | Max distance | Minimum order |
|---|---|---|---|---|---|---|
| GROCERY | 3 km | ₹25.00 | ₹8.00 | ₹499.00 | 15 km | ₹99.00 |
| RESTAURANT | 3 km | ₹30.00 | ₹10.00 | — | 12 km | — |

`distanceKm` = straight-line (haversine) distance between the store and the address × 1.3 road factor, 1 decimal.
Store locations: vendor business address (vendor-service) or restaurant address (restaurant-service).
All prices are **inclusive of taxes** (MRP rule for groceries; restaurant menu prices as published).

## 4. Internal endpoints added in Phase 5 (any authenticated caller; not routed by the gateway)

| Service | Method & path | Body → Response |
|---|---|---|
| pricing | `POST /internal/prices/quote` | (moved from `/api/prices/quote`, unchanged) |
| inventory | `POST /internal/inventory/availability` | `{ "productIds": [ … ] }` (1–100) → `{ "items": [ { "productId", "vendorId", "available": 7, "inStock": true } ], "missing": [ … ] }` (unknown or archived → missing) |
| catalog-query | `POST /internal/catalog/products/lookup` | `{ "productIds": [ … ] }` (1–100) → `{ "items": [ ProductCard ], "missing": [ … ] }` — only **visible** products (Phase 3 rule); hidden/unknown → missing |
| vendor | `GET /internal/vendors/{vendorId}/store` | → `{ "vendorId", "name", "status", "active": true, "city", "latitude", "longitude" }` · 404 `VENDOR_NOT_FOUND` |
| restaurant | `POST /internal/restaurants/{restaurantId}/menu-quote` | `{ "itemIds": [ … ] }` (1–100) → `MenuQuote` · 404 `RESTAURANT_NOT_FOUND` |

```jsonc
// MenuQuote — authoritative restaurant data from MySQL
{ "restaurantId": "…", "name": "Spice Route", "status": "APPROVED",
  "acceptingOrders": true, "openNow": true,            // openNow from weekly hours in Asia/Kolkata (overnight hours supported)
  "city": "Bengaluru", "latitude": 12.9784, "longitude": 77.6408,
  "items": [ { "itemId": "…", "name": "Chicken Biryani", "price": 249.00, "foodType": "NON_VEG",
               "available": true, "imageUrl": null } ],
  "missing": [ … ] }                                    // ids that are not on this restaurant's menu
```

## 5. Health

`GET /api/health/cart` (public, through the gateway).

## 6. Events

None new. From Phase 6, cart-service will consume `OrderCreated` to empty the customer's cart.

## 7. Phase 12 additions — coupon ([Phase 12 §2](phase-12-post-mvp-features.md#2-promotions--coupons-pricing-service-cart-service-order-service))

| Method & path | Body → Response |
|---|---|
| `PUT /api/cart/coupon` | `{ "code": "SAVE10" }` (any case; stored upper-case) → `CartView` · 422 `COUPON_NOT_APPLICABLE` with `reason` (pricing-service's reason, or `EMPTY_CART`), `code` and, for `COUPON_MIN_ORDER`, `minOrderSubtotal`; nothing is stored · 503 `DEPENDENCY_UNAVAILABLE` (`dependency: pricing`) when it cannot be checked |
| `DELETE /api/cart/coupon` | → `CartView` (idempotent) |

- One coupon per cart, stored with the cart; a second `PUT` replaces it. It is **evaluated live** by pricing-service
  (`POST /internal/coupons/evaluate`) every time the cart or the preview is shown.
- `CartView` and `CheckoutPreview` gain `coupon` (null without one). The preview also gains `discount` and
  `deliveryFeeDiscount` (0.00 without a usable coupon), and **`total = subtotal + delivery fee − discount −
  deliveryFeeDiscount`**. A coupon always leaves at least ₹1.00 to pay (pricing-service cuts `discount` first, then
  `deliveryFeeDiscount`). `CartView` has no delivery fee, so a FREE_DELIVERY coupon shows `deliveryFeeDiscount: 0.00`
  there.

```jsonc
"coupon": { "code": "SAVE10", "valid": true, "discount": 15.00, "deliveryFeeDiscount": 0.00 },
"discount": 15.00, "deliveryFeeDiscount": 0.00, "total": 190.00            // 180 + 25 − 15 − 0

"coupon": { "code": "SAVE10", "valid": false, "discount": 0.00, "deliveryFeeDiscount": 0.00,
            "reason": "COUPON_MIN_ORDER", "minOrderSubtotal": 150.00,
            "message": "Add ₹60.00 more to use SAVE10 (minimum order ₹150.00)" },
"discount": 0.00, "deliveryFeeDiscount": 0.00                              // + issue COUPON_NOT_APPLICABLE
```

- A coupon that cannot be used (any more) stays visible with `valid: false` and the **blocking** cart-level issue
  `COUPON_NOT_APPLICABLE` (`itemId: null`, message = the coupon's `message`), so the customer removes it or fixes the cart.
  Reasons: those of pricing-service (`COUPON_NOT_FOUND`, `COUPON_PAUSED`, `COUPON_NOT_STARTED`, `COUPON_EXPIRED`,
  `COUPON_WRONG_STORE`, `COUPON_MIN_ORDER`, `COUPON_USAGE_LIMIT`, `COUPON_CUSTOMER_LIMIT`), plus `COUPON_UNAVAILABLE`
  (pricing-service unreachable, or the cart's prices unavailable — never a 500) and `EMPTY_CART`.
- Changing to another store's items (`replaceCart`, or an emptied cart filled from another store) clears a `STORE`
  coupon of the old store; a `PLATFORM` coupon stays. `DELETE /api/cart` and the clear after `OrderCreated` remove it.

| Code | Blocks checkout | When |
|---|---|---|
| `COUPON_NOT_APPLICABLE` | yes | the cart's coupon cannot be used (any more) or could not be checked |
