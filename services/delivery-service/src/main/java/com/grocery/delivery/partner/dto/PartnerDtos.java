package com.grocery.delivery.partner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Delivery partner API bodies (docs/api/phase-2-identity-and-stakeholders.md §5). Update fields are
 * optional while editing a draft; completeness is enforced on submit.
 */
public final class PartnerDtos {

    public static final String PHONE = "^\\+?[0-9]{10,15}$";
    public static final String VEHICLE_TYPE = "^(BICYCLE|SCOOTER|MOTORCYCLE|CAR)$";
    /** Indian registration number after normalisation, e.g. KA01AB1234, DL3C1234, MH12A1234. */
    public static final String VEHICLE_NUMBER = "^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4}$";
    /** Driving licence number after normalisation: 10-20 letters or digits. */
    public static final String DRIVING_LICENSE = "^[A-Z0-9]{10,20}$";

    private PartnerDtos() {
    }

    /** How vehicle and licence numbers are compared and stored: no spaces or hyphens, upper case. */
    public static String normalizeCode(String value) {
        return value == null ? null : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public record PartnerUpdateRequest(
            @Size(max = 150) String fullName,
            @Pattern(regexp = PHONE, message = "must be 10-15 digits, optionally starting with +") String phone,
            @Size(max = 100) String city,
            @Pattern(regexp = VEHICLE_TYPE, message = "must be one of BICYCLE, SCOOTER, MOTORCYCLE, CAR") String vehicleType,
            @NormalizedPattern(regexp = VEHICLE_NUMBER, message = "must be a vehicle registration number such as KA01AB1234")
            String vehicleNumber,
            @NormalizedPattern(regexp = DRIVING_LICENSE, message = "must be 10-20 letters or digits")
            String drivingLicenseNumber) {
    }

    public record PartnerView(UUID id, String email, String status, String fullName, String phone, String city,
                              String vehicleType, String vehicleNumber, String drivingLicenseNumber,
                              String statusReason, Instant submittedAt, Instant approvedAt,
                              Instant createdAt, Instant updatedAt) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record StatusChangeView(String fromStatus, String toStatus, String reason, UUID changedBy,
                                   Instant changedAt) {
    }
}
