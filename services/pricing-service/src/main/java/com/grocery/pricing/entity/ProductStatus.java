package com.grocery.pricing.entity;

/** The catalog's product status, as carried by {@code ProductUpserted}. ARCHIVED is final and read-only here. */
public enum ProductStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED,
    ARCHIVED
}
