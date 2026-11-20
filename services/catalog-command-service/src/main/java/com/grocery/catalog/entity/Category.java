package com.grocery.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One node of the category tree. Tree rules (depth, cycles, sibling names) need the whole tree and are
 * checked by {@code CategoryTree} / {@code CategoryAdminService}; the generated {@code parent_key}
 * column (unique with {@code name}) is deliberately not mapped.
 */
@Entity
@Table(name = "category")
public class Category {

    public static final int MAX_DEPTH = 3;

    @Id
    private UUID id;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private int depth;

    @Column(name = "category_version", nullable = false)
    private long categoryVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;   // null until persisted, so Spring Data persists (no merge SELECT)

    protected Category() {
    }

    public static Category create(UUID id, UUID parentId, int depth, String name, String description,
                                  String imageUrl, int sortOrder, Instant now) {
        Category category = new Category();
        category.id = id;
        category.parentId = parentId;
        category.depth = depth;
        category.name = name;
        category.description = description;
        category.imageUrl = imageUrl;
        category.sortOrder = sortOrder;
        category.active = true;
        category.createdAt = now;
        category.updatedAt = now;
        return category;
    }

    public void update(String name, String description, String imageUrl, int sortOrder, Instant now) {
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.sortOrder = sortOrder;
        this.updatedAt = now;
    }

    /** Re-parents this node; depths of the moved subtree are recomputed by the caller (CategoryTree). */
    public void moveTo(UUID parentId, Instant now) {
        this.parentId = parentId;
        this.updatedAt = now;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    /** Next snapshot version; call only while holding the row lock (see SnapshotPublisher). */
    public long nextVersion() {
        return ++categoryVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public int getDepth() {
        return depth;
    }

    public long getCategoryVersion() {
        return categoryVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
