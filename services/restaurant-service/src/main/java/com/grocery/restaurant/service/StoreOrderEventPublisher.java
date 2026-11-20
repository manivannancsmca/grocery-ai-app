package com.grocery.restaurant.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.restaurant.entity.RestaurantOrder;
import com.grocery.restaurant.event.RestaurantOrderEvents;
import com.grocery.restaurant.event.RestaurantOrderEvents.RestaurantOrderAccepted;
import com.grocery.restaurant.event.RestaurantOrderEvents.RestaurantOrderPreparing;
import com.grocery.restaurant.event.RestaurantOrderEvents.RestaurantOrderReadyForPickup;
import com.grocery.restaurant.event.RestaurantOrderEvents.RestaurantOrderReceived;
import com.grocery.restaurant.event.RestaurantOrderEvents.RestaurantOrderRejected;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Writes the store order events (contract §5) to the outbox, in the caller's transaction — the one that made the
 * change the event describes. Built from the entity after the transition, so the event carries the stored values.
 */
@Component
public class StoreOrderEventPublisher {

    private final OutboxPublisher outbox;

    public StoreOrderEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void received(RestaurantOrder order, UUID ownerUserId) {
        publish(order, RestaurantOrderEvents.RECEIVED, new RestaurantOrderReceived(order.getOrderId(),
                order.getOrderNumber(), order.getRestaurantId(), ownerUserId, order.getItemCount(), order.getTotal(),
                order.getAcceptBy(), order.getReceivedAt()));
    }

    public void accepted(RestaurantOrder order, UUID actorUserId) {
        publish(order, RestaurantOrderEvents.ACCEPTED, new RestaurantOrderAccepted(order.getOrderId(),
                order.getOrderNumber(), order.getRestaurantId(), order.getReadyInMinutes(), order.getReadyBy(),
                order.getAcceptedAt(), actorUserId));
    }

    public void rejected(RestaurantOrder order) {
        publish(order, RestaurantOrderEvents.REJECTED, new RestaurantOrderRejected(order.getOrderId(),
                order.getOrderNumber(), order.getRestaurantId(), order.getRejectionReason().name(),
                order.getRejectionNote(), order.isRejectionAuto(), order.getRejectedAt(), order.getRejectedBy()));
    }

    public void preparing(RestaurantOrder order, UUID actorUserId) {
        publish(order, RestaurantOrderEvents.PREPARING, new RestaurantOrderPreparing(order.getOrderId(),
                order.getOrderNumber(), order.getRestaurantId(), order.getPreparingAt(), actorUserId));
    }

    public void readyForPickup(RestaurantOrder order, UUID actorUserId) {
        publish(order, RestaurantOrderEvents.READY_FOR_PICKUP, new RestaurantOrderReadyForPickup(order.getOrderId(),
                order.getOrderNumber(), order.getRestaurantId(), order.getReadyAt(), actorUserId));
    }

    private void publish(RestaurantOrder order, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(RestaurantOrderEvents.TOPIC, RestaurantOrderEvents.AGGREGATE,
                order.getOrderId().toString(), eventType, payload));
    }
}
