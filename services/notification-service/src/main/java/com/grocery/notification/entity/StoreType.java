package com.grocery.notification.entity;

/** The two kinds of store an order can belong to (the {@code store_owner} projection key). */
public enum StoreType {

    /** vendor.events; GROCERY orders. Store home in the app: {@code /vendor}, order board {@code /vendor/orders}. */
    VENDOR("/vendor"),
    /** restaurant.events; RESTAURANT orders. Store home: {@code /restaurant}, board {@code /restaurant/orders}. */
    RESTAURANT("/restaurant");

    private final String home;

    StoreType(String home) {
        this.home = home;
    }

    /** The store owner's home route in the React app. */
    public String home() {
        return home;
    }

    /** The store's order board in the React app (Phase 8). */
    public String board() {
        return home + "/orders";
    }

    /** Phase 12: the store's reviews page ({@code /vendor/reviews}, {@code /restaurant/reviews}). */
    public String reviews() {
        return home + "/reviews";
    }

    /** Phase 12: the store's returns page ({@code /vendor/returns}, {@code /restaurant/returns}). */
    public String returns() {
        return home + "/returns";
    }

    /** GROCERY → VENDOR, RESTAURANT → RESTAURANT, anything else → null. */
    public static StoreType ofOrderType(String orderType) {
        if ("GROCERY".equals(orderType)) {
            return VENDOR;
        }
        if ("RESTAURANT".equals(orderType)) {
            return RESTAURANT;
        }
        return null;
    }
}
