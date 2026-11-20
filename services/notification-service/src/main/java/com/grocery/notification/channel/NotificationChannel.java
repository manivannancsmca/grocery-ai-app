package com.grocery.notification.channel;

import com.grocery.notification.dispatch.NotificationDraft;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A channel besides the in-app inbox (Phase 12 §5: email; SMS and push later). Every stored notification is offered to
 * every channel <b>inside the event's dispatch transaction</b>, right after the notification row was inserted: a
 * channel only records what it will deliver (an outbox row), it never talks to the outside world here, so a channel
 * that is down can never block or roll back the in-app notification. Its own job delivers later.
 */
public interface NotificationChannel {

    /**
     * @param notificationId the stored notification (its id is the in-app / SSE id)
     * @param draft          type, title, body, link and data of the notification
     * @param audiences      its audience keys ({@code user:<id>}, {@code role:<R>}); never {@code public}
     * @param createdAt      when it was stored
     */
    void enqueue(UUID notificationId, NotificationDraft draft, Set<String> audiences, Instant createdAt);

    /**
     * A message for one person at the address the event names, outside the inbox: the security emails of password
     * management (a reset link, "your password was changed"). Delivered whatever the person's preferences, even when
     * the address projection does not know them. A channel that cannot deliver such a message ignores it.
     *
     * @param notificationId the stored notification row it belongs to (it has no audience, so no inbox shows it)
     * @param userId         the person it is about
     * @param address        where to send it (from the event)
     * @param name           the person's name, or null
     */
    default void enqueueDirect(UUID notificationId, NotificationDraft draft, UUID userId, String address, String name,
                               Instant createdAt) {
    }
}
