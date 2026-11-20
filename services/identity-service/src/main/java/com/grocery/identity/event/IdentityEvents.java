package com.grocery.identity.event;

import java.time.Instant;
import java.util.UUID;

/** Payloads PUBLISHED by identity on {@code identity.events} (v1). Consumers keep their own copies. */
public final class IdentityEvents {

    public static final String TOPIC = "identity.events";
    public static final String AGGREGATE = "User";
    /** Aggregate of {@link SecurityEventRecorded}: one auth_audit row, keyed by its id. */
    public static final String SECURITY_EVENT_AGGREGATE = "SecurityEvent";
    public static final String SECURITY_EVENT_RECORDED = "SecurityEventRecorded";

    private IdentityEvents() {
    }

    public record UserRegistered(UUID userId, String email, String fullName, String phone, String role,
                                 Instant registeredAt) {
    }

    public record UserSuspended(UUID userId, String reason, UUID suspendedBy) {
    }

    public record UserReactivated(UUID userId, UUID reactivatedBy) {
    }

    /**
     * Carries the raw reset token: notification-service needs it to email the link. The link works once, for 30
     * minutes, and identity_db keeps only the token's hash.
     */
    public record PasswordResetRequested(UUID userId, String email, String fullName, String token,
                                         Instant expiresAt) {
    }

    /** {@code method}: CHANGE (signed in) or RESET (emailed link). */
    public record PasswordChanged(UUID userId, String email, String fullName, String method, Instant changedAt) {
    }

    /**
     * One auth_audit row, for the central audit trail (reporting-service). {@code type} is the AuditService constant;
     * {@code actorUserId} is the admin who acted on the account, null when the account holder or nobody acted. Never
     * carries a password or token.
     */
    public record SecurityEventRecorded(UUID recordId, String type, UUID userId, String email, UUID actorUserId,
                                        String detail, String ipAddress, String userAgent, Instant occurredAt) {
    }
}
