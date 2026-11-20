package com.grocery.pricing.entity;

import com.grocery.common.web.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's copy of a catalog product, built from {@code catalog.events} → {@code ProductUpserted}.
 * Its row is also the <b>lock</b> that serialises every change of the product's price and price-change
 * requests (see {@code PriceWriter}); it exists before the first price, so there is always something to lock.
 */
@Entity
@Table(name = "product_ref")
public class ProductRef {

    /** The fields of a {@code ProductUpserted} snapshot this service keeps. */
    public record Snapshot(UUID vendorId, String vendorName, String name, String unitLabel, String sku,
                           ProductStatus status, long productVersion) {
    }

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "unit_label", length = 40)
    private String unitLabel;

    @Column(length = 40)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ProductStatus status;

    @Column(name = "product_version", nullable = false)
    private long productVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductRef() {
    }

    public static ProductRef create(UUID productId, Snapshot snapshot, Instant now) {
        ProductRef ref = new ProductRef();
        ref.productId = productId;
        ref.apply(snapshot, now);
        return ref;
    }

    /** Takes over a snapshot; the caller has checked that it is newer ({@link #isOlderThan}). */
    public void apply(Snapshot snapshot, Instant now) {
        vendorId = snapshot.vendorId();
        vendorName = snapshot.vendorName();
        name = snapshot.name();
        unitLabel = snapshot.unitLabel();
        sku = snapshot.sku();
        status = snapshot.status();
        productVersion = snapshot.productVersion();
        updatedAt = now;
    }

    public boolean isOlderThan(Snapshot snapshot) {
        return snapshot.productVersion() > productVersion;
    }

    public boolean isOwnedBy(UUID vendor) {
        return vendorId.equals(vendor);
    }

    public boolean isArchived() {
        return status == ProductStatus.ARCHIVED;
    }

    /** Archived products are read-only: every price change is refused. */
    public void requireNotArchived() {
        if (isArchived()) {
            throw new ConflictException("PRODUCT_ARCHIVED", "The product is archived; its price cannot be changed");
        }
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public String getName() {
        return name;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public String getSku() {
        return sku;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public long getProductVersion() {
        return productVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
