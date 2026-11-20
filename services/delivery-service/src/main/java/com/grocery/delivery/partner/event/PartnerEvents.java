package com.grocery.delivery.partner.event;

import java.util.UUID;

/**
 * Partner lifecycle events PUBLISHED on {@code delivery.events} (v1). Every lifecycle event uses the
 * same payload; {@code partnerId} is the partner's user id; {@code reason} and {@code actorUserId}
 * are null when not applicable. Consumers: notification-service (Phase 7). Delivery events
 * (DeliveryCreated, DeliveryPartnerAssigned, …) join this topic in Phase 9.
 */
public final class PartnerEvents {

    public static final String TOPIC = "delivery.events";
    public static final String AGGREGATE = "DeliveryPartner";

    public static final String REGISTERED = "DeliveryPartnerRegistered";
    public static final String SUBMITTED = "DeliveryPartnerSubmitted";
    public static final String APPROVED = "DeliveryPartnerApproved";
    public static final String REJECTED = "DeliveryPartnerRejected";
    public static final String SUSPENDED = "DeliveryPartnerSuspended";
    public static final String REINSTATED = "DeliveryPartnerReinstated";

    private PartnerEvents() {
    }

    public record PartnerLifecycleEvent(UUID partnerId, String fullName, String reason, UUID actorUserId) {
    }
}
