# Phase 4 API contract — Inventory & pricing

Single source of truth for the Phase 4 backend and the React app. All paths go through the API gateway
(`http://localhost:8080`) unless marked *internal*. Conventions (auth header, ProblemDetail errors with `errorCode`,
`PageResponse`, UUIDv7 ids, ISO-8601 UTC timestamps, correlation IDs) are unchanged from
[Phase 2](phase-2-identity-and-stakeholders.md#1-conventions) and [Phase 3](phase-3-catalog-and-search.md).

## 1. Who owns what

| Service | Port | Store | Owns |
|---|---|---|---|
| **inventory-service** | 8107 | MySQL `inventory_db` | stock per product (on hand, reserved), the stock movement ledger, reservations, low-stock thresholds |
| **pricing-service** | 8108 | MySQL `pricing_db` + Redis cache | grocery prices (MRP + selling price), price history, price-change requests and their approval, the price quote |
| **catalog-query-service** | 8106 | Elasticsearch | adds `price` and `inStock` to the product documents it already serves |

```
vendor ──REST──► inventory-service ──outbox──► Debezium ──► inventory.events ─┐
vendor / admin ─► pricing-service  ──outbox──► Debezium ──► pricing.events ───┤
                                                                              ▼
catalog-command ──► catalog.events ──► inventory + pricing (product_ref)   catalog-query ──► Elasticsearch
```

- **Products come from the catalog.** Both services keep a small local copy of each product (`product_ref`: vendor,
  name, unit label, SKU, status) built from `catalog.events` → `ProductUpserted`. Nothing calls catalog-command.
  A product created a moment ago may therefore be unknown for a second or two (404 `PRODUCT_NOT_FOUND`).
- **Ownership** comes from the token's `vendor_id` claim (approved vendors only; otherwise 403 `VENDOR_NOT_ACTIVE`).
  Another vendor's product id returns 404 `PRODUCT_NOT_FOUND`.
- **Archived products** are read-only: every change returns 409 `PRODUCT_ARCHIVED`. Lists leave them out.
- **Search is updated by events**, usually within 1–2 seconds of a price or stock change.

## 2. Inventory (inventory-service, role `VENDOR`, approved vendors only)

Stock is counted in **sellable units** (whole numbers): a product "Tomato 500 g" with stock 12 means 12 packs.

```
available = onHand − reserved          inStock = available > 0          lowStock = available ≤ lowStockThreshold (threshold > 0)
```

A stock row (0 on hand) is created automatically when a product first arrives from the catalog.

| Method & path | Body → Response |
|---|---|
| `GET /api/vendor/inventory?q=&filter=&page=&size=` | → `PageResponse<StockView>`. `q` = product name contains / SKU equals. `filter` = `LOW_STOCK` \| `OUT_OF_STOCK` \| `IN_STOCK` (else 400 `INVALID_PARAMETER`). Sorted by product name. Archived products are left out |
| `GET /api/vendor/inventory/summary` | → `{ "products": 42, "inStock": 30, "lowStock": 5, "outOfStock": 12 }` (non-archived products; `lowStock` counts products that are low but not out) |
| `GET /api/vendor/inventory/{productId}` | → `StockView` |
| `POST /api/vendor/inventory/{productId}/adjustments` | `AdjustmentRequest` → `StockView` |
| `POST /api/vendor/inventory/adjustments/bulk` | `{ "items": [ BulkAdjustmentItem ] }` (1–100 items, each product at most once → 400 `VALIDATION_FAILED`) → `StockView[]` in request order. **All or nothing**: one failing item fails the whole request and nothing changes; the error's `productId` property names the item |
| `PUT /api/vendor/inventory/{productId}/threshold` | `{ "lowStockThreshold": 5 }` (0–100000; 0 = no low-stock alerts) → `StockView` |
| `GET /api/vendor/inventory/{productId}/movements?page=&size=` | → `PageResponse<StockMovementView>`, newest first |
| `POST /api/admin/inventory/republish` | `SUPER_ADMIN`/`OPS_ADMIN` → `{ "products": 230 }`. Re-sends `ProductAvailabilityChanged` for every product (rebuilds stock in search) |

```jsonc
// AdjustmentRequest            (BulkAdjustmentItem = the same + "productId")
{ "type": "INCREASE",           // required: INCREASE | DECREASE | SET
  "quantity": 10,               // required. INCREASE/DECREASE: 1–100000. SET: 0–1000000 (the counted stock on hand)
  "reason": "RESTOCK",          // required: RESTOCK | RETURN | DAMAGED | EXPIRED | STOCK_COUNT | CORRECTION | OTHER
  "note": "Morning delivery" }  // optional ≤ 300

// StockView
{ "productId": "…", "productName": "Tomato", "unitLabel": "500 g", "sku": "TOM-500",
  "productStatus": "PUBLISHED",                 // DRAFT | PUBLISHED | UNPUBLISHED
  "onHand": 12, "reserved": 2, "available": 10,
  "lowStockThreshold": 5, "lowStock": false, "inStock": true,
  "updatedAt": "2026-11-11T10:15:30Z" }

// StockMovementView
{ "id": "…", "type": "INCREASE",                // INCREASE | DECREASE | SET | RESERVE | RELEASE | COMMIT
  "quantityDelta": 10,                          // change of onHand (0 for RESERVE / RELEASE)
  "onHandAfter": 12, "reservedAfter": 2,
  "reason": "RESTOCK", "note": "Morning delivery",
  "reference": null,                            // order id for RESERVE / RELEASE / COMMIT
  "actorUserId": "…", "createdAt": "…" }
```

**Rules**
- Stock can never go below what is reserved, and never below zero:
  `DECREASE` by more than `available` → 409 `INSUFFICIENT_STOCK`; `SET` below `reserved` → 409 `STOCK_BELOW_RESERVED`.
  Both errors carry `available` and `reserved` properties.
- On hand may not exceed 1,000,000 → 422 `STOCK_LIMIT_EXCEEDED` (with a `maxOnHand` property). An INCREASE/DECREASE
  quantity outside 1–100000 is 400 `VALIDATION_FAILED` on `quantity` (`items[i].quantity` in bulk).
- The `LOW_STOCK` list filter uses the `lowStock` formula, so it includes out-of-stock products with a threshold; the
  summary's `lowStock` counts only products that are low but not out. Changing the threshold is not a stock change
  (no ledger entry, no event).
- A single archived product can still be read (`GET /{productId}`, its movements) with `productStatus: "ARCHIVED"`.
- **No lost updates, no overselling.** Every stock change first takes a row lock on the product's stock row
  (`SELECT … FOR UPDATE`, several products always in id order), applies the change and writes the ledger entry and
  events in the same transaction. Two simultaneous changes are applied one after the other; the database also
  enforces `0 ≤ reserved ≤ on_hand` with CHECK constraints.
- **Reservations** (stock held for an order) use the same lock: `reserve(orderId, items, ttl)` is all-or-nothing and
  idempotent per order, `release(orderId)` gives the stock back, `commit(orderId)` removes it from on hand. They are
  built and tested in this phase; the order saga calls them from Phase 6 (`OrderCreated`, `OrderCancelled`,
  `OrderOutForDelivery`), together with reservation expiry.

## 3. Prices (pricing-service, role `VENDOR`, approved vendors only)

Every grocery product has at most one live price: `mrp` (printed maximum retail price) and `sellingPrice`
(what the customer pays), both INR with 2 decimals, `1.00 ≤ sellingPrice ≤ mrp ≤ 1000000.00`.

| Method & path | Body → Response |
|---|---|
| `GET /api/vendor/prices?q=&filter=&page=&size=` | → `PageResponse<PriceView>`. `filter` = `PRICED` \| `UNPRICED` \| `PENDING_APPROVAL`. Sorted by product name. Archived products are left out |
| `GET /api/vendor/prices/summary` | → `{ "products": 42, "priced": 40, "unpriced": 2, "pendingApproval": 1 }` |
| `GET /api/vendor/prices/{productId}` | → `PriceView` |
| `PUT /api/vendor/prices/{productId}` | `PriceRequest` → **200** `PriceView` with `outcome: "APPLIED"` (live now), or **202** `PriceView` with `outcome: "PENDING_APPROVAL"` (waits for an admin; the live price is unchanged) |
| `GET /api/vendor/prices/{productId}/history?page=&size=` | → `PageResponse<PriceHistoryEntry>`, newest first |
| `GET /api/vendor/price-change-requests?status=&page=&size=` | → `PageResponse<PriceChangeRequestView>`, newest first |
| `POST /api/vendor/price-change-requests/{id}/cancel` | → `PriceChangeRequestView` (`CANCELLED`). 409 `PRICE_CHANGE_NOT_PENDING` |

```jsonc
// PriceRequest
{ "mrp": 60.00,                 // required, 1.00–1000000.00, ≤ 2 decimals
  "sellingPrice": 49.00,        // required, 1.00–1000000.00, ≤ 2 decimals, ≤ mrp → else 422 SELLING_PRICE_ABOVE_MRP (field sellingPrice)
  "note": "Festival offer" }    // optional ≤ 300; shown to the admin when approval is needed

// PriceView
{ "productId": "…", "productName": "Tomato", "unitLabel": "500 g", "sku": "TOM-500", "productStatus": "PUBLISHED",
  "price": { "mrp": 60.00, "sellingPrice": 49.00, "currency": "INR", "discountPercent": 18,
             "updatedAt": "…" } | null,                       // null = no price yet
  "pendingRequest": PriceChangeRequestView | null,
  "approvalThresholdPercent": 20,
  "outcome": "APPLIED" }                                      // only in the PUT response

// PriceHistoryEntry
{ "id": "…", "oldMrp": 60.00, "oldSellingPrice": 49.00,       // old* null for the first price
  "newMrp": 60.00, "newSellingPrice": 45.00, "changePercent": -8.16,
  "source": "VENDOR",                                         // VENDOR | APPROVED_REQUEST
  "requestId": null, "changedBy": "…", "changedAt": "…" }

// PriceChangeRequestView
{ "id": "…", "productId": "…", "productName": "Tomato", "unitLabel": "500 g", "vendorId": "…",
  "currentMrp": 60.00, "currentSellingPrice": 49.00,
  "requestedMrp": 60.00, "requestedSellingPrice": 25.00,
  "changePercent": -48.98,                                    // selling price change, signed, 2 decimals
  "status": "PENDING",                                        // PENDING | APPROVED | REJECTED | CANCELLED
  "note": "Clearance", "requestedBy": "…", "requestedAt": "…",
  "decidedBy": null, "decidedAt": null, "decisionReason": null }
```

**Rules**
- `discountPercent` = `round((mrp − sellingPrice) / mrp × 100)`, 0–99.
- **The first price applies immediately.**
- **A later change applies immediately when the selling price moves by at most the approval threshold**
  (default **20 %**, `grocery.pricing.approval-threshold-percent`), in either direction. Exactly 20 % is allowed.
- **A larger change needs an admin**: a `PENDING` price-change request is created, the live price stays, and the
  response is 202. While a request is pending, another `PUT` for that product returns 409 `PRICE_CHANGE_PENDING`
  (cancel the request first). A request with the same values as the live price is 409 `PRICE_UNCHANGED`.
- When a product is archived its pending request is cancelled automatically (`decisionReason` "Product archived").
- The threshold is compared with the rounded `changePercent` shown to the vendor (20.00 applies, 20.01 needs approval).
  A vendor cancellation records "Cancelled by vendor"; an approval note is stored as the `decisionReason`.

## 4. Admin price-change approval (pricing-service)

Reads: any admin role. Decisions: `SUPER_ADMIN`, `OPS_ADMIN` or `FINANCE_ADMIN` (otherwise 403).

| Method & path | Body → Response |
|---|---|
| `GET /api/admin/price-change-requests?status=&vendorId=&page=&size=` | → `PageResponse<PriceChangeRequestView>` (+ `vendorName` when known). Default `status=PENDING`, oldest first; other statuses newest first |
| `GET /api/admin/price-change-requests/{id}` | → `PriceChangeRequestView` · 404 `PRICE_CHANGE_REQUEST_NOT_FOUND` |
| `POST /api/admin/price-change-requests/{id}/approve` | `{ "note": "ok" }` (optional body) → view (`APPROVED`). The requested price becomes the live price. 409 `PRICE_CHANGE_NOT_PENDING`, 409 `PRODUCT_ARCHIVED` |
| `POST /api/admin/price-change-requests/{id}/reject` | `{ "reason": "Too low for this category" }` (required, ≤ 300) → view (`REJECTED`). 409 `PRICE_CHANGE_NOT_PENDING` |
| `POST /api/admin/pricing/republish` | `SUPER_ADMIN`/`OPS_ADMIN` → `{ "prices": 228 }`. Re-sends `PriceChanged` for every live price (rebuilds prices in search) |

Two admins deciding the same request at once: the first wins, the second gets 409 `PRICE_CHANGE_NOT_PENDING`.

## 5. Price quote (pricing-service, *internal* — not routed through the gateway)

Used by cart and order from Phase 5. Any authenticated caller.

| Method & path | Body → Response |
|---|---|
| `POST /internal/prices/quote` (was `/api/prices/quote` until Phase 5) | `{ "productIds": ["…"] }` (1–100) → `{ "items": [ { "productId", "vendorId", "mrp", "sellingPrice", "currency", "priceVersion" } ], "missing": ["…"] }` — `missing` = products without a live price (or unknown / archived) |

Quotes are read through a Redis cache (`price:v1:<productId>`, 10 minutes). Each entry carries its `priceVersion` and
is only ever replaced by a higher version, so a slow reader can never overwrite a newer price. If Redis is down
the quote reads MySQL directly (and skips Redis for 5 s before trying again).
*As built:* the compare-and-set uses `priceVersion` first and `productVersion` second, so archiving a product can
overwrite its entry with an "archived" marker (it then shows as `missing`) without a price change.

## 6. Search and product pages (catalog-query) — additions

| Method & path | New |
|---|---|
| `GET /api/search/products` | query: `inStock=true` (only products in stock; `false` or absent = no filter), `minPrice`, `maxPrice` (selling price, ≥ 0, `minPrice ≤ maxPrice` else 400 `INVALID_PARAMETER`; products without a price never match a price filter), `sort` also `price_asc`, `price_desc` (products without a price last) |
| `GET /api/catalog/products/{id}` | response gains `price` and `inStock` |

```jsonc
// ProductCard and ProductDetail gain
{ "price": { "mrp": 60.00, "sellingPrice": 49.00, "currency": "INR", "discountPercent": 18 } | null,   // null = not priced yet
  "inStock": true }                                                                                     // false until stock is added
```

A product stays visible by the Phase 3 rule (published, vendor active, category active). A visible product without a
price shows "Price not available"; without stock it shows "Out of stock". Neither can be added to a cart (Phase 5).

## 7. Health (public, through the gateway)

`GET /api/health/inventory` and `GET /api/health/pricing`.

## 8. Events introduced in Phase 4 (see docs/events/event-catalog.md)

| Topic | Event (v1) | Key | Producer → consumers |
|---|---|---|---|
| `catalog.events` (Phase 3) | `ProductUpserted` | productId | catalog-command → **inventory** (`inventory-service.catalog-events`), **pricing** (`pricing-service.catalog-events`): the local `product_ref` copy |
| `inventory.events` | `StockAdjusted` | productId | inventory → (audit / analytics later) |
| `inventory.events` | `ProductAvailabilityChanged` | productId | inventory → catalog-query (`catalog-query.stock-indexer`); notification later |
| `inventory.events` | `LowStockDetected` | productId | inventory → notification later |
| `pricing.events` | `PriceChanged` | productId | pricing → catalog-query (`catalog-query.price-indexer`) |
| `pricing.events` | `PriceChangeRequested` / `Approved` / `Rejected` / `Cancelled` | productId | pricing → notification later |

Outbox aggregate types: `Stock` (inventory) and `Price` (pricing). Money is a JSON number with at most 2 decimals.
In REST responses it keeps both decimals (`60.00`). In Kafka events trailing zeros may be dropped (`60.0`) because the
outbox's MySQL JSON column stores numbers as doubles. The value is the same; consumers read it as a decimal and
round to 2 places.

```jsonc
// StockAdjusted — every vendor adjustment (INCREASE / DECREASE / SET)
{ "productId", "vendorId", "movementId", "type": "INCREASE", "delta": 10, "onHand": 12, "reserved": 2, "available": 10,
  "reason": "RESTOCK", "actorUserId", "stockVersion": 7 }

// ProductAvailabilityChanged — only when a product goes in ↔ out of stock, and on republish
{ "productId", "vendorId", "inStock": true, "stockVersion": 7 }       // stockVersion strictly increasing per product

// LowStockDetected — when available drops from above the threshold to the threshold or below
{ "productId", "vendorId", "productName", "available": 4, "threshold": 5 }

// PriceChanged — every change of the live price, and on republish
{ "productId", "vendorId", "mrp": 60.00, "sellingPrice": 49.00, "currency": "INR",
  "priceVersion": 3, "changedAt": "…" }                               // priceVersion strictly increasing per product

// PriceChangeRequested
{ "requestId", "productId", "vendorId", "productName", "oldMrp", "oldSellingPrice", "newMrp", "newSellingPrice",
  "changePercent": -48.98, "requestedBy" }
// PriceChangeApproved   { "requestId", "productId", "vendorId", "approvedBy" }
// PriceChangeRejected   { "requestId", "productId", "vendorId", "rejectedBy", "reason" }
// PriceChangeCancelled  { "requestId", "productId", "vendorId", "cancelledBy", "reason" }   // cancelledBy null = automatic
```

**How search applies them.** One product document now has three independently versioned parts: the catalog snapshot
(`productVersion`), the price (`priceVersion`) and the stock flag (`stockVersion`). Each event updates only its own
part, and only when its version is newer than the one stored in the document, so replays, duplicates and
out-of-order events can never win and no part overwrites another. A price or stock event that arrives before the
product itself creates a hidden placeholder that the product snapshot later completes. After the products index is
lost, run the three republish calls (catalog, pricing, inventory).
