package com.grocery.restaurant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Restaurant API bodies (docs/api/phase-2-identity-and-stakeholders.md §5). Update fields are optional
 * while editing a draft; completeness is enforced on submit.
 */
public final class RestaurantDtos {

    public static final String GSTIN = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$";
    public static final String FSSAI = "^[0-9]{14}$";
    public static final String PHONE = "^\\+?[0-9]{10,15}$";
    public static final String PIN = "^[1-9][0-9]{5}$";
    public static final String CUISINE = "^[A-Z_]{2,30}$";
    public static final String DAY_OF_WEEK = "^(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)$";
    /** 24-hour clock, minutes precision: 00:00 … 23:59. */
    public static final String TIME = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    private RestaurantDtos() {
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

    /** One day of the week; closesAt before opensAt = open past midnight. Times are "HH:mm". */
    public record OpeningHoursDto(
            @NotNull @Pattern(regexp = DAY_OF_WEEK, message = "must be a day name, MONDAY to SUNDAY") String dayOfWeek,
            @NotNull @Pattern(regexp = TIME, message = "must be a 24-hour time HH:mm") String opensAt,
            @NotNull @Pattern(regexp = TIME, message = "must be a 24-hour time HH:mm") String closesAt) {
    }

    public record RestaurantUpdateRequest(
            @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @Size(max = 8, message = "at most 8 cuisines")
            List<@NotNull @Pattern(regexp = CUISINE, message = "must be an uppercase token such as NORTH_INDIAN (2-30 letters or _)") String> cuisines,
            @Pattern(regexp = FSSAI, message = "must be a 14-digit FSSAI licence number") String fssaiLicense,
            @Pattern(regexp = GSTIN, message = "must be a valid 15-character GSTIN") String gstin,
            @Email @Size(max = 254) String contactEmail,
            @Pattern(regexp = PHONE, message = "must be 10-15 digits, optionally starting with +") String contactPhone,
            @Valid AddressDto address,
            @Size(max = 7, message = "at most one entry per day") @ValidOpeningHours
            List<@NotNull @Valid OpeningHoursDto> openingHours) {
    }

    /** {@code acceptingOrders} (Phase 3): the owner's "temporarily stop new orders" switch. */
    public record RestaurantView(UUID id, UUID ownerUserId, String status, String name, String description,
                                 List<String> cuisines, String fssaiLicense, String gstin, String contactEmail,
                                 String contactPhone, AddressDto address, List<OpeningHoursDto> openingHours,
                                 boolean acceptingOrders, String statusReason, Instant submittedAt,
                                 Instant approvedAt, Instant createdAt, Instant updatedAt) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record StatusChangeView(String fromStatus, String toStatus, String reason, UUID changedBy,
                                   Instant changedAt) {
    }
}
