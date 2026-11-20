package com.grocery.restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A dish on a restaurant's menu. References its restaurant and category by id. An unavailable item
 * ("sold out") stays on the menu and in search with {@code available = false}.
 */
@Entity
@Table(name = "menu_item")
public class MenuItem {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    /** INR, always 2 decimals. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_type", nullable = false, length = 10)
    private FoodType foodType;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper type: null marks a new entity, so Spring Data persists instead of merging. */
    @Version
    private Long version;

    protected MenuItem() {
    }

    public static MenuItem create(UUID id, UUID restaurantId, UUID categoryId, String name, String description,
                                  BigDecimal price, FoodType foodType, String imageUrl, boolean available,
                                  int sortOrder, Instant now) {
        MenuItem item = new MenuItem();
        item.id = id;
        item.restaurantId = restaurantId;
        item.createdAt = now;
        item.update(categoryId, name, description, price, foodType, imageUrl, available, sortOrder, now);
        return item;
    }

    /** Replaces every editable field (PUT semantics). The price must already have at most 2 decimals. */
    public void update(UUID categoryId, String name, String description, BigDecimal price, FoodType foodType,
                       String imageUrl, boolean available, int sortOrder, Instant now) {
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.price = price.setScale(2, RoundingMode.UNNECESSARY);
        this.foodType = foodType;
        this.imageUrl = imageUrl;
        this.available = available;
        this.sortOrder = sortOrder;
        this.updatedAt = now;
    }

    /** @return false when the item already had this availability (nothing changed). */
    public boolean changeAvailability(boolean available, Instant now) {
        if (this.available == available) {
            return false;
        }
        this.available = available;
        this.updatedAt = now;
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRestaurantId() {
        return restaurantId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public FoodType getFoodType() {
        return foodType;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public boolean isAvailable() {
        return available;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
