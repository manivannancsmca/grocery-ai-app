package com.grocery.pricing.entity;

/** Where a live-price change came from: the vendor directly, or an admin approving a request. */
public enum PriceSource {
    VENDOR,
    APPROVED_REQUEST
}
