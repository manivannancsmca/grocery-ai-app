package com.grocery.vendor.event;

import java.util.UUID;

/**
 * Events PUBLISHED on {@code vendor.events} (v1). Every lifecycle event uses the same payload;
 * {@code reason} and {@code actorUserId} are null when not applicable.
 * Consumers: identity-service (vendor_id claim), notification-service (Phase 7), catalog (Phase 3).
 */
public final class VendorEvents {

    public static final String TOPIC = "vendor.events";
    public static final String AGGREGATE = "Vendor";

    public static final String REGISTERED = "VendorRegistered";
    public static final String SUBMITTED = "VendorSubmitted";
    public static final String APPROVED = "VendorApproved";
    public static final String REJECTED = "VendorRejected";
    public static final String SUSPENDED = "VendorSuspended";
    public static final String REINSTATED = "VendorReinstated";

    private VendorEvents() {
    }

    public record VendorLifecycleEvent(UUID vendorId, UUID ownerUserId, String businessName,
                                       String reason, UUID actorUserId) {
    }
}
