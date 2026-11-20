package com.grocery.cart.model;

/** A grocery product (store = its vendor) or a restaurant menu item (store = the restaurant). */
public enum ItemType {
    PRODUCT,
    MENU_ITEM;

    public StoreType storeType() {
        return this == PRODUCT ? StoreType.GROCERY : StoreType.RESTAURANT;
    }
}
