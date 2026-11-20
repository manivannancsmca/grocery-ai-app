package com.grocery.common.security;

import com.grocery.common.web.ForbiddenOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    private static Jwt.Builder token(UUID sub) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject(sub.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("email", "v@x.com").claim("name", "Vee");
    }

    @Test
    void readsIdentityRolesAndActiveVendor() {
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        CurrentUser user = CurrentUser.from(token(userId)
                .claim("roles", List.of("VENDOR")).claim("vendor_id", vendorId.toString()).build());

        assertThat(user.userId()).isEqualTo(userId);
        assertThat(user.hasRole(Roles.VENDOR)).isTrue();
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.requireVendorId()).isEqualTo(vendorId);
    }

    @Test
    void vendorWithoutActiveClaimIsForbidden() {
        CurrentUser user = CurrentUser.from(token(UUID.randomUUID()).claim("roles", List.of("VENDOR")).build());
        assertThatThrownBy(user::requireVendorId).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void anyAdminRoleCountsAsAdmin() {
        CurrentUser user = CurrentUser.from(token(UUID.randomUUID()).claim("roles", List.of("SUPPORT")).build());
        assertThat(user.isAdmin()).isTrue();
    }
}
