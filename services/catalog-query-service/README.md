# catalog-query-service

The catalog **read side**: every public catalog read — product search, suggestions, the category tree, product pages,
restaurant search and restaurant pages — plus, since Phase 5, the internal product lookup used by cart-service.
Elasticsearch is its only store; it is filled from events and never written by
anyone else. Since Phase 4 the product documents also carry the live **price** (pricing-service) and the **in-stock**
flag (inventory-service), and since Phase 12 products and restaurants carry their **rating** (review-service).

| | |
|---|---|
| Port | 8106 |
| Store | Elasticsearch 9.4.6 only — aliases `products`, `restaurants`, `categories` → indexes `products_v1`, `restaurants_v1`, `categories_v1`. No MySQL, Flyway, JPA or outbox |
| Publishes | nothing (no Debezium connector) |
| Consumes | `catalog.events` → `ProductUpserted`, `CategoryChanged` (group `catalog-query.product-indexer`)<br>`pricing.events` → `PriceChanged` (group `catalog-query.price-indexer`); price-change requests/decisions are ignored<br>`inventory.events` → `ProductAvailabilityChanged` (group `catalog-query.stock-indexer`); `StockAdjusted`, `LowStockDetected` are ignored<br>`restaurant.events` → `RestaurantListingUpdated` (group `catalog-query.restaurant-indexer`); lifecycle events are ignored<br>`review.events` → `RatingSummaryUpdated` of `PRODUCT` and `RESTAURANT` targets (group `catalog-query.rating-indexer`, Phase 12); other target types and the other review events are ignored |
| API | `docs/api/phase-3-catalog-and-search.md` §2 (public, no token) and §6 (`GET /api/admin/search/indexes`, any admin); price/stock additions: `docs/api/phase-4-inventory-and-pricing.md` §6; internal product lookup: `docs/api/phase-5-cart-and-checkout-preview.md` §4; ratings: `docs/api/phase-12-post-mvp-features.md` §1 |

## How indexing works
```
catalog.events ───► CatalogEventsListener ───► CatalogIndexer ──────┐ merge (products: catalog part)
pricing.events ───► PricingEventsListener ───► PriceAndStockIndexer ┤ merge (products: price part)
inventory.events ─► InventoryEventsListener ─► PriceAndStockIndexer ┤ merge (products: stock part)
review.events ────► ReviewEventsListener ────► RatingIndexer ───────┤ merge (products / restaurants: rating part)
catalog.events ───► CatalogEventsListener ───► CatalogIndexer ──────┤ index/delete, external version (categories)
restaurant.events ► RestaurantEventsListener ► RestaurantIndexer ───┴ merge (restaurants: listing part; Phase 12)
                                                                     └─► SearchDocumentWriter ──► alias (require_alias=true)
```

