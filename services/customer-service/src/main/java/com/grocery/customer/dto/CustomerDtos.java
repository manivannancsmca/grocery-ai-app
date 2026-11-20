package com.grocery.customer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Customer API bodies (docs/api/phase-2-identity-and-stakeholders.md §4). */
public final class CustomerDtos {

    public static final String PHONE = "^\\+?[0-9]{10,15}$";
    public static final String PIN = "^[1-9][0-9]{5}$";

    private CustomerDtos() {
    }

    /** PUT /api/customers/me. A missing phone clears it (PUT replaces). */
    public record ProfileUpdateRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = PHONE, message = "must be 10-15 digits, optionally starting with +") String phone) {
    }

    /** id = the user id (customerId = userId). */
    public record CustomerProfile(UUID id, String email, String fullName, String phone, Instant createdAt,
                                  Instant updatedAt) {
    }

    /**
     * Create / replace an address. {@code country} defaults to IN. {@code isDefault: true} makes it the
     * default; false/omitted never removes the default flag (there is always exactly one default).
     */
    public record AddressRequest(
            @NotBlank @Size(max = 50) String label,
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Pattern(regexp = PHONE, message = "must be 10-15 digits, optionally starting with +") String phone,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @Size(max = 200) String landmark,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @NotBlank @Pattern(regexp = PIN, message = "must be a 6-digit PIN code") String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code") String country,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            Boolean isDefault) {
    }

    public record AddressView(UUID id, String label, String recipientName, String phone, String line1, String line2,
                              String landmark, String city, String state, String postalCode, String country,
                              Double latitude, Double longitude, boolean isDefault, Instant createdAt,
                              Instant updatedAt) {
    }
}
