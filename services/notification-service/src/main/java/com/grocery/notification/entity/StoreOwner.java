package com.grocery.notification.entity;

import java.util.UUID;

/** A row of the {@code store_owner} projection: who owns a vendor or a restaurant. */
public record StoreOwner(StoreType storeType, UUID storeId, UUID ownerUserId, String storeName) {
}
