package com.grocery.notification.email;

import com.grocery.common.id.Ids;
import com.grocery.notification.channel.NotificationChannel;
import com.grocery.notification.dispatch.NotificationDraft;
import com.grocery.notification.repository.EmailAddressRepository;
import com.grocery.notification.repository.EmailAddressRepository.Recipient;
import com.grocery.notification.repository.EmailOutboxRepository;
import com.grocery.notification.repository.EmailOutboxRepository.NewEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The email channel's half inside the dispatch transaction (Phase 12 §5): for a notification of an emailed type
 * ({@code grocery.notification.email.types}, only while {@code enabled}), one {@code email_outbox} row per recipient —
 * written in the SAME transaction as the notification, so the email exists exactly when the notification does.
 * <ul>
 *   <li>{@code user:<id>} audiences → that user; {@code role:<R>} audiences → every active user known to hold R (a
 *       user holding two of the roles gets one email);</li>
 *   <li>only users the address projection knows ({@code email_address}), that are active, and whose preference for the
 *       type's category ({@link EmailCategory}) is on;</li>
 *   <li>idempotent per notification and recipient (unique key; a second insert changes nothing).</li>
 * </ul>
 * Nothing is sent here: the {@link EmailSender} job does that, so SMTP being down never touches in-app delivery.
 */
@Component
public class EmailOutbox implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailOutbox.class);

    private final EmailProperties properties;
    private final EmailAddressRepository addresses;
    private final EmailOutboxRepository outbox;

    public EmailOutbox(EmailProperties properties, EmailAddressRepository addresses, EmailOutboxRepository outbox) {
        this.properties = properties;
        this.addresses = addresses;
        this.outbox = outbox;
    }

    @Override
    public void enqueue(UUID notificationId, NotificationDraft draft, Set<String> audiences, Instant createdAt) {
        if (!properties.emails(draft.type())) {
            return;
        }
        Set<UUID> users = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        for (String audience : audiences) {
            if (audience.startsWith("user:")) {
                UUID user = uuid(audience.substring("user:".length()));
                if (user != null) {
                    users.add(user);
                }
            } else if (audience.startsWith("role:")) {
                roles.add(audience.substring("role:".length()));
            }
        }
        EmailCategory category = EmailCategory.of(draft.type());
        int queued = 0;
        for (Recipient recipient : addresses.recipients(users, roles, category)) {
            if (outbox.insert(new NewEmail(Ids.newId(), notificationId, recipient.userId(), recipient.email(),
                    recipient.fullName(), draft.type().name(), draft.title(), draft.body(), draft.link(),
                    createdAt))) {
                queued++;
            }
        }
        log.debug("{} {}: {} emails queued for {}", draft.type(), notificationId, queued, audiences);
    }

    /**
     * A security email ({@link EmailProperties#SECURITY_TYPES}): to the address the event names, whatever the type list,
     * the preferences and the address projection say (an admin nobody has seen yet must still get their reset link).
     * Only the channel switch ({@code enabled}) stops it.
     */
    @Override
    public void enqueueDirect(UUID notificationId, NotificationDraft draft, UUID userId, String address, String name,
                              Instant createdAt) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!EmailAddressRepository.usable(address)) {
            log.warn("{} {} of user {}: no usable address, not emailed", draft.type(), notificationId, userId);
            return;
        }
        outbox.insert(new NewEmail(Ids.newId(), notificationId, userId, address.strip(), name, draft.type().name(),
                draft.title(), draft.body(), draft.link(), createdAt));
        log.debug("{} {}: security email queued for user {}", draft.type(), notificationId, userId);
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
