package com.grocery.cart.client;

/** The services the cart reads from; {@link #id()} names the circuit breaker and appears in 503 problems. */
public enum Dependency {
    PRICING("pricing"),
    /** pricing-service's delivery fees: a breaker of its own, never part of a 503 (the cart falls back to known fees). */
    PRICING_FEES("pricing-fees"),
    INVENTORY("inventory"),
    CATALOG("catalog"),
    VENDOR("vendor"),
    RESTAURANT("restaurant"),
    CUSTOMER("customer");

    private final String id;

    Dependency(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
