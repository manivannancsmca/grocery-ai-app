package com.grocery.notification.entity;

/**
 * Phase 12 §1: what a review is about ({@code targetType} of review.events), with the store whose owner hears about it
 * and the neutral words used when the name is not known — per reader: the store owner ("your product", contract §1),
 * the customer ("a product") and admins ("a product").
 */
public enum ReviewTarget {

    PRODUCT(StoreType.VENDOR, true, "your product", "a product", "a product"),
    MENU_ITEM(StoreType.RESTAURANT, true, "your dish", "a dish", "a dish"),
    VENDOR(StoreType.VENDOR, false, "your store", "the store", "a store"),
    RESTAURANT(StoreType.RESTAURANT, false, "your restaurant", "the restaurant", "a restaurant"),
    /** Not the store's business: the store is neither notified nor signalled about these. */
    DELIVERY_PARTNER(null, false, "your delivery partner", "your delivery partner", "a delivery partner");

    private final StoreType storeType;
    private final boolean item;
    private final String ownerFallback;
    private final String customerFallback;
    private final String adminFallback;

    ReviewTarget(StoreType storeType, boolean item, String ownerFallback, String customerFallback,
                 String adminFallback) {
        this.storeType = storeType;
        this.item = item;
        this.ownerFallback = ownerFallback;
        this.customerFallback = customerFallback;
        this.adminFallback = adminFallback;
    }

    /** The store type whose owner hears about the review; null for DELIVERY_PARTNER. */
    public StoreType storeType() {
        return storeType;
    }

    /** A product or a dish (its name comes from the {@code item_name} projection). */
    public boolean item() {
        return item;
    }

    public String ownerFallback() {
        return ownerFallback;
    }

    public String customerFallback() {
        return customerFallback;
    }

    public String adminFallback() {
        return adminFallback;
    }

    /** The target type of an event; null for an unknown one (handled neutrally, never sent to a store). */
    public static ReviewTarget of(String targetType) {
        if (targetType == null) {
            return null;
        }
        try {
            return valueOf(targetType.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
