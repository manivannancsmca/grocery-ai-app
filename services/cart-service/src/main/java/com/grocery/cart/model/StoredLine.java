package com.grocery.cart.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line as stored in Redis: what the customer wants and the price they saw. The display snapshot (name, unit,
 * image, food type) is shown only when the live data cannot be fetched.
 *
 * @param itemId         productId or menuItemId
 * @param priceWhenAdded the unit price when the line was created or its quantity last changed
 */
public record StoredLine(UUID itemId, ItemType type, int quantity, BigDecimal priceWhenAdded, String name,
                         String unitLabel, String imageUrl, String foodType, Instant addedAt) {

    public StoredLine withQuantity(int newQuantity, BigDecimal newPrice, Snapshot display) {
        return new StoredLine(itemId, type, newQuantity, newPrice, display.name(), display.unitLabel(),
                display.imageUrl(), display.foodType(), addedAt);
    }

    /** Display data of an item, taken from the owning service when the line is written. */
    public record Snapshot(String name, String unitLabel, String imageUrl, String foodType) {
    }
}
