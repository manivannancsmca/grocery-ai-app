package com.grocery.pricing.entity;

/**
 * <pre>
 * PENDING ──approve──▶ APPROVED      PENDING ──reject──▶ REJECTED
 * PENDING ──cancel (vendor, or automatically when the product is archived)──▶ CANCELLED
 * </pre>
 * Every state but PENDING is final.
 */
public enum PriceChangeStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}
