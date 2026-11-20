package com.grocery.order.client;

/** The services order-service calls; {@link #id()} names the circuit breaker and appears in 503 problems. */
public enum Dependency {
    CART("cart"),
    /** Phase 12: coupon redemption at placement ({@code POST /internal/coupons/redeem}). */
    PRICING("pricing");

    private final String id;

    Dependency(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
