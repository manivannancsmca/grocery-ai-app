package com.grocery.restaurant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Menu API bodies (docs/api/phase-3-catalog-and-search.md §5). */
public final class MenuDtos {

    public static final String FOOD_TYPE = "^(VEG|NON_VEG|EGG)$";
    public static final String HTTP_URL = "^https?://\\S+$";

    private MenuDtos() {
    }

    /** {@code sortOrder} defaults to 0 (lower first; ties sorted by name). */
    public record MenuCategoryRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description,
            @Min(0) @Max(9999) Integer sortOrder) {
    }

    /** {@code available} defaults to true, {@code sortOrder} to 0. */
    public record MenuItemRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull
            @DecimalMin(value = "1.00", message = "must be at least 1.00")
            @DecimalMax(value = "100000.00", message = "must be at most 100000.00")
            @Digits(integer = 6, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal price,
            @NotNull @Pattern(regexp = FOOD_TYPE, message = "must be VEG, NON_VEG or EGG") String foodType,
            @Size(max = 500) @Pattern(regexp = HTTP_URL, message = "must be an http(s) URL") String imageUrl,
            Boolean available,
            @Min(0) @Max(9999) Integer sortOrder) {
    }

    public record AvailabilityRequest(@NotNull Boolean available) {
    }

    public record AcceptingOrdersRequest(@NotNull Boolean acceptingOrders) {
    }

    public record AcceptingOrdersView(boolean acceptingOrders) {
    }

    public record MenuItemView(UUID id, UUID categoryId, String name, String description, BigDecimal price,
                               String foodType, String imageUrl, boolean available, int sortOrder,
                               Instant updatedAt) {
    }

    /** Items sorted by sortOrder, then name. */
    public record MenuCategoryView(UUID id, String name, String description, int sortOrder,
                                   List<MenuItemView> items) {
    }

    /** The whole menu; categories (and their items) sorted by sortOrder, then name. */
    public record MenuView(UUID restaurantId, String restaurantStatus, boolean acceptingOrders, int itemCount,
                           List<MenuCategoryView> categories) {
    }

    /** {@code POST /api/admin/restaurants/listings/republish} → how many listings were re-sent. */
    public record RepublishResult(int restaurants) {
    }
}
