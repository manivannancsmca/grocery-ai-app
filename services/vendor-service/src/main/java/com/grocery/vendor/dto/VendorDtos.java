package com.grocery.vendor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Vendor API bodies (docs/api/phase-2-identity-and-stakeholders.md §5). Update fields are optional
 * while editing a draft; completeness is enforced on submit.
 */
public final class VendorDtos {

    public static final String GSTIN = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";
    public static final String FSSAI = "^[0-9]{14}$";
    public static final String PHONE = "^\\+?[0-9]{10,15}$";
    public static final String PIN = "^[1-9][0-9]{5}$";

    private VendorDtos() {
    }

    public record AddressDto(
            @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = PIN, message = "must be a 6-digit PIN code") String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code") String country,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {
    }

    public record VendorUpdateRequest(
            @Size(max = 150) String businessName,
            @Size(max = 200) String legalName,
            @Pattern(regexp = GSTIN, message = "must be a valid 15-character GSTIN") String gstin,
            @Pattern(regexp = FSSAI, message = "must be a 14-digit FSSAI licence number") String fssaiLicense,
            @Email @Size(max = 254) String contactEmail,
            @Pattern(regexp = PHONE, message = "must be 10-15 digits, optionally starting with +") String contactPhone,
            @Valid AddressDto address) {
    }

    public record VendorView(UUID id, UUID ownerUserId, String status, String businessName, String legalName,
                             String gstin, String fssaiLicense, String contactEmail, String contactPhone,
                             AddressDto address, String statusReason, Instant submittedAt, Instant approvedAt,
                             Instant createdAt, Instant updatedAt) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record StatusChangeView(String fromStatus, String toStatus, String reason, UUID changedBy,
                                   Instant changedAt) {
    }

    /**
     * GET /internal/vendors/{vendorId}/store (docs/api/phase-5-cart-and-checkout-preview.md §4): what cart-service
     * needs about a grocery store. {@code name} = business name, {@code active} = status APPROVED; city and
     * coordinates come from the business address (the pickup point) and are null while it is not set.
     */
    public record StoreView(UUID vendorId, String name, String status, boolean active, String city,
                            Double latitude, Double longitude) {
    }
}