### Product documents: four independently versioned parts
| Part | Fields | Version field | Written by |
|---|---|---|---|
| catalog snapshot | everything else (name, brand, category path, `visible`, …) | `productVersion` | `ProductUpserted` |
| price | `mrp`, `sellingPrice`, `currency`, `discountPercent` | `priceVersion` | `PriceChanged` |
| stock | `inStock` | `stockVersion` | `ProductAvailabilityChanged` |
| rating (Phase 12) | `rating`, `ratingCount` | `ratingVersion` (= the event's `summaryVersion`) | `RatingSummaryUpdated`, target `PRODUCT` |

- **Each event writes only its own part, only when its version is newer than the one stored in the document.**
  `SearchDocumentWriter.merge` sends a scripted upsert (`scripted_upsert`, `retry_on_conflict=8`, `require_alias=true`)
  whose Painless script compares the event's version with the part's version field, drops the part's old fields
  (so a value that became null — e.g. a removed brand — really disappears) and puts the new ones. Replays, duplicates
  and out-of-order events are no-ops (`ctx.op = 'none'`), and no part can overwrite another: a new catalog snapshot
  keeps the price and stock, a new price keeps the catalog fields and the stock. A version conflict that survives the
  retries means concurrent writers, so it is retried as a transient failure, never dropped.
- **Placeholders.** A price or stock event for a product that has no document yet creates a document holding just
  that part plus `visible=false`; it is invisible everywhere (search, product page, category counts) until the
  product snapshot completes it. A `DRAFT` snapshot is skipped only when there is no document at all; a draft that
  already has a placeholder gets its catalog part (still hidden).
- `discountPercent = round((mrp − sellingPrice) / mrp × 100)`, half up, 0–99, computed when the price is indexed;
  `currency` defaults to `INR`.
- Price and stock never affect visibility: a visible product without a price has `price: null`, without stock
  `inStock: false`.
- **Ratings (Phase 12, `RatingIndexer`).** `rating` = the summary's `averageRating` (2 decimals), `ratingCount` its
  count. A summary with `ratingCount` 0 (every review removed or deleted) drops `rating`, so the target is unrated
  again (`rating: null`, `ratingCount: 0` in the API). Like price and stock, an early rating waits in a hidden
  placeholder and never affects visibility. `MENU_ITEM`, `VENDOR` and `DELIVERY_PARTNER` summaries are ignored.

### Restaurant documents: two independently versioned parts (Phase 12)
| Part | Fields | Version field | Written by |
|---|---|---|---|
| listing | everything else (name, menu, hours, `visible`, …) | `listingVersion` | `RestaurantListingUpdated` |
| rating | `rating`, `ratingCount` | `ratingVersion` (= `summaryVersion`) | `RatingSummaryUpdated`, target `RESTAURANT` |

Until Phase 11 a listing was indexed as the whole document with `listingVersion` as Elasticsearch's external version.
A rating written into such a document would bump Elasticsearch's `_version` and make the next listing look stale, so
the listing is now **merged** like the product parts (the script compares the `listingVersion` stored in the source).
Documents written the old way already carry `listingVersion` in their source, so existing indexes keep working
without a reindex (`RatingSearchIntegrationTest.aRestaurantIndexedBeforePhase12KeepsWorking`).

### Categories (unchanged)
- **Snapshots + external versions = idempotency.** Each event carries the complete aggregate and a strictly increasing
  version (`categoryVersion`), used as Elasticsearch's external version: a write is kept only if it is newer (a 409
  version conflict is logged at debug and treated as success). There is no `processed_event` table.
- **Visibility is computed at index time:** a product is `visible` when `PUBLISHED`, its vendor is active and its whole
  category path is active; a restaurant when `APPROVED`. Hidden documents stay in the index so their versions are
  remembered. `CategoryChanged` with `deleted=true` is a versioned delete.

### Failures
Elasticsearch unreachable, 429 or 5xx → `TransientEventProcessingException` (the shared Kafka error handler retries
the same record for up to 30 minutes). Unreadable payloads, missing ids/versions, a `PriceChanged` without
`mrp`/`sellingPrice` or with a non-positive price, a `ProductAvailabilityChanged` without `inStock`, a
`RatingSummaryUpdated` without `targetType` / a UUID `targetId` / `ratingCount` / `summaryVersion`, with a negative
count or an average outside 0–5 → `InvalidEventException` (straight to `<topic>.DLT`).

