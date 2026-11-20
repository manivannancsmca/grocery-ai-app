package com.grocery.notification.dto;

import java.time.Instant;
import java.util.UUID;

/** Request and response bodies of the Phase 12 §5 email APIs. */
public final class EmailDtos {

    private EmailDtos() {
    }

    /** {@code GET/PUT /api/notifications/preferences}: {@code { "email": { "orders", "account", "approvals" } }}. */
    public record Preferences(EmailPreferencesView email) {
    }

    public record EmailPreferencesView(boolean orders, boolean account, boolean approvals) {
    }

    /** The PUT body: a missing category keeps its current value. */
    public record PreferencesRequest(EmailPreferencesRequest email) {
    }

    public record EmailPreferencesRequest(Boolean orders, Boolean account, Boolean approvals) {
    }

    /** One row of {@code GET /api/admin/notifications/emails}. */
    public record EmailLogEntry(UUID id, UUID notificationId, String to, String subject, String type, String status,
                                int attempts, String lastError, Instant createdAt, Instant sentAt) {
    }
}
