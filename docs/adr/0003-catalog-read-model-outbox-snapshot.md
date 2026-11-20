# ADR-0003: Catalog read model via outbox snapshot → Elasticsearch

- **Status:** Accepted
- **Date:** 2026-11-10

## Context

The blueprint requires a write service on MySQL and a read service on Elasticsearch, with Debezium CDC between them.
A search document needs data from several tables (product, category path, brand, images, vendor). It also needs
data from other services: price from pricing-service, availability from inventory-service, ratings from review-service.

## Decision

- **catalog-command-service** writes a `ProductUpserted` event containing the **full denormalized product snapshot**
  into its outbox, in the same transaction as the product change. It also writes `CategoryChanged` (see the
  refinements below).
- Debezium relays these events to `catalog.events` ([ADR-0002](0002-transactional-outbox-with-debezium.md)).
- **catalog-query-service** consumes them and upserts into Elasticsearch 9.4.6 using `version_type=external`
  (product version), so stale or out-of-order events are ignored.
- Price, availability and rating events update only their own fields, each protected by its own version field.
- **Full reindex:** an admin "republish all" call on catalog-command writes a snapshot event for every product.
- Restaurants and menus follow the same pattern from restaurant-service (`RestaurantListingUpdated`).

## Consequences

- ✅ The read side depends on a **stable published contract**, not on catalog_db's internal table layout.
- ✅ No stream joins. Each event is self-contained.
- ✅ catalog-query never reads another service's database.
- ⚠️ Search is eventually consistent, typically about 1–2 s behind. Checkout always re-checks price and stock with the owners.
- ⚠️ Snapshot events are larger than row changes. This is negligible at about 1,000 products.

## Refinements made while building Phase 3 (2026-11-11)

- **No separate removal or menu events.** Archiving a product is a `ProductUpserted` with status `ARCHIVED`. A
  restaurant's profile, location, hours, accepting-orders switch and full menu travel in one
  `RestaurantListingUpdated` snapshot, instead of the planned `ProductRemoved`, `MenuChanged` and
  `RestaurantStatusChanged`. One versioned snapshot per aggregate means the read side never merges partial updates.
- **Cross-aggregate changes re-publish snapshots.** A category rename, move or deactivation, and a vendor suspension or
  reinstatement, re-publish every affected product with a new version. catalog-command keeps a small vendor
  projection from `vendor.events` for this.
- **Version safety on the write side.** Each writer locks the aggregate row first (`SELECT … FOR UPDATE`), then bumps
  the version and builds the snapshot in the same transaction, so a higher version can never carry older data.
  Republish sends current versions without bumping them: existing documents ignore them, missing ones are recreated.
- **Index safety on the read side.** Documents are written through aliases with `require_alias=true`, so a deleted
  index is recreated with the proper mappings rather than auto-created with dynamic ones.

## Alternatives considered

- **Raw table CDC** (Debezium change events for product, category and image tables): it exposes the internal schema,
  forces the read side to rebuild joins, and breaks on column renames. Rejected.
- **Kafka Connect Elasticsearch sink connector:** less control over document shape, merging and versioning, and
  licensing concerns. Rejected.
- **MySQL FULLTEXT only:** enough for keyword search at this size, but it lacks typo tolerance, autocomplete, synonyms,
  facets, and geo search combined with text. Rejected because of required features, not load.
