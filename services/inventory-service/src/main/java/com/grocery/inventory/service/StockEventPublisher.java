package com.grocery.inventory.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.entity.StockMovement;
import com.grocery.inventory.entity.StockReservation;
import com.grocery.inventory.event.InventoryEvents;
import com.grocery.inventory.service.ReservationFailedException.UnavailableItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes the inventory events to the outbox, in the caller's transaction: the stock events (aggregate
 * {@code Stock}, key productId), which carry the row's current {@code stockVersion} and are written while the
 * stock row lock is held (see {@link StockLocks}), and the reservation events of the order saga (aggregate
 * {@code Reservation}, key orderId), written in the same transaction as the reservation change.
 */
@Component
public class StockEventPublisher {

    /** Why held stock was given back ({@code StockReleased.reason}). */
    public enum ReleaseReason {
        ORDER_CANCELLED,
        RESERVATION_EXPIRED
    }

    private final OutboxPublisher outbox;

    public StockEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /** {@code StockAdjusted} for a vendor adjustment already applied to {@code stock}. */
    public void adjusted(StockItem stock, StockMovement movement) {
        publish(stock, InventoryEvents.STOCK_ADJUSTED, new InventoryEvents.StockAdjusted(
                stock.getProductId(), stock.getVendorId(), movement.getId(), movement.getType().name(),
                movement.getQuantityDelta(), stock.getOnHand(), stock.getReserved(), stock.available(),
                movement.getReason() == null ? null : movement.getReason().name(), movement.getActorUserId(),
                stock.getStockVersion()));
    }

    /**
     * The transition events of any stock change: {@code ProductAvailabilityChanged} when the product went in ↔ out
     * of stock, {@code LowStockDetected} when available dropped from above the threshold to the threshold or below.
     *
     * @param availableBefore available stock before the change
     * @param productName     read only when a low-stock event is due
     */
    public void transitions(StockItem stock, int availableBefore, Supplier<String> productName) {
        boolean wasInStock = availableBefore > 0;
        if (wasInStock != stock.inStock()) {
            availability(stock);
        }
        int threshold = stock.getLowStockThreshold();
        if (threshold > 0 && availableBefore > threshold && stock.available() <= threshold) {
            publish(stock, InventoryEvents.LOW_STOCK_DETECTED, new InventoryEvents.LowStockDetected(
                    stock.getProductId(), stock.getVendorId(), productName.get(), stock.available(), threshold));
        }
    }

    /** The current availability with the current version (also used by republish, which changes nothing). */
    public void availability(StockItem stock) {
        publish(stock, InventoryEvents.PRODUCT_AVAILABILITY_CHANGED, new InventoryEvents.ProductAvailabilityChanged(
                stock.getProductId(), stock.getVendorId(), stock.inStock(), stock.getStockVersion()));
    }

    // ---- reservation events (aggregate Reservation, key orderId) ------------------------------------------

    /** {@code StockReserved} for a reservation just created. */
    public void reserved(StockReservation reservation) {
        publish(reservation.getOrderId(), InventoryEvents.STOCK_RESERVED, new InventoryEvents.StockReserved(
                reservation.getOrderId(), reservation.getId(),
                reservation.getItems().stream()
                        .map(item -> new InventoryEvents.Line(item.getProductId(), item.getQuantity()))
                        .toList(),
                reservation.getExpiresAt()));
    }

    /** {@code StockReservationFailed}: nothing was held for the order. */
    public void reservationFailed(UUID orderId, List<UnavailableItem> unavailable) {
        publish(orderId, InventoryEvents.STOCK_RESERVATION_FAILED, new InventoryEvents.StockReservationFailed(orderId,
                unavailable.stream()
                        .map(i -> new InventoryEvents.UnavailableItem(i.productId(), i.requested(), i.available()))
                        .toList()));
    }

    /** {@code StockReleased} for a reservation whose stock was just given back. */
    public void released(StockReservation reservation, ReleaseReason reason) {
        publish(reservation.getOrderId(), InventoryEvents.STOCK_RELEASED, new InventoryEvents.StockReleased(
                reservation.getOrderId(), reservation.getId(), reason.name()));
    }

    /** {@code StockCommitted} for a reservation whose stock just left the store (Phase 9, at pickup). */
    public void committed(StockReservation reservation, Instant committedAt) {
        publish(reservation.getOrderId(), InventoryEvents.STOCK_COMMITTED, new InventoryEvents.StockCommitted(
                reservation.getOrderId(), reservation.getId(),
                reservation.getItems().stream()
                        .map(item -> new InventoryEvents.Line(item.getProductId(), item.getQuantity()))
                        .toList(),
                committedAt));
    }

    /** {@code StockReservationExpired}, together with {@code StockReleased(RESERVATION_EXPIRED)}. */
    public void expired(StockReservation reservation) {
        publish(reservation.getOrderId(), InventoryEvents.STOCK_RESERVATION_EXPIRED,
                new InventoryEvents.StockReservationExpired(reservation.getOrderId(), reservation.getId()));
    }

    private void publish(StockItem stock, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(InventoryEvents.TOPIC, InventoryEvents.STOCK_AGGREGATE,
                stock.getProductId().toString(), eventType, payload));
    }

    private void publish(UUID orderId, String eventType, Object payload) {
        outbox.publish(OutboxEvent.of(InventoryEvents.TOPIC, InventoryEvents.RESERVATION_AGGREGATE,
                orderId.toString(), eventType, payload));
    }
}
