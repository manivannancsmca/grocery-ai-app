package com.grocery.inventory.entity;

/** The catalog status of a product, as carried by {@code ProductUpserted}. */
public enum ProductStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED,
    /** Read-only here: every stock change returns 409 PRODUCT_ARCHIVED; left out of lists and the summary. */
    ARCHIVED
}
