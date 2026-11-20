package com.grocery.order.service;

import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.DeliveryAddress;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.StuckReason;
import com.grocery.order.event.OrderEvents;
import com.grocery.order.repository.OrderEventLog;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes the order events to the outbox, in the caller's transaction (which created the order or holds its row
 * lock, see {@link OrderLocks}). Every event is keyed by the order id. Phase 10: every published event is also a
 * PUBLISHED entry of the order's event timeline ({@link OrderEventLog}), in the same transaction — Phase 12's return
 * events included (keyed by their order).
 */
@Component
public class OrderEventPublisher {

    private final OutboxPublisher outbox;
    private final OrderEventLog eventLog;
    private final Clock clock;

    public OrderEventPublisher(OutboxPublisher outbox, OrderEventLog eventLog, Clock clock) {
        this.outbox = outbox;
        this.eventLog = eventLog;
        this.clock = clock;
    }

    /**
     * Phase 12: with the coupon ({@code couponCode}, {@code discount}, {@code deliveryFeeDiscount}; null without);
     * 1.7.0: its {@code couponName} and {@code couponFundedBy} (null without, and for older orders).
     */
    public void created(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_CREATED, new OrderEvents.OrderCreated(order.getId(), order.getOrderNumber(),
                order.getType(), order.getCustomerId(), order.getStoreId(), order.getStoreName(), items(order),
                order.getSubtotal(), order.getDeliveryFee(), order.getTotal(), order.getCurrency(),
                order.getCartUpdatedAt(), order.getPaymentDueAt(), order.getCreatedAt(), order.getCouponCode(),
                couponAmount(order, order.getDiscount()), couponAmount(order, order.getDeliveryFeeDiscount()),
                order.getCouponName(), order.getCouponFundedBy()));
    }

    public void awaitingPayment(CustomerOrder order) {
        DeliveryAddress address = order.getDeliveryAddress();
        publish(order, OrderEvents.ORDER_AWAITING_PAYMENT, new OrderEvents.OrderAwaitingPayment(order.getId(),
                order.getOrderNumber(), order.getCustomerId(), order.getTotal(), order.getCurrency(),
                order.getPaymentDueAt(), address == null ? null : address.getPhone()));
    }

    public void confirmed(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_CONFIRMED, new OrderEvents.OrderConfirmed(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(), items(order),
                order.getTotal(), address(order.getDeliveryAddress()), order.getConfirmedAt(), order.getSubtotal(),
                order.getNote(), storeLocation(order), order.getCouponCode(), couponAmount(order, order.getDiscount()),
                couponAmount(order, order.getDeliveryFeeDiscount()), order.getCouponName(),
                order.getCouponFundedBy()));
    }

    public void accepted(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_ACCEPTED, new OrderEvents.OrderAccepted(order.getId(), order.getOrderNumber(),
                order.getType(), order.getCustomerId(), order.getStoreId(), order.getReadyInMinutes(),
                order.getReadyBy(), order.getAcceptedAt()));
    }

    public void preparing(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_PREPARING, new OrderEvents.OrderPreparing(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(),
                order.getPreparingAt()));
    }

    public void readyForPickup(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_READY_FOR_PICKUP, new OrderEvents.OrderReadyForPickup(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(),
                order.getReadyAt()));
    }

    /** Phase 9: the partner of the pickup and its time. */
    public void outForDelivery(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_OUT_FOR_DELIVERY, new OrderEvents.OrderOutForDelivery(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(),
                order.getPartnerId(), order.getPickedUpAt()));
    }

    public void delivered(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_DELIVERED, new OrderEvents.OrderDelivered(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(),
                order.getPartnerId(), order.getDeliveredAt()));
    }

    public void deliveryFailed(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_DELIVERY_FAILED, new OrderEvents.OrderDeliveryFailed(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(),
                order.getPartnerId(), order.getDeliveryFailureReason(), order.getDeliveryFailureNote(),
                order.getDeliveryFailedAt()));
    }

    public void rejected(CustomerOrder order) {
        publish(order, OrderEvents.ORDER_REJECTED, new OrderEvents.OrderRejected(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(),
                order.getRejectionReason(), order.getRejectedItems().stream()
                .map(item -> new OrderEvents.UnavailableItem(item.getItemId(), item.getRequested(),
                        item.getAvailable()))
                .toList()));
    }

    public void cancelled(CustomerOrder order, OrderStatus previousStatus) {
        publish(order, OrderEvents.ORDER_CANCELLED, new OrderEvents.OrderCancelled(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(), previousStatus,
                order.getCancellationReason(), order.getCancelledBy(), order.getCancelledByUserId(),
                order.getCancellationNote(), order.getCancelledAt()));
    }

    /** Phase 10: the watchdog found the order stuck in its current status (first detection only). */
    public void stuckDetected(CustomerOrder order, StuckReason reason, Instant since, Instant detectedAt) {
        publish(order, OrderEvents.ORDER_STUCK_DETECTED, new OrderEvents.OrderStuckDetected(order.getId(),
                order.getOrderNumber(), order.getType(), order.getCustomerId(), order.getStoreId(), order.getStatus(),
                reason, since, detectedAt));
    }

    // ---- Phase 12 §3: returns, keyed by the order id; the return snapshots the order's number, type and store ------

    public void returnRequested(OrderReturn request) {
        publish(request.getOrderId(), OrderEvents.RETURN_REQUESTED, new OrderEvents.ReturnRequested(request.getId(),
                request.getReturnNumber(), request.getOrderId(), request.getOrderNumber(), request.getOrderType(),
                request.getCustomerId(), request.getStoreId(), returnLines(request), request.getRefundAmount(),
                request.getReason(), request.getNote(), request.getDecideBy(), request.getRequestedAt()));
    }

    public void returnApproved(OrderReturn request) {
        publish(request.getOrderId(), OrderEvents.RETURN_APPROVED, new OrderEvents.ReturnApproved(request.getId(),
                request.getReturnNumber(), request.getOrderId(), request.getOrderNumber(), request.getOrderType(),
                request.getCustomerId(), request.getStoreId(), returnLines(request), request.getRefundAmount(),
                Boolean.TRUE.equals(request.getRestock()), request.getDecidedBy(), request.getDecidedByRole(),
                request.getDecidedAt()));
    }

    public void returnRejected(OrderReturn request) {
        publish(request.getOrderId(), OrderEvents.RETURN_REJECTED, new OrderEvents.ReturnRejected(request.getId(),
                request.getReturnNumber(), request.getOrderId(), request.getOrderNumber(), request.getOrderType(),
                request.getCustomerId(), request.getStoreId(), request.getComment(), request.getDecidedBy(),
                request.getDecidedByRole(), request.getDecidedAt()));
    }

    public void returnEscalated(OrderReturn request) {
        publish(request.getOrderId(), OrderEvents.RETURN_ESCALATED, new OrderEvents.ReturnEscalated(request.getId(),
                request.getReturnNumber(), request.getOrderId(), request.getOrderNumber(), request.getOrderType(),
                request.getCustomerId(), request.getStoreId(), request.getRefundAmount(), request.getRequestedAt(),
                request.getEscalatedAt()));
    }

    private static List<OrderEvents.ReturnLine> returnLines(OrderReturn request) {
        return request.getItems().stream()
                .map(item -> new OrderEvents.ReturnLine(item.getItemId(), item.getName(), item.getQuantity(),
                        item.getRefundAmount()))
                .toList();
    }

    /** A coupon amount for the events: the amount when the order has a coupon (0.00 included), else null. */
    private static BigDecimal couponAmount(CustomerOrder order, BigDecimal amount) {
        return order.hasCoupon() ? amount : null;
    }

    private static List<OrderEvents.Item> items(CustomerOrder order) {
        return order.getItems().stream()
                .map(item -> new OrderEvents.Item(item.getItemId(), item.getType(), item.getName(),
                        item.getQuantity(), item.getUnitPrice(), item.getLineTotal()))
                .toList();
    }

    /** The pickup point for delivery-service: only with both coordinates (the schema requires them), else null. */
    private static OrderEvents.StoreLocation storeLocation(CustomerOrder order) {
        if (order.getStoreLatitude() == null || order.getStoreLongitude() == null) {
            return null;
        }
        return new OrderEvents.StoreLocation(order.getStoreName(), order.getStoreCity(), order.getStoreLatitude(),
                order.getStoreLongitude());
    }

    private static OrderEvents.Address address(DeliveryAddress address) {
        if (address == null) {
            return null;
        }
        return new OrderEvents.Address(address.getLabel(), address.getRecipientName(), address.getPhone(),
                address.getLine1(), address.getLine2(), address.getLandmark(), address.getCity(), address.getState(),
                address.getPostalCode(), address.getLatitude(), address.getLongitude());
    }

    /**
     * The outbox row and the timeline entry. The entry's correlation id follows the outbox's rule (the current one,
     * else the event's own id).
     */
    private void publish(CustomerOrder order, String eventType, Object payload) {
        publish(order.getId(), eventType, payload);
    }

    private void publish(UUID orderId, String eventType, Object payload) {
        UUID eventId = outbox.publish(OutboxEvent.of(OrderEvents.TOPIC, OrderEvents.ORDER_AGGREGATE,
                orderId.toString(), eventType, payload));
        Instant now = clock.instant();
        String correlationId = CorrelationId.current() != null ? CorrelationId.current() : eventId.toString();
        eventLog.published(orderId, eventId.toString(), eventType, OrderEvents.TOPIC, now, correlationId, now);
    }
}
