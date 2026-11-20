package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's copy of a catalog product (Phase 12 §4), built from {@code catalog.events → ProductUpserted} the way
 * pricing- and inventory-service build theirs: only written by {@code ProductProjectionService}, and only with a newer
 * {@code productVersion} than the stored one. Purchase order lines may name only the caller's own, non-archived
 * products.
 */
@Entity
@Table(name = "product_ref")
public class ProductRef {

    /** The fields of a {@code ProductUpserted} snapshot this service keeps. */
    public record Snapshot(UUID productId, UUID vendorId, String name, String unitLabel, String sku,
                           ProductStatus status, long productVersion) {
    }

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

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

    public static ProductRef from(Snapshot snapshot, Instant now) {
        ProductRef ref = new ProductRef();
        ref.productId = snapshot.productId();
        ref.apply(snapshot, now);
        return ref;
    }

    /** Applies a snapshot if it is newer than the stored copy; returns false (nothing changed) for stale ones. */
    public boolean applyIfNewer(Snapshot snapshot, Instant now) {
        if (snapshot.productVersion() <= productVersion) {
            return false;
        }
        apply(snapshot, now);
        return true;
    }

    private void apply(Snapshot snapshot, Instant now) {
        this.vendorId = snapshot.vendorId();
        this.name = snapshot.name();
        this.unitLabel = snapshot.unitLabel();
        this.sku = snapshot.sku();
        this.status = snapshot.status();
        this.productVersion = snapshot.productVersion();
        this.updatedAt = now;
    }

    /** The vendor may order it: its own product and not archived. */
    public boolean isOrderableBy(UUID vendor) {
        return vendorId.equals(vendor) && status != ProductStatus.ARCHIVED;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVendorId() {
        return vendorId;
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
}
