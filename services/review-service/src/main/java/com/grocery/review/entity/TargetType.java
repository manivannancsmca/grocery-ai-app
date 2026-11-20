package com.grocery.review.entity;

import java.util.Set;

/** What a review is about (contract §1). */
public enum TargetType {

    /** A product of a GROCERY order (productId). */
    PRODUCT,
    /** A dish of a RESTAURANT order (menuItemId). */
    MENU_ITEM,
    /** The store of a GROCERY order (vendorId). */
    VENDOR,
    /** The store of a RESTAURANT order (restaurantId). */
    RESTAURANT,
    /** The partner who delivered the order. */
    DELIVERY_PARTNER;

    /** The targets a vendor's store sees and replies to: the store itself and its products. */
    public static final Set<TargetType> VENDOR_STORE = Set.of(VENDOR, PRODUCT);
    /** The targets a restaurant sees and replies to: the restaurant itself and its dishes. */
    public static final Set<TargetType> RESTAURANT_STORE = Set.of(RESTAURANT, MENU_ITEM);

    /** The store target of an order type (GROCERY → VENDOR, RESTAURANT → RESTAURANT), null for anything else. */
    public static TargetType storeOf(String orderType) {
        return switch (orderType == null ? "" : orderType) {
            case "GROCERY" -> VENDOR;
            case "RESTAURANT" -> RESTAURANT;
            default -> null;
        };
    }

    /** The item target of an order line type (PRODUCT, MENU_ITEM), null for anything else. */
    public static TargetType itemOf(String lineType) {
        return switch (lineType == null ? "" : lineType) {
            case "PRODUCT" -> PRODUCT;
            case "MENU_ITEM" -> MENU_ITEM;
            default -> null;
        };
    }
}
