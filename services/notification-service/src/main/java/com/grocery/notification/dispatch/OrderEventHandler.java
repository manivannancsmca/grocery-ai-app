package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.OrderRef;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.OrderEvent;
import com.grocery.notification.event.EventPayloads.OrderItem;
import com.grocery.notification.repository.ItemNameRepository;
import com.grocery.notification.repository.OrderRefRepository;
import com.grocery.notification.repository.StoreOwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * order.events: the {@code order_ref} projection (OrderCreated fills it, every order event sets the status, including
 * Phase 8's ACCEPTED, PREPARING, READY_FOR_PICKUP and Phase 9's OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED), then
 * contract Phase 7 §4 as changed by Phase 8 §6 and Phase 9 §7:
 * <ul>
 *   <li>OrderConfirmed → customer {@code ORDER_CONFIRMED} (the store's {@code NEW_ORDER} now comes from
 *       VendorOrderReceived / RestaurantOrderReceived, see {@link StoreOrderEventHandler});</li>
 *   <li>OrderAccepted → customer {@code ORDER_ACCEPTED}; OrderReadyForPickup → customer {@code ORDER_READY};
 *       OrderPreparing → signals only;</li>
 *   <li>Phase 9: OrderReadyForPickup → also the assigned partner (projection from DeliveryPartnerAssigned)
 *       {@code PICKUP_READY}; OrderOutForDelivery → customer {@code OUT_FOR_DELIVERY} and the store owner
 *       {@code STORE_ORDERS_CHANGED {orderId, status: PICKED_UP}}; OrderDelivered → customer {@code ORDER_DELIVERED};
 *       OrderDeliveryFailed → customer {@code DELIVERY_FAILED} and SUPER_ADMIN, OPS_ADMIN, SUPPORT, DELIVERY_MANAGER
 *       {@code DELIVERY_FAILED_ADMIN};</li>
 *   <li>OrderRejected → customer {@code ORDER_REJECTED};</li>
 *   <li>OrderCancelled → customer {@code ORDER_CANCELLED} (STORE_REJECTED / STORE_NO_RESPONSE with their own texts);
 *       when the store had the order (previous status CONFIRMED or later): the store owner's {@code ORDER_CANCELLED}
 *       (not when the store itself rejected it) and {@code STORE_ORDERS_CHANGED} {@code {orderId, status}};</li>
 *   <li>every order event → {@code ORDER_UPDATED} to the customer (and to the store owner for OrderConfirmed and
 *       OrderCancelled of an order the store had) and {@code ADMIN_ORDERS_CHANGED} to every admin role.</li>
 * </ul>
 * An unknown store owner (its vendor / restaurant events not seen yet) skips only the store's part, logged.
 * Once the order's row exists, the payment events parked for it are applied in the same transaction
 * ({@link DispatchPlan#orderKnown}).
 */
@Component
public class OrderEventHandler implements EventHandler {

    static final String TOPIC = "order.events";
    static final String ORDER_CREATED = "OrderCreated";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_REJECTED = "OrderRejected";
    static final String ORDER_CANCELLED = "OrderCancelled";
    static final String ORDER_ACCEPTED = "OrderAccepted";
    static final String ORDER_READY_FOR_PICKUP = "OrderReadyForPickup";
    static final String ORDER_OUT_FOR_DELIVERY = "OrderOutForDelivery";
    static final String ORDER_DELIVERED = "OrderDelivered";
    static final String ORDER_DELIVERY_FAILED = "OrderDeliveryFailed";
    /**
     * Event type → the order status after it (Phase 8 adds ACCEPTED, PREPARING, READY_FOR_PICKUP; Phase 9
     * OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED).
     */
    private static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry(ORDER_CREATED, "CREATED"),
            Map.entry("OrderAwaitingPayment", "AWAITING_PAYMENT"),
            Map.entry(ORDER_CONFIRMED, "CONFIRMED"),
            Map.entry(ORDER_REJECTED, "REJECTED"),
            Map.entry(ORDER_CANCELLED, "CANCELLED"),
            Map.entry(ORDER_ACCEPTED, "ACCEPTED"),
            Map.entry("OrderPreparing", "PREPARING"),
            Map.entry(ORDER_READY_FOR_PICKUP, "READY_FOR_PICKUP"),
            Map.entry(ORDER_OUT_FOR_DELIVERY, "OUT_FOR_DELIVERY"),
            Map.entry(ORDER_DELIVERED, "DELIVERED"),
            Map.entry(ORDER_DELIVERY_FAILED, "DELIVERY_FAILED"));
    /** The store order's status once the partner took the order (vendor- / restaurant-service, Phase 9). */
    private static final String STORE_PICKED_UP = "PICKED_UP";
    /** Cancellations decided by the store itself: the store gets no "do not prepare it" notification. */
    private static final Set<String> STORE_DECISIONS = Set.of("STORE_REJECTED", "STORE_NO_RESPONSE");
    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    private final OrderRefRepository orders;
    private final StoreOwnerRepository owners;
    private final ItemNameRepository itemNames;
    private final JsonMapper json;

    public OrderEventHandler(OrderRefRepository orders, StoreOwnerRepository owners, ItemNameRepository itemNames,
                             JsonMapper json) {
        this.orders = orders;
        this.owners = owners;
        this.itemNames = itemNames;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && STATUS.containsKey(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        OrderEvent order = event.payloadAs(json, OrderEvent.class);
        UUID orderId = Payloads.require(event, order.orderId(), "orderId");
        String orderNumber = Payloads.require(event, order.orderNumber(), "orderNumber");
        UUID customerId = Payloads.require(event, order.customerId(), "customerId");
        String status = STATUS.get(event.eventType());
        OrderRef ref = new OrderRef(orderId, orderNumber, order.orderType(), customerId, order.storeId(),
                order.storeName(), order.total(), status);
        if (ORDER_CREATED.equals(event.eventType())) {
            orders.created(ref, plan.now());
        } else {
            orders.statusChanged(ref, plan.now());
        }
        // Phase 12: the names of the ordered products / dishes, for review notifications (old events too)
        if (order.items() != null) {
            for (OrderItem item : order.items()) {
                if (item != null) {
                    itemNames.upsert(item.itemId(), item.type(), item.name(), plan.now());
                }
            }
        }
        // the order is known now (even from an old event): payment events parked for it are applied after this one
        plan.orderKnown(orderId);
        if (!plan.fresh()) {
            return;
        }

        OrderRef stored = orders.find(orderId).orElse(ref);
        StoreType storeType = StoreType.ofOrderType(stored.orderType());
        List<String> customer = List.of(Audiences.user(customerId));
        UUID storeRecipient = null;
        switch (event.eventType()) {
            case ORDER_CONFIRMED -> {
                // Phase 8: NEW_ORDER comes from VendorOrderReceived / RestaurantOrderReceived instead
                Optional<StoreOwner> owner = storeOwner(event, storeType, stored);
                BigDecimal total = order.total() != null ? order.total() : stored.total();
                plan.notify(NotificationTemplates.orderConfirmed(orderId, orderNumber, total,
                        storeName(stored, owner)), customer);
                storeRecipient = owner.map(StoreOwner::ownerUserId).orElse(null);
            }
            case ORDER_ACCEPTED -> plan.notify(NotificationTemplates.orderAccepted(orderId, orderNumber,
                    storeName(stored, owners.find(storeType, stored.storeId())), order.readyInMinutes()), customer);
            case ORDER_READY_FOR_PICKUP -> {
                plan.notify(NotificationTemplates.orderReady(orderId, orderNumber, storeType), customer);
                // Phase 9: the assigned partner (projection from DeliveryPartnerAssigned) may come now
                if (stored.partnerId() != null) {
                    plan.notify(NotificationTemplates.pickupReady(orderId, orderNumber, stored.deliveryId(),
                                    storeName(stored, owners.find(storeType, stored.storeId()))),
                            List.of(Audiences.user(stored.partnerId())));
                }
            }
            case ORDER_OUT_FOR_DELIVERY -> {
                plan.notify(NotificationTemplates.outForDelivery(orderId, orderNumber), customer);
                // the store order is PICKED_UP: off the board in every open tab
                Optional<StoreOwner> owner = storeOwner(event, storeType, stored);
                if (owner.isPresent()) {
                    plan.signal(SignalType.STORE_ORDERS_CHANGED, data("orderId", orderId, "status", STORE_PICKED_UP),
                            List.of(Audiences.user(owner.get().ownerUserId())));
                }
            }
            case ORDER_DELIVERED -> plan.notify(NotificationTemplates.orderDelivered(orderId, orderNumber), customer);
            case ORDER_DELIVERY_FAILED -> {
                plan.notify(NotificationTemplates.deliveryFailedForCustomer(orderId, orderNumber), customer);
                plan.notify(NotificationTemplates.deliveryFailedForAdmins(orderId, orderNumber, stored.deliveryId(),
                        order.reason(), order.note()), Audiences.deliveryFailureAdmins());
            }
            case ORDER_REJECTED -> plan.notify(NotificationTemplates.orderRejected(orderId, orderNumber), customer);
            case ORDER_CANCELLED -> {
                boolean storeHadIt = NotificationTemplates.wasPaid(order.previousStatus());
                Optional<StoreOwner> owner = storeHadIt ? storeOwner(event, storeType, stored) : Optional.empty();
                plan.notify(NotificationTemplates.orderCancelledForCustomer(orderId, orderNumber, order.reason(),
                        order.note(), order.previousStatus(), storeName(stored, owner)), customer);
                if (owner.isPresent()) {
                    storeRecipient = owner.get().ownerUserId();
                    List<String> store = List.of(Audiences.user(storeRecipient));
                    if (!STORE_DECISIONS.contains(order.reason())) {
                        plan.notify(NotificationTemplates.orderCancelledForStore(orderId, orderNumber, storeType),
                                store);
                    }
                    plan.signal(SignalType.STORE_ORDERS_CHANGED, data("orderId", orderId, "status", status), store);
                }
            }
            default -> {
                // OrderCreated, OrderAwaitingPayment, OrderPreparing: signals only
            }
        }

        List<String> recipients = new ArrayList<>(List.of(Audiences.user(customerId)));
        if (storeRecipient != null) {
            recipients.add(Audiences.user(storeRecipient));
        }
        plan.signal(SignalType.ORDER_UPDATED, data("orderId", orderId, "status", status, "event", event.eventType()),
                recipients);
        plan.signal(SignalType.ADMIN_ORDERS_CHANGED, data("orderId", orderId, "event", event.eventType()),
                Audiences.allAdmins());
    }

    /** The order's store name (OrderCreated), else the store owner projection's; null when neither knows it. */
    private static String storeName(OrderRef order, Optional<StoreOwner> owner) {
        return order.storeName() != null ? order.storeName() : owner.map(StoreOwner::storeName).orElse(null);
    }

    private Optional<StoreOwner> storeOwner(EventEnvelope event, StoreType storeType, OrderRef order) {
        Optional<StoreOwner> owner = owners.find(storeType, order.storeId());
        if (owner.isEmpty()) {
            log.warn("No owner known for {} {} of order {} ({} {}): the store is not notified", storeType,
                    order.storeId(), order.orderId(), event.eventType(), event.eventId());
        }
        return owner;
    }
}
