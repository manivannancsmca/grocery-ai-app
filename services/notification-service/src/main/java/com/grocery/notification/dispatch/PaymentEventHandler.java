package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.security.Roles;
import com.grocery.notification.entity.OrderRef;
import com.grocery.notification.event.EventPayloads.PaymentEvent;
import com.grocery.notification.repository.OrderRefRepository;
import com.grocery.notification.repository.PendingEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * payment.events (they carry only the orderId: customer, order number and status come from {@code order_ref}):
 * <ul>
 *   <li>PaymentFailed with {@code retryable} → customer {@code PAYMENT_FAILED};</li>
 *   <li>RefundCompleted → customer {@code REFUND_COMPLETED};</li>
 *   <li>RefundFailed → {@code role:SUPER_ADMIN} + {@code role:FINANCE_ADMIN} {@code REFUND_FAILED} (one row);</li>
 *   <li>every payment event → {@code ORDER_UPDATED} to the customer and {@code ADMIN_ORDERS_CHANGED}.</li>
 * </ul>
 * A payment event whose order is not known yet (its OrderCreated, on another topic, not processed yet) is
 * <b>parked</b> in {@code pending_event} in the same transaction as its processed mark, and the record is done: the
 * partition is never blocked (order.events and payment.events are co-partitioned by orderId, so a blocking retry
 * could starve the very OrderCreated it waits for). The transaction that makes the order known applies the parked
 * events ({@link com.grocery.notification.service.DispatchService}). Events older than {@code max-event-age} notify
 * nobody, so they are neither parked nor kept waiting.
 */
@Component
public class PaymentEventHandler implements EventHandler {

    static final String TOPIC = "payment.events";
    static final String PAYMENT_FAILED = "PaymentFailed";
    static final String REFUND_COMPLETED = "RefundCompleted";
    static final String REFUND_FAILED = "RefundFailed";
    private static final Set<String> TYPES = Set.of("PaymentInitiated", "PaymentCaptured", PAYMENT_FAILED,
            "PaymentExpired", REFUND_COMPLETED, REFUND_FAILED);
    private static final List<String> REFUND_ADMINS = List.of(Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN);

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    private final OrderRefRepository orders;
    private final PendingEventRepository pending;
    private final JsonMapper json;

    public PaymentEventHandler(OrderRefRepository orders, PendingEventRepository pending, JsonMapper json) {
        this.orders = orders;
        this.pending = pending;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        PaymentEvent payment = event.payloadAs(json, PaymentEvent.class);
        UUID orderId = Payloads.require(event, payment.orderId(), "orderId");
        if (!plan.fresh()) {
            return;   // payment events update no projection
        }
        Optional<OrderRef> known = orders.findLocked(orderId);
        if (known.isEmpty()) {
            // its OrderCreated (another topic) has not been processed yet: park it, never block the partition
            pending.park(event, orderId, plan.occurredAt(), plan.now());
            plan.markParked();
            log.info("{} {}: order {} is not known yet, parked until it is", event.eventType(), event.eventId(),
                    orderId);
            return;
        }
        OrderRef order = known.get();
        String customer = Audiences.user(order.customerId());
        switch (event.eventType()) {
            case PAYMENT_FAILED -> {
                if (Boolean.TRUE.equals(payment.retryable())) {
                    plan.notify(NotificationTemplates.paymentFailed(orderId, order.orderNumber(),
                            payment.errorDescription()), List.of(customer));
                }
            }
            case REFUND_COMPLETED -> plan.notify(NotificationTemplates.refundCompleted(orderId, order.orderNumber(),
                    payment.refundId(), payment.amount(), payment.returnId()), List.of(customer));
            case REFUND_FAILED -> plan.notify(NotificationTemplates.refundFailed(orderId, order.orderNumber(),
                            payment.paymentId(), payment.refundId(), payment.amount(), payment.reason(),
                            payment.errorCode()),
                    Audiences.roles(REFUND_ADMINS));
            default -> {
                // PaymentInitiated, PaymentCaptured, PaymentExpired: signals only
            }
        }
        plan.signal(SignalType.ORDER_UPDATED,
                data("orderId", orderId, "status", order.status(), "event", event.eventType()), List.of(customer));
        plan.signal(SignalType.ADMIN_ORDERS_CHANGED, data("orderId", orderId, "event", event.eventType()),
                Audiences.allAdmins());
    }
}
