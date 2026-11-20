package com.grocery.delivery.dispatch.entity;

/**
 * PENDING until the partner accepts (ACCEPTED) or rejects (REJECTED), it expires (EXPIRED), or it is WITHDRAWN
 * (another partner accepted, the delivery was cancelled or reassigned).
 */
public enum OfferStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    WITHDRAWN
}
