package com.grocery.restaurant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A section of a restaurant's menu ("Biryani", "Starters"). Its own entity referencing the restaurant by id;
 * items reference it by id. Name unique per restaurant, case-insensitively (unique key + collation).
 */
@Entity
@Table(name = "menu_category")
public class MenuCategory {

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 300)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper type: null marks a new entity, so Spring Data persists instead of merging. */
    @Version
    private Long version;

    protected MenuCategory() {
    }

    public static MenuCategory create(UUID id, UUID restaurantId, String name, String description, int sortOrder,
                                      Instant now) {
        MenuCategory category = new MenuCategory();
        category.id = id;
        category.restaurantId = restaurantId;
        category.createdAt = now;
        category.update(name, description, sortOrder, now);
        return category;
    }

    public void update(String name, String description, int sortOrder, Instant now) {
        this.name = name;
        this.description = description;
        this.sortOrder = sortOrder;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRestaurantId() {
        return restaurantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
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
