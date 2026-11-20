package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.OrderRef;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.ReturnEvent;
import com.grocery.notification.event.EventPayloads.ReturnLine;
import com.grocery.notification.repository.OrderRefRepository;
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
 * The return events on order.events (Phase 12 §3, schemas 1.5.0, key orderId):
 * <ul>
 *   <li>ReturnRequested → {@code RETURN_REQUESTED} · WARNING to the owner of the order's store ({@code orderType} +
 *       {@code storeId} → {@code store_owner}; never another store's owner): "Return requested for order {no}" ·
 *       "{n} items · {refund} · decide by {HH:mm}" · {@code /vendor/returns} or {@code /restaurant/returns};</li>
 *   <li>ReturnEscalated → {@code RETURN_ESCALATED} · WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT (one row) ·
 *       {@code /admin/returns};</li>
 *   <li>ReturnApproved → {@code RETURN_APPROVED} · SUCCESS to the customer; ReturnRejected → {@code RETURN_REJECTED} ·
 *       WARNING to the customer, with the comment; both link to the order ({@code /orders/{id}});</li>
 *   <li>signals, every return event: {@code ORDER_UPDATED {orderId, status, event, returnId}} → the customer (the
 *       order page and "my returns"; {@code status} = the order's last known status), {@code STORE_RETURNS_CHANGED
 *       {returnId, orderId, status}} → the store owner, {@code ADMIN_RETURNS_CHANGED {returnId, orderId, status}} and
 *       {@code ADMIN_ORDERS_CHANGED {orderId, event}} → all admin roles ({@code status} = the return's: REQUESTED,
 *       ESCALATED, APPROVED, REJECTED).</li>
 * </ul>
 * Everything comes from the event itself, so no order needs to be known (nothing is parked) and no projection is
 * written: an old event does nothing. An unknown store owner skips only the store's part (logged). Missing
 * {@code returnId}, {@code returnNumber}, {@code orderId}, {@code orderNumber} or {@code customerId} →
 * {@code InvalidEventException} (DLT).
 */
@Component
public class ReturnEventHandler implements EventHandler {

    static final String TOPIC = "order.events";
    static final String REQUESTED = "ReturnRequested";
    static final String ESCALATED = "ReturnEscalated";
    static final String APPROVED = "ReturnApproved";
    static final String REJECTED = "ReturnRejected";
    /** Event type → the return's status after it. */
    private static final Map<String, String> STATUS = Map.of(
            REQUESTED, "REQUESTED",
            ESCALATED, "ESCALATED",
            APPROVED, "APPROVED",
            REJECTED, "REJECTED");
    private static final Logger log = LoggerFactory.getLogger(ReturnEventHandler.class);

    private final StoreOwnerRepository owners;
    private final OrderRefRepository orders;
    private final JsonMapper json;

    public ReturnEventHandler(StoreOwnerRepository owners, OrderRefRepository orders, JsonMapper json) {
        this.owners = owners;
        this.orders = orders;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && STATUS.containsKey(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        ReturnEvent request = event.payloadAs(json, ReturnEvent.class);
        UUID returnId = Payloads.require(event, request.returnId(), "returnId");
        String returnNumber = Payloads.require(event, request.returnNumber(), "returnNumber");
        UUID orderId = Payloads.require(event, request.orderId(), "orderId");
        String orderNumber = Payloads.require(event, request.orderNumber(), "orderNumber");
        UUID customerId = Payloads.require(event, request.customerId(), "customerId");
        if (!plan.fresh()) {
            return;                                     // no projection to update
        }

        Optional<OrderRef> order = orders.find(orderId);
        String orderType = request.orderType() != null ? request.orderType()
                : order.map(OrderRef::orderType).orElse(null);
        UUID storeId = request.storeId() != null ? request.storeId() : order.map(OrderRef::storeId).orElse(null);
        StoreType storeType = StoreType.ofOrderType(orderType);
        Optional<StoreOwner> owner = owners.find(storeType, storeId);
        if (owner.isEmpty()) {
            log.warn("No owner known for {} {} of order {} ({} {}): the store is not notified", storeType, storeId,
                    orderId, event.eventType(), event.eventId());
        }
        String status = STATUS.get(event.eventType());
        List<String> customer = List.of(Audiences.user(customerId));

        switch (event.eventType()) {
            case REQUESTED -> owner.ifPresent(store -> plan.notify(NotificationTemplates.returnRequested(orderId,
                    orderNumber, returnId, returnNumber, quantity(request.items()), request.refundAmount(),
                    request.decideBy(), store.storeType()), List.of(Audiences.user(store.ownerUserId()))));
            case ESCALATED -> plan.notify(NotificationTemplates.returnEscalated(orderId, orderNumber, returnId,
                    returnNumber, request.refundAmount()), Audiences.moderators());
            case APPROVED -> plan.notify(NotificationTemplates.returnApproved(orderId, orderNumber, returnId,
                    returnNumber, request.refundAmount()), customer);
            default -> plan.notify(NotificationTemplates.returnRejected(orderId, orderNumber, returnId,
                    returnNumber, request.comment()), customer);
        }

        plan.signal(SignalType.ORDER_UPDATED, data("orderId", orderId,
                "status", order.map(OrderRef::status).orElse(null), "event", event.eventType(),
                "returnId", returnId), customer);
        owner.ifPresent(store -> plan.signal(SignalType.STORE_RETURNS_CHANGED,
                data("returnId", returnId, "orderId", orderId, "status", status),
                List.of(Audiences.user(store.ownerUserId()))));
        plan.signal(SignalType.ADMIN_RETURNS_CHANGED, data("returnId", returnId, "orderId", orderId,
                "status", status), Audiences.allAdmins());
        plan.signal(SignalType.ADMIN_ORDERS_CHANGED, data("orderId", orderId, "event", event.eventType()),
                Audiences.allAdmins());
    }

    /** The returned quantity over all lines; null when the event lists none. */
    private static Integer quantity(List<ReturnLine> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        int total = 0;
        for (ReturnLine line : items) {
            if (line != null && line.quantity() != null) {
                total += line.quantity();
            }
        }
        return total;
    }
}
