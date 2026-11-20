package com.grocery.payment;

import com.grocery.common.id.Ids;
import com.grocery.payment.service.PaymentExpiryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The payment window poller (contract §3.1 "Window over"). The tests run it themselves. */
class ExpiryPollerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentExpiryService expiry;

    private long listCalls(String razorpayOrderId) {
        return RAZORPAY.calls("GET", "/v1/orders/" + razorpayOrderId + "/payments");
    }

    @Test
    void anUnpaidPaymentPastItsWindowExpires() {
        UUID orderId = payableOrder("125.00");
        UUID stillOpen = payableOrder("10.00");
        String rzp = razorpayOrderId(orderId);
        RAZORPAY.addPayment(rzp, "failed", "card", 12500);           // a declined attempt is not a payment
        endWindow(orderId);

        expiryPoller.run();

        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(listCalls(rzp)).isEqualTo(1);                      // asked Razorpay first
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentExpired");
        JsonNode expired = event(orderId, "PaymentExpired");
        assertThat(expired.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(expired.get("paymentId").asString()).isEqualTo(paymentRow(orderId).get("id"));
        assertThat(paymentRow(orderId).get("poll_lease_until")).isNull();

        assertThat(paymentStatus(stillOpen)).isEqualTo("PENDING");    // window not over
        assertThat(listCalls(razorpayOrderId(stillOpen))).isZero();

        expiryPoller.run();                                           // nothing left to do
        assertThat(events(orderId, "PaymentExpired")).hasSize(1);
    }

    @Test
    void aPaymentCapturedAtRazorpayButNeverReportedIsCapturedInsteadOfExpired() {
        UUID orderId = payableOrder("80.00");
        String rzp = razorpayOrderId(orderId);
        RAZORPAY.addPayment(rzp, "failed", "card", 8000);
        String paid = RAZORPAY.addPayment(rzp, "captured", "upi", 8000);   // webhook lost, browser closed
        endWindow(orderId);

        expiryPoller.run();

        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED").containsEntry("razorpay_payment_id", paid)
                .containsEntry("late_capture", false);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
        assertThat(event(orderId, "PaymentCaptured").get("late").asBoolean()).isFalse();
        assertThat(refundRows(orderId)).isEmpty();
    }

    @Test
    void anAuthorizedPaymentIsCapturedInsteadOfExpired() {
        UUID orderId = payableOrder("15.00");
        String rzp = razorpayOrderId(orderId);
        String authorized = RAZORPAY.addPayment(rzp, "authorized", "card", 1500);
        endWindow(orderId);

        expiryPoller.run();

        assertThat(RAZORPAY.payment(authorized).get("status")).isEqualTo("captured");
        assertThat(paymentStatus(orderId)).isEqualTo("CAPTURED");
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
    }

    @Test
    void razorpayUnreachableLeavesThePaymentForALaterRound() {
        UUID orderId = payableOrder("15.00");
        endWindow(orderId);
        RAZORPAY.failNext(FakeRazorpay.LIST_ORDER_PAYMENTS, 503, 1);

        expiryPoller.run();

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");      // never expired without asking Razorpay
        assertThat(paymentRow(orderId).get("poll_lease_until")).isNotNull();
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        expiryPoller.run();                                           // leased: not taken again yet
        assertThat(listCalls(razorpayOrderId(orderId))).isEqualTo(1);

        clock.advance(Duration.ofMinutes(3));                         // lease (2 min) over
        expiryPoller.run();
        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
    }

    @Test
    void aPaymentWithoutRazorpayOrderExpiresWithoutAskingRazorpay() {
        UUID orderId = Ids.newId();
        orderListener.on(awaitingPayment(newEventId(), orderId, customer, "10.00", Instant.now().minusSeconds(1)));
        assertThat(paymentStatus(orderId)).isEqualTo("CREATED");

        expiryPoller.run();

        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(eventTypes(orderId)).containsExactly("PaymentExpired");
    }

    @Test
    void thePollerRunsOnTheInjectedClock() {
        UUID orderId = payableOrder("10.00");                         // window: 15 minutes from now
        expiryPoller.run();
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(16));
        expiryPoller.run();

        assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
    }

    /** Two pollers (two replicas) at the same time: every payment is handled by exactly one of them. */
    @Test
    void twoPollersNeverHandleThePaymentTwice() throws Exception {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID orderId = payableOrder("10.00");
            endWindow(orderId);
            orders.add(orderId);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int poller = 0; poller < 2; poller++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    int handled = 0;
                    List<UUID> claimed;
                    // small batches, so both pollers keep claiming while the other works
                    while (!(claimed = expiry.claim(3)).isEmpty()) {
                        for (UUID paymentId : claimed) {
                            expiry.handle(paymentId);
                            handled++;
                        }
                    }
                    return handled;
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID orderId : orders) {
            assertThat(paymentStatus(orderId)).isEqualTo("EXPIRED");
            assertThat(events(orderId, "PaymentExpired")).hasSize(1);
            assertThat(listCalls(razorpayOrderId(orderId))).as("Razorpay asked once for %s", orderId).isEqualTo(1);
        }
    }
}
