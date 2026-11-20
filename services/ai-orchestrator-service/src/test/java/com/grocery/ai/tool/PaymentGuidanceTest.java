package com.grocery.ai.tool;

import com.grocery.ai.tool.PaymentGuidance.Assessment;
import com.grocery.ai.tool.PaymentGuidance.Attempt;
import com.grocery.ai.tool.PaymentGuidance.Facts;
import com.grocery.ai.tool.PaymentGuidance.Refund;
import com.grocery.ai.tool.PaymentGuidance.Situation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentGuidance: every payment situation a customer can be in, and what the payment assistant tells them. Times are
 * written in India time (UTC+5:30): 08:30 UTC is 2:00 pm IST.
 */
class PaymentGuidanceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T08:30:00Z");
    private static final BigDecimal TOTAL = new BigDecimal("260.00");
    private static final String TIMELINE = "5–7 working days";

    private static Facts facts(String orderStatus, String paymentStatus, Instant payBy, String gatewayStatus,
                               List<Attempt> attempts, List<Refund> refunds) {
        return new Facts(orderStatus, paymentStatus, null, TOTAL, payBy, null, null, gatewayStatus, "upi", attempts,
                refunds);
    }

    private static Assessment assess(Facts facts) {
        return PaymentGuidance.assess(facts, NOW, TIMELINE);
    }

    @Test
    void anOpenWindowSaysHowMuchAndUntilWhenToPay() {
        Assessment result = assess(facts("AWAITING_PAYMENT", "PENDING", NOW.plus(Duration.ofMinutes(12)), "CREATED",
                List.of(), List.of()));

        assertThat(result.situation()).isEqualTo(Situation.WAITING_FOR_PAYMENT);
        assertThat(result.canPayNow()).isTrue();
        assertThat(result.minutesLeftToPay()).isEqualTo(12);
        assertThat(result.whatHappened()).isEqualTo("The order is waiting for your payment of ₹260.00.");
        assertThat(result.nextStep()).isEqualTo("Pay before 19 Nov, 2:12 pm IST (12 minutes left): open the order in "
                + "the app and tap Pay now. If the time runs out, the order is cancelled automatically and nothing is "
                + "charged.");
    }

    @Test
    void aFailedAttemptExplainsWhyAndThatTheCustomerCanTryAgain() {
        Attempt failed = new Attempt("FAILED", "card", "BAD_REQUEST_ERROR", "Your card was declined by the bank",
                NOW.minus(Duration.ofMinutes(2)));
        Assessment result = assess(facts("AWAITING_PAYMENT", "PENDING", NOW.plus(Duration.ofSeconds(61)), "PENDING",
                List.of(failed), List.of()));

        assertThat(result.situation()).isEqualTo(Situation.PAYMENT_FAILED_CAN_RETRY);
        assertThat(result.canPayNow()).isTrue();
        assertThat(result.minutesLeftToPay()).isEqualTo(2);           // 61 s rounds up
        assertThat(result.whatHappened()).isEqualTo("Your last payment attempt by card at 19 Nov, 1:58 pm IST failed: "
                + "Your card was declined by the bank. A failed attempt is not charged to your order.");
        assertThat(result.nextStep()).startsWith("You can try again before 19 Nov, 2:01 pm IST (2 minutes left): "
                + "open the order in the app and tap Pay now. Another method");
    }

    @Test
    void theNewestAttemptDecides() {
        Attempt failed = new Attempt("FAILED", "card", null, "Declined", NOW.minus(Duration.ofMinutes(5)));
        Attempt captured = new Attempt("CAPTURED", "upi", null, null, NOW.minus(Duration.ofMinutes(1)));
        Assessment result = assess(facts("AWAITING_PAYMENT", "PENDING", NOW.plus(Duration.ofMinutes(10)), "PENDING",
                List.of(captured, failed), List.of()));

        assertThat(result.situation()).isEqualTo(Situation.CONFIRMING_PAYMENT);
        assertThat(result.canPayNow()).isFalse();
        assertThat(result.nextStep()).startsWith("Please don't pay again");
    }

    @Test
    void aWindowThatClosedWithoutPaymentMeansNothingWasCharged() {
        Assessment pending = assess(facts("AWAITING_PAYMENT", "PENDING", NOW.minus(Duration.ofMinutes(1)), "CREATED",
                List.of(), List.of()));
        assertThat(pending.situation()).isEqualTo(Situation.PAYMENT_TIME_OVER);
        assertThat(pending.canPayNow()).isFalse();
        assertThat(pending.whatHappened()).isEqualTo("The time to pay ran out at 19 Nov, 1:59 pm IST, so the order is "
                + "being cancelled automatically. Nothing was charged.");

        Facts cancelled = new Facts("CANCELLED", "EXPIRED", "PAYMENT_TIMEOUT", TOTAL,
                NOW.minus(Duration.ofHours(1)), null, null, "EXPIRED", null, List.of(), List.of());
        assertThat(assess(cancelled).whatHappened()).isEqualTo("The time to pay ran out at 19 Nov, 1:00 pm IST, so "
                + "the order was cancelled. Nothing was charged.");
        assertThat(assess(cancelled).nextStep()).isEqualTo("To still get these items, place the order again.");
    }

    @Test
    void aPaymentThatArrivedTooLateIsRefunded() {
        Facts late = new Facts("CANCELLED", "EXPIRED", "PAYMENT_TIMEOUT", TOTAL, NOW.minus(Duration.ofMinutes(3)),
                null, null, "CAPTURED", "upi", List.of(), List.of());

        Assessment result = assess(late);

        assertThat(result.situation()).isEqualTo(Situation.REFUND_IN_PROGRESS);
        assertThat(result.whatHappened()).isEqualTo("Your payment arrived after the time to pay had run out, so the "
                + "order was cancelled.");
        assertThat(result.nextStep()).contains("refunded automatically").contains("5–7 working days");
    }

    @Test
    void aPaidOrderSaysWhenAndHow() {
        Facts paid = new Facts("PREPARING", "PAID", null, TOTAL, null, NOW.minus(Duration.ofMinutes(20)), null,
                "CAPTURED", "upi", List.of(), List.of());

        Assessment result = assess(paid);

        assertThat(result.situation()).isEqualTo(Situation.PAID);
        assertThat(result.whatHappened()).isEqualTo("You paid ₹260.00 on 19 Nov, 1:40 pm IST by UPI. The order is "
                + "being prepared.");
        assertThat(result.nextStep()).isEqualTo("Nothing to do for the payment.");
    }

    @Test
    void aPaidOrderThatWasCancelledIsRefundedAutomatically() {
        Facts cancelled = new Facts("CANCELLED", "PAID", "STORE_REJECTED", TOTAL, null, NOW, null, "CAPTURED", "card",
                List.of(), List.of());

        Assessment result = assess(cancelled);

        assertThat(result.situation()).isEqualTo(Situation.REFUND_IN_PROGRESS);
        assertThat(result.whatHappened()).isEqualTo("You paid ₹260.00 on 19 Nov, 2:00 pm IST by card. The order was "
                + "then cancelled because the store could not accept it.");
    }

    @Test
    void aRefundAfterTheStoreRejectedAPaidOrderSaysThePaymentWentThrough() {
        // The live case: told only "CANCELLED" and "REFUNDED", the small model said the payment "was not completed".
        BigDecimal total = new BigDecimal("239.00");
        Refund processed = new Refund(total, "PROCESSED", "ORDER_CANCELLED", NOW.minus(Duration.ofMinutes(16)),
                NOW.minus(Duration.ofMinutes(16)));
        Facts rejected = new Facts("CANCELLED", "REFUNDED", "STORE_REJECTED", total, null,
                NOW.minus(Duration.ofMinutes(17)), total, "CAPTURED", "upi", List.of(), List.of(processed));

        Assessment result = assess(rejected);

        assertThat(result.situation()).isEqualTo(Situation.REFUNDED);
        assertThat(result.whatHappened()).isEqualTo("You paid ₹239.00 on 19 Nov, 1:43 pm IST by UPI. ₹239.00 was "
                + "refunded in full on 19 Nov, 1:44 pm IST because the order was cancelled (the store could not "
                + "accept it).");
        assertThat(result.nextStep()).startsWith("It went back to the payment method you used (UPI); nothing to do. "
                + "Banks usually take 5–7 working days");
    }

    @Test
    void refundsSayHowMuchWhyAndWhen() {
        Refund pending = new Refund(new BigDecimal("260.00"), "PENDING", "ORDER_CANCELLED",
                NOW.minus(Duration.ofHours(2)), null);
        Assessment inProgress = assess(facts("CANCELLED", "REFUND_PENDING", null, "CAPTURED", List.of(),
                List.of(pending)));
        assertThat(inProgress.situation()).isEqualTo(Situation.REFUND_IN_PROGRESS);
        assertThat(inProgress.whatHappened()).isEqualTo("You paid ₹260.00 by UPI. A refund of ₹260.00 was started "
                + "on 19 Nov, 12:00 pm IST because the order was cancelled. It is being processed.");
        assertThat(inProgress.nextStep()).isEqualTo("It goes back to the payment method you used (UPI); nothing to "
                + "do. Banks usually take 5–7 working days to show a refund in your account.");

        Refund processed = new Refund(new BigDecimal("260.00"), "PROCESSED", "ORDER_CANCELLED",
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofHours(20)));
        Facts refunded = new Facts("CANCELLED", "REFUNDED", null, TOTAL, null, null, new BigDecimal("260.00"),
                "CAPTURED", "upi", List.of(), List.of(processed));
        Assessment done = assess(refunded);
        assertThat(done.situation()).isEqualTo(Situation.REFUNDED);
        assertThat(done.whatHappened()).isEqualTo("You paid ₹260.00 by UPI. ₹260.00 was refunded in full on 18 Nov, "
                + "6:00 pm IST because the order was cancelled.");

        Refund partial = new Refund(new BigDecimal("90.00"), "PROCESSED", "RETURN",
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)));
        Facts partly = new Facts("DELIVERED", "PARTIALLY_REFUNDED", null, TOTAL, null, null, new BigDecimal("90.00"),
                "CAPTURED", "upi", List.of(), List.of(partial));
        Assessment part = assess(partly);
        assertThat(part.situation()).isEqualTo(Situation.PARTIALLY_REFUNDED);
        assertThat(part.whatHappened()).isEqualTo("₹90.00 of the ₹260.00 you paid was refunded on 18 Nov, 2:00 pm IST "
                + "because a return was approved; the rest of the order stays paid.");
    }

    @Test
    void aFailedRefundIsRetriedAutomatically() {
        Refund failed = new Refund(new BigDecimal("260.00"), "FAILED", "ORDER_CANCELLED",
                NOW.minus(Duration.ofHours(1)), null);

        Assessment result = assess(facts("CANCELLED", "REFUND_PENDING", null, "CAPTURED", List.of(),
                List.of(failed)));

        assertThat(result.situation()).isEqualTo(Situation.REFUND_FAILED_RETRYING);
        assertThat(result.whatHappened()).isEqualTo("You paid ₹260.00 by UPI. A refund of ₹260.00 was started because "
                + "the order was cancelled. The payment provider has not completed it yet.");
        assertThat(result.nextStep()).startsWith("No action is needed: failed refunds are retried automatically.");
    }

    @Test
    void ordersCancelledBeforePaymentAndOrdersStillCheckingStockNeedNoPayment() {
        Assessment rejected = assess(facts("REJECTED", "NOT_REQUIRED", null, null, List.of(), List.of()));
        assertThat(rejected.situation()).isEqualTo(Situation.NOTHING_TO_PAY);
        assertThat(rejected.whatHappened()).isEqualTo("Nothing was charged: the order was rejected by the store before "
                + "payment.");

        Assessment checking = assess(facts("CREATED", "NOT_STARTED", null, null, List.of(), List.of()));
        assertThat(checking.situation()).isEqualTo(Situation.CHECKING_STOCK);
        assertThat(checking.canPayNow()).isFalse();
        assertThat(checking.nextStep()).isEqualTo("Payment opens as soon as that is done: then open the order in the "
                + "app and tap Pay now.");
    }

    @Test
    void withoutAConfiguredTimelineNothingIsSaidAboutBankTimes() {
        Refund pending = new Refund(new BigDecimal("50.00"), "PENDING", "ADMIN", NOW, null);

        Assessment result = PaymentGuidance.assess(facts("DELIVERED", "REFUND_PENDING", null, "CAPTURED", List.of(),
                List.of(pending)), NOW, "");

        assertThat(result.nextStep()).isEqualTo("It goes back to the payment method you used (UPI); nothing to do.");
    }

    @Test
    void minutesLeftRoundUpAndStopAtTheDeadline() {
        assertThat(PaymentGuidance.minutesLeft(NOW.plusSeconds(59), NOW)).isEqualTo(1);
        assertThat(PaymentGuidance.minutesLeft(NOW.plusSeconds(60), NOW)).isEqualTo(1);
        assertThat(PaymentGuidance.minutesLeft(NOW.plusSeconds(61), NOW)).isEqualTo(2);
        assertThat(PaymentGuidance.minutesLeft(NOW, NOW)).isNull();
        assertThat(PaymentGuidance.minutesLeft(null, NOW)).isNull();
    }
}
