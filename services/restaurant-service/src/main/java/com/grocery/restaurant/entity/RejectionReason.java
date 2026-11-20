package com.grocery.restaurant.entity;

/** Why a store order was rejected; {@link #NO_RESPONSE} only by the auto-reject poller, never through the API. */
public enum RejectionReason {
    OUT_OF_STOCK,
    STORE_CLOSED,
    TOO_BUSY,
    OTHER,
    NO_RESPONSE
}
