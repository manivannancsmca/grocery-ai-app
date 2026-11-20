package com.grocery.pricing.entity;

/** RESERVED (redeemed at placement) → CONFIRMED (OrderCreated) or RELEASED (cancelled, rejected, never saved). */
public enum RedemptionStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
