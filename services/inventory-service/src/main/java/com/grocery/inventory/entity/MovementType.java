package com.grocery.inventory.entity;

/** The kinds of stock change recorded in the {@code stock_movement} ledger. */
public enum MovementType {
    /** Vendor adjustment: on hand + quantity. */
    INCREASE,
    /** Vendor adjustment: on hand − quantity (at most the available stock). */
    DECREASE,
    /** Vendor adjustment: on hand = the counted quantity (at least the reserved stock). */
    SET,
    /** Stock held for an order: reserved + quantity (on hand unchanged). */
    RESERVE,
    /** A hold given back: reserved − quantity (on hand unchanged). */
    RELEASE,
    /** A hold that left the store: on hand − quantity and reserved − quantity. */
    COMMIT;

    public boolean isAdjustment() {
        return this == INCREASE || this == DECREASE || this == SET;
    }
}
