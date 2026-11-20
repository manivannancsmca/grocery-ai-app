package com.grocery.reporting.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the central audit trail ({@code audit_entry}): who did what to what, when, from one event.
 *
 * @param id          the event's id (a redelivered event never adds a second row)
 * @param occurredAt  when it happened: the event's own time, else its {@code occurredAt} header
 * @param category    the area, for filtering: SECURITY, ACCOUNTS, STORES, PARTNERS, PRICES, STOCK, ORDERS,
 *                    DELIVERIES, PAYMENTS, REVIEWS, AI, SETTINGS
 * @param action      what happened, e.g. LOGIN_FAILURE, VENDOR_APPROVED, SETTING_CHANGED
 * @param source      the producing service, e.g. identity-service
 * @param actorId     who acted; null for the system or an unknown caller
 * @param actorRole   in what capacity: ADMIN, STORE, PARTNER, CUSTOMER, USER (the account holder or a user whose role
 *                    the event does not say), SYSTEM, ANONYMOUS (nobody signed in)
 * @param actorName   the actor's name when the event carries it
 * @param targetType  what was acted on: USER, VENDOR, RESTAURANT, PARTNER, ORDER, PRODUCT, REVIEW, APPROVAL, SETTING
 * @param targetLabel a human handle for the target: an order number, a business name, an e-mail, a setting
 * @param summary     one sentence for the list
 * @param detail      the reason, comment or old → new value; null if none
 * @param ipAddress   the client's address (sign-in and account events only)
 * @param userAgent   the client's User-Agent (sign-in and account events only)
 * @param eventType   the event it came from
 */
public record AuditEntry(UUID id, Instant occurredAt, String category, String action, String source, UUID actorId,
                         String actorRole, String actorName, String targetType, String targetId, String targetLabel,
                         String summary, String detail, String ipAddress, String userAgent, String eventType) {

    /** What an event tells about a user id: their account's e-mail or their name (for the actor column). */
    public record KnownUser(UUID userId, String email, String name, Instant seenAt) {
    }
}
