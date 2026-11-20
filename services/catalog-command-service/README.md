# catalog-command-service

Write side of the product catalog (Phase 3): the category tree, the shared brand dictionary and vendor products.
Every change is published as a **complete snapshot** through the outbox; catalog-query-service indexes the
snapshots into Elasticsearch and serves every public catalog read.

| | |
|---|---|
| Port | 8105 |
| Database | `catalog_db` (user `catalog_user`) — tables `category`, `brand`, `vendor_ref`, `product`, `product_image`, `product_attribute`, `product_tag`, `outbox_event`, `processed_event` |
| Publishes | `catalog.events`: `ProductUpserted` (aggregate `Product`, key productId), `CategoryChanged` (aggregate `Category`, key categoryId) |
| Consumes | `vendor.events` → `vendor_ref` projection (group `catalog-command.vendor-events`) |
| API | `docs/api/phase-3-catalog-and-search.md` §3, §4 (events §8) |
| Debezium | `deploy/debezium-connector.json` (connector `catalog-outbox`) |

## Endpoints
| Method & path | Who |
|---|---|
| `GET/POST /api/vendor/products`, `GET/PUT/DELETE /api/vendor/products/{id}`, `POST …/{id}/publish`, `POST …/{id}/unpublish`, `GET /api/vendor/brands?q=` | `VENDOR` with a `vendor_id` claim (approved), else 403 `VENDOR_NOT_ACTIVE` |
| `GET /api/admin/categories`, `GET /api/admin/products`, `GET /api/admin/products/{id}` | any admin role |
| `POST/PUT/DELETE /api/admin/categories…`, `POST …/{id}/activate`, `POST …/{id}/deactivate`, `POST /api/admin/catalog/republish` | `SUPER_ADMIN`, `OPS_ADMIN` |

Product lists (vendor and admin) query MySQL, newest change first; `q` = name contains (case-insensitive) or
SKU equals; ARCHIVED products only with `status=ARCHIVED`. The admin `categoryId` filter includes sub-categories.

## Rules
```
Product:  DRAFT ──publish──▶ PUBLISHED ⇄ UNPUBLISHED      any non-archived ──DELETE──▶ ARCHIVED (final)
```
- Product status rules live in `entity/Product.java` (409 `INVALID_STATUS_TRANSITION`, `PRODUCT_ARCHIVED`,
  `CATEGORY_INACTIVE` on publish). SKU unique per vendor among non-archived products (generated `sku_key` column
  + unique key → 409 `SKU_ALREADY_EXISTS`). Brands are matched by a normalised key (`"farm  fresh"` = `"Farm Fresh"`).
- Categories: ≤ 3 levels (422 `CATEGORY_TOO_DEEP`, also when moving a subtree), no cycles (409 `CATEGORY_CYCLE`),
  sibling names unique case-insensitively (409 `CATEGORY_NAME_TAKEN`), delete only when empty (409 `CATEGORY_IN_USE`;
  archived products don't count and lose their category). Product create/update with an unknown category → 422
  `CATEGORY_NOT_FOUND` (field `categoryId`).
- `vendorActive` in a snapshot = the vendor is not SUSPENDED. A vendor without a `vendor_ref` row yet counts as
  active (the token's `vendor_id` claim proves approval; the event may simply not have arrived).

## Snapshots, versions and locking
Every snapshot carries `productVersion` / `categoryVersion`, which the read side uses as Elasticsearch's external
version. A transaction that changes anything that appears in a snapshot **first** takes the row locks
(`SELECT … FOR UPDATE` on the aggregate; `FOR SHARE` on the category / vendor row a product snapshot reads), then
increments the version, builds the snapshot and writes the outbox row — all in one transaction — so a higher
version never carries older data. Details in `service/SnapshotPublisher.java`.

| Trigger | Events |
|---|---|
| product create / edit / publish / unpublish / archive | `ProductUpserted` |
| category create, or edit of description/image/sort order | `CategoryChanged` (that category) |
| category rename, move, (de)activation | `CategoryChanged` for the category and all descendants + `ProductUpserted` for every non-archived product in the subtree |
| category delete | `CategoryChanged` with `deleted=true` |
| `VendorApproved/Suspended/Reinstated/…` changing the vendor's active flag or name | `ProductUpserted` for the vendor's non-archived products |
| `POST /api/admin/catalog/republish` | every category, then every product (batches of 500), **same versions** |

## Events: Avro ([ADR-0011](../../docs/adr/0011-avro-and-schema-registry.md))
- `catalog.events` payloads travel as **Avro** (Confluent wire format), validated against the central schemas in
  `libs/grocery-event-schemas` (`avro/catalog.events/ProductUpserted.avsc`, `CategoryChanged.avsc`; dependency
  `grocery-event-schemas:1.3.0`). Subjects: `catalog.events-com.grocery.events.catalog.<EventType>`.
- The outbox (grocery-common 0.6.0) writes both columns in the business transaction: `payload` (JSON, kept for
  auditing and the tests) and `payload_avro` (Flyway `V3`), which Debezium publishes byte for byte
  (`deploy/debezium-connector.json`). A payload that does not match its schema fails the transaction.
- `vendor.events` is read through grocery-common's Avro deserializer: listeners still get the same JSON.
- Schema Registry URL: env `GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL` (`grocery.events.avro.schema-registry-url`);
  tests use the in-memory `mock://catalog-command-service`. `ProductUpserted.unitValue` is `decimal(10,3)`:
  consumers see `500.000` where the JSON column has `500` (the same number).
- Rollback switch: `grocery.events.avro.enabled=false` (JSON only; switch the connector back to `payload` first).

## Package layout
```
config/       Clock (µs precision = DATETIME(6)), lock-conflict → 409 advice
controller/   VendorProductController (/api/vendor/…), AdminCategoryController, AdminProductController
dto/          request/response records + validation rules
entity/       Product (status machine), Category, Brand, VendorRef, enums
event/        CatalogEvents (published payloads), VendorEventsListener (Kafka consumer)
mapper/       ProductMapper, CategoryMapper, SnapshotMapper (plain Java)
repository/   Spring Data JPA (lock queries, Specifications for lists)
service/      VendorProductService, ProductQueryService, CategoryAdminService, BrandService,
              VendorProjectionService, RepublishService, SnapshotPublisher, CategoryTree
```

## Run / test
```bash
# (once infrastructure/local/docker-compose.yml has a catalog-command-service entry)
cd infrastructure/local && docker compose --profile catalog up -d --build catalog-command-service
# tests (from repo root, Git Bash) — Testcontainers MySQL
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/workspace" -v grocery-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /workspace maven:3.9.16-eclipse-temurin-25-noble mvn -f services/catalog-command-service/pom.xml verify
```
