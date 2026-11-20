package com.grocery.pricing.entity;

/** Why a redemption was released (its use given back). */
public enum ReleaseReason {
    ORDER_CANCELLED,
    ORDER_REJECTED,
    /** No OrderCreated within the reservation TTL: the order was never saved (the sweeper). */
    RESERVATION_EXPIRED
}
