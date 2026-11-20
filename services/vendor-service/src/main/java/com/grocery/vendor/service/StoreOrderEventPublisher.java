package com.grocery.vendor.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.vendor.entity.VendorOrder;
import com.grocery.vendor.event.VendorOrderEvents;
import com.grocery.vendor.event.VendorOrderEvents.VendorOrderAccepted;
import com.grocery.vendor.event.VendorOrderEvents.VendorOrderPreparing;
import com.grocery.vendor.event.VendorOrderEvents.VendorOrderReadyForPickup;
import com.grocery.vendor.event.VendorOrderEvents.VendorOrderReceived;
import com.grocery.vendor.event.VendorOrderEvents.VendorOrderRejected;
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

    public void received(VendorOrder order, UUID ownerUserId) {
        publish(order, VendorOrderEvents.RECEIVED, new VendorOrderReceived(order.getOrderId(), order.getOrderNumber(),
                order.getVendorId(), ownerUserId, order.getItemCount(), order.getTotal(), order.getAcceptBy(),
                order.getReceivedAt()));
    }

    public void accepted(VendorOrder order, UUID actorUserId) {
        publish(order, VendorOrderEvents.ACCEPTED, new VendorOrderAccepted(order.getOrderId(), order.getOrderNumber(),
                order.getVendorId(), order.getReadyInMinutes(), order.getReadyBy(), order.getAcceptedAt(), actorUserId));
    }

    public void rejected(VendorOrder order) {
        publish(order, VendorOrderEvents.REJECTED, new VendorOrderRejected(order.getOrderId(), order.getOrderNumber(),
                order.getVendorId(), order.getRejectionReason().name(), order.getRejectionNote(),
                order.isRejectionAuto(), order.getRejectedAt(), order.getRejectedBy()));
    }

    public void preparing(VendorOrder order, UUID actorUserId) {
        publish(order, VendorOrderEvents.PREPARING, new VendorOrderPreparing(order.getOrderId(), order.getOrderNumber(),
                order.getVendorId(), order.getPreparingAt(), actorUserId));
    }

    public void readyForPickup(VendorOrder order, UUID actorUserId) {
        publish(order, VendorOrderEvents.READY_FOR_PICKUP, new VendorOrderReadyForPickup(order.getOrderId(),
                order.getOrderNumber(), order.getVendorId(), order.getReadyAt(), actorUserId));
    }

    private void publish(VendorOrder order, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(VendorOrderEvents.TOPIC, VendorOrderEvents.AGGREGATE,
                order.getOrderId().toString(), eventType, payload));
    }
}
