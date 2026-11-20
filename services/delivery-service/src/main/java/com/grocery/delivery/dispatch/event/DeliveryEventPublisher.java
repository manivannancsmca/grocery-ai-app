package com.grocery.delivery.dispatch.event;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryAssignmentFailed;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryCancelled;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryCompleted;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryFailed;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryOfferClosed;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryOfferCreated;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryPartnerAssigned;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryPartnerUnassigned;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryPickedUp;
import com.grocery.delivery.dispatch.geo.Distances;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the delivery events to the outbox in the caller's transaction (topic {@code delivery.events}, aggregate
 * {@code Delivery}, key = orderId, so every event of one order stays in order). Distances are rounded to 0.1 km, as
 * the views show them.
 */
@Component
public class DeliveryEventPublisher {

    private final OutboxPublisher outbox;

    public DeliveryEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void offerCreated(Delivery d, DeliveryOffer offer) {
        Double tripKm = Distances.roundKm(Distances.haversineKmOrNull(d.pickupPoint(), d.dropPoint()));
        publish(d, DeliveryEvents.OFFER_CREATED, new DeliveryOfferCreated(d.getId(), d.getOrderId(),
                d.getOrderNumber(), offer.getId(), offer.getPartnerId(), d.getStoreName(),
                Distances.roundKm(offer.getDistanceKm()), tripKm, offer.getExpiresAt()));
    }

    public void offerClosed(Delivery d, DeliveryOffer offer) {
        publish(d, DeliveryEvents.OFFER_CLOSED, new DeliveryOfferClosed(d.getId(), d.getOrderId(), d.getOrderNumber(),
                offer.getId(), offer.getPartnerId(), offer.getStatus().name(), offer.getClosedAt()));
    }

    public void partnerAssigned(Delivery d, DeliveryPartner partner, boolean manual) {
        publish(d, DeliveryEvents.PARTNER_ASSIGNED, new DeliveryPartnerAssigned(d.getId(), d.getOrderId(),
                d.getOrderNumber(), partner.getId(), partner.getFullName(),
                partner.getVehicleType() == null ? null : partner.getVehicleType().name(), manual, d.getAssignedAt()));
    }

    public void partnerUnassigned(Delivery d, UUID partnerId, String reason, UUID actorUserId, Instant at) {
        publish(d, DeliveryEvents.PARTNER_UNASSIGNED, new DeliveryPartnerUnassigned(d.getId(), d.getOrderId(),
                d.getOrderNumber(), partnerId, reason, actorUserId, at));
    }

    public void pickedUp(Delivery d) {
        publish(d, DeliveryEvents.PICKED_UP, new DeliveryPickedUp(d.getId(), d.getOrderId(), d.getOrderNumber(),
                d.getPartnerId(), d.getPickedUpAt()));
    }

    public void completed(Delivery d) {
        publish(d, DeliveryEvents.COMPLETED, new DeliveryCompleted(d.getId(), d.getOrderId(), d.getOrderNumber(),
                d.getPartnerId(), d.getDeliveredAt()));
    }

    public void failed(Delivery d) {
        publish(d, DeliveryEvents.FAILED, new DeliveryFailed(d.getId(), d.getOrderId(), d.getOrderNumber(),
                d.getPartnerId(), d.getFailureReason().name(), d.getFailureNote(), d.getFailedAt()));
    }

    public void assignmentFailed(Delivery d, double radiusKm, Instant at) {
        publish(d, DeliveryEvents.ASSIGNMENT_FAILED, new DeliveryAssignmentFailed(d.getId(), d.getOrderId(),
                d.getOrderNumber(), d.getOffersSent(), radiusKm, at));
    }

    public void cancelled(Delivery d, UUID partnerId) {
        publish(d, DeliveryEvents.CANCELLED, new DeliveryCancelled(d.getId(), d.getOrderId(), d.getOrderNumber(),
                partnerId, d.getCancelledAt()));
    }

    private void publish(Delivery d, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, d.getOrderId().toString(),
                eventType, payload));
    }
}
