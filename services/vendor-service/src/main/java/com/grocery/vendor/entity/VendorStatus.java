package com.grocery.vendor.entity;

/**
 * <pre>
 * DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
 *   ▲                      │ reject
 *   └──── edit ◀── REJECTED ◀┘
 * </pre>
 */
public enum VendorStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    SUSPENDED
}
