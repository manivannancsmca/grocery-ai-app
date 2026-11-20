# Phase 3 API contract — Product catalog, restaurant menus & search

Single source of truth for the Phase 3 backend and the React app. All paths go through the API gateway
(`http://localhost:8080`). Conventions (auth header, ProblemDetail errors, `PageResponse`, UUIDv7 ids, ISO-8601
UTC timestamps, correlation IDs) are unchanged from [Phase 2](phase-2-identity-and-stakeholders.md#1-conventions).

## 1. Who owns what

| Service | Port | Store | Owns |
|---|---|---|---|
| **catalog-command-service** | 8105 | MySQL `catalog_db` | category tree, brands, vendor products (write side) |
| **catalog-query-service** | 8106 | Elasticsearch only | every public catalog read: product search, suggestions, category tree, product detail, restaurant search and restaurant pages |
| **restaurant-service** | 8104 | MySQL `restaurant_db` | menus (categories, items, prices, availability) and the "accepting orders" switch |

```
admin / vendor ──REST──► catalog-command ──outbox──► Debezium ──► catalog.events ─┐
restaurant owner ─REST─► restaurant-service ─outbox─► Debezium ──► restaurant.events ┤
                                                                                     ▼
customer (anyone) ──REST──► catalog-query ◄──── Elasticsearch ◄──── catalog-query indexers
```

- **Writes never touch Elasticsearch directly.** A change commits to MySQL together with an outbox row; Debezium
  publishes it; catalog-query indexes it, usually **within 1–2 seconds**.
- **Snapshot events.** Every product change publishes the **complete** product (`ProductUpserted`), and every
  restaurant change publishes the complete listing including the menu (`RestaurantListingUpdated`). The read side
  never has to join anything or apply partial updates.
- **Stale events can't win.** Each snapshot carries a version that increases with every change. Elasticsearch
  stores it as an *external version* and ignores any event whose version is not newer (replays, duplicates,
  republish).
- **Prices and stock for grocery products arrive in Phase 4** (pricing-service, inventory-service) and will be
  added to the same search documents. Restaurant menu prices are part of the menu and are live now.

## 2. Public catalog reads (catalog-query, no token needed)

Anonymous visitors and every role may call these. When a valid token is sent it is accepted but not required.

| Method & path | Query | Response |
|---|---|---|
| `GET /api/search/products` | `q`, `categoryId`, `brandId`, `vendorId` (repeatable, ≤ 50: products of any of those stores — see [§9](#9-store-directory-and-search-across-nearby-stores)), `foodType` (`VEG`/`NON_VEG`/`EGG`), `sort` (`relevance` default with `q`, otherwise `newest`; also `name_asc`), `page`, `size` (default 20, max 50) | `ProductSearchResponse` |
| `GET /api/search/suggest` | `q` (≥ 2 characters) | `SuggestResponse` (≤ 8 products, ≤ 4 categories) |
| `GET /api/search/restaurants` | `lat`, `lng` (both or neither), `radiusKm` (default 10, max 30; only with a location), `q`, `cuisine` (repeatable), `pureVeg` (`true`), `openNow` (`true`), `page`, `size` (default 20, max 50) | `RestaurantSearchResponse` |
| `GET /api/catalog/categories` | — | `CategoryNode[]` (active categories as a tree, with visible product counts) |
| `GET /api/catalog/products/{id}` | — | `ProductDetail` · 404 `PRODUCT_NOT_FOUND` if missing or not visible |
| `GET /api/catalog/restaurants/{id}` | — | `RestaurantDetail` · 404 `RESTAURANT_NOT_FOUND` if missing or not visible |

`page × size` may not exceed 1,000 results (400 `INVALID_PARAMETER`). An invalid enum, a malformed id or number, a
half-given or out-of-range location, `radiusKm ≤ 0` or more than 50 `vendorId` values is 400 `INVALID_PARAMETER`. A `size` above 50 is capped at 50 and
`radiusKm` above 30 at 30. If the search cluster is down or overloaded: 503 `SEARCH_UNAVAILABLE` (retry later).
`matchingItems` is `[]` when no `q` is given.

**What is visible**
- A product is visible when its status is `PUBLISHED`, its vendor is active (not suspended) and its category and
  every parent category are active.
- A restaurant is visible when it is `APPROVED` (suspension hides it). `acceptingOrders=false` restaurants stay
  visible but show "not accepting orders".

**Search behaviour**
- Typo tolerant: `tomatos` finds *Tomato*, `bananna` finds *Banana*.
- Grocery synonyms: `curd` ↔ `yogurt` ↔ `dahi`, `atta` ↔ `wheat flour`, `brinjal` ↔ `eggplant`, `coriander` ↔
  `cilantro` ↔ `dhania`, `lady finger` ↔ `okra` ↔ `bhindi`, `capsicum` ↔ `bell pepper`, `paneer` ↔ `cottage cheese`, and more.
  The list lives in `services/catalog-query-service/src/main/resources/elasticsearch/synonyms.txt`.
- Matches in the product name rank above brand, category, tags and description.
- `categoryId` matches the category **and all its sub-categories**.
- Restaurant search: `q` matches the restaurant name, cuisines **and dish names** (e.g. `biryani`). Matching dishes
  are returned on each result. With a location, results are within `radiusKm` and sorted by distance (or by
  relevance first when `q` is given). `openNow` uses each restaurant's weekly hours in India time (Asia/Kolkata).

```jsonc
// ProductSearchResponse  (PageResponse<ProductCard> + facets)
{ "content": [ ProductCard ], "page": 0, "size": 20, "totalElements": 3, "totalPages": 1,
  "facets": {
    "categories": [ { "id": "…", "name": "Fresh Vegetables", "count": 3 } ],   // leaf categories of the hits
    "brands":     [ { "id": "…", "name": "Farm Fresh", "count": 2 } ],
    "foodTypes":  [ { "value": "VEG", "count": 3 } ] } }

// ProductCard
{ "id": "…", "name": "Tomato", "unitLabel": "500 g", "foodType": "VEG",          // foodType may be null
  "primaryImageUrl": "https://…" ,                                               // may be null
  "brand": { "id": "…", "name": "Farm Fresh" },                                  // may be null
  "category": { "id": "…", "name": "Fresh Vegetables" },
  "vendor": { "id": "…", "name": "Green Basket" } }
  // Phase 4 adds: "price": { "mrp", "sellingPrice", "currency" }, "inStock"
  // 2026-11-20 adds: "shortDescription": "Light, crisp Marie tea biscuits."  // ≤ 300 chars, cut at a word with "…";
  //   null when the product has no description. The search also matches descriptions, and the shopping assistant
  //   uses this to tell what a product is (it read each product's detail before).

// ProductDetail = ProductCard + 
{ "description": "…", "unitValue": 500, "unitOfMeasure": "G", "sku": "TOM-500", "barcode": null,
  "tags": ["organic"], "attributes": [ { "name": "Origin", "value": "Nashik" } ],
  "imageUrls": ["https://…"], "categoryPath": [ { "id": "…", "name": "Fruits & Vegetables" }, { "id": "…", "name": "Fresh Vegetables" } ],
  "publishedAt": "2026-11-11T10:15:30Z", "updatedAt": "…" }

// SuggestResponse
{ "products":   [ { "id": "…", "name": "Tomato", "brandName": "Farm Fresh", "unitLabel": "500 g", "primaryImageUrl": null } ],
  "categories": [ { "id": "…", "name": "Fresh Vegetables", "path": ["Fruits & Vegetables", "Fresh Vegetables"] } ] }

// CategoryNode (sorted by sortOrder, then name)
{ "id": "…", "name": "Fruits & Vegetables", "description": null, "imageUrl": null, "productCount": 12,
  "children": [ CategoryNode ] }

// RestaurantSearchResponse (PageResponse<RestaurantCard> + facets)
{ "content": [ RestaurantCard ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "facets": { "cuisines": [ { "value": "BIRYANI", "count": 1 } ] } }

// RestaurantCard
{ "id": "…", "name": "Spice Route", "description": "…", "cuisines": ["NORTH_INDIAN", "BIRYANI"],
  "city": "Bengaluru", "locality": "Indiranagar",           // locality = address line 2 when present
  "distanceKm": 2.4,                                          // null when no location was given
  "openNow": true, "acceptingOrders": true, "pureVeg": false,
  "matchingItems": [ { "id": "…", "name": "Chicken Biryani", "price": 249.00, "foodType": "NON_VEG" } ] }  // ≤ 3, only with q

// RestaurantDetail
{ "id", "name", "description", "cuisines", "openNow", "acceptingOrders", "pureVeg",
  "address": { "line1", "line2", "city", "state", "postalCode", "latitude", "longitude" },
  "openingHours": [ { "dayOfWeek": "MONDAY", "opensAt": "09:00", "closesAt": "23:00" } ],
  "menu": [ { "id": "…", "name": "Biryani", "description": null,
              "items": [ { "id": "…", "name": "Chicken Biryani", "description": "…", "price": 249.00,
                           "foodType": "NON_VEG", "available": true, "imageUrl": null } ] } ] }
  // menu categories without items are omitted; unavailable items are included with available=false
```

## 3. Vendor product management (catalog-command, role `VENDOR`, approved vendors only)

Ownership comes from the token's `vendor_id` claim, which exists only while the vendor is **APPROVED**. Without it:
403 `VENDOR_NOT_ACTIVE`. Another vendor's product id returns 404 `PRODUCT_NOT_FOUND`.

| Method & path | Body → Response |
|---|---|
| `GET /api/vendor/products?status=&q=&page=&size=` | → `PageResponse<ProductView>`; archived products only when `status=ARCHIVED` |
| `POST /api/vendor/products` | `ProductRequest` → 201 `ProductView` (status `DRAFT`, not visible yet) |
| `GET /api/vendor/products/{id}` | → `ProductView` |
| `PUT /api/vendor/products/{id}` | `ProductRequest` → `ProductView`. A published product updates in search within seconds. 409 `PRODUCT_ARCHIVED` |
| `POST /api/vendor/products/{id}/publish` | → `ProductView` (`PUBLISHED`). 409 `CATEGORY_INACTIVE`, 409 `INVALID_STATUS_TRANSITION` |
| `POST /api/vendor/products/{id}/unpublish` | → `ProductView` (`UNPUBLISHED`, hidden from search) |
| `DELETE /api/vendor/products/{id}` | → 204. **Archives** the product (hidden everywhere, kept for order history) |
| `GET /api/vendor/brands?q=` | → `[{ "id", "name" }]` (≤ 10 existing brands for the brand field's suggestions) |

Product status: `DRAFT → PUBLISHED ⇄ UNPUBLISHED`, and any of them `→ ARCHIVED` (final).

```jsonc
// ProductRequest
{ "name": "Tomato",                         // required, ≤ 150
  "description": "Farm-fresh red tomatoes",  // ≤ 2000
  "categoryId": "…",                         // required; must exist and be active
  "brandName": "Farm Fresh",                 // optional ≤ 80; matched case-insensitively to an existing brand or created
  "unitValue": 500,                          // required, > 0, ≤ 100000, up to 3 decimals
  "unitOfMeasure": "G",                      // required: G | KG | ML | L | PCS | PACK
  "sku": "TOM-500",                          // optional ^[A-Za-z0-9._-]{1,40}$, unique per vendor among non-archived products → 409 SKU_ALREADY_EXISTS
  "barcode": "8901234567890",                // optional, 8–14 digits
  "foodType": "VEG",                         // optional: VEG | NON_VEG | EGG
  "tags": ["organic"],                       // ≤ 10, each ^[a-z0-9 -]{2,30}$ (lower-cased by the server)
  "attributes": [ { "name": "Origin", "value": "Nashik" } ],   // ≤ 20, name ≤ 50, value ≤ 200
  "imageUrls": ["https://…"] }               // ≤ 8 http(s) URLs ≤ 500 chars; first = primary image

// ProductView
{ "id", "vendorId", "name", "description",
  "category": { "id", "name", "path": ["Fruits & Vegetables", "Fresh Vegetables"], "active": true },
  "brand": { "id", "name" } | null, "unitValue", "unitOfMeasure", "unitLabel": "500 g",
  "sku", "barcode", "foodType", "tags", "attributes", "imageUrls",
  "status": "DRAFT", "publishedAt": null, "createdAt", "updatedAt" }
  // the admin view adds "vendorName"
```

## 4. Admin catalog management (catalog-command)

Reads: any admin role. Changes: `SUPER_ADMIN` or `OPS_ADMIN` (otherwise 403).

| Method & path | Body → Response |
|---|---|
| `GET /api/admin/categories` | → `AdminCategoryNode[]` (the whole tree including inactive) |
| `POST /api/admin/categories` | `CategoryRequest` → 201 `AdminCategoryNode` (no children) |
| `PUT /api/admin/categories/{id}` | `CategoryRequest` → `AdminCategoryNode`. Can move the category (`parentId`). 409 `CATEGORY_CYCLE`, 422 `CATEGORY_TOO_DEEP` |
| `POST /api/admin/categories/{id}/deactivate` | → node. Hides the category, its sub-categories and their products from customers |
| `POST /api/admin/categories/{id}/activate` | → node |
| `DELETE /api/admin/categories/{id}` | → 204. 409 `CATEGORY_IN_USE` if it has sub-categories or non-archived products |
| `GET /api/admin/products?vendorId=&categoryId=&status=&q=&page=&size=` | → `PageResponse<ProductView>` (+ `vendorName`) |
| `GET /api/admin/products/{id}` | → `ProductView` (+ `vendorName`) |
| `POST /api/admin/catalog/republish` | → `{ "categories": 14, "products": 230 }`. Re-sends every category and product snapshot (rebuilds the search index after it is lost or recreated) |

Categories are at most **3 levels** deep. Names are unique among siblings (case-insensitive) → 409 `CATEGORY_NAME_TAKEN`.

```jsonc
// CategoryRequest
{ "name": "Fresh Vegetables",   // required ≤ 80
  "parentId": "…" | null,       // null = top level
  "description": null,          // ≤ 500
  "imageUrl": null,             // http(s) ≤ 500
  "sortOrder": 0 }              // 0–9999, lower first

// AdminCategoryNode
{ "id", "parentId", "name", "description", "imageUrl", "sortOrder", "active", "depth": 1,
  "productCount": 4,            // non-archived products directly in this category
  "children": [ AdminCategoryNode ] }
```

## 5. Restaurant menus (restaurant-service, role `RESTAURANT_OWNER`)

The owner's restaurant is looked up from the token's user id (like `/api/restaurants/me`), so the **menu can be
prepared before approval**. It goes live in search when the restaurant is approved. While the restaurant is
`SUSPENDED`, changes return 409 `RESTAURANT_SUSPENDED`. No restaurant profile yet → 404 `RESTAURANT_NOT_FOUND`.

| Method & path | Body → Response |
|---|---|
| `GET /api/restaurant/menu` | → `MenuView` |
| `POST /api/restaurant/menu/categories` | `MenuCategoryRequest` → 201 `MenuCategoryView` (≤ 30 categories → 422 `MENU_LIMIT_REACHED`) |
| `PUT /api/restaurant/menu/categories/{id}` | `MenuCategoryRequest` → `MenuCategoryView` |
| `DELETE /api/restaurant/menu/categories/{id}` | → 204. 409 `MENU_CATEGORY_NOT_EMPTY` |
| `POST /api/restaurant/menu/items` | `MenuItemRequest` → 201 `MenuItemView` (≤ 500 items → 422 `MENU_LIMIT_REACHED`) |
| `PUT /api/restaurant/menu/items/{id}` | `MenuItemRequest` → `MenuItemView` |
| `PATCH /api/restaurant/menu/items/{id}/availability` | `{ "available": false }` → `MenuItemView` ("sold out" toggle) |
| `DELETE /api/restaurant/menu/items/{id}` | → 204 |
| `PUT /api/restaurant/accepting-orders` | `{ "acceptingOrders": false }` → `{ "acceptingOrders": false }` (temporarily stop new orders) |
| `GET /api/admin/restaurants/{id}/menu` | any admin → `MenuView` (review the menu before approving) |
| `POST /api/admin/restaurants/listings/republish` | `SUPER_ADMIN`/`OPS_ADMIN` → `{ "restaurants": 12 }` (re-sends every listing to search) |

Unknown menu category or item ids, or another restaurant's ids, return 404 `MENU_CATEGORY_NOT_FOUND` / `MENU_ITEM_NOT_FOUND`.
`RestaurantView` (`GET /api/restaurants/me`, admin views) gains `"acceptingOrders": true`.

```jsonc
// MenuCategoryRequest
{ "name": "Biryani", "description": null, "sortOrder": 0 }       // name required ≤ 80 (unique in the menu → 409 MENU_CATEGORY_NAME_TAKEN), description ≤ 300, sortOrder 0–9999

// MenuItemRequest
{ "categoryId": "…", "name": "Chicken Biryani",                    // required; name ≤ 120
  "description": "Dum-cooked with basmati rice",                   // ≤ 500
  "price": 249.00,                                                 // required, 1.00 – 100000.00, 2 decimals (INR)
  "foodType": "NON_VEG",                                           // required: VEG | NON_VEG | EGG
  "imageUrl": null,                                                // http(s) ≤ 500
  "available": true,                                               // default true
  "sortOrder": 0 }

// MenuView
{ "restaurantId": "…", "restaurantStatus": "APPROVED", "acceptingOrders": true, "itemCount": 12,
  "categories": [ { "id", "name", "description", "sortOrder",
                    "items": [ MenuItemView ] } ] }                 // sorted by sortOrder, then name
// MenuItemView
{ "id", "categoryId", "name", "description", "price", "foodType", "imageUrl", "available", "sortOrder", "updatedAt" }
```

**MCP (2026-11-20).** The same product search is also an MCP tool, `searchProducts`, on catalog-query's MCP server
(`POST /api/mcp/catalog`, stateless Streamable HTTP, a token required): see
[phase-11 §3 "MCP pilot"](phase-11-agentic-ai-and-approvals.md#3-tools-risk-classes-and-toolguard).

## 6. Admin search status (catalog-query, any admin)

| Method & path | Response |
|---|---|
| `GET /api/admin/search/indexes` | `[{ "alias": "products", "index": "products_v1", "documents": 230, "health": "green" }, …]` for `products`, `restaurants`, `categories` |

## 7. Health (public, through the gateway)

`GET /api/health/catalog-command` and `GET /api/health/catalog-query` (plus the Phase 2 ones).

## 8. Events introduced in Phase 3 (see docs/events/event-catalog.md)

| Topic | Event (v1) | Key | Producer → consumers |
|---|---|---|---|
| `catalog.events` | `ProductUpserted` | productId | catalog-command → catalog-query (`catalog-query.product-indexer`); later inventory, pricing, cart |
| `catalog.events` | `CategoryChanged` | categoryId | catalog-command → catalog-query (`catalog-query.product-indexer`) |
| `restaurant.events` | `RestaurantListingUpdated` | restaurantId | restaurant-service → catalog-query (`catalog-query.restaurant-indexer`); identity ignores it |
| `vendor.events` (Phase 2) | `VendorApproved/Suspended/Reinstated` | vendorId | vendor-service → **catalog-command** (`catalog-command.vendor-events`): hides or shows the vendor's products |

```jsonc
// ProductUpserted — the complete product; published on every change (create, edit, publish, unpublish, archive,
// category rename/move/(de)activation, vendor suspension/reinstatement, republish).
{ "productId", "vendorId", "vendorName", "vendorActive": true,
  "name", "description", "brand": { "id", "name" } | null,
  "category": { "id", "name" }, "categoryPath": [ { "id", "name" } ],   // root → leaf
  "categoryActive": true,                                               // the category and all its parents are active
  "unitValue": 500, "unitOfMeasure": "G", "unitLabel": "500 g", "sku", "barcode", "foodType",
  "tags": [], "attributes": [ { "name", "value" } ], "imageUrls": [],
  "status": "PUBLISHED",                                                // DRAFT | PUBLISHED | UNPUBLISHED | ARCHIVED
  "publishedAt", "updatedAt",
  "productVersion": 7 }                                                 // strictly increasing per product

// CategoryChanged — the complete category; published on create, edit, move, (de)activate, delete, republish.
{ "categoryId", "parentId", "name", "description", "imageUrl", "sortOrder", "depth",
  "path": [ { "id", "name" } ],                 // root → this category
  "active": true,                               // own flag
  "effectiveActive": true,                      // own flag AND every parent active
  "deleted": false,
  "categoryVersion": 3 }

// RestaurantListingUpdated — the complete public listing; published on approval, suspension, reinstatement,
// every menu change, the accepting-orders switch and republish (only for restaurants that were ever approved).
{ "restaurantId", "ownerUserId", "status": "APPROVED", "name", "description", "cuisines": [],
  "address": { "line1", "line2", "city", "state", "postalCode", "country", "latitude", "longitude" },
  "openingHours": [ { "dayOfWeek": "MONDAY", "opensAt": "09:00", "closesAt": "23:00" } ],
  "acceptingOrders": true,
  "menu": [ { "categoryId", "name", "description", "sortOrder",
              "items": [ { "itemId", "name", "description", "price": 249.00, "foodType", "imageUrl", "available", "sortOrder" } ] } ],
  "listingVersion": 12, "updatedAt" }
```

## 9. Store directory and search across nearby stores

Customers may browse every store's products; only checkout enforces the grocery delivery limit (cart-service
`grocery.cart.delivery`: road factor 1.3, grocery `max-distance-km` 15 — [Phase 5 §3](phase-5-cart-and-checkout-preview.md#3-checkout-preview)).
The store directory tells the app which grocery stores deliver to a point, and product search can be limited to those
stores.

### Store directory (vendor-service, no token needed)

Anonymous visitors and every role may call these (`/api/stores/**` is a guest read at the gateway, GET only). When a
valid token is sent it is accepted but not required. Only `APPROVED` grocery stores are shown.

| Method & path | Query | Response |
|---|---|---|
| `GET /api/stores/nearby` | `lat` (−90…90) and `lng` (−180…180), both required | `NearbyStores` |
| `GET /api/stores/{id}` | optional `lat` and `lng` (both or neither) | `StoreCard` · 404 `STORE_NOT_FOUND` if unknown or not `APPROVED` (draft, pending, rejected, suspended) |

- `distanceKm` = haversine(store, point) × road factor 1.3, rounded to 1 decimal HALF_UP — exactly the cart's delivery
  distance, so a store with `deliversHere: true` is one that checkout accepts for an address at that point.
- `/nearby` lists the stores with `distanceKm ≤ deliveryLimitKm` (15.0), sorted by `distanceKm`, then name, then id;
  at most 50. Stores without a location are never listed.
- `/{id}` without a point: `distanceKm` and `deliversHere` are `null`. With a point: both are computed;
  `deliversHere` may be `false` (the store is too far). A store without a location has `distanceKm: null` and
  `deliversHere: false`.
- A missing, non-numeric or out-of-range `lat` / `lng`, only one of them on `/{id}`, or an id that is not a UUID is 400
  `INVALID_PARAMETER`.
- Never shown: GSTIN, FSSAI licence, contact e-mail / phone, owner, status.
- Settings (vendor-service `grocery.stores`): `road-factor` 1.3 and `delivery-limit-km` 15 must match cart-service
  `grocery.cart.delivery`; `max-results` 50.

```jsonc
// NearbyStores (GET /api/stores/nearby?lat=12.9202&lng=80.1445)
{ "deliveryLimitKm": 15.0,
  "content": [ StoreCard ] }                       // nearest first; [] when no store delivers there

// StoreCard
{ "id": "…", "name": "Tambaram Super Bazaar",       // the vendor's business name
  "line1": "112 Shanmugam Road", "area": "West Tambaram",   // area = address line 2, may be null
  "city": "Chennai", "postalCode": "600045",
  "latitude": 12.9267, "longitude": 80.1146,
  "distanceKm": 4.3,                                // null without lat/lng
  "deliversHere": true }                            // always true in /nearby; null without lat/lng
```

### Search across several stores (catalog-query)

`GET /api/search/products?vendorId=a&vendorId=b…` — `vendorId` is repeatable: the results are the products of **any**
of those stores (a repeated value counts once). One `vendorId` works exactly as before; none = every store. More than
50 values is 400 `INVALID_PARAMETER` ("at most 50 vendorId values"); a malformed one is 400 `INVALID_PARAMETER` as
before. Facets describe the filtered hits. Typical use: the ids from `/api/stores/nearby` (at most 50).
