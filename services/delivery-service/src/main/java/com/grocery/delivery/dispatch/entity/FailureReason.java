package com.grocery.delivery.dispatch.entity;

/** Why the partner could not deliver an order they picked up (contract §3, {@code /fail}). */
public enum FailureReason {
    CUSTOMER_UNREACHABLE,
    ADDRESS_NOT_FOUND,
    CUSTOMER_REFUSED,
    OTHER
}
