package com.grocery.order.service;

import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.ItemType;
import com.grocery.order.entity.OrderItem;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.ReturnItem;
import com.grocery.order.entity.ReturnReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The return rules of docs/api/phase-12-post-mvp-features.md §3 without a database: the refund per line
 * ({@code lineTotal × quantity / orderedQuantity × (1 − discount / subtotal)}, rounded once to 0.01), the paid delivery
 * fee on a return of everything left, what is left after earlier returns, and who may ask when.
 */
class ReturnRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(48);
    private static final UUID RICE = UUID.randomUUID();
    private static final UUID DAL = UUID.randomUUID();

    /** 2 × rice 50.00 (100.00) + 1 × dal 120.00 = 220.00, fee 25.00, with the coupon's amounts. */
    private static CustomerOrder order(String discount, String feeDiscount) {
        List<OrderItem> items = List.of(
                new OrderItem(RICE, ItemType.PRODUCT, "Rice", "1 kg", null, "VEG", 2, new BigDecimal("50.00"), null,
                        new BigDecimal("100.00")),
                new OrderItem(DAL, ItemType.PRODUCT, "Dal", "500 g", null, "VEG", 1, new BigDecimal("120.00"), null,
                        new BigDecimal("120.00")));
        BigDecimal off = new BigDecimal(discount);
        BigDecimal feeOff = new BigDecimal(feeDiscount);
        CustomerOrder.Coupon coupon = off.signum() == 0 && feeOff.signum() == 0
                ? CustomerOrder.Coupon.NONE
                : new CustomerOrder.Coupon("SAVE", UUID.randomUUID(), off, feeOff, "Save", "PLATFORM");
        BigDecimal total = new BigDecimal("245.00").subtract(off).subtract(feeOff);
        return CustomerOrder.create(new CustomerOrder.Draft(UUID.randomUUID(), "GA-00000001", UUID.randomUUID(),
                OrderType.GROCERY, UUID.randomUUID(), "Store", null, null, null, items, 3, new BigDecimal("220.00"),
                new BigDecimal("220.00"), new BigDecimal("0.00"), new BigDecimal("25.00"), total, null, null, null,
                null, NOW.plusSeconds(900), coupon), NOW.minusSeconds(7200));
    }

    /** The order taken through to DELIVERED at {@code deliveredAt}. */
    private static CustomerOrder delivered(CustomerOrder order, Instant deliveredAt) {
        order.awaitPayment(NOW);
        order.confirm(null, null, NOW);
        order.accept(15, NOW, NOW, NOW);
        order.readyForPickup(NOW, NOW);
        UUID partner = UUID.randomUUID();
        order.pickUp(partner, NOW, NOW);
        order.deliver(partner, deliveredAt, NOW);
        return order;
    }

    private static OrderItem line(CustomerOrder order, UUID itemId) {
        return order.getItems().stream().filter(item -> item.getItemId().equals(itemId)).findFirst().orElseThrow();
    }

    @Test
    void theRefundOfALineIsProratedByQuantityAndCouponDiscount() {
        CustomerOrder plain = order("0.00", "0.00");
        assertThat(ReturnRules.lineRefund(line(plain, RICE), 1, plain.getSubtotal(), plain.getDiscount()))
                .isEqualByComparingTo("50.00");
        assertThat(ReturnRules.lineRefund(line(plain, RICE), 2, plain.getSubtotal(), plain.getDiscount()))
                .isEqualByComparingTo("100.00");

        CustomerOrder percent = order("22.00", "0.00");                       // 10 %
        assertThat(ReturnRules.lineRefund(line(percent, RICE), 1, percent.getSubtotal(), percent.getDiscount()))
                .isEqualByComparingTo("45.00");
        assertThat(ReturnRules.lineRefund(line(percent, DAL), 1, percent.getSubtotal(), percent.getDiscount()))
                .isEqualByComparingTo("108.00");

        CustomerOrder flat = order("10.00", "0.00");                          // 210 / 220, rounded half up once
        assertThat(ReturnRules.lineRefund(line(flat, RICE), 1, flat.getSubtotal(), flat.getDiscount()))
                .isEqualByComparingTo("47.73");                                // 47.7272…
        assertThat(ReturnRules.lineRefund(line(flat, RICE), 2, flat.getSubtotal(), flat.getDiscount()))
                .isEqualByComparingTo("95.45");                                // 95.4545…
        assertThat(ReturnRules.lineRefund(line(flat, DAL), 1, flat.getSubtotal(), flat.getDiscount()))
                .isEqualByComparingTo("114.55");                               // 114.5454…
        assertThat(ReturnRules.lineRefund(line(flat, RICE), 1, flat.getSubtotal(), flat.getDiscount()).scale())
                .isEqualTo(2);
    }

    @Test
    void theDeliveryFeeIsAddedOnlyWhenEverythingLeftIsReturned() {
        CustomerOrder plain = order("0.00", "0.00");
        Map<UUID, Integer> remaining = ReturnRules.remaining(plain, List.of());
        assertThat(remaining).containsExactly(Map.entry(RICE, 2), Map.entry(DAL, 1));

        ReturnRules.Evaluated partial = ReturnRules.evaluate(plain, remaining,
                List.of(new ReturnRules.Requested(RICE, 2)));
        assertThat(partial.deliveryFeeRefund()).isEqualByComparingTo("0.00");
        assertThat(partial.refundAmount()).isEqualByComparingTo("100.00");

        ReturnRules.Evaluated everything = ReturnRules.evaluate(plain, remaining,
                List.of(new ReturnRules.Requested(RICE, 2), new ReturnRules.Requested(DAL, 1)));
        assertThat(everything.deliveryFeeRefund()).isEqualByComparingTo("25.00");
        assertThat(everything.refundAmount()).isEqualByComparingTo("245.00");

        // free delivery: the customer paid no fee, so none is refunded
        CustomerOrder freeDelivery = order("0.00", "25.00");
        assertThat(ReturnRules.paidDeliveryFee(freeDelivery)).isEqualByComparingTo("0.00");
        assertThat(ReturnRules.evaluate(freeDelivery, ReturnRules.remaining(freeDelivery, List.of()),
                List.of(new ReturnRules.Requested(RICE, 2), new ReturnRules.Requested(DAL, 1))).refundAmount())
                .isEqualByComparingTo("220.00");
    }

    @Test
    void earlierReturnsTakeTheirQuantitiesUnlessRejected() {
        CustomerOrder order = delivered(order("22.00", "0.00"), NOW.minusSeconds(3600));
        OrderReturn approved = returnOf(order, RICE, 1);
        approved.approve(true, UUID.randomUUID(), com.grocery.order.entity.DeciderRole.STORE, NOW);
        OrderReturn rejected = returnOf(order, DAL, 1);
        rejected.reject("No", UUID.randomUUID(), com.grocery.order.entity.DeciderRole.STORE, NOW);

        Map<UUID, Integer> remaining = ReturnRules.remaining(order, List.of(approved, rejected));
        assertThat(remaining).containsExactly(Map.entry(RICE, 1), Map.entry(DAL, 1));
        assertThat(ReturnRules.exceeded(remaining, List.of(new ReturnRules.Requested(RICE, 2))))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("returnable", 1)
                        .containsEntry("requested", 2));
        assertThat(ReturnRules.exceeded(remaining, List.of(new ReturnRules.Requested(UUID.randomUUID(), 1))))
                .singleElement().satisfies(item -> assertThat(item).containsEntry("returnable", 0));

        // the rest (rice 1 + dal 1) is everything that is left: + the 25.00 fee
        ReturnRules.Evaluated rest = ReturnRules.evaluate(order, remaining,
                List.of(new ReturnRules.Requested(RICE, 1), new ReturnRules.Requested(DAL, 1)));
        assertThat(rest.refundAmount()).isEqualByComparingTo("178.00");        // 45.00 + 108.00 + 25.00
    }

    @Test
    void whoMayAskAndWhen() {
        assertThat(ReturnRules.refusal(order("0.00", "0.00"), List.of(), NOW, WINDOW))
                .hasValueSatisfying(refusal -> assertThat(refusal.properties())
                        .containsEntry("currentStatus", OrderStatus.CREATED.name()));

        CustomerOrder recent = delivered(order("0.00", "0.00"), NOW.minus(Duration.ofHours(47)));
        assertThat(ReturnRules.refusal(recent, List.of(), NOW, WINDOW)).isEmpty();
        assertThat(ReturnRules.returnableUntil(recent, WINDOW)).isEqualTo(NOW.plus(Duration.ofHours(1)));

        CustomerOrder old = delivered(order("0.00", "0.00"), NOW.minus(Duration.ofHours(48)).minusSeconds(1));
        assertThat(ReturnRules.refusal(old, List.of(), NOW, WINDOW))
                .hasValueSatisfying(refusal -> assertThat(refusal.properties()).containsKey("returnableUntil"));

        OrderReturn open = returnOf(recent, RICE, 1);
        assertThat(ReturnRules.refusal(recent, List.of(open), NOW, WINDOW))
                .hasValueSatisfying(refusal -> assertThat(refusal.properties())
                        .containsEntry("openReturnNumber", open.getReturnNumber()));
        open.escalate(NOW);                                                      // escalated is still open
        assertThat(ReturnRules.refusal(recent, List.of(open), NOW, WINDOW)).isPresent();
        open.approve(false, UUID.randomUUID(), com.grocery.order.entity.DeciderRole.ADMIN, NOW);
        assertThat(ReturnRules.refusal(recent, List.of(open), NOW, WINDOW)).isEmpty();

        OrderReturn all = returnOf(recent, DAL, 1);
        OrderReturn rest = returnOf(recent, RICE, 1);
        all.approve(false, UUID.randomUUID(), com.grocery.order.entity.DeciderRole.ADMIN, NOW);
        rest.approve(false, UUID.randomUUID(), com.grocery.order.entity.DeciderRole.ADMIN, NOW);
        assertThat(ReturnRules.refusal(recent, List.of(open, all, rest), NOW, WINDOW)).isPresent();   // nothing left
    }

    private static OrderReturn returnOf(CustomerOrder order, UUID itemId, int quantity) {
        OrderItem line = line(order, itemId);
        BigDecimal refund = ReturnRules.lineRefund(line, quantity, order.getSubtotal(), order.getDiscount());
        return OrderReturn.request(UUID.randomUUID(), "RT-" + UUID.randomUUID().toString().substring(0, 8), order,
                List.of(new ReturnItem(itemId, line.getName(), quantity, refund)), new BigDecimal("0.00"),
                ReturnReason.DAMAGED, null, NOW.plus(Duration.ofHours(24)), NOW);
    }
}
