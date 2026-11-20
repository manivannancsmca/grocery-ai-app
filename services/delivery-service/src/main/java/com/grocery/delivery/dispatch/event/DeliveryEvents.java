package com.grocery.delivery.dispatch.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Delivery events PUBLISHED by dispatch (contract §6). Payloads exactly as the central Avro schemas of
 * {@code libs/grocery-event-schemas} 1.2.0 ({@code avro/delivery.events/Delivery*.avsc},
 * {@code avro/delivery.location/DeliveryLocationUpdated.avsc}): same field names, same order, nullable exactly where
 * the schema allows null — the outbox converts them strictly, so a mismatch fails the business transaction.
 *
 * <p>{@code delivery.events}: through the outbox, aggregate {@value #AGGREGATE}, key = orderId (next to the partner
 * lifecycle events, which stay keyed by partnerId). {@code delivery.location}: published directly (best effort).
 */
public final class DeliveryEvents {

    public static final String TOPIC = "delivery.events";
    public static final String LOCATION_TOPIC = "delivery.location";
    public static final String AGGREGATE = "Delivery";

    public static final String OFFER_CREATED = "DeliveryOfferCreated";
    public static final String OFFER_CLOSED = "DeliveryOfferClosed";
    public static final String PARTNER_ASSIGNED = "DeliveryPartnerAssigned";
    public static final String PARTNER_UNASSIGNED = "DeliveryPartnerUnassigned";
    public static final String PICKED_UP = "DeliveryPickedUp";
    public static final String COMPLETED = "DeliveryCompleted";
    public static final String FAILED = "DeliveryFailed";
    public static final String ASSIGNMENT_FAILED = "DeliveryAssignmentFailed";
    public static final String CANCELLED = "DeliveryCancelled";
    public static final String LOCATION_UPDATED = "DeliveryLocationUpdated";

    private DeliveryEvents() {
    }

    public record DeliveryOfferCreated(UUID deliveryId, UUID orderId, String orderNumber, UUID offerId, UUID partnerId,
                                       String storeName, double distanceKm, Double tripKm, Instant expiresAt) {
    }

    /** {@code outcome}: EXPIRED, REJECTED or WITHDRAWN. */
    public record DeliveryOfferClosed(UUID deliveryId, UUID orderId, String orderNumber, UUID offerId, UUID partnerId,
                                      String outcome, Instant closedAt) {
    }

    public record DeliveryPartnerAssigned(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId,
                                          String partnerName, String vehicleType, boolean manual, Instant assignedAt) {
    }

    public record DeliveryPartnerUnassigned(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId,
                                            String reason, UUID actorUserId, Instant unassignedAt) {
    }

    public record DeliveryPickedUp(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId,
                                   Instant pickedUpAt) {
    }

    public record DeliveryCompleted(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId,
                                    Instant deliveredAt) {
    }

    public record DeliveryFailed(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId, String reason,
                                 String note, Instant failedAt) {
    }

    public record DeliveryAssignmentFailed(UUID deliveryId, UUID orderId, String orderNumber, int attempts,
                                           double radiusKm, Instant failedAt) {
    }

    public record DeliveryCancelled(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId,
                                    Instant cancelledAt) {
    }

    /** {@code delivery.location}, key orderId. */
    public record DeliveryLocationUpdated(UUID deliveryId, UUID orderId, UUID partnerId, double latitude,
                                          double longitude, Double heading, Double speedKmh, Double accuracyMeters,
                                          Instant recordedAt) {
    }
}
