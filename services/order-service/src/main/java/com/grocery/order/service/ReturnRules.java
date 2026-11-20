package com.grocery.order.service;

import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.OrderItem;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.PaymentStatus;
import com.grocery.order.entity.ReturnItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The rules of a return request (docs/api/phase-12-post-mvp-features.md §3), as plain functions of the order, its
 * earlier returns and the clock — computed by the server, never by the client.
 * <ul>
 *   <li><b>Who may request:</b> the customer of a DELIVERED order, until {@code deliveredAt + window} (48 h by
 *       default, an admin platform setting), while no other request of the order is open (REQUESTED / ESCALATED),
 *       something is left to return and the payment is not fully refunded already.</li>
 *   <li><b>What is left</b> of a line: the ordered quantity minus the quantities of every return that is not
 *       REJECTED (open, approved or refunded).</li>
 *   <li><b>Refund per line:</b> {@code lineTotal × quantity / orderedQuantity × (1 − discount / subtotal)}, computed
 *       exactly and rounded once to 0.01 (half up).</li>
 *   <li><b>Delivery fee:</b> when the request returns every remaining unit of the order, the paid delivery fee
 *       ({@code deliveryFee − deliveryFeeDiscount}) is added.</li>
 * </ul>
 */
public final class ReturnRules {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private ReturnRules() {
    }

    /** One requested line (quantities of the same item are added up). */
    public record Requested(UUID itemId, int quantity) {
    }

    /** Why the order cannot be returned now: the message and the problem properties. */
    public record Refusal(String message, Map<String, Object> properties) {
    }

    /** A request that passed every rule: its lines with their refunds and the delivery fee part. */
    public record Evaluated(List<ReturnItem> items, BigDecimal deliveryFeeRefund) {

        public BigDecimal refundAmount() {
            return items.stream().map(ReturnItem::getRefundAmount).reduce(deliveryFeeRefund, BigDecimal::add);
        }
    }

    /** When the return window of a delivered order ends (null when the order was never delivered). */
    public static Instant returnableUntil(CustomerOrder order, Duration window) {
        return order.getDeliveredAt() == null ? null : order.getDeliveredAt().plus(window);
    }

    /**
     * Why {@code order} cannot be returned at {@code now} — empty when it can ({@code OrderView.returnable}).
     *
     * @param returns every return of the order
     */
    public static Optional<Refusal> refusal(CustomerOrder order, List<OrderReturn> returns, Instant now,
                                            Duration window) {
        if (order.getStatus() != OrderStatus.DELIVERED) {
            return Optional.of(new Refusal("Only a delivered order can be returned",
                    Map.of("currentStatus", order.getStatus().name())));
        }
        Instant until = returnableUntil(order, window);
        if (until == null || now.isAfter(until)) {
            Map<String, Object> properties = new LinkedHashMap<>();
            if (until != null) {
                properties.put("returnableUntil", until.toString());
            }
            return Optional.of(new Refusal("The return window of this order is over", properties));
        }
        Optional<OrderReturn> open = returns.stream().filter(r -> r.getStatus().isOpen()).findFirst();
        if (open.isPresent()) {
            return Optional.of(new Refusal("Return " + open.get().getReturnNumber()
                    + " of this order is still open", Map.of("openReturnNumber", open.get().getReturnNumber())));
        }
        if (order.getPaymentStatus() == PaymentStatus.REFUNDED) {
            return Optional.of(new Refusal("This order has been refunded in full",
                    Map.of("paymentStatus", PaymentStatus.REFUNDED.name())));
        }
        if (remaining(order, returns).values().stream().allMatch(left -> left <= 0)) {
            return Optional.of(new Refusal("Every item of this order has been returned", Map.of()));
        }
        return Optional.empty();
    }

    /** What is left to return of each line, by item id, in order-line order. */
    public static Map<UUID, Integer> remaining(CustomerOrder order, List<OrderReturn> returns) {
        Map<UUID, Integer> left = new LinkedHashMap<>();
        for (OrderItem line : order.getItems()) {
            left.merge(line.getItemId(), line.getQuantity(), Integer::sum);
        }
        for (OrderReturn earlier : returns) {
            if (earlier.getStatus().holdsQuantities()) {
                for (ReturnItem item : earlier.getItems()) {
                    left.computeIfPresent(item.getItemId(), (id, quantity) -> quantity - item.getQuantity());
                }
            }
        }
        return left;
    }

    /**
     * The offending lines of {@code requested} (more than is left, or not a line of the order) as
     * {@code {itemId, requested, returnable}}; empty when every line fits.
     */
    public static List<Map<String, Object>> exceeded(Map<UUID, Integer> remaining, List<Requested> requested) {
        List<Map<String, Object>> offending = new ArrayList<>();
        for (Requested line : requested) {
            int left = Math.max(0, remaining.getOrDefault(line.itemId(), 0));
            if (line.quantity() > left) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("itemId", line.itemId().toString());
                item.put("requested", line.quantity());
                item.put("returnable", left);
                offending.add(item);
            }
        }
        return offending;
    }

    /**
     * The refunds of a request that fits ({@link #exceeded} empty): one return line per requested line, with its
     * prorated refund, plus the paid delivery fee when it returns every remaining unit of the order.
     */
    public static Evaluated evaluate(CustomerOrder order, Map<UUID, Integer> remaining, List<Requested> requested) {
        Map<UUID, OrderItem> lines = new LinkedHashMap<>();
        for (OrderItem line : order.getItems()) {
            lines.putIfAbsent(line.getItemId(), line);
        }
        List<ReturnItem> items = new ArrayList<>();
        Map<UUID, Integer> asked = new LinkedHashMap<>();
        for (Requested line : requested) {
            OrderItem orderLine = lines.get(line.itemId());
            items.add(new ReturnItem(orderLine.getItemId(), orderLine.getName(), line.quantity(),
                    lineRefund(orderLine, line.quantity(), order.getSubtotal(), order.getDiscount())));
            asked.merge(line.itemId(), line.quantity(), Integer::sum);
        }
        boolean everything = remaining.entrySet().stream()
                .allMatch(left -> asked.getOrDefault(left.getKey(), 0) >= left.getValue());
        return new Evaluated(items, everything ? paidDeliveryFee(order) : ZERO);
    }

    /**
     * {@code lineTotal × quantity / orderedQuantity × (1 − discount / subtotal)}, rounded to 0.01 (half up) — as one
     * exact fraction, {@code lineTotal × quantity × (subtotal − discount) / (orderedQuantity × subtotal)}, so it is
     * rounded once.
     */
    public static BigDecimal lineRefund(OrderItem line, int quantity, BigDecimal subtotal, BigDecimal discount) {
        if (subtotal == null || subtotal.signum() <= 0 || line.getQuantity() <= 0) {
            return ZERO;
        }
        BigDecimal off = discount == null ? BigDecimal.ZERO : discount;
        BigDecimal numerator = line.getLineTotal().multiply(BigDecimal.valueOf(quantity))
                .multiply(subtotal.subtract(off));
        BigDecimal denominator = BigDecimal.valueOf(line.getQuantity()).multiply(subtotal);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP).max(ZERO);
    }

    /** The delivery fee the customer paid: {@code deliveryFee − deliveryFeeDiscount} (never negative). */
    public static BigDecimal paidDeliveryFee(CustomerOrder order) {
        BigDecimal fee = order.getDeliveryFee().subtract(order.getDeliveryFeeDiscount());
        return fee.signum() < 0 ? ZERO : fee.setScale(2, RoundingMode.HALF_UP);
    }
}
