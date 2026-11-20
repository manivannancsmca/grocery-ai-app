package com.grocery.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's projection of a vendor, built from {@code vendor.events}. Only what product snapshots
 * need: the display name and whether the vendor is suspended.
 */
@Entity
@Table(name = "vendor_ref")
public class VendorRef {

    public static final String SUSPENDED = "SUSPENDED";

    @Id
    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VendorRef() {
    }

    public VendorRef(UUID vendorId) {
        this.vendorId = vendorId;
    }

    public void apply(UUID ownerUserId, String businessName, String status, Instant now) {
        if (ownerUserId != null) {
            this.ownerUserId = ownerUserId;
        }
        if (businessName != null && !businessName.isBlank()) {
            this.businessName = businessName;
        }
        this.status = status;
        this.updatedAt = now;
    }

    /**
     * A vendor is active unless suspended. No row at all also counts as active: the token's
     * {@code vendor_id} claim proved approval, the VendorApproved event may simply not have arrived yet.
     */
    public static boolean isActive(VendorRef ref) {
        return ref == null || !SUSPENDED.equals(ref.status);
    }

    public static String nameOf(VendorRef ref) {
        return ref == null ? null : ref.businessName;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getStatus() {
        return status;
    }
}
