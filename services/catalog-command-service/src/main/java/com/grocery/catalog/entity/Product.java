package com.grocery.catalog.entity;

import com.grocery.common.web.ConflictException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A vendor's grocery product and its status machine. Category, brand and vendor are referenced by id
 * (no JPA associations), so locking a product row never locks or loads anything else.
 * The generated {@code sku_key} column (SKU unique among non-archived products) is not mapped.
 */
@Entity
@Table(name = "product")
public class Product {

    /** Everything a vendor edits with PUT, already validated and normalised. */
    public record Details(UUID categoryId, UUID brandId, String name, String description, BigDecimal unitValue,
                          UnitOfMeasure unitOfMeasure, String sku, String barcode, FoodType foodType,
                          List<String> tags, List<ProductAttribute> attributes, List<String> imageUrls) {
    }

    @Id
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "brand_id")
    private UUID brandId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "unit_value", nullable = false, precision = 10, scale = 3)
    private BigDecimal unitValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_of_measure", nullable = false, length = 10)
    private UnitOfMeasure unitOfMeasure;

    @Column(length = 40)
    private String sku;

    @Column(length = 14)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_type", length = 10)
    private FoodType foodType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ProductStatus status;

    @Column(name = "product_version", nullable = false)
    private long productVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;   // null until persisted, so Spring Data persists (no merge SELECT)

    @ElementCollection
    @CollectionTable(name = "product_image", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "url", nullable = false, length = 500)
    @BatchSize(size = 100)
    private List<String> imageUrls = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "product_attribute", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "sort_order")
    @BatchSize(size = 100)
    private List<ProductAttribute> attributes = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "product_tag", joinColumns = @JoinColumn(name = "product_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "tag", nullable = false, length = 30)
    @BatchSize(size = 100)
    private List<String> tags = new ArrayList<>();

    protected Product() {
    }

    /** A new product starts as an invisible DRAFT. */
    public static Product draft(UUID id, UUID vendorId, Details details, Instant now) {
        Product product = new Product();
        product.id = id;
        product.vendorId = vendorId;
        product.status = ProductStatus.DRAFT;
        product.createdAt = now;
        product.apply(details, now);
        return product;
    }

    /** PUT: replaces all editable fields. Archived products are frozen. */
    public void update(Details details, Instant now) {
        requireEditable();
        apply(details, now);
    }

    public void requireEditable() {
        if (status == ProductStatus.ARCHIVED) {
            throw new ConflictException("PRODUCT_ARCHIVED", "An archived product cannot be edited");
        }
    }

    /** DRAFT/UNPUBLISHED → PUBLISHED; the category and all its parents must be active. */
    public void publish(boolean categoryEffectivelyActive, Instant now) {
        requireStatus(EnumSet.of(ProductStatus.DRAFT, ProductStatus.UNPUBLISHED), ProductStatus.PUBLISHED);
        if (!categoryEffectivelyActive) {
            throw new ConflictException("CATEGORY_INACTIVE",
                    "The product's category (or one of its parents) is inactive; it cannot be published");
        }
        status = ProductStatus.PUBLISHED;
        if (publishedAt == null) {
            publishedAt = now;
        }
        updatedAt = now;
    }

    public void unpublish(Instant now) {
        requireStatus(EnumSet.of(ProductStatus.PUBLISHED), ProductStatus.UNPUBLISHED);
        status = ProductStatus.UNPUBLISHED;
        updatedAt = now;
    }

    /** Hidden everywhere but kept for order history. Final. */
    public void archive(Instant now) {
        requireStatus(EnumSet.of(ProductStatus.DRAFT, ProductStatus.PUBLISHED, ProductStatus.UNPUBLISHED),
                ProductStatus.ARCHIVED);
        status = ProductStatus.ARCHIVED;
        archivedAt = now;
        updatedAt = now;
    }

    /** Next snapshot version; call only while holding the row lock (see SnapshotPublisher). */
    public long nextVersion() {
        return ++productVersion;
    }

    public boolean isArchived() {
        return status == ProductStatus.ARCHIVED;
    }

    public String unitLabel() {
        return unitOfMeasure.label(unitValue);
    }

    private void apply(Details d, Instant now) {
        categoryId = d.categoryId();
        brandId = d.brandId();
        name = d.name();
        description = d.description();
        unitValue = d.unitValue();
        unitOfMeasure = d.unitOfMeasure();
        sku = d.sku();
        barcode = d.barcode();
        foodType = d.foodType();
        replace(tags, d.tags());
        replace(attributes, d.attributes());
        replace(imageUrls, d.imageUrls());
        updatedAt = now;
    }

    private static <T> void replace(List<T> target, List<T> values) {
        target.clear();
        if (values != null) {
            target.addAll(values);
        }
    }

    private void requireStatus(Set<ProductStatus> allowedFrom, ProductStatus target) {
        if (!allowedFrom.contains(status)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION",
                    "Cannot move product from " + status + " to " + target);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getBrandId() {
        return brandId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getUnitValue() {
        return unitValue;
    }

    public UnitOfMeasure getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public String getSku() {
        return sku;
    }

    public String getBarcode() {
        return barcode;
    }

    public FoodType getFoodType() {
        return foodType;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public long getProductVersion() {
        return productVersion;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<String> getImageUrls() {
        return List.copyOf(imageUrls);
    }

    public List<ProductAttribute> getAttributes() {
        return List.copyOf(attributes);
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }
}
