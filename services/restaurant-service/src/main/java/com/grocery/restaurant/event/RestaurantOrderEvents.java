package com.grocery.restaurant.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Store order events PUBLISHED on {@code restaurant.events} (v1, Phase 8 contract §5): aggregate
 * {@code RestaurantOrder}, key orderId. The payloads are exactly the central schemas
 * {@code avro/restaurant.events/RestaurantOrder*.avsc} (grocery-event-schemas 1.1.0). Consumers: order-service
 * ({@code order-service.restaurant-events}, ignores Received), notification-service. The other consumers of the topic
 * (identity, catalog-query) ignore these types.
 */
public final class RestaurantOrderEvents {

    public static final String TOPIC = RestaurantEvents.TOPIC;
    public static final String AGGREGATE = "RestaurantOrder";

    public static final String RECEIVED = "RestaurantOrderReceived";
    public static final String ACCEPTED = "RestaurantOrderAccepted";
    public static final String REJECTED = "RestaurantOrderRejected";
    public static final String PREPARING = "RestaurantOrderPreparing";
    public static final String READY_FOR_PICKUP = "RestaurantOrderReadyForPickup";

    private RestaurantOrderEvents() {
    }

    /** A paid order is on the board, waiting for acceptance until {@code acceptBy}; {@code itemCount} = units. */
    public record RestaurantOrderReceived(UUID orderId, String orderNumber, UUID restaurantId, UUID ownerUserId,
                                          int itemCount, BigDecimal total, Instant acceptBy, Instant receivedAt) {
    }

    /** {@code readyInMinutes} = the kitchen's preparation time. */
    public record RestaurantOrderAccepted(UUID orderId, String orderNumber, UUID restaurantId, int readyInMinutes,
                                          Instant readyBy, Instant acceptedAt, UUID actorUserId) {
    }

    /** {@code reason} OUT_OF_STOCK / STORE_CLOSED / TOO_BUSY / OTHER, or NO_RESPONSE with {@code auto} (no actor). */
    public record RestaurantOrderRejected(UUID orderId, String orderNumber, UUID restaurantId, String reason,
                                          String note, boolean auto, Instant rejectedAt, UUID actorUserId) {
    }

    public record RestaurantOrderPreparing(UUID orderId, String orderNumber, UUID restaurantId, Instant preparingAt,
                                           UUID actorUserId) {
    }

    public record RestaurantOrderReadyForPickup(UUID orderId, String orderNumber, UUID restaurantId, Instant readyAt,
                                                UUID actorUserId) {
    }
}