### Events: Avro ([ADR-0011](../../docs/adr/0011-avro-and-schema-registry.md))
- The five topics arrive as **Avro** (Confluent wire format, central schemas of `libs/grocery-event-schemas`).
  grocery-common 0.6.0 installs `ErrorHandlingDeserializer(AvroEventDeserializer)` on the consumer factory, which
  renders each record back into the producer's JSON, so the listeners and payload views are unchanged. Records
  still in JSON (before a producer's switch) pass through as they are.
- Avro renders decimals at their schema scale and timestamps with microsecond precision. The only visible effect
  was `ProductUpserted.unitValue` (`decimal(10,3)`: `500` arrives as `500.000`); `CatalogIndexer` strips the
  trailing zeros again, so documents and API responses are the same as with JSON (`AvroEventPathIntegrationTest`).
- This service publishes nothing: no outbox, no Debezium connector; `grocery-event-schemas` is a test dependency
  only (the tests encode events the way producers do).
- Schema Registry URL: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`);
  tests use the in-memory `mock://catalog-query-service`. Rollback switch: `grocery.events.avro.enabled=false`
  (plain `StringDeserializer`; only once no Avro record is left on the topics).

## Indexes (`src/main/resources/elasticsearch/`)
- `products.json`, `restaurants.json`, `categories.json`: settings (1 shard, 0 replicas) and **strict** mappings.
  `IndexManager` creates any missing index + alias at startup (never failing startup when Elasticsearch is down),
  before writes, and whenever Elasticsearch reports the index missing. Writes always go to the alias with
  `require_alias=true`, so a deleted index is recreated from these definitions — never auto-created with dynamic
  mappings. A search on a missing alias recreates it and returns an empty result.
- **Additive mapping changes reach existing indexes.** Because the mappings are strict, an index created by an older
  release (e.g. `products_v1` from Phase 3, without `mrp`, `sellingPrice`, `inStock`, …) would reject the new fields.
  Whenever `IndexManager` finds an existing alias/index (at startup and before the first write) it sends the
  definition's `mappings.properties` with **put-mapping**: new fields are added, existing ones are unchanged, and
  repeating it is harmless. A change Elasticsearch cannot apply in place (another type or analyzer for an existing
  field) is only logged: that needs a new index version (`products_v2`) and a republish. The price sort also uses
  `unmapped_type`, so it works even before the mapping has been updated.
- Analysis: `product_text` (standard, lowercase, asciifolding, possessive + light English stemmers) at index time;
  `product_search` = the same + the `grocery_synonyms` synonym graph, used only at search time (fuzzy clauses use
  `product_text`, since fuzziness and synonym graphs don't mix); `autocomplete_text` for the `search_as_you_type`
  sub-fields; `keyword_normalizer` (lowercase + asciifolding) for sortable keywords. Money fields are `scaled_float`
  (factor 100).
- `synonyms.txt` (Solr format) is inlined into each index's settings when the index is created. To roll out a change,
  bump the index version in `IndexDefinition` (e.g. `products_v2`), deploy, and republish (below).

### Rebuilding after the products index is lost or replaced
Run the republish calls in this order — **catalog → pricing → inventory**:
1. `POST /api/admin/catalog/republish` (catalog-command: categories + product snapshots)
2. `POST /api/admin/pricing/republish` (pricing-service: `PriceChanged` for every live price)
3. `POST /api/admin/inventory/republish` (inventory-service: `ProductAvailabilityChanged` for every product)

Any order produces the same documents (parts are independent, early prices/stock wait in hidden placeholders);
catalog first just means products become visible with their price and stock moments later. Restaurants:
`POST /api/admin/restaurants/listings/republish`. Ratings (products and restaurants, Phase 12):
`POST /api/admin/reviews/summaries/republish` (review-service: `RatingSummaryUpdated` for every summary, current
versions), in any order relative to the others.

## Queries
- **Products:** `visible` filter + category (matches the whole subtree via `categoryIds`), brand, vendor (`vendorId`
  repeatable: a `terms` filter, products of any of the stores; more than 50 values → 400 `INVALID_PARAMETER`), food type,
  `inStock=true` (term on `inStock`; `false`/absent = no filter), `minPrice` / `maxPrice` (range on `sellingPrice`,
  inclusive; products without a price have no `sellingPrice` and never match a price filter; negative or
  `minPrice > maxPrice` → 400 `INVALID_PARAMETER`). Text: synonym-aware `best_fields` (name^4, brand^2,
  category^1.5, tags^1.5, description^0.5, operator AND) OR a fuzzy name/brand match OR a name prefix. Facets: leaf
  category, brand, food type. Sorts: relevance, newest, `name_asc`, `price_asc`, `price_desc` (unpriced products last
  in both directions), `rating` (Phase 12: highest `rating` first, unrated last, then `ratingCount`, then newest),
  always with `productId` as tie-breaker. Cards and the product page carry
  `price: {mrp, sellingPrice, currency, discountPercent} | null` (amounts with 2 decimals), `inStock`, and
  `rating` (2 decimals, `null` while unrated) + `ratingCount` (0 while unrated).
- **Suggest:** `bool_prefix` on `name.sayt` (+ `_2gram`, `_3gram`) for visible products (8) and active categories (4).
- **Category tree:** active categories; product counts from a terms aggregation on `categoryIds` (a parent counts its
  subtree).
- **Restaurants:** `visible`, optional `geo_distance` (default 10 km, max 30) with distance sort (`distanceKm`),
  cuisine / pure-veg filters, text over name, cuisines, description and **dish names** (nested `menuItems` with
  `inner_hits` → `matchingItems`), and "open now": a nested filter on the weekly hours (overnight ranges split at
  midnight) evaluated in `grocery.catalog.timezone` (Asia/Kolkata) from the injected `Clock`. Phase 12:
  `minRating` (0–5, range on `rating`; unrated restaurants never match; else 400 `INVALID_PARAMETER`) and
  `sort=rating` (highest first, unrated last, then `ratingCount`, then the default order: relevance / distance /
  name; any other `sort` is 400). Cards and the restaurant page carry `rating` and `ratingCount`.

## Internal endpoint (Phase 5, service-to-service)
`POST /internal/catalog/products/lookup` `{ "productIds": [ … ] }` → `{ "items": [ ProductCard ], "missing": [ … ] }`
— cart-service's product lookup. Not routed by the gateway; **any authenticated caller** (the caller forwards the
user's JWT). Only `/api/search/**` and `/api/catalog/**` are in `grocery.security.public-paths`, so `/internal/**`
needs a valid token (no token → 401).
- 1–100 ids (UUIDs), else 400 `VALIDATION_FAILED` (a non-UUID → 400). Duplicates are looked up and returned once;
  `items` and `missing` keep the request order.
- **One multi-get** (`_mget`, real-time, `_source` limited to the card fields + `visible`) through the `products`
  alias. Only documents with `visible=true` (published + vendor active + whole category path active, computed at
  index time) become items; unknown ids, hidden products (unpublished, archived, inactive vendor/category) and
  price/stock placeholders are `missing`.
- Items are exactly the search `ProductCard` (incl. `price` with 2 decimals or null, and `inStock`).
- Missing index → everything is missing and the index + alias are created (like the other reads); Elasticsearch
  unreachable / 429 / 5xx → 503 `SEARCH_UNAVAILABLE`.

## Package layout
```
config/      Clock bean, CatalogProperties (grocery.catalog.timezone)
controller/  SearchController (/api/search/**), CatalogController (/api/catalog/**), AdminSearchController,
             InternalCatalogController (/internal/catalog/**)
document/    Elasticsearch document records (mirror the JSON mappings); ProductDocument names its four parts,
             RestaurantDocument its two
dto/         API responses
event/       Kafka listeners (catalog, pricing, inventory, restaurant, review) + our own tolerant views of the payloads
index/       IndexManager (indexes, aliases, put-mapping), SearchDocumentWriter (versioned writes and part merges),
             SearchGateway (reads: search, get, multi-get)
service/     CatalogIndexer, PriceAndStockIndexer, RestaurantIndexer, RatingIndexer, ProductSearchService,
             RestaurantSearchService,
             CategoryTreeService, IndexStatusService, OpeningHours
```

## Run / test
```bash
cd infrastructure/local && docker compose --profile catalog up -d --build catalog-query-service
# tests (from repo root, Git Bash) — Testcontainers Elasticsearch 9.4.6
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -f services/catalog-query-service/pom.xml verify
```
Integration tests feed events by calling the listener methods directly (Kafka is not started).
`PriceAndStockSearchIntegrationTest` covers the Phase 4 parts: versions per part, part independence, placeholders,
filters/sorts/validation, index loss + republish, concurrent writers and put-mapping onto a Phase 3 index.
`ProductLookupIntegrationTest` covers the Phase 5 lookup (cards equal to search cards, request order, duplicates,
hidden/placeholder/unknown → missing, 1–100 validation, missing index, 401 without a token / any role accepted);
`ProductLookupOutageTest` the 503 when Elasticsearch is down. `RatingSearchIntegrationTest` covers Phase 12: ratings
on cards and pages, the `summaryVersion` guard, part independence (products and restaurants), placeholders, unrated
again, `sort=rating` / `minRating` and their validation, ignored target types, invalid events, put-mapping onto older
indexes and a restaurant document written the pre-Phase-12 way; `AvroEventPathIntegrationTest` the 1.5.0
`RatingSummaryUpdated` through the Avro wire path.
