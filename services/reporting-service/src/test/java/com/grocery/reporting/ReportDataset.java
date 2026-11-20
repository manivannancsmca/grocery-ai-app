package com.grocery.reporting;

import com.grocery.reporting.TestEvents.Item;
import com.grocery.reporting.TestEvents.Order;
import com.grocery.reporting.TestEvents.ReceivedLine;
import com.grocery.reporting.TestEvents.ReturnedLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grocery.reporting.TestEvents.dish;
import static com.grocery.reporting.TestEvents.goodsReceived;
import static com.grocery.reporting.TestEvents.grocery;
import static com.grocery.reporting.TestEvents.ist;
import static com.grocery.reporting.TestEvents.money;
import static com.grocery.reporting.TestEvents.orderCancelled;
import static com.grocery.reporting.TestEvents.orderConfirmed;
import static com.grocery.reporting.TestEvents.orderCreated;
import static com.grocery.reporting.TestEvents.orderDelivered;
import static com.grocery.reporting.TestEvents.orderDeliveryFailed;
import static com.grocery.reporting.TestEvents.orderRejected;
import static com.grocery.reporting.TestEvents.partnerAssigned;
import static com.grocery.reporting.TestEvents.paymentCaptured;
import static com.grocery.reporting.TestEvents.pickedUp;
import static com.grocery.reporting.TestEvents.product;
import static com.grocery.reporting.TestEvents.ratingSummary;
import static com.grocery.reporting.TestEvents.refundCompleted;
import static com.grocery.reporting.TestEvents.restaurant;
import static com.grocery.reporting.TestEvents.returnApproved;
import static com.grocery.reporting.TestEvents.reviewSubmitted;

/**
 * The small fixed dataset every report test checks by hand. The report range is 2026-10-05 … 2026-10-06 (India);
 * O0 (2026-10-01) and the 2026-10-07 events lie outside it.
 *
 * <pre>
 * order store        cust placed (IST)   lines                    fee   coupon            total  what happens
 * O0    Green Basket C1   10-01 10:00    Milk 1×40                30                      70.00  confirmed (before the range)
 * O1    Green Basket C1   10-05 10:00    Apples 2×50, Milk 1×40   30                     170.00  confirmed 10:02; assigned A 10:12, B 10:17,
 *                                                                                                picked up 10:32, delivered 11:02
 * O2    Green Basket C2   10-05 12:00    Apples 4×50              30    SAVE10 −20.00    210.00  confirmed 12:02; assigned 12:22, picked up 12:32,
 *                                                                                                delivered 13:12; return Apples 1 → 45.00
 *                                                                                                approved 10-06 09:00, refunded 09:05
 * O3    Spice Route  C3   10-05 20:00    Biryani 1×250            40    FREEDEL −40 fee  250.00  confirmed 20:01; cancelled 20:05; refund 250.00
 * O4    Fresh,"Daily" C1  10-06 09:00    "Bread, brown" 3×30      30    SAVE10 −9.00     111.00  confirmed 09:01; assigned 09:30, picked up 09:50,
 *                                                                                                delivery failed 10:30
 * O5    Green Basket C2   10-06 11:00    Milk 1×40                30                      70.00  payment timeout 11:15; late capture 11:20 refunded
 *                                                                                                (LATE_CAPTURE) 11:21
 * O6    Green Basket C4   10-06 13:00    Apples 10×50             30                     530.00  rejected (out of stock) 13:01
 * </pre>
 * Coupons: SAVE10 is a platform coupon ("Save 10%"), FREEDEL Spice Route's own (STORE, "Free delivery at Spice
 * Route"). O2 was placed before schemas 1.7.0, so its events carry neither the name nor the funding; O3 and O4 do.
 */
public final class ReportDataset {

    public static final String FROM = "2026-10-05";
    public static final String TO = "2026-10-06";

