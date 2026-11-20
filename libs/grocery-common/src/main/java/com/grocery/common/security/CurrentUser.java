package com.grocery.common.security;

import com.grocery.common.web.ForbiddenOperationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, read from the validated JWT. Declare it as a controller method parameter:
 * <pre>{@code @GetMapping("/me") VendorView me(CurrentUser user) { ... } }</pre>
 * Business ownership checks use {@link #userId()} and the stakeholder IDs, never request parameters.
 */
public record CurrentUser(UUID userId, String email, String name, Set<String> roles,
                          UUID vendorId, UUID restaurantId) {

    public static CurrentUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                roles == null ? Set.of() : Set.copyOf(roles),
                uuidClaim(jwt, "vendor_id"),
                uuidClaim(jwt, "restaurant_id"));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return roles.stream().anyMatch(Roles.ADMIN_ROLES::contains);
    }

    /** The caller's active vendor, or 403 if the caller is not an approved vendor. */
    public UUID requireVendorId() {
        if (vendorId == null) {
            throw new ForbiddenOperationException("VENDOR_NOT_ACTIVE", "An approved vendor account is required");
        }
        return vendorId;
    }

    /** The caller's active restaurant, or 403 if the caller is not an approved restaurant owner. */
    public UUID requireRestaurantId() {
        if (restaurantId == null) {
            throw new ForbiddenOperationException("RESTAURANT_NOT_ACTIVE", "An approved restaurant account is required");
        }
        return restaurantId;
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
