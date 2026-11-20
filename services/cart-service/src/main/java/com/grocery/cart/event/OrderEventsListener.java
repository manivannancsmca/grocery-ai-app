package com.grocery.cart.event;

import com.grocery.cart.service.CartService;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.web.ApiException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * order.events → the cart an order was placed from is emptied (docs/api/phase-6-orders-and-payments.md §6 "Cart").
 * Only {@code OrderCreated} is used, for grocery and restaurant orders alike; every other event type is ignored.
 * The cart is emptied only if it has not changed since the order was placed ({@code cartUpdatedAt} = the cart's
 * {@code updatedAt}), see {@link CartService#clearIfUnchangedSince}.
 *
 * <p><b>No {@code processed_event} table</b> (this service has no database): the effect is idempotent by nature. A
 * redelivered event finds the cart already emptied (or edited since), whose {@code updatedAt} no longer matches,
 * and changes nothing.
 *
 * <p><b>Failures.</b> A payload that cannot be read or lacks {@code orderId} / {@code customerId} →
 * {@link InvalidEventException} (dead-letter topic at once). Redis unreachable or timing out (the API's 503
 * {@code CART_UNAVAILABLE}), or a cart that kept changing through every compare-and-set retry, →
 * {@link TransientEventProcessingException}: grocery-common's error handler keeps retrying the same record (outage
 * policy, see {@code TransientFailures}) instead of dead-lettering it. An event without {@code cartUpdatedAt}
 * cannot prove the cart is unchanged, so the cart is kept.
 */
@Component
public class OrderEventsListener {

    public static final String CONSUMER = "cart-service.order-events";
    static final String TOPIC = "order.events";
    static final String ORDER_CREATED = "OrderCreated";

    /** Store failures that mean "try again later": Redis down / slow, or a cart under constant concurrent edits. */
    private static final Set<String> RETRY_LATER = Set.of("CART_UNAVAILABLE", "CONCURRENT_MODIFICATION");
    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    /**
     * Our own view of the OrderCreated payload: only the fields this service needs (tolerant reader).
     * {@code cartUpdatedAt} = the {@code updatedAt} of the checkout preview the order was placed from (ISO-8601).
     */
    record OrderCreated(UUID orderId, UUID customerId, Instant cartUpdatedAt) {
    }

    private final CartService carts;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(CartService carts, JsonMapper jsonMapper) {
        this.carts = carts;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "cart-order-events", groupId = CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!ORDER_CREATED.equals(event.eventType())) {
            return;                             // OrderAwaitingPayment, OrderConfirmed, OrderCancelled, …
        }
        OrderCreated order = event.payloadAs(jsonMapper, OrderCreated.class);
        if (order.orderId() == null || order.customerId() == null) {
            throw new InvalidEventException("OrderCreated " + event.eventId() + " lacks orderId or customerId");
        }
        if (order.cartUpdatedAt() == null) {
            log.warn("OrderCreated {} (order {}) has no cartUpdatedAt: the cart of customer {} is kept",
                    event.eventId(), order.orderId(), order.customerId());
            return;
        }
        boolean cleared;
        try {
            cleared = carts.clearIfUnchangedSince(order.customerId(), order.cartUpdatedAt());
        } catch (ApiException e) {
            if (RETRY_LATER.contains(e.errorCode())) {
                throw new TransientEventProcessingException("Cannot empty the cart of customer " + order.customerId()
                        + " for order " + order.orderId() + " right now (" + e.errorCode() + ")", e);
            }
            throw e;
        }
        if (cleared) {
            log.info("Cart of customer {} emptied: order {} was placed from it", order.customerId(), order.orderId());
        } else {
            log.debug("Cart of customer {} kept for order {}: missing, empty or changed since {}",
                    order.customerId(), order.orderId(), order.cartUpdatedAt());
        }
    }
}