    public final UUID greenBasket = UUID.randomUUID();
    public final UUID freshDaily = UUID.randomUUID();
    public final UUID spiceRoute = UUID.randomUUID();
    public static final String GREEN_BASKET = "Green Basket";
    public static final String FRESH_DAILY = "Fresh, \"Daily\" Mart";
    public static final String SPICE_ROUTE = "Spice Route";
    public static final String SAVE10_NAME = "Save 10%";
    public static final String FREEDEL_NAME = "Free delivery at Spice Route";

    public final UUID apples = UUID.randomUUID();
    public final UUID milk = UUID.randomUUID();
    public final UUID bread = UUID.randomUUID();
    public final UUID biryani = UUID.randomUUID();

    public final UUID c1 = UUID.randomUUID();
    public final UUID c2 = UUID.randomUUID();
    public final UUID c3 = UUID.randomUUID();
    public final UUID c4 = UUID.randomUUID();

    public final UUID partnerA = UUID.randomUUID();
    public final UUID partnerB = UUID.randomUUID();

    public final Order o0;
    public final Order o1;
    public final Order o2;
    public final Order o3;
    public final Order o4;
    public final Order o5;
    public final Order o6;

    public ReportDataset() {
        Item milk1 = product(milk, "Milk", 1, "40.00");
        o0 = grocery(c1, greenBasket, GREEN_BASKET, "30.00", milk1);
        o1 = grocery(c1, greenBasket, GREEN_BASKET, "30.00", product(apples, "Apples", 2, "50.00"), milk1);
        o2 = grocery(c2, greenBasket, GREEN_BASKET, "30.00", product(apples, "Apples", 4, "50.00"))
                .withCoupon("SAVE10", "20.00", null);                                // placed before 1.7.0
        o3 = restaurant(c3, spiceRoute, SPICE_ROUTE, "40.00", dish(biryani, "Biryani", 1, "250.00"))
                .withCoupon("FREEDEL", null, "40.00", FREEDEL_NAME, "STORE");
        o4 = grocery(c1, freshDaily, FRESH_DAILY, "30.00", product(bread, "Bread, brown", 3, "30.00"))
                .withCoupon("SAVE10", "9.00", null, SAVE10_NAME, "PLATFORM");
        o5 = grocery(c2, greenBasket, GREEN_BASKET, "30.00", milk1);
        o6 = grocery(c4, greenBasket, GREEN_BASKET, "30.00", product(apples, "Apples", 10, "50.00"));
    }

