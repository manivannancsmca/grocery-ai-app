package com.grocery.identity.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.identity.entity.BusinessMembership;
import com.grocery.identity.entity.BusinessMembership.BusinessType;
import com.grocery.identity.entity.BusinessMembership.Status;
import com.grocery.identity.repository.BusinessMembershipRepository;
import com.grocery.identity.security.TokenRevocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps {@code business_membership} in step with vendor/restaurant lifecycle events, so access
 * tokens can carry {@code vendor_id}/{@code restaurant_id} for APPROVED businesses only.
 * When the approval state changes, the owner's current tokens are revoked so the next refresh
 * picks up (or drops) the claim immediately.
 */
@Service
public class BusinessMembershipService {

    public static final String CONSUMER = "identity-service.business-events";
    private static final Logger log = LoggerFactory.getLogger(BusinessMembershipService.class);

    private final BusinessMembershipRepository memberships;
    private final ProcessedEventGuard processedEvents;
    private final TokenRevocationService revocation;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public BusinessMembershipService(BusinessMembershipRepository memberships, ProcessedEventGuard processedEvents,
                                     TokenRevocationService revocation, JsonMapper jsonMapper, Clock clock) {
        this.memberships = memberships;
        this.processedEvents = processedEvents;
        this.revocation = revocation;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Only the fields identity needs from vendor and restaurant lifecycle events (tolerant reader). */
    record BusinessLifecycle(UUID vendorId, UUID restaurantId, UUID ownerUserId) {
        UUID businessId() {
            return vendorId != null ? vendorId : restaurantId;
        }
    }

    @Transactional
    public void handle(EventEnvelope event) {
        BusinessType type;
        String transition;
        if (event.eventType().startsWith("Vendor")) {
            type = BusinessType.VENDOR;
            transition = event.eventType().substring("Vendor".length());
        } else if (event.eventType().startsWith("Restaurant")) {
            type = BusinessType.RESTAURANT;
            transition = event.eventType().substring("Restaurant".length());
        } else {
            return; // not a lifecycle event we care about
        }
        Status newStatus = switch (transition) {
            case "Registered", "Submitted" -> Status.PENDING;
            case "Approved", "Reinstated" -> Status.ACTIVE;
            case "Rejected" -> Status.REJECTED;
            case "Suspended" -> Status.SUSPENDED;
            default -> null; // e.g. MenuChanged — ignore
        };
        if (newStatus == null || !processedEvents.markProcessed(event.eventId(), CONSUMER)) {
            return;
        }
        BusinessLifecycle payload = event.payloadAs(jsonMapper, BusinessLifecycle.class);
        if (payload.businessId() == null || payload.ownerUserId() == null) {
            log.warn("Ignoring {} {} without business/owner id", event.eventType(), event.eventId());
            return;
        }
        Instant now = clock.instant();
        boolean wasActive;
        BusinessMembership existing = memberships.findByBusinessTypeAndBusinessId(type, payload.businessId()).orElse(null);
        if (existing == null) {
            wasActive = false;
            memberships.save(new BusinessMembership(Ids.newId(), payload.ownerUserId(), type,
                    payload.businessId(), newStatus, now));
        } else {
            wasActive = existing.isActive();
            existing.changeStatus(newStatus, now);
        }
        boolean claimsChange = wasActive != (newStatus == Status.ACTIVE);
        if (claimsChange) {
            revocation.revokeAccessTokensIssuedBeforeNow(payload.ownerUserId());
        }
        log.info("{} {} for user {} is now {}", type, payload.businessId(), payload.ownerUserId(), newStatus);
    }
}
