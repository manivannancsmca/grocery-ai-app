package com.grocery.review.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.review.repository.OrderProjection;
import com.grocery.review.repository.OrderProjection.OrderItem;
import com.grocery.review.repository.OrderProjection.ProjectedOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * order.events → the eligibility projection (contract §1): {@code OrderConfirmed} gives the store, type and lines,
 * {@code OrderDelivered} the partner and the delivery time. Idempotent per event ({@code processed_event}, consumer
 * {@value #CONSUMER}), and each event writes only its own columns, so duplicates and either order are harmless.
 */
@Service
public class OrderEventsService {

    public static final String CONSUMER = "review-service.order-events";

    private static final Logger log = LoggerFactory.getLogger(OrderEventsService.class);

    private final ProcessedEventGuard guard;
    private final OrderProjection projection;
    private final Clock clock;

    public OrderEventsService(ProcessedEventGuard guard, OrderProjection projection, Clock clock) {
        this.guard = guard;
        this.projection = projection;
        this.clock = clock;
    }

    @Transactional
    public void orderConfirmed(String eventId, ProjectedOrder order, List<OrderItem> items) {
        if (!guard.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderConfirmed {} already handled", eventId);
            return;
        }
        projection.confirmed(order, items, clock.instant());
    }

    @Transactional
    public void orderDelivered(String eventId, ProjectedOrder order) {
        if (!guard.markProcessed(eventId, CONSUMER)) {
            log.debug("OrderDelivered {} already handled", eventId);
            return;
        }
        projection.delivered(order, clock.instant());
    }
}
