package com.grocery.delivery.partner.entity;

/**
 * <pre>
 * DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──suspend──▶ SUSPENDED ──reinstate──▶ APPROVED
 *   ▲                      │ reject
 *   └──── edit ◀── REJECTED ◀┘
 * </pre>
 */
public enum PartnerStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    SUSPENDED
}
