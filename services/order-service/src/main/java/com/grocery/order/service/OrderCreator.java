package com.grocery.order.service;

import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderType;
import com.grocery.order.repository.CustomerOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The creating transaction of {@code POST /api/orders}: claims the {@code Idempotency-Key} and stores the order, its
 * lines, its first history entries and its outbox events — all or nothing. Runs only after the slow cart-service call
 * (which happens outside any transaction, see {@link OrderPlacementService}).
 */
@Component
public class OrderCreator {

    private static final Logger log = LoggerFactory.getLogger(OrderCreator.class);

    private final IdempotencyKeys idempotencyKeys;
    private final CustomerOrderRepository orders;
    private final OrderEventPublisher events;

    public OrderCreator(IdempotencyKeys idempotencyKeys, CustomerOrderRepository orders, OrderEventPublisher events) {
        this.idempotencyKeys = idempotencyKeys;
        this.orders = orders;
        this.events = events;
    }

    /**
     * Claims {@code key} for {@code draft.id()} and creates the order, or returns the order id stored by an earlier
     * request with the same key (a concurrent duplicate waits here until the first one commits). Grocery orders stay
     * {@code CREATED} (inventory reserves the stock); restaurant orders move on to {@code AWAITING_PAYMENT} at once.
     *
     * @return empty when this call created the order; otherwise the earlier order's id
     * @throws org.springframework.dao.DataIntegrityViolationException when the order number is taken (the caller
     *                                                                  retries with a new number and id)
     */
    @Transactional
    public Optional<String> create(String scope, String key, String requestHash, CustomerOrder.Draft draft,
                                   Instant now) {
        Optional<String> earlier = idempotencyKeys.claim(scope, key, requestHash, draft.id().toString());
        if (earlier.isPresent()) {
            log.info("Idempotency-Key {} of {} was claimed meanwhile by order {}", key, scope, earlier.get());
            return earlier;
        }
        CustomerOrder order = CustomerOrder.create(draft, now);
        events.created(order);
        if (order.getType() == OrderType.RESTAURANT) {
            // the preview already checked menu, opening hours and accepting-orders: nothing to reserve
            order.awaitPayment(now);
            events.awaitingPayment(order);
        }
        orders.saveAndFlush(order);   // a taken order number fails here, inside the transaction
        log.info("Order {} ({}, {}) created for customer {}: total {}", order.getOrderNumber(), order.getId(),
                order.getType(), order.getCustomerId(), order.getTotal());
        return Optional.empty();
    }
}