    /** The order, payment and delivery events (in the order the platform publishes them). */
    public List<ConsumerRecord<String, String>> orderEvents() {
        List<ConsumerRecord<String, String>> events = new ArrayList<>();
        // O0: before the range (C1's first order)
        events.add(orderCreated(o0, ist("2026-10-01T10:00")));
        events.add(paymentCaptured(o0.orderId(), o0.total(), ist("2026-10-01T10:01"), false));
        events.add(orderConfirmed(o0, ist("2026-10-01T10:01")));
        // O1: delivered, reassigned once
        UUID d1 = UUID.randomUUID();
        events.add(orderCreated(o1, ist("2026-10-05T10:00")));
        events.add(paymentCaptured(o1.orderId(), o1.total(), ist("2026-10-05T10:02"), false));
        events.add(orderConfirmed(o1, ist("2026-10-05T10:02")));
        events.add(partnerAssigned(o1, d1, partnerA, "Ravi Kumar", ist("2026-10-05T10:12")));
        events.add(partnerAssigned(o1, d1, partnerB, "Meena Iyer", ist("2026-10-05T10:17")));
        events.add(pickedUp(o1, d1, partnerB, ist("2026-10-05T10:32")));
        events.add(TestEvents.deliveryCompleted(o1, d1, partnerB, ist("2026-10-05T11:02")));
        events.add(orderDelivered(o1, partnerB, ist("2026-10-05T11:02")));
        // O2: coupon SAVE10, delivered, one apple returned and refunded the next day
        UUID d2 = UUID.randomUUID();
        UUID return2 = UUID.randomUUID();
        events.add(orderCreated(o2, ist("2026-10-05T12:00")));
        events.add(paymentCaptured(o2.orderId(), o2.total(), ist("2026-10-05T12:02"), false));
        events.add(orderConfirmed(o2, ist("2026-10-05T12:02")));
        events.add(partnerAssigned(o2, d2, partnerA, "Ravi Kumar", ist("2026-10-05T12:22")));
        events.add(pickedUp(o2, d2, partnerA, ist("2026-10-05T12:32")));
        events.add(TestEvents.deliveryCompleted(o2, d2, partnerA, ist("2026-10-05T13:12")));
        events.add(orderDelivered(o2, partnerA, ist("2026-10-05T13:12")));
        events.add(returnApproved(o2, return2, "45.00", ist("2026-10-06T09:00"),
                new ReturnedLine(apples, "Apples", 1, "45.00")));
        events.add(refundCompleted(o2.orderId(), UUID.randomUUID(), "45.00", "RETURN", return2,
                ist("2026-10-06T09:05")));
        // O3: free delivery, paid, then cancelled by the customer and refunded
        events.add(orderCreated(o3, ist("2026-10-05T20:00")));
        events.add(paymentCaptured(o3.orderId(), o3.total(), ist("2026-10-05T20:01"), false));
        events.add(orderConfirmed(o3, ist("2026-10-05T20:01")));
        events.add(orderCancelled(o3, ist("2026-10-05T20:05"), "CONFIRMED", "CUSTOMER_CANCELLED", "CUSTOMER"));
        events.add(refundCompleted(o3.orderId(), UUID.randomUUID(), "250.00", "ORDER_CANCELLED", null,
                ist("2026-10-05T20:06")));
        // O4: delivery failed
        UUID d4 = UUID.randomUUID();
        events.add(orderCreated(o4, ist("2026-10-06T09:00")));
        events.add(paymentCaptured(o4.orderId(), o4.total(), ist("2026-10-06T09:01"), false));
        events.add(orderConfirmed(o4, ist("2026-10-06T09:01")));
        events.add(partnerAssigned(o4, d4, partnerA, "Ravi Kumar", ist("2026-10-06T09:30")));
        events.add(pickedUp(o4, d4, partnerA, ist("2026-10-06T09:50")));
        events.add(orderDeliveryFailed(o4, partnerA, ist("2026-10-06T10:30")));
        // O5: never paid in time; the late capture is refunded automatically
        events.add(orderCreated(o5, ist("2026-10-06T11:00")));
        events.add(orderCancelled(o5, ist("2026-10-06T11:15"), "AWAITING_PAYMENT", "PAYMENT_TIMEOUT", "SYSTEM"));
        events.add(paymentCaptured(o5.orderId(), o5.total(), ist("2026-10-06T11:20"), true));
        events.add(refundCompleted(o5.orderId(), UUID.randomUUID(), "70.00", "LATE_CAPTURE", null,
                ist("2026-10-06T11:21")));
        // O6: out of stock
        events.add(orderCreated(o6, ist("2026-10-06T13:00")));
        events.add(orderRejected(o6, ist("2026-10-06T13:01")));
        return events;
    }

