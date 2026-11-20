package com.grocery.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code grocery.identity.*} — token lifetimes, cookie settings, lockout policy and the bootstrap admin.
 */
@ConfigurationProperties(prefix = "grocery.identity")
public record IdentityProperties(
        @DefaultValue("https://identity.grocery.local") String issuer,
        @DefaultValue("grocery-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        /* A rotated refresh token reused within this window is treated as a concurrent refresh (two tabs),
           not as theft. */
        @DefaultValue("10s") Duration refreshReuseGrace,
        @DefaultValue RefreshCookie refreshCookie,
        @DefaultValue Lockout lockout,
        @DefaultValue BootstrapAdmin bootstrapAdmin,
        @DefaultValue DevAccounts devAccounts,
        @DefaultValue PasswordReset passwordReset) {

    public record RefreshCookie(
            @DefaultValue("grocery_rt") String name,
            @DefaultValue("/api/auth") String path,
            @DefaultValue("true") boolean secure,
            @DefaultValue("Strict") String sameSite) {
    }

    public record Lockout(
            @DefaultValue("5") int maxFailedAttempts,
            @DefaultValue("15m") Duration duration) {
    }

    /** Creates the first SUPER_ADMIN on startup when none exists (leave email empty to disable). */
    public record BootstrapAdmin(
            String email,
            String password,
            @DefaultValue("Platform Administrator") String fullName) {
    }

    /**
     * Development only: one well-known account per stakeholder, created on startup by {@code DevAccountSeeder}
     * and listed on the sign-in screen of the Vite dev server. Off unless {@code enabled} is set; never on AWS.
     */
    public record DevAccounts(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("Dev@12345") String password) {
    }

    /**
     * Forgot / reset password. A link works once, for {@code ttl}. An account gets at most {@code maxRequestsPerHour}
     * links per hour (more requests are answered the same way and do nothing). Every forgot request takes at least
     * {@code minResponseTime}, so the response time does not tell whether the address has an account.
     */
    public record PasswordReset(
            @DefaultValue("30m") Duration ttl,
            @DefaultValue("3") int maxRequestsPerHour,
            @DefaultValue("250ms") Duration minResponseTime) {
    }
}
