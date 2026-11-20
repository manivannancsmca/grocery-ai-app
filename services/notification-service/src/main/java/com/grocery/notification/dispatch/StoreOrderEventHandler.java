package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.StoreOrderEvent;
import com.grocery.notification.repository.StoreOwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * Phase 8 store order events (docs/api/phase-8-vendor-and-restaurant-fulfillment.md §5, §6) on vendor.events
 * ({@code VendorOrder*}) and restaurant.events ({@code RestaurantOrder*}):
 * <ul>
 *   <li>…OrderReceived → {@code NEW_ORDER} to the store owner = the event's {@code ownerUserId} ("{n} items · {total}
 *       · accept by {HH:mm India time}", link = the order board, {@code data} with {@code acceptBy}). It replaces the
 *       Phase 7 NEW_ORDER on OrderConfirmed, so the board already has the order when the alert arrives;</li>
 *   <li>…OrderRejected with {@code auto: true} → {@code ORDER_AUTO_REJECTED} to the store owner;</li>
 *   <li>every one of them → signal {@code STORE_ORDERS_CHANGED} {@code {orderId, status}} (the store order's status:
 *       NEW, ACCEPTED, PREPARING, READY_FOR_PICKUP, REJECTED) to the store owner.</li>
 * </ul>
 * The owner of Accepted / Rejected / Preparing / ReadyForPickup comes from the {@code store_owner} projection; an
 * unknown owner skips the event's notification and signal (logged). The customer is told by order-service's own
 * events (OrderAccepted, OrderReadyForPickup, OrderCancelled), not by these. The {@code order_ref} projection is not
 * touched (the order's status follows order.events); Received only fills a missing {@code store_owner} row.
 */
@Component
public class StoreOrderEventHandler implements EventHandler {

    static final String VENDOR_TOPIC = "vendor.events";
    static final String RESTAURANT_TOPIC = "restaurant.events";
    static final String RECEIVED = "OrderReceived";
    static final String REJECTED = "OrderRejected";
    /** Event type without the Vendor / Restaurant prefix → the store order status after it. */
    private static final Map<String, String> STATUS = Map.of(
            RECEIVED, "NEW",
            "OrderAccepted", "ACCEPTED",
            REJECTED, "REJECTED",
            "OrderPreparing", "PREPARING",
            "OrderReadyForPickup", "READY_FOR_PICKUP");
    private static final Logger log = LoggerFactory.getLogger(StoreOrderEventHandler.class);

    private final StoreOwnerRepository owners;
    private final JsonMapper json;

    public StoreOrderEventHandler(StoreOwnerRepository owners, JsonMapper json) {
        this.owners = owners;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        StoreType storeType = storeType(topic);
        return storeType != null && step(eventType, storeType) != null;
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        StoreType storeType = storeType(event.topic());
        String step = step(event.eventType(), storeType);
        StoreOrderEvent payload = event.payloadAs(json, StoreOrderEvent.class);
        UUID orderId = Payloads.require(event, payload.orderId(), "orderId");
        String orderNumber = Payloads.require(event, payload.orderNumber(), "orderNumber");
        UUID storeId = Payloads.require(event,
                storeType == StoreType.VENDOR ? payload.vendorId() : payload.restaurantId(),
                storeType == StoreType.VENDOR ? "vendorId" : "restaurantId");

        UUID owner;
        if (RECEIVED.equals(step)) {
            owner = Payloads.require(event, payload.ownerUserId(), "ownerUserId");
            owners.insertIfAbsent(storeType, storeId, owner, plan.now());
        } else {
            Optional<StoreOwner> known = owners.find(storeType, storeId);
            if (known.isEmpty()) {
                log.warn("No owner known for {} {} of order {} ({} {}): the store is not notified", storeType, storeId,
                        orderId, event.eventType(), event.eventId());
                return;
            }
            owner = known.get().ownerUserId();
        }
        if (!plan.fresh()) {
            return;
        }

        List<String> audience = List.of(Audiences.user(owner));
        if (RECEIVED.equals(step)) {
            plan.notify(NotificationTemplates.newOrder(orderId, orderNumber, payload.itemCount(), payload.total(),
                    payload.acceptBy(), storeType), audience);
        } else if (REJECTED.equals(step) && Boolean.TRUE.equals(payload.auto())) {
            plan.notify(NotificationTemplates.orderAutoRejected(orderId, orderNumber, storeType), audience);
        }
        plan.signal(SignalType.STORE_ORDERS_CHANGED, data("orderId", orderId, "status", STATUS.get(step)), audience);
    }

    private static StoreType storeType(String topic) {
        if (VENDOR_TOPIC.equals(topic)) {
            return StoreType.VENDOR;
        }
        if (RESTAURANT_TOPIC.equals(topic)) {
            return StoreType.RESTAURANT;
        }
        return null;
    }

    /** "VendorOrderAccepted" on vendor.events → "OrderAccepted"; anything else (lifecycle events, …) → null. */
    private static String step(String eventType, StoreType storeType) {
        String prefix = storeType == StoreType.VENDOR ? "Vendor" : "Restaurant";
        if (eventType == null || !eventType.startsWith(prefix)) {
            return null;
        }
        String step = eventType.substring(prefix.length());
        return STATUS.containsKey(step) ? step : null;
    }
}
