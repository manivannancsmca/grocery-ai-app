package com.grocery.catalog.entity;

/**
 * <pre>
 * DRAFT ──publish──▶ PUBLISHED ◀──publish── UNPUBLISHED
 *                        └──────unpublish──────▶┘
 * DRAFT / PUBLISHED / UNPUBLISHED ──archive──▶ ARCHIVED (final)
 * </pre>
 */
public enum ProductStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED,
    ARCHIVED
}
