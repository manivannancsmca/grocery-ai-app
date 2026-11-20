package com.grocery.inventory.entity;

/** Why a vendor adjusted the stock (required on every adjustment). */
public enum AdjustmentReason {
    RESTOCK,
    RETURN,
    DAMAGED,
    EXPIRED,
    STOCK_COUNT,
    CORRECTION,
    OTHER
}
