package com.grocery.vendor.entity;

import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A vendor (grocery seller) and its approval lifecycle. All status rules live here; services only
 * load, call a method, save, record history and publish the event.
 */
@Entity
@Table(name = "vendor")
public class Vendor {

    @Id
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, unique = true)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VendorStatus status;

    @Column(name = "business_name", length = 150)
    private String businessName;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(length = 15)
    private String gstin;

    @Column(name = "fssai_license", length = 14)
    private String fssaiLicense;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Embedded
    private BusinessAddress address;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Vendor() {
    }

    /** New vendor profile created when a user registers with the VENDOR role. */
    public static Vendor draft(UUID id, UUID ownerUserId, String businessName, String contactEmail,
                               String contactPhone, Instant now) {
        Vendor vendor = new Vendor();
        vendor.id = id;
        vendor.ownerUserId = ownerUserId;
        vendor.status = VendorStatus.DRAFT;
        vendor.businessName = businessName;
        vendor.contactEmail = contactEmail;
        vendor.contactPhone = contactPhone;
        vendor.createdAt = now;
        vendor.updatedAt = now;
        return vendor;
    }

    /** Replaces the editable details. Allowed only before approval (DRAFT) or after a rejection. */
    public void updateDetails(String businessName, String legalName, String gstin, String fssaiLicense,
                              String contactEmail, String contactPhone, BusinessAddress address, Instant now) {
        if (status != VendorStatus.DRAFT && status != VendorStatus.REJECTED) {
            throw new ConflictException("PROFILE_LOCKED",
                    "The vendor profile can only be edited while in DRAFT or REJECTED status (current: " + status + ")");
        }
        this.businessName = businessName;
        this.legalName = legalName;
        this.gstin = gstin;
        this.fssaiLicense = fssaiLicense;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.address = address;
        this.updatedAt = now;
    }

    public VendorStatus submit(Instant now) {
        List<Map<String, String>> missing = missingForSubmission();
        if (!missing.isEmpty()) {
            requireStatus(EnumSet.of(VendorStatus.DRAFT, VendorStatus.REJECTED), VendorStatus.PENDING_APPROVAL);
            throw new BusinessRuleException("PROFILE_INCOMPLETE", "Complete the required fields before submitting.", missing);
        }
        VendorStatus from = moveTo(VendorStatus.PENDING_APPROVAL, EnumSet.of(VendorStatus.DRAFT, VendorStatus.REJECTED), null, now);
        submittedAt = now;
        return from;
    }

    public VendorStatus approve(Instant now) {
        VendorStatus from = moveTo(VendorStatus.APPROVED, EnumSet.of(VendorStatus.PENDING_APPROVAL), null, now);
        approvedAt = now;
        return from;
    }

    public VendorStatus reject(String reason, Instant now) {
        return moveTo(VendorStatus.REJECTED, EnumSet.of(VendorStatus.PENDING_APPROVAL), reason, now);
    }

    public VendorStatus suspend(String reason, Instant now) {
        return moveTo(VendorStatus.SUSPENDED, EnumSet.of(VendorStatus.APPROVED), reason, now);
    }

    public VendorStatus reinstate(Instant now) {
        return moveTo(VendorStatus.APPROVED, EnumSet.of(VendorStatus.SUSPENDED), null, now);
    }

    /** Field-level problems that block submission, in the same shape as validation errors. */
    public List<Map<String, String>> missingForSubmission() {
        List<Map<String, String>> missing = new ArrayList<>();
        requireText(missing, "businessName", businessName);
        requireText(missing, "legalName", legalName);
        requireText(missing, "gstin", gstin);
        requireText(missing, "contactEmail", contactEmail);
        requireText(missing, "contactPhone", contactPhone);
        if (address == null) {
            missing.add(Map.of("field", "address", "message", "is required"));
        } else {
            requireText(missing, "address.line1", address.getLine1());
            requireText(missing, "address.city", address.getCity());
            requireText(missing, "address.state", address.getState());
            requireText(missing, "address.postalCode", address.getPostalCode());
            if (address.getLatitude() == null || address.getLongitude() == null) {
                missing.add(Map.of("field", "address.latitude", "message", "store location (latitude/longitude) is required"));
            }
        }
        return missing;
    }

    private VendorStatus moveTo(VendorStatus target, Set<VendorStatus> allowedFrom, String reason, Instant now) {
        requireStatus(allowedFrom, target);
        VendorStatus from = status;
        status = target;
        statusReason = reason;
        updatedAt = now;
        return from;
    }

    private void requireStatus(Set<VendorStatus> allowedFrom, VendorStatus target) {
        if (!allowedFrom.contains(status)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION",
                    "Cannot move vendor from " + status + " to " + target);
        }
    }

    private static void requireText(List<Map<String, String>> missing, String field, String value) {
        if (value == null || value.isBlank()) {
            missing.add(Map.of("field", field, "message", "is required"));
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public VendorStatus getStatus() {
        return status;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getGstin() {
        return gstin;
    }

    public String getFssaiLicense() {
        return fssaiLicense;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public BusinessAddress getAddress() {
        return address;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
