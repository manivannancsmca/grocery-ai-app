package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.event.EventPayloads.OrderStuckDetected;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * order.events → {@code OrderStuckDetected} (Phase 10 §4): the stuck-order watchdog found an order in one status
 * longer than its threshold. {@code STUCK_ORDER} · WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT (one row: "Order {no} is
 * stuck: {reason label}" · "{status} since {HH:mm}." · {@code /admin/orders}) and {@code ADMIN_ORDERS_CHANGED} to every
 * admin role, so the stuck-order card refreshes. The order itself did not change: the {@code order_ref} projection is
 * left alone and the customer hears nothing. Missing {@code orderId}, {@code orderNumber}, {@code status} or
 * {@code reason} → {@code InvalidEventException} (DLT).
 */
@Component
public class StuckOrderEventHandler implements EventHandler {

    static final String TOPIC = "order.events";
    static final String ORDER_STUCK_DETECTED = "OrderStuckDetected";

    private final JsonMapper json;

    public StuckOrderEventHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && ORDER_STUCK_DETECTED.equals(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        OrderStuckDetected stuck = event.payloadAs(json, OrderStuckDetected.class);
        UUID orderId = Payloads.require(event, stuck.orderId(), "orderId");
        String orderNumber = Payloads.require(event, stuck.orderNumber(), "orderNumber");
        String status = Payloads.require(event, stuck.status(), "status");
        String reason = Payloads.require(event, stuck.reason(), "reason");
        if (!plan.fresh()) {
            return;                                     // an old flag: nobody is alerted any more
        }
        plan.notify(NotificationTemplates.stuckOrder(orderId, orderNumber, status, reason, stuck.since()),
                Audiences.stuckOrderAdmins());
        plan.signal(SignalType.ADMIN_ORDERS_CHANGED, data("orderId", orderId, "event", event.eventType()),
                Audiences.allAdmins());
    }
}
