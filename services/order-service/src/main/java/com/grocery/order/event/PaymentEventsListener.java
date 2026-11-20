package com.grocery.order.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.order.service.EventSource;
import com.grocery.order.service.OrderEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * payment.events → the order's payment projection and status (consumer group {@code order-service.payment-events}):
 * {@code PaymentInitiated}, {@code PaymentCaptured}, {@code PaymentFailed}, {@code PaymentExpired},
 * {@code RefundCompleted} (Phase 12: with {@code returnId} the return becomes REFUNDED). {@code RefundFailed} is only
 * logged (the admin retries with a new key; the order and a return stay as they are). The state guards are applied by
 * {@link OrderEventService}.
 */
@Component
public class PaymentEventsListener {

    static final String TOPIC = "payment.events";
    static final String PAYMENT_INITIATED = "PaymentInitiated";
    static final String PAYMENT_CAPTURED = "PaymentCaptured";
    static final String PAYMENT_FAILED = "PaymentFailed";
    static final String PAYMENT_EXPIRED = "PaymentExpired";
    static final String REFUND_COMPLETED = "RefundCompleted";
    static final String REFUND_FAILED = "RefundFailed";
    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    /** Our own views of the payloads: only the fields order-service uses (tolerant reader). */
    record PaymentRef(UUID orderId, UUID paymentId) {
    }

    record PaymentCaptured(UUID orderId, UUID paymentId, Instant capturedAt, Boolean late) {
    }

    record PaymentFailed(UUID orderId, String errorCode, String errorDescription) {
    }

    /** Phase 12: {@code returnId} (a RETURN refund) and {@code refundId}; both may be absent. */
    record RefundCompleted(UUID orderId, UUID refundId, BigDecimal totalRefunded, Boolean fullyRefunded,
                           UUID returnId) {
    }

    record RefundFailed(UUID orderId, UUID refundId, BigDecimal amount, String reason, String errorCode,
                        UUID returnId) {
    }

    private final OrderEventService orderEvents;
    private final JsonMapper jsonMapper;

    public PaymentEventsListener(OrderEventService orderEvents, JsonMapper jsonMapper) {
        this.orderEvents = orderEvents;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "order-payment-events", groupId = OrderEventService.PAYMENT_CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        EventSource source = EventSource.of(record, event);
        switch (event.eventType()) {
            case PAYMENT_INITIATED -> {
                PaymentRef payment = read(event, PaymentRef.class, PaymentRef::orderId);
                orderEvents.paymentInitiated(source, payment.orderId(), payment.paymentId());
            }
            case PAYMENT_CAPTURED -> {
                PaymentCaptured captured = read(event, PaymentCaptured.class, PaymentCaptured::orderId);
                orderEvents.paymentCaptured(source, captured.orderId(), captured.paymentId(),
                        captured.capturedAt());
            }
            case PAYMENT_FAILED -> {
                PaymentFailed failed = read(event, PaymentFailed.class, PaymentFailed::orderId);
                orderEvents.paymentFailed(source, failed.orderId(), failed.errorCode(),
                        failed.errorDescription());
            }
            case PAYMENT_EXPIRED -> {
                PaymentRef payment = read(event, PaymentRef.class, PaymentRef::orderId);
                orderEvents.paymentExpired(source, payment.orderId());
            }
            case REFUND_COMPLETED -> {
                RefundCompleted refund = read(event, RefundCompleted.class, RefundCompleted::orderId);
                if (refund.totalRefunded() == null) {
                    throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks totalRefunded");
                }
                orderEvents.refundCompleted(source, refund.orderId(), refund.totalRefunded(),
                        refund.fullyRefunded(), refund.returnId(), refund.refundId());
            }
            case REFUND_FAILED -> {
                RefundFailed failed = event.payloadAs(jsonMapper, RefundFailed.class);
                log.warn("Refund {} of order {} failed ({} {}, reason {}, return {}): the order is unchanged",
                        failed.refundId(), failed.orderId(), failed.amount(), failed.errorCode(), failed.reason(),
                        failed.returnId());
            }
            default -> {
                // any other payment event: not for orders
            }
        }
    }

    private <T> T read(EventEnvelope event, Class<T> type, Function<T, UUID> orderId) {
        T payload = event.payloadAs(jsonMapper, type);
        if (orderId.apply(payload) == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        return payload;
    }
}
