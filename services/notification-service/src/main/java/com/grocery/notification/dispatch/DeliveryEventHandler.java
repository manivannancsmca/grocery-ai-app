package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.OrderRef;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.DeliveryEvent;
import com.grocery.notification.repository.OrderRefRepository;
import com.grocery.notification.repository.PendingEventRepository;
import com.grocery.notification.repository.StoreOwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * Phase 9 delivery events on delivery.events (docs/api/phase-9-delivery.md §7; the partner lifecycle events on the same
 * topic stay with {@link AccountEventHandler}):
 * <ul>
 *   <li>DeliveryOfferCreated → the offered partner: {@code DELIVERY_OFFER} (only what their OfferView shows: store,
 *       distance, the time to respond — no customer details) + signal {@code DELIVERY_OFFERS_CHANGED}. An offer that
 *       has already expired when the event is handled notifies nobody (the signal still refreshes the list);</li>
 *   <li>DeliveryOfferClosed → the partner: signal {@code DELIVERY_OFFERS_CHANGED};</li>
 *   <li>DeliveryPartnerAssigned → records the partner in {@code order_ref}; the customer: {@code PARTNER_ASSIGNED}
 *       (the partner's first name); the partner: signal {@code DELIVERY_UPDATED}; the store owner: signal
 *       {@code STORE_ORDERS_CHANGED}. An order not known yet parks the event, like a payment event;</li>
 *   <li>DeliveryPartnerUnassigned → clears the partner (if still recorded); the partner: {@code DELIVERY_REASSIGNED};</li>
 *   <li>DeliveryAssignmentFailed → SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER: {@code NO_DELIVERY_PARTNER};</li>
 *   <li>DeliveryCancelled with a partner → clears the partner; the partner: {@code DELIVERY_CANCELLED};</li>
 *   <li>DeliveryPickedUp, DeliveryCompleted, DeliveryFailed → nothing more here (the customer and the store hear it
 *       from order-service's OrderOutForDelivery / OrderDelivered / OrderDeliveryFailed);</li>
 *   <li>every one of them → signal {@code ADMIN_DELIVERIES_CHANGED {deliveryId, orderId}} to SUPER_ADMIN, OPS_ADMIN,
 *       DELIVERY_MANAGER.</li>
 * </ul>
 * {@code data} carries orderId, orderNumber and deliveryId / offerId. Projections are updated whatever the event's age;
 * notifications and signals only for fresh events.
 */
@Component
public class DeliveryEventHandler implements EventHandler {

    static final String TOPIC = "delivery.events";
    static final String OFFER_CREATED = "DeliveryOfferCreated";
    static final String OFFER_CLOSED = "DeliveryOfferClosed";
    static final String PARTNER_ASSIGNED = "DeliveryPartnerAssigned";
    static final String PARTNER_UNASSIGNED = "DeliveryPartnerUnassigned";
    static final String ASSIGNMENT_FAILED = "DeliveryAssignmentFailed";
    static final String CANCELLED = "DeliveryCancelled";
    private static final Set<String> TYPES = Set.of(OFFER_CREATED, OFFER_CLOSED, PARTNER_ASSIGNED, PARTNER_UNASSIGNED,
            "DeliveryPickedUp", "DeliveryCompleted", "DeliveryFailed", ASSIGNMENT_FAILED, CANCELLED);
    private static final Logger log = LoggerFactory.getLogger(DeliveryEventHandler.class);

    private final OrderRefRepository orders;
    private final StoreOwnerRepository owners;
    private final PendingEventRepository pending;
    private final JsonMapper json;

    public DeliveryEventHandler(OrderRefRepository orders, StoreOwnerRepository owners, PendingEventRepository pending,
                                JsonMapper json) {
        this.orders = orders;
        this.owners = owners;
        this.pending = pending;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        DeliveryEvent delivery = event.payloadAs(json, DeliveryEvent.class);
        UUID deliveryId = Payloads.require(event, delivery.deliveryId(), "deliveryId");
        UUID orderId = Payloads.require(event, delivery.orderId(), "orderId");
        String orderNumber = Payloads.require(event, delivery.orderNumber(), "orderNumber");

        switch (event.eventType()) {
            case OFFER_CREATED -> offerCreated(event, delivery, plan);
            case OFFER_CLOSED -> {
                UUID partnerId = Payloads.require(event, delivery.partnerId(), "partnerId");
                if (plan.fresh()) {
                    plan.signal(SignalType.DELIVERY_OFFERS_CHANGED,
                            data("deliveryId", deliveryId, "offerId", delivery.offerId()),
                            List.of(Audiences.user(partnerId)));
                }
            }
            case PARTNER_ASSIGNED -> {
                if (!partnerAssigned(event, delivery, plan)) {
                    return;                                         // parked: nothing else happens to it now
                }
            }
            case PARTNER_UNASSIGNED -> {
                UUID partnerId = Payloads.require(event, delivery.partnerId(), "partnerId");
                orders.unassignPartner(orderId, partnerId, plan.now());
                if (plan.fresh()) {
                    plan.notify(NotificationTemplates.deliveryReassigned(orderId, orderNumber, deliveryId),
                            List.of(Audiences.user(partnerId)));
                }
            }
            case ASSIGNMENT_FAILED -> {
                if (plan.fresh()) {
                    plan.notify(NotificationTemplates.noDeliveryPartner(orderId, orderNumber, deliveryId),
                            Audiences.deliveryAdmins());
                }
            }
            case CANCELLED -> {
                UUID partnerId = delivery.partnerId();
                if (partnerId != null) {
                    orders.unassignPartner(orderId, partnerId, plan.now());     // the partner is released
                    if (plan.fresh()) {
                        plan.notify(NotificationTemplates.deliveryCancelled(orderId, orderNumber, deliveryId),
                                List.of(Audiences.user(partnerId)));
                    }
                }
            }
            default -> {
                // DeliveryPickedUp, DeliveryCompleted, DeliveryFailed: the order events tell the customer and store
            }
        }
        if (plan.fresh()) {
            plan.signal(SignalType.ADMIN_DELIVERIES_CHANGED, data("deliveryId", deliveryId, "orderId", orderId),
                    Audiences.deliveryAdmins());
        }
    }

    private void offerCreated(EventEnvelope event, DeliveryEvent offer, DispatchPlan plan) {
        UUID partnerId = Payloads.require(event, offer.partnerId(), "partnerId");
        UUID offerId = Payloads.require(event, offer.offerId(), "offerId");
        if (!plan.fresh()) {
            return;
        }
        List<String> partner = List.of(Audiences.user(partnerId));
        long seconds = secondsLeft(offer.expiresAt(), plan.now());
        if (seconds > 0) {
            String storeName = offer.storeName() != null ? offer.storeName()
                    : orders.find(offer.orderId()).map(OrderRef::storeName).orElse(null);
            plan.notify(NotificationTemplates.deliveryOffer(offer.orderId(), offer.orderNumber(), offer.deliveryId(),
                    offerId, storeName, Payloads.require(event, offer.distanceKm(), "distanceKm"), seconds), partner);
        } else {
            log.info("{} {}: offer {} already expired at {}, the partner is not notified", event.eventType(),
                    event.eventId(), offerId, offer.expiresAt());
        }
        plan.signal(SignalType.DELIVERY_OFFERS_CHANGED, data("deliveryId", offer.deliveryId(), "offerId", offerId),
                partner);
    }

    /** @return false when the event was parked (its order is not known yet) */
    private boolean partnerAssigned(EventEnvelope event, DeliveryEvent assigned, DispatchPlan plan) {
        UUID partnerId = Payloads.require(event, assigned.partnerId(), "partnerId");
        UUID orderId = assigned.orderId();
        if (!plan.fresh()) {
            orders.assignPartner(orderId, assigned.deliveryId(), partnerId, plan.now());     // projection only
            return true;
        }
        Optional<OrderRef> known = orders.findLocked(orderId);
        if (known.isEmpty()) {
            // its order's events (another topic) have not been processed yet: park it, never block the partition
            pending.park(event, orderId, plan.occurredAt(), plan.now());
            plan.markParked();
            log.info("{} {}: order {} is not known yet, parked until it is", event.eventType(), event.eventId(),
                    orderId);
            return false;
        }
        orders.assignPartner(orderId, assigned.deliveryId(), partnerId, plan.now());
        OrderRef order = known.get();
        plan.notify(NotificationTemplates.partnerAssigned(orderId, assigned.orderNumber(), assigned.deliveryId(),
                assigned.partnerName()), List.of(Audiences.user(order.customerId())));
        plan.signal(SignalType.DELIVERY_UPDATED, data("deliveryId", assigned.deliveryId(), "orderId", orderId),
                List.of(Audiences.user(partnerId)));
        StoreType storeType = StoreType.ofOrderType(order.orderType());
        Optional<StoreOwner> owner = storeType == null ? Optional.empty() : owners.find(storeType, order.storeId());
        if (owner.isPresent()) {
            plan.signal(SignalType.STORE_ORDERS_CHANGED, data("orderId", orderId, "status", order.status()),
                    List.of(Audiences.user(owner.get().ownerUserId())));
        } else {
            log.warn("No owner known for {} {} of order {} ({} {}): the store is not signalled", storeType,
                    order.storeId(), orderId, event.eventType(), event.eventId());
        }
        return true;
    }

    /** Whole seconds until {@code expiresAt}, rounded up; 0 when it has passed (or is unknown). */
    static long secondsLeft(Instant expiresAt, Instant now) {
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return 0;
        }
        Duration left = Duration.between(now, expiresAt);
        return left.toSeconds() + (left.toNanosPart() > 0 ? 1 : 0);
    }
}
