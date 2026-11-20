package com.grocery.order.entity;

/** Who cancelled an order ({@code cancellation.by} / {@code cancelledBy}); {@code STORE} = a vendor or restaurant user. */
public enum Actor {
    CUSTOMER,
    ADMIN,
    STORE,
    SYSTEM
}