    /**
     * Ratings: Apples 4.50 (2; version 2 arrives before the older version 1), Biryani 2.00 (1), Green Basket 5.00
     * (3), partner A 3.00 (1), Spice Route 0 ratings (all removed: never listed). Reviews first submitted in the
     * range: 3 (Apples PUBLISHED, Biryani FLAGGED, Green Basket PUBLISHED then edited → FLAGGED); one on 10-07.
     */
    public List<ConsumerRecord<String, String>> reviewEvents() {
        UUID review3 = UUID.randomUUID();
        return List.of(
                ratingSummary("PRODUCT", apples, "4.50", 2, List.of(0, 0, 0, 1, 1), 2, ist("2026-10-06T12:00")),
                ratingSummary("PRODUCT", apples, "4.00", 1, List.of(0, 0, 0, 1, 0), 1, ist("2026-10-05T15:00")),
                ratingSummary("MENU_ITEM", biryani, "2.00", 1, List.of(0, 1, 0, 0, 0), 1, ist("2026-10-05T21:00")),
                ratingSummary("VENDOR", greenBasket, "5.00", 3, List.of(0, 0, 0, 0, 3), 3, ist("2026-10-06T10:00")),
                ratingSummary("DELIVERY_PARTNER", partnerA, "3.00", 1, List.of(0, 0, 1, 0, 0), 1,
                        ist("2026-10-05T14:00")),
                ratingSummary("RESTAURANT", spiceRoute, "0.00", 0, List.of(0, 0, 0, 0, 0), 2, ist("2026-10-06T08:00")),
                reviewSubmitted(UUID.randomUUID(), "PRODUCT", apples, greenBasket, 4, "PUBLISHED", false,
                        ist("2026-10-05T15:00")),
                reviewSubmitted(UUID.randomUUID(), "MENU_ITEM", biryani, spiceRoute, 2, "FLAGGED", false,
                        ist("2026-10-05T21:00")),
                reviewSubmitted(review3, "VENDOR", greenBasket, greenBasket, 5, "PUBLISHED", false,
                        ist("2026-10-06T10:00")),
                reviewSubmitted(review3, "VENDOR", greenBasket, greenBasket, 1, "FLAGGED", true,
                        ist("2026-10-06T11:00")),
                reviewSubmitted(UUID.randomUUID(), "PRODUCT", apples, greenBasket, 5, "PUBLISHED", false,
                        ist("2026-10-07T10:00")));
    }

    /**
     * Goods receipts: Green Basket PO1 twice on 10-05 (15 units / 427.50, then 5 / 150.00), Fresh Daily PO2 on 10-06
     * (20 / 245.00), Green Basket again on 10-07 (outside the range).
     */
    public List<ConsumerRecord<String, String>> purchasingEvents() {
        UUID po1 = UUID.randomUUID();
        UUID po2 = UUID.randomUUID();
        return List.of(
                goodsReceived(greenBasket, po1, "PO-" + TestEvents.code(), UUID.randomUUID(), ist("2026-10-05T10:00"),
                        new ReceivedLine(apples, 10, "30.00"), new ReceivedLine(milk, 5, "25.50")),
                goodsReceived(greenBasket, po1, "PO-" + TestEvents.code(), UUID.randomUUID(), ist("2026-10-05T18:00"),
                        new ReceivedLine(apples, 5, "30.00")),
                goodsReceived(freshDaily, po2, "PO-" + TestEvents.code(), UUID.randomUUID(), ist("2026-10-06T08:00"),
                        new ReceivedLine(bread, 20, "12.25")),
                goodsReceived(greenBasket, UUID.randomUUID(), "PO-" + TestEvents.code(), UUID.randomUUID(),
                        ist("2026-10-07T08:00"), new ReceivedLine(milk, 100, "20.00")));
    }

    public List<ConsumerRecord<String, String>> all() {
        List<ConsumerRecord<String, String>> all = new ArrayList<>(orderEvents());
        all.addAll(reviewEvents());
        all.addAll(purchasingEvents());
        return all;
    }

    static {
        // sanity: the hand-computed totals of the table above
        ReportDataset d = new ReportDataset();
        check(d.o1.total(), "170.00");
        check(d.o2.total(), "210.00");
        check(d.o3.total(), "250.00");
        check(d.o4.total(), "111.00");
    }

    private static void check(java.math.BigDecimal actual, String expected) {
        if (actual.compareTo(money(expected)) != 0) {
            throw new IllegalStateException("dataset total " + actual + " != " + expected);
        }
    }
}
