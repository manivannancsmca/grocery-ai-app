package com.grocery.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response bodies of the identity API (see docs/api/phase-2-identity-and-stakeholders.md). */
public final class AuthDtos {

    private static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).+$";
    private static final String PHONE_RULE = "^\\+?[0-9]{10,15}$";

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 64)
            @Pattern(regexp = PASSWORD_RULE, message = "must contain at least one letter and one digit") String password,
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = PHONE_RULE, message = "must be 10-15 digits, optionally starting with +") String phone,
            @NotBlank @Pattern(regexp = "CUSTOMER|VENDOR|RESTAURANT_OWNER|DELIVERY_PARTNER",
                    message = "must be CUSTOMER, VENDOR, RESTAURANT_OWNER or DELIVERY_PARTNER") String role) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    /** POST /api/auth/password/change: the new password follows the registration rule. */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 64)
            @Pattern(regexp = PASSWORD_RULE, message = "must contain at least one letter and one digit") String newPassword) {
    }

    /** POST /api/auth/password/forgot. */
    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    /** POST /api/auth/password/reset: the token from the emailed link and the new password (registration rule). */
    public record ResetPasswordRequest(
            @NotBlank @Size(max = 256) String token,
            @NotBlank @Size(min = 8, max = 64)
            @Pattern(regexp = PASSWORD_RULE, message = "must contain at least one letter and one digit") String newPassword) {
    }

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserView user) {
    }

    public record BusinessView(String type, UUID businessId, String status) {
    }

    public record UserView(UUID id, String email, String fullName, String phone, String status,
                           List<String> roles, UUID vendorId, UUID restaurantId, List<BusinessView> businesses) {
    }

    public record AdminUserView(UUID id, String email, String fullName, String phone, List<String> roles,
                                String status, String suspensionReason, Instant createdAt, Instant lastLoginAt) {
    }

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CreateAdminRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(min = 8, max = 64)
            @Pattern(regexp = PASSWORD_RULE, message = "must contain at least one letter and one digit") String password,
            @Pattern(regexp = PHONE_RULE, message = "must be 10-15 digits, optionally starting with +") String phone,
            @NotEmpty Set<@Pattern(regexp = "SUPER_ADMIN|OPS_ADMIN|FINANCE_ADMIN|SUPPORT|DELIVERY_MANAGER",
                    message = "must be an admin role") String> roles) {
    }
}
