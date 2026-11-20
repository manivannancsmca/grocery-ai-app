package com.grocery.identity.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.identity.entity.UserAccount;
import com.grocery.identity.repository.UserAccountRepository;
import com.grocery.identity.security.TokenRevocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps the account's name and phone in step with the customer's profile (customer.events CustomerProfileUpdated;
 * customerId = userId). Without it the header, the JWT {@code name} claim, the admin user list and email greetings
 * would keep the registration name forever.
 *
 * <p>Idempotent ({@code processed_event}); an event older than the profile last applied changes nothing, so a replay
 * never brings back an old name. When the name changed, the user's access tokens are revoked: the client's next
 * call refreshes and gets a token with the new {@code name}.
 */
@Service
public class CustomerProfileSyncService {

    public static final String CONSUMER = "identity-service.customer-events";
    static final String PROFILE_UPDATED = "CustomerProfileUpdated";
    private static final Logger log = LoggerFactory.getLogger(CustomerProfileSyncService.class);

    private final UserAccountRepository users;
    private final ProcessedEventGuard processedEvents;
    private final TokenRevocationService revocation;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public CustomerProfileSyncService(UserAccountRepository users, ProcessedEventGuard processedEvents,
                                      TokenRevocationService revocation, JsonMapper jsonMapper, Clock clock) {
        this.users = users;
        this.processedEvents = processedEvents;
        this.revocation = revocation;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Only the fields identity needs (tolerant reader). */
    record ProfileUpdated(UUID customerId, String fullName, String phone, Instant updatedAt) {
    }

    @Transactional
    public void handle(EventEnvelope event) {
        if (!PROFILE_UPDATED.equals(event.eventType()) || !processedEvents.markProcessed(event.eventId(), CONSUMER)) {
            return;
        }
        ProfileUpdated profile = event.payloadAs(jsonMapper, ProfileUpdated.class);
        if (profile.customerId() == null || profile.fullName() == null || profile.fullName().isBlank()
                || profile.updatedAt() == null) {
            log.warn("Ignoring {} {} without customerId, fullName or updatedAt", event.eventType(), event.eventId());
            return;
        }
        UserAccount user = users.findById(profile.customerId()).orElse(null);
        if (user == null) {
            log.warn("Ignoring {} {}: no account {}", event.eventType(), event.eventId(), profile.customerId());
            return;
        }
        String oldName = user.getFullName();
        String phone = profile.phone() == null || profile.phone().isBlank() ? null : profile.phone().trim();
        if (!user.applyCustomerProfile(profile.fullName().trim(), phone, profile.updatedAt(), clock.instant())) {
            log.info("Ignoring {} {} of user {}: older than the profile already applied", event.eventType(),
                    event.eventId(), profile.customerId());
            return;
        }
        if (!Objects.equals(oldName, user.getFullName())) {
            // The name is a JWT claim: the client's next call gets 401 TOKEN_REVOKED, refreshes, and shows it.
            revocation.revokeAccessTokensIssuedBeforeNow(user.getId());
        }
        log.info("Profile of user {} applied (updated {})", user.getId(), profile.updatedAt());
    }
}
