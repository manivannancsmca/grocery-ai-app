package com.grocery.order.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grocery.order.dto.ReturnDtos.ReturnSummary;
import com.grocery.order.entity.Actor;
import com.grocery.order.entity.CancellationReason;
import com.grocery.order.entity.EventDirection;
import com.grocery.order.entity.EventOutcome;
import com.grocery.order.entity.ItemType;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.PaymentStatus;
import com.grocery.order.entity.RejectionReason;
import com.grocery.order.entity.StuckReason;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Order API bodies (docs/api/phase-6-orders-and-payments.md §2, §4; Phase 8 §4 adds {@code acceptedAt},
 * {@code readyInMinutes}, {@code readyBy} and the admin views' {@code adminCancellable}; Phase 9 §7 adds
 * {@code delivery} and {@code trackable}; Phase 10 §4, §5 the stuck-order list and the order event timeline). Money is a JSON number with 2 decimals (INR); nulls are written out (e.g.
 * {@code "cancellation": null}).
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    // ---- requests ---------------------------------------------------------------------------------------------

    /**
     * POST /api/orders. {@code addressId} null = the customer's default address (same rule as the checkout preview);
     * {@code expectedTotal} = the total the customer saw, which must equal the live total. Phase 11:
     * {@code expectedCartUpdatedAt} (optional, ISO instant) = the {@code updatedAt} of the checkout preview the
     * customer saw; when present it must equal the live cart's (409 CART_CHANGED), so an order the customer approved
     * through the assistant holds exactly the items they saw.
     */
    public record PlaceOrderRequest(
            UUID addressId,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal expectedTotal,
            @Size(max = 300) String note,
            Instant expectedCartUpdatedAt) {
    }

    /** POST /api/orders/{id}/cancel — the body and its reason are optional. */
    public record CancelRequest(@Size(max = 300) String reason) {
    }

    /** POST /api/admin/orders/{id}/cancel — the reason is required. */
    public record AdminCancelRequest(@NotBlank @Size(max = 300) String reason) {
    }

    // ---- views ------------------------------------------------------------------------------------------------

    public record StoreView(UUID id, OrderType type, String name) {
    }

    public record ItemView(UUID itemId, ItemType type, String name, String unitLabel, String imageUrl,
                           String foodType, int quantity, BigDecimal unitPrice, BigDecimal mrp, BigDecimal lineTotal) {
    }

    public record AddressView(String label, String recipientName, String phone, String line1, String line2,
                              String landmark, String city, String state, String postalCode, Double latitude,
                              Double longitude) {
    }

    /** The most recent declined attempt, shown while the order is still payable. */
    public record FailureView(String code, String description, Instant at) {
    }

    public record PaymentView(PaymentStatus status, Instant dueAt, Instant paidAt, BigDecimal refundedAmount,
                              FailureView lastFailure) {
    }

    public record CancellationView(CancellationReason reason, String note, Actor by, Instant at) {
    }

    public record UnavailableItemView(UUID itemId, String name, int requested, int available) {
    }

    public record RejectionView(RejectionReason reason, List<UnavailableItemView> unavailableItems) {
    }

    /**
     * One timeline entry, oldest first; {@code reason} = the rejection / cancellation reason, or the failure reason of
     * a {@code DELIVERY_FAILED} entry.
     */
    public record TimelineEntry(OrderStatus status, Instant at, String reason) {
    }

    /**
     * The delivery (Phase 9 §7): the assigned partner's first name (null when unknown) and the delivery's times; null
     * on the order while no partner has been assigned.
     */
    public record DeliveryView(String partnerName, Instant assignedAt, Instant pickedUpAt, Instant deliveredAt) {
    }

    /**
     * Phase 12: {@code couponCode} (null without a coupon), {@code discount} and {@code deliveryFeeDiscount} (0.00
     * without) — {@code total} is the discounted total; {@code returnable} = the customer may request a return now,
     * {@code returns} = the order's return requests, oldest first.
     */
    public record OrderView(
            UUID id,
            String orderNumber,
            OrderType type,
            OrderStatus status,
            StoreView store,
            List<ItemView> items,
            int itemCount,
            BigDecimal subtotal,
            BigDecimal mrpTotal,
            BigDecimal savings,
            BigDecimal deliveryFee,
            String couponCode,
            BigDecimal discount,
            BigDecimal deliveryFeeDiscount,
            BigDecimal total,
            String currency,
            AddressView deliveryAddress,
            BigDecimal deliveryDistanceKm,
            String note,
            PaymentView payment,
            Instant acceptedAt,
            Integer readyInMinutes,
            Instant readyBy,
            boolean cancellable,
            CancellationView cancellation,
            RejectionView rejection,
            DeliveryView delivery,
            boolean trackable,
            List<TimelineEntry> timeline,
            boolean returnable,
            List<ReturnSummary> returns,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * {@code OrderView} + {@code customerId} and {@code adminCancellable} (admin detail and admin cancel), written
     * first. {@code adminCancellable}: an admin may cancel it now (any non-terminal status up to READY_FOR_PICKUP).
     */
    public record AdminOrderView(UUID customerId, boolean adminCancellable, @JsonUnwrapped OrderView order) {
    }

    /**
     * The payment in the lists. {@code dueAt} = the end of the payment window ({@code PaymentView.dueAt}), only while
     * the order is AWAITING_PAYMENT (null otherwise), so the list can say "Pay within 12 min" exactly.
     */
    public record PaymentStatusView(PaymentStatus status, Instant dueAt) {
    }

    /** Phase 12: {@code couponCode} (null without), {@code discount}, {@code deliveryFeeDiscount} (0.00 without). */
    public record OrderSummary(UUID id, String orderNumber, OrderType type, OrderStatus status, StoreView store,
                               int itemCount, String couponCode, BigDecimal discount, BigDecimal deliveryFeeDiscount,
                               BigDecimal total, String currency, String firstItemName, PaymentStatusView payment,
                               Instant createdAt) {
    }

    /** {@code OrderSummary} + {@code customerId} and {@code adminCancellable} (admin list), written first. */
    public record AdminOrderSummary(UUID customerId, boolean adminCancellable, @JsonUnwrapped OrderSummary order) {
    }

    /**
     * GET /api/admin/stats: one day in the business time zone. {@code orders}, {@code byStatus} (current status)
     * and {@code byType} count the orders created that day; {@code confirmedRevenue} sums the totals of the orders
     * confirmed (paid) that day and {@code averageOrderValue} divides it by their number (0 when none).
     */
    public record OrderStats(LocalDate date, String timezone, long orders, Map<OrderStatus, Long> byStatus,
                             Map<OrderType, Long> byType, BigDecimal confirmedRevenue, BigDecimal averageOrderValue,
                             String currency) {
    }

    // ---- Phase 10 (docs/api/phase-10-event-driven-hardening.md §4, §5) --------------------------------------------

    /** The store of a stuck order: {@code {id, name}}. */
    public record StuckStoreView(UUID id, String name) {
    }

    /**
     * GET /api/admin/orders/stuck: an order the watchdog flagged and that is still in the flagged {@code status};
     * {@code since} = when it entered the status, {@code minutesOverdue} = whole minutes past its threshold.
     */
    public record StuckOrderView(UUID orderId, String orderNumber, OrderType type, OrderStatus status,
                                 StuckReason reason, Instant since, Instant detectedAt, long minutesOverdue,
                                 StuckStoreView store, UUID customerId) {
    }

    /**
     * GET /api/admin/orders/{id}/events: one entry of the order event timeline. {@code detail} = the guard's reason of
     * an IGNORED event, the status change of an APPLIED one, or null.
     */
    public record OrderEventView(String eventId, String eventType, String sourceTopic, EventDirection direction,
                                 EventOutcome outcome, String detail, Instant occurredAt, Instant recordedAt,
                                 String correlationId) {
    }
}
