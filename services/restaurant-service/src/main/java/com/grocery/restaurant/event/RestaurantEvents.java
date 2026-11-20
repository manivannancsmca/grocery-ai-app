package com.grocery.restaurant.event;

import java.util.UUID;

/**
 * Events PUBLISHED on {@code restaurant.events} (v1). Every lifecycle event uses the same payload;
 * {@code reason} and {@code actorUserId} are null when not applicable.
 * Consumers: identity-service (restaurant_id claim — reads {@code restaurantId} and {@code ownerUserId}),
 * notification-service (Phase 7). Phase 3 adds {@link #LISTING_UPDATED} (payload
 * {@link RestaurantListingUpdated}) for catalog-query; identity ignores it.
 */
public final class RestaurantEvents {

    public static final String TOPIC = "restaurant.events";
    public static final String AGGREGATE = "Restaurant";

    public static final String REGISTERED = "RestaurantRegistered";
    public static final String SUBMITTED = "RestaurantSubmitted";
    public static final String APPROVED = "RestaurantApproved";
    public static final String REJECTED = "RestaurantRejected";
    public static final String SUSPENDED = "RestaurantSuspended";
    public static final String REINSTATED = "RestaurantReinstated";
    /** Complete public listing incl. menu; see service.RestaurantListingPublisher. */
    public static final String LISTING_UPDATED = "RestaurantListingUpdated";

    private RestaurantEvents() {
    }

    public record RestaurantLifecycleEvent(UUID restaurantId, UUID ownerUserId, String name,
                                           String reason, UUID actorUserId) {
    }
}
