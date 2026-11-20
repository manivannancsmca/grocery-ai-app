package com.grocery.payment.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.service.OrderEventsService;
import com.grocery.payment.service.OrderEventsService.ApprovedReturn;
import com.grocery.payment.service.OrderEventsService.AwaitingPayment;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * order.events → payments (docs/api/phase-6-orders-and-payments.md §3.1, §6): {@code OrderAwaitingPayment} creates
 * the payment and its Razorpay order, {@code OrderCancelled} cancels or refunds it, (Phase 12 §3)
 * {@code ReturnApproved} refunds the return. Every other event type is ignored.
 *
 * <p>Failures: a broken payload is an {@link InvalidEventException} (straight to {@code order.events.DLT}). Razorpay
 * unreachable is rethrown as {@link TransientEventProcessingException}, and database outages propagate as they are,
 * so grocery-common's error handler retries the same record with its outage backoff
 * ({@code TransientFailures}) instead of dead-lettering it.
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order.events";
    static final String ORDER_AWAITING_PAYMENT = "OrderAwaitingPayment";
    static final String ORDER_CANCELLED = "OrderCancelled";
    static final String RETURN_APPROVED = "ReturnApproved";

    /** Our own view of the OrderAwaitingPayment payload (tolerant reader). */
    record OrderAwaitingPayment(UUID orderId, String orderNumber, UUID customerId, BigDecimal amount,
                                String currency, Instant expiresAt, String contactPhone) {
    }

    /** OrderCancelled: only which order. */
    record OrderRef(UUID orderId) {
    }

    /** Phase 12: ReturnApproved — which return of which order, and how much to refund (tolerant reader). */
    record ReturnApproved(UUID returnId, String returnNumber, UUID orderId, BigDecimal refundAmount) {
    }

    static final int MAX_ORDER_NUMBER = 40;
    static final int MAX_CONTACT_PHONE = 32;

    private final OrderEventsService service;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(OrderEventsService service, JsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "payment-order-events", groupId = OrderEventsService.CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        try {
            switch (event.eventType()) {
                case ORDER_AWAITING_PAYMENT -> service.orderAwaitingPayment(event.eventId(), awaiting(event));
                case ORDER_CANCELLED -> service.orderCancelled(event.eventId(), ref(event).orderId());
                case RETURN_APPROVED -> service.returnApproved(event.eventId(), approvedReturn(event));
                default -> {
                    // OrderCreated, OrderConfirmed, OrderRejected, …: nothing to do here
                }
            }
        } catch (ProviderUnavailableException e) {
            throw new TransientEventProcessingException(event.eventType() + " " + event.eventId()
                    + ": payment provider unavailable, retrying", e);
        }
    }

    private AwaitingPayment awaiting(EventEnvelope event) {
        OrderAwaitingPayment p = event.payloadAs(jsonMapper, OrderAwaitingPayment.class);
        if (p.orderId() == null || p.customerId() == null || p.expiresAt() == null || blank(p.orderNumber())
                || blank(p.currency())) {
            throw invalid(event, "lacks orderId, orderNumber, customerId, currency or expiresAt");
        }
        if (p.orderNumber().length() > MAX_ORDER_NUMBER || p.currency().strip().length() != 3) {
            throw invalid(event, "has an invalid orderNumber or currency");
        }
        if (p.amount() == null || p.amount().signum() <= 0 || p.amount().stripTrailingZeros().scale() > 2) {
            throw invalid(event, "needs a positive amount with at most 2 decimals");
        }
        String phone = blank(p.contactPhone()) || p.contactPhone().strip().length() > MAX_CONTACT_PHONE
                ? null : p.contactPhone().strip();
        return new AwaitingPayment(p.orderId(), p.orderNumber().strip(), p.customerId(),
                p.amount().setScale(2, java.math.RoundingMode.UNNECESSARY), p.currency().strip(), p.expiresAt(),
                phone);
    }

    private ApprovedReturn approvedReturn(EventEnvelope event) {
        ReturnApproved p = event.payloadAs(jsonMapper, ReturnApproved.class);
        if (p.returnId() == null || p.orderId() == null || blank(p.returnNumber())) {
            throw invalid(event, "lacks returnId, returnNumber or orderId");
        }
        if (p.refundAmount() == null || p.refundAmount().signum() < 0
                || p.refundAmount().stripTrailingZeros().scale() > 2) {
            throw invalid(event, "needs a refundAmount ≥ 0 with at most 2 decimals");
        }
        String returnNumber = p.returnNumber().strip();
        if (returnNumber.length() > MAX_ORDER_NUMBER) {
            throw invalid(event, "has an invalid returnNumber");
        }
        return new ApprovedReturn(p.returnId(), returnNumber, p.orderId(),
                p.refundAmount().setScale(2, java.math.RoundingMode.UNNECESSARY));
    }

    private OrderRef ref(EventEnvelope event) {
        OrderRef order = event.payloadAs(jsonMapper, OrderRef.class);
        if (order.orderId() == null) {
            throw invalid(event, "lacks orderId");
        }
        return order;
    }

    private static InvalidEventException invalid(EventEnvelope event, String problem) {
        return new InvalidEventException(event.eventType() + " " + event.eventId() + " " + problem);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
