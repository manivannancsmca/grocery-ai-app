package com.grocery.restaurant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code POST /internal/restaurants/{restaurantId}/menu-quote} (docs/api/phase-5-cart-and-checkout-preview.md §4). */
public final class MenuQuoteDtos {

    private MenuQuoteDtos() {
    }

    /** 1–100 menu item ids; duplicates are quoted once. */
    public record MenuQuoteRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> itemIds) {
    }

    /** A dish on this restaurant's menu; {@code price} in INR with 2 decimals. */
    public record QuotedItem(UUID itemId, String name, BigDecimal price, String foodType, boolean available,
                             String imageUrl) {
    }

    /**
     * Authoritative restaurant data from MySQL.
     *
     * @param openNow  from the weekly hours in {@code grocery.restaurant.timezone} (overnight hours supported)
     * @param city     null (as are the coordinates) while the profile has no address
     * @param items    the requested items that are on THIS restaurant's menu, in request order
     * @param missing  the requested ids that are not on this restaurant's menu (unknown, deleted or another
     *                 restaurant's), in request order
     */
    public record MenuQuote(UUID restaurantId, String name, String status, boolean acceptingOrders, boolean openNow,
                            String city, Double latitude, Double longitude, List<QuotedItem> items,
                            List<UUID> missing) {
    }
}
