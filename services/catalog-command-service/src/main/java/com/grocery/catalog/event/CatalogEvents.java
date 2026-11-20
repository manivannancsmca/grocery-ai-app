package com.grocery.catalog.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code catalog.events} (v1, docs/api/phase-3-catalog-and-search.md §8). Both are
 * complete snapshots, keyed by the aggregate id; the read side stores the version as Elasticsearch's
 * external version, so an event is applied only when its version is newer than the stored one.
 * Consumer: catalog-query-service ({@code catalog-query.product-indexer}).
 */
public final class CatalogEvents {

    public static final String TOPIC = "catalog.events";
    public static final String PRODUCT_AGGREGATE = "Product";
    public static final String CATEGORY_AGGREGATE = "Category";

    public static final String PRODUCT_UPSERTED = "ProductUpserted";
    public static final String CATEGORY_CHANGED = "CategoryChanged";

    private CatalogEvents() {
    }

    public record IdName(UUID id, String name) {
    }

    public record Attribute(String name, String value) {
    }

    /** The complete product, published on every change that affects any of these fields. */
    public record ProductUpserted(
            UUID productId,
            UUID vendorId,
            String vendorName,
            boolean vendorActive,
            String name,
            String description,
            IdName brand,
            IdName category,
            List<IdName> categoryPath,       // root → leaf
            boolean categoryActive,          // the category and all its parents are active
            BigDecimal unitValue,            // JSON number, no trailing zeros
            String unitOfMeasure,
            String unitLabel,
            String sku,
            String barcode,
            String foodType,
            List<String> tags,
            List<Attribute> attributes,
            List<String> imageUrls,
            String status,                   // DRAFT | PUBLISHED | UNPUBLISHED | ARCHIVED
            Instant publishedAt,
            Instant updatedAt,
            long productVersion) {           // strictly increasing per product
    }

    /** The complete category, published on create, edit, move, (de)activate, delete and republish. */
    public record CategoryChanged(
            UUID categoryId,
            UUID parentId,
            String name,
            String description,
            String imageUrl,
            int sortOrder,
            int depth,
            List<IdName> path,               // root → this category
            boolean active,                  // own flag
            boolean effectiveActive,         // own flag AND every parent active
            boolean deleted,
            long categoryVersion) {
    }
}
