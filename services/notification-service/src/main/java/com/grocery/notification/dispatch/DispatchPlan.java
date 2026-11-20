package com.grocery.notification.dispatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What one event produces: persistent notifications, signals and session closes, each with its audiences, and the
 * security emails of password management, which appear in no inbox. Filled by an {@link EventHandler} inside the
 * event's transaction; stored and published by {@link com.grocery.notification.service.DispatchService}.
 *
 * <p>Identical texts are merged: the same draft for several audiences is ONE notification row with several audience
 * rows (an admin holding two of those roles sees it once), and the same signal for several audiences is one message.
 * An event older than {@code max-event-age} is not {@link #fresh()}: handlers then only update their projections.
 */
public final class DispatchPlan {

    public record PlannedNotification(NotificationDraft draft, Set<String> audiences) {
    }

    /** {@code throttleKey} is set for public signals only (at most one per key per throttle window). */
    public record PlannedSignal(String type, Map<String, Object> data, Set<String> audiences, String throttleKey) {
    }

    public record PlannedSession(String reason, Set<String> audiences) {
    }

    /**
     * An email only (a security email), to the address the event names: no inbox entry, no signal. {@code userId} is
     * the person it is about (the email row needs one).
     */
    public record PlannedEmail(NotificationDraft draft, UUID userId, String email, String fullName) {
    }

    private final boolean fresh;
    private final Instant now;
    private final Instant occurredAt;
    private final List<PlannedNotification> notifications = new ArrayList<>();
    private final List<PlannedSignal> signals = new ArrayList<>();
    private final List<PlannedSession> sessions = new ArrayList<>();
    private final List<PlannedEmail> emails = new ArrayList<>();
    private final Set<UUID> knownOrders = new LinkedHashSet<>();
    private boolean parked;

    public DispatchPlan(boolean fresh, Instant now) {
        this(fresh, now, null);
    }

    /** @param occurredAt the event's own occurredAt header (null = unknown); kept when the event is parked */
    public DispatchPlan(boolean fresh, Instant now, Instant occurredAt) {
        this.fresh = fresh;
        this.now = now;
        this.occurredAt = occurredAt;
    }

    /** False for events older than {@code max-event-age}: projections only. */
    public boolean fresh() {
        return fresh;
    }

    public Instant now() {
        return now;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    /** The order's {@code order_ref} row now exists: its parked payment events are applied in this transaction. */
    public void orderKnown(UUID orderId) {
        knownOrders.add(orderId);
    }

    public Set<UUID> knownOrders() {
        return Collections.unmodifiableSet(knownOrders);
    }

    /** The event was parked until its order is known (nothing else is planned for it now). */
    public void markParked() {
        parked = true;
    }

    public boolean parked() {
        return parked;
    }

    public void notify(NotificationDraft draft, Collection<String> audiences) {
        requireFresh();
        Set<String> keys = clean(audiences);
        if (keys.isEmpty()) {
            return;
        }
        for (PlannedNotification planned : notifications) {
            if (planned.draft().equals(draft)) {
                planned.audiences().addAll(keys);
                return;
            }
        }
        notifications.add(new PlannedNotification(draft, keys));
    }

    public void signal(String type, Map<String, Object> data, Collection<String> audiences) {
        requireFresh();
        Set<String> keys = clean(audiences);
        if (keys.isEmpty()) {
            return;
        }
        for (PlannedSignal planned : signals) {
            if (planned.throttleKey() == null && planned.type().equals(type) && planned.data().equals(data)) {
                planned.audiences().addAll(keys);
                return;
            }
        }
        signals.add(new PlannedSignal(type, data, keys, null));
    }

    /** A signal to everyone connected, throttled per {@code throttleKey} (e.g. {@code STOCK_CHANGED:<productId>}). */
    public void publicSignal(String type, Map<String, Object> data, String throttleKey) {
        requireFresh();
        signals.add(new PlannedSignal(type, data, new LinkedHashSet<>(List.of(Audiences.PUBLIC)),
                Objects.requireNonNull(throttleKey)));
    }

    /** An email to {@code email} that appears in no inbox and reaches no stream (see {@link PlannedEmail}). */
    public void emailOnly(NotificationDraft draft, UUID userId, String email, String fullName) {
        requireFresh();
        emails.add(new PlannedEmail(draft, userId, email, fullName));
    }

    public void endSessions(String reason, Collection<String> audiences) {
        requireFresh();
        Set<String> keys = clean(audiences);
        if (!keys.isEmpty()) {
            sessions.add(new PlannedSession(reason, keys));
        }
    }

    public List<PlannedNotification> notifications() {
        return Collections.unmodifiableList(notifications);
    }

    public List<PlannedSignal> signals() {
        return Collections.unmodifiableList(signals);
    }

    public List<PlannedSession> sessions() {
        return Collections.unmodifiableList(sessions);
    }

    public List<PlannedEmail> emails() {
        return Collections.unmodifiableList(emails);
    }

    public boolean isEmpty() {
        return notifications.isEmpty() && signals.isEmpty() && sessions.isEmpty() && emails.isEmpty();
    }

    private void requireFresh() {
        if (!fresh) {
            throw new IllegalStateException("An event older than max-event-age must not notify anybody");
        }
    }

    private static Set<String> clean(Collection<String> audiences) {
        Set<String> keys = new LinkedHashSet<>();
        for (String audience : audiences) {
            if (audience != null && !audience.isBlank()) {
                keys.add(audience);
            }
        }
        return keys;
    }
}
