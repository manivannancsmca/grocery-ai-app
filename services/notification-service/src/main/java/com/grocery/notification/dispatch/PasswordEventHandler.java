package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.event.EventPayloads.PasswordChanged;
import com.grocery.notification.event.EventPayloads.PasswordResetRequested;
import com.grocery.notification.repository.EmailAddressRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Password management on identity.events: security emails only, never an inbox entry or a signal.
 * <ul>
 *   <li>PasswordResetRequested → the reset link ({@code /reset-password?token=…}) to the address in the event. The raw
 *       token is in the event only for this email: it is never logged, and the stored notification row keeps neither
 *       the link nor the token;</li>
 *   <li>PasswordChanged → "your password was changed — if it wasn't you, reset it" ({@code /forgot-password}).</li>
 * </ul>
 * Both go to the address the event names, whatever the person's preferences, even if the address projection does not
 * know them (an admin who never opened the app). Like every notification, an event older than max-event-age sends
 * nothing (a replay must not email old links again); a reset link that has already expired is not sent either.
 */
@Component
public class PasswordEventHandler implements EventHandler {

    static final String IDENTITY_TOPIC = "identity.events";
    static final String RESET_REQUESTED = "PasswordResetRequested";
    static final String CHANGED = "PasswordChanged";
    private static final Logger log = LoggerFactory.getLogger(PasswordEventHandler.class);

    private final JsonMapper json;

    public PasswordEventHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return IDENTITY_TOPIC.equals(topic) && (RESET_REQUESTED.equals(eventType) || CHANGED.equals(eventType));
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        if (!plan.fresh()) {
            return;   // no projection to keep: an old event does nothing
        }
        if (RESET_REQUESTED.equals(event.eventType())) {
            PasswordResetRequested reset = event.payloadAs(json, PasswordResetRequested.class);
            UUID userId = Payloads.require(event, reset.userId(), "userId");
            String token = Payloads.require(event, reset.token(), "token");
            Instant expiresAt = Payloads.require(event, reset.expiresAt(), "expiresAt");
            if (!expiresAt.isAfter(plan.now())) {
                log.info("{} {} of user {}: the link has already expired, not emailed", event.eventType(),
                        event.eventId(), userId);
                return;
            }
            if (usable(event, userId, reset.email())) {
                plan.emailOnly(NotificationTemplates.passwordResetRequested(reset.fullName(), reset.email(), token,
                        expiresAt, plan.now()), userId, reset.email(), reset.fullName());
            }
        } else {
            PasswordChanged changed = event.payloadAs(json, PasswordChanged.class);
            UUID userId = Payloads.require(event, changed.userId(), "userId");
            if (usable(event, userId, changed.email())) {
                plan.emailOnly(NotificationTemplates.passwordChanged(changed.fullName(), changed.email(),
                        changed.method(), changed.changedAt()), userId, changed.email(), changed.fullName());
            }
        }
    }

    /** An address SMTP cannot take is logged and skipped, never dead-lettered (nothing would ever fix it). */
    private static boolean usable(EventEnvelope event, UUID userId, String email) {
        if (EmailAddressRepository.usable(email)) {
            return true;
        }
        log.warn("{} {} of user {} has no usable email address: not emailed", event.eventType(), event.eventId(),
                userId);
        return false;
    }
}
