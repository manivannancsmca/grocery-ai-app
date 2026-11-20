package com.grocery.restaurant.entity;

/**
 * <pre>
 * DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
 *   ▲                      │ reject
 *   └──── edit ◀── REJECTED ◀┘
 * </pre>
 */
public enum RestaurantStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    SUSPENDED
}
