package com.grocery.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token (only its SHA-256 hash is stored). Tokens of one login session share a
 * {@code familyId}; rotation revokes the old token and links it to its replacement.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    public static final String ROTATED = "ROTATED";
    public static final String LOGOUT = "LOGOUT";
    public static final String REUSE_DETECTED = "REUSE_DETECTED";
    public static final String USER_SUSPENDED = "USER_SUSPENDED";
    /** The other sessions of an account whose owner changed its password. */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    /** Every session of an account whose password was reset with an emailed link. */
    public static final String PASSWORD_RESET = "PASSWORD_RESET";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 30)
    private String revokeReason;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    protected RefreshToken() {
    }

    public RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Rotated moments ago: a concurrent refresh from another tab, not a stolen token. */
    public boolean wasRotatedWithin(Duration grace, Instant now) {
        return ROTATED.equals(revokeReason) && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    public void rotateTo(UUID replacementId, Instant now) {
        revokedAt = now;
        revokeReason = ROTATED;
        replacedById = replacementId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
