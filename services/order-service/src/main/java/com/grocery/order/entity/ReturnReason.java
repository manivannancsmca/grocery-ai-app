package com.grocery.order.entity;

/** Why the customer returns the items ({@code ReturnRequested.reason}). */
public enum ReturnReason {
    DAMAGED,
    WRONG_ITEM,
    MISSING,
    QUALITY,
    OTHER
}
