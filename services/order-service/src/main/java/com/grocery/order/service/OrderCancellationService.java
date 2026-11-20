package com.grocery.order.service;

import com.grocery.order.dto.OrderDtos.AdminOrderView;
import com.grocery.order.dto.OrderDtos.OrderView;
import com.grocery.order.dto.OrderMapper;
import com.grocery.order.entity.Actor;
import com.grocery.order.entity.CancellationReason;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.repository.StuckOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Cancellation by the customer ({@code POST /api/orders/{id}/cancel}) or an admin
 * ({@code POST /api/admin/orders/{id}/cancel}), Phase 6 contract §2 and §4 as narrowed by Phase 8 §4. Same rule as
 * the event consumers ({@link OrderLocks}): one transaction that locks the order row first, checks the status on the
 * locked row, cancels and writes the history entry and {@code OrderCancelled} (the single compensation trigger:
 * inventory releases the stock, payment cancels the session or refunds, the store order leaves the board).
 * <ul>
 *   <li>Customer: {@code CREATED}, {@code AWAITING_PAYMENT}, {@code CONFIRMED} (until the store accepts) →
 *       {@code CANCELLED}; later ({@code ACCEPTED}, {@code PREPARING}, {@code READY_FOR_PICKUP}) → 409
 *       {@code ORDER_NOT_CANCELLABLE} (the customer contacts support).</li>
 *   <li>Admin: any non-terminal status up to and including {@code READY_FOR_PICKUP} → {@code CANCELLED}.</li>
 *   <li>Nobody after pickup (Phase 9 §7): {@code OUT_FOR_DELIVERY}, {@code DELIVERED}, {@code DELIVERY_FAILED} → 409
 *       {@code ORDER_NOT_CANCELLABLE} with {@code currentStatus}, for the customer and for admins (a failed delivery
 *       is settled with an admin refund in payment-service).</li>
 *   <li>A paid order's payment becomes {@code REFUND_PENDING} (full refund), an unpaid one's {@code NOT_REQUIRED}.</li>
 *   <li>Already {@code CANCELLED} (by anyone) → the cancelled order, unchanged and without a second event
 *       (idempotent).</li>
 *   <li>{@code REJECTED} → 409 {@code ORDER_NOT_CANCELLABLE}.</li>
 *   <li>Unknown order, or another customer's → 404 {@code ORDER_NOT_FOUND}.</li>
 * </ul>
 * Phase 10: the cancellation resolves the order's open stuck flags ({@link StuckOrders}) in the same transaction.
 */
@Service
public class OrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

    private record Cancelled<T>(T view, OrderStatus previous) {
    }

    private final OrderLocks locks;
    private final OrderEventPublisher events;
    private final StuckOrders stuckOrders;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OrderCancellationService(OrderLocks locks, OrderEventPublisher events, StuckOrders stuckOrders,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.locks = locks;
        this.events = events;
        this.stuckOrders = stuckOrders;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * {@code note} = the customer's optional free-text reason (≤ 300); the cancellation reason is CUSTOMER_CANCELLED.
     * Only until the store accepts ({@link OrderStatus#isCancellable()}).
     */
    public OrderView cancelByCustomer(UUID customerId, UUID orderId, String note) {
        return cancel(orderId, customerId, CancellationReason.CUSTOMER_CANCELLED, Actor.CUSTOMER, customerId, note,
                OrderStatus::isCancellable, OrderMapper::view);
    }

    /**
     * {@code note} = the admin's required reason; the cancellation reason is ADMIN_CANCELLED. Any non-terminal status
     * up to and including READY_FOR_PICKUP ({@link OrderStatus#isAdminCancellable()}).
     */
    public AdminOrderView cancelByAdmin(UUID adminUserId, UUID orderId, String note) {
        return cancel(orderId, null, CancellationReason.ADMIN_CANCELLED, Actor.ADMIN, adminUserId, note,
                OrderStatus::isAdminCancellable, OrderMapper::adminView);
    }

    /**
     * @param owner   the customer who must own the order, or null (admin: any order)
     * @param allowed the caller's cancellation rule
     * @param view    maps the order inside the transaction (its lines and history are loaded lazily)
     */
    private <T> T cancel(UUID orderId, UUID owner, CancellationReason reason, Actor by, UUID actorUserId,
                         String note, Predicate<OrderStatus> allowed, Function<CustomerOrder, T> view) {
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        Cancelled<T> result = transaction.execute(status -> {
            CustomerOrder order = locks.lock(orderId);                       // 1. lock first
            if (order == null || (owner != null && !order.getCustomerId().equals(owner))) {
                throw OrderException.orderNotFound();
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                return new Cancelled<>(view.apply(order), null);              // idempotent: nothing changes
            }
            if (!allowed.test(order.getStatus())) {
                throw OrderException.notCancellable(order.getStatus());
            }
            Instant now = clock.instant();
            OrderStatus previous = order.cancel(reason, by, actorUserId, cleanNote, now);
            events.cancelled(order, previous);                                // same transaction
            stuckOrders.resolveOthers(orderId, OrderStatus.CANCELLED, now);
            return new Cancelled<>(view.apply(order), previous);
        });
        if (result.previous() != null) {
            log.info("Order {} cancelled by {} {} ({} → CANCELLED, {})", orderId, by, actorUserId, result.previous(),
                    reason);
        } else {
            log.debug("Order {} was already cancelled; {} cancel answered with the cancelled order", orderId, by);
        }
        return result.view();
    }
}
