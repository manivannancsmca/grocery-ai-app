package com.grocery.order.service;

import com.grocery.order.config.OrderProperties;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.StatusChange;
import com.grocery.order.entity.StuckReason;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When an order is stuck (docs/api/phase-10-event-driven-hardening.md §4): per status, the moment it should have moved
 * on by ({@link #dueAt}) = a reference time of the order + a configurable threshold.
 * <table>
 *   <tr><th>Status</th><th>Due at</th><th>Reason</th></tr>
 *   <tr><td>CREATED</td><td>createdAt + created-after (2m)</td><td>STOCK_CHECK_OVERDUE</td></tr>
 *   <tr><td>AWAITING_PAYMENT</td><td>payment.dueAt + payment-grace (2m)</td><td>PAYMENT_EXPIRY_OVERDUE</td></tr>
 *   <tr><td>CONFIRMED</td><td>confirmedAt + confirmed-after (15m)</td><td>STORE_RESPONSE_OVERDUE</td></tr>
 *   <tr><td>ACCEPTED, PREPARING</td><td>readyBy + preparation-grace (30m)</td><td>PREPARATION_OVERDUE</td></tr>
 *   <tr><td>READY_FOR_PICKUP</td><td>readyAt + pickup-after (30m)</td><td>PICKUP_OVERDUE</td></tr>
 *   <tr><td>OUT_FOR_DELIVERY</td><td>pickedUpAt + delivery-after (90m)</td><td>DELIVERY_OVERDUE</td></tr>
 * </table>
 * The other statuses are terminal and never stuck. {@link #column} is the {@code customer_order} column of the
 * reference time (the watchdog's candidate query); an order without that time (null) is never flagged.
 */
public enum StuckRule {

    CREATED(OrderStatus.CREATED, StuckReason.STOCK_CHECK_OVERDUE, "created_at"),
    AWAITING_PAYMENT(OrderStatus.AWAITING_PAYMENT, StuckReason.PAYMENT_EXPIRY_OVERDUE, "payment_due_at"),
    CONFIRMED(OrderStatus.CONFIRMED, StuckReason.STORE_RESPONSE_OVERDUE, "confirmed_at"),
    ACCEPTED(OrderStatus.ACCEPTED, StuckReason.PREPARATION_OVERDUE, "ready_by"),
    PREPARING(OrderStatus.PREPARING, StuckReason.PREPARATION_OVERDUE, "ready_by"),
    READY_FOR_PICKUP(OrderStatus.READY_FOR_PICKUP, StuckReason.PICKUP_OVERDUE, "ready_at"),
    OUT_FOR_DELIVERY(OrderStatus.OUT_FOR_DELIVERY, StuckReason.DELIVERY_OVERDUE, "picked_up_at");

    private final OrderStatus status;
    private final StuckReason reason;
    private final String column;

    StuckRule(OrderStatus status, StuckReason reason, String column) {
        this.status = status;
        this.reason = reason;
        this.column = column;
    }

    /** The rule of a status; empty for the terminal statuses. */
    public static Optional<StuckRule> of(OrderStatus status) {
        for (StuckRule rule : values()) {
            if (rule.status == status) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public OrderStatus status() {
        return status;
    }

    public StuckReason reason() {
        return reason;
    }

    /** The {@code customer_order} column holding {@link #reference}; a constant, safe to put into SQL. */
    public String column() {
        return column;
    }

    public Duration threshold(OrderProperties.Watchdog settings) {
        return switch (this) {
            case CREATED -> settings.getCreatedAfter();
            case AWAITING_PAYMENT -> settings.getPaymentGrace();
            case CONFIRMED -> settings.getConfirmedAfter();
            case ACCEPTED, PREPARING -> settings.getPreparationGrace();
            case READY_FOR_PICKUP -> settings.getPickupAfter();
            case OUT_FOR_DELIVERY -> settings.getDeliveryAfter();
        };
    }

    /** The order's time the threshold counts from (null when the order has none). */
    public Instant reference(CustomerOrder order) {
        return switch (this) {
            case CREATED -> order.getCreatedAt();
            case AWAITING_PAYMENT -> order.getPaymentDueAt();
            case CONFIRMED -> order.getConfirmedAt();
            case ACCEPTED, PREPARING -> order.getReadyBy();
            case READY_FOR_PICKUP -> order.getReadyAt();
            case OUT_FOR_DELIVERY -> order.getPickedUpAt();
        };
    }

    /** When the order should have left the status; null = cannot tell (never flagged). */
    public Instant dueAt(CustomerOrder order, OrderProperties.Watchdog settings) {
        Instant reference = reference(order);
        return reference == null ? null : reference.plus(threshold(settings));
    }

    /**
     * When the order entered its status: the latest timeline entry of that status (order-service's own time), else the
     * reference time.
     */
    public Instant since(CustomerOrder order) {
        List<StatusChange> history = order.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getStatus() == status) {
                return history.get(i).getChangedAt();
            }
        }
        return reference(order);
    }
}
