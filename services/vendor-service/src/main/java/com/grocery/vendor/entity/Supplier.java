package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One of a vendor's suppliers (Phase 12 §4). Deleting it only deactivates it; an inactive supplier cannot be named by a
 * new or edited purchase order, and a supplier with open purchase orders cannot be deactivated (checked by
 * {@code SupplierService} under the supplier's row lock).
 */
@Entity
@Table(name = "supplier")
public class Supplier {

    /** The editable fields, already trimmed (blank → null). */
    public record Details(String name, String contactName, String phone, String email, String gstin, String address,
                          Integer leadTimeDays, String notes) {
    }

    @Id
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(length = 20)
    private String phone;

    @Column(length = 254)
    private String email;

    @Column(length = 15)
    private String gstin;

    @Column(length = 300)
    private String address;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Supplier() {
    }

    public static Supplier create(UUID id, UUID vendorId, Details details, boolean active, Instant now) {
        Supplier supplier = new Supplier();
        supplier.id = Objects.requireNonNull(id, "id");
        supplier.vendorId = Objects.requireNonNull(vendorId, "vendorId");
        supplier.createdAt = now;
        supplier.update(details, active, now);
        return supplier;
    }

    public void update(Details details, boolean active, Instant now) {
        this.name = Objects.requireNonNull(details.name(), "name");
        this.contactName = details.contactName();
        this.phone = details.phone();
        this.email = details.email();
        this.gstin = details.gstin();
        this.address = details.address();
        this.leadTimeDays = details.leadTimeDays();
        this.notes = details.notes();
        this.active = active;
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    public boolean isOwnedBy(UUID vendor) {
        return vendorId.equals(vendor);
    }

    public UUID getId() {
        return id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getName() {
        return name;
    }

    public String getContactName() {
        return contactName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getGstin() {
        return gstin;
    }

    public String getAddress() {
        return address;
    }

    public Integer getLeadTimeDays() {
        return leadTimeDays;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
