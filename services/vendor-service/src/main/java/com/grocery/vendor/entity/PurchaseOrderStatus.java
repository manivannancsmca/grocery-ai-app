package com.grocery.vendor.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status of a purchase order (Phase 12 §4):
 * <pre>
 * DRAFT ──send──▶ SENT ──receive (part)──▶ PARTIALLY_RECEIVED ──receive (rest)──▶ RECEIVED
 *   │              │  └──────────receive (all)───────────────────────────────────────▲
 *   └──cancel──────┴──cancel──▶ CANCELLED          PARTIALLY_RECEIVED ──close──▶ CLOSED (short-closed)
 * </pre>
 * Only DRAFT is editable. RECEIVED, CANCELLED and CLOSED are terminal.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SENT,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED,
    CLOSED;

    /** Still in progress: a supplier with such purchase orders cannot be deactivated ({@code status=OPEN} filter). */
    public static final Set<PurchaseOrderStatus> OPEN = EnumSet.of(DRAFT, SENT, PARTIALLY_RECEIVED);

    /** Goods may be received. */
    public static final Set<PurchaseOrderStatus> RECEIVABLE = EnumSet.of(SENT, PARTIALLY_RECEIVED);

    /** Which statuses an action may start from. */
    public static final Set<PurchaseOrderStatus> SENDABLE = EnumSet.of(DRAFT);
    public static final Set<PurchaseOrderStatus> CANCELLABLE = EnumSet.of(DRAFT, SENT);
    public static final Set<PurchaseOrderStatus> CLOSABLE = EnumSet.of(PARTIALLY_RECEIVED);
}
