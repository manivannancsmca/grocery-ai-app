package com.grocery.ai.tool;

import com.grocery.ai.support.IndiaTime;
import com.grocery.ai.support.Money;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the facts about one order's payment into one situation, in plain words: what happened and what the customer
 * can do next. The payment assistant hands this to the model with the raw facts, so even a small model gives the right
 * guidance for a failed, pending, successful or refunded payment instead of guessing.
 *
 * <p>Pure logic (no API calls, the clock comes in as {@code now}), so every scenario is unit-tested
 * ({@code PaymentGuidanceTest}). The facts come from two APIs: order-service's view of the order and its payment
 * ({@code GET /api/orders/{id}}) and payment-service's payment with its attempts and refunds
 * ({@code GET /api/payments/orders/{orderId}}).
 */
public final class PaymentGuidance {

    private PaymentGuidance() {
    }

    /** The customer's situation, most specific first (see {@link #assess}). */
    public enum Situation {
        /** The order was just placed; the store checks stock before payment opens. */
        CHECKING_STOCK,
        /** The payment window is open and nothing has been paid yet. */
        WAITING_FOR_PAYMENT,
        /** The last attempt failed and the window is still open: the customer can try again. */
        PAYMENT_FAILED_CAN_RETRY,
        /** The provider took the money; the order has not caught up yet (seconds). */
        CONFIRMING_PAYMENT,
        /** The window closed without a payment: the order is (being) cancelled, nothing charged. */
        PAYMENT_TIME_OVER,
        PAID,
        REFUND_IN_PROGRESS,
        /** A refund failed at the provider; payment-service retries it on its own. */
        REFUND_FAILED_RETRYING,
        REFUNDED,
        PARTIALLY_REFUNDED,
        /** Cancelled or rejected before any payment. */
        NOTHING_TO_PAY,
        /** A combination this class does not know: the model reports the raw status. */
        UNKNOWN
    }

    /** One payment attempt at the provider ({@code AUTHORIZED}, {@code CAPTURED} or {@code FAILED}). */
    public record Attempt(String status, String method, String errorCode, String errorDescription, Instant at) {
    }

    /**
     * One refund ({@code PENDING}, {@code PROCESSED} or {@code FAILED}). The provider's failure text is left out on
     * purpose: it is technical ("The id provided does not exist") and the app shows it to admins only.
     */
    public record Refund(BigDecimal amount, String status, String reason, Instant createdAt, Instant processedAt) {
    }

    /**
     * @param orderStatus        order-service status (AWAITING_PAYMENT, CONFIRMED, CANCELLED, …)
     * @param paymentStatus      order-service's view of the payment: NOT_STARTED, PENDING, PAID, EXPIRED,
     *                           NOT_REQUIRED, REFUND_PENDING, REFUNDED or PARTIALLY_REFUNDED
     * @param cancellationReason why the order was cancelled (e.g. PAYMENT_TIMEOUT), null when it was not
     * @param total              the order total
     * @param payBy              the end of the payment window (only while AWAITING_PAYMENT)
     * @param paidAt             when the payment was captured
     * @param refundedAmount     refunded so far (0 or null without refunds)
     * @param gatewayStatus      payment-service's status (CREATED, PENDING, CAPTURED, EXPIRED, CANCELLED); null when
     *                           no payment was started yet
     * @param method             how it was paid (upi, card, …), when known
     * @param attempts           the provider attempts (any order)
     * @param refunds            the payment's refunds (any order)
     */
    public record Facts(String orderStatus, String paymentStatus, String cancellationReason, BigDecimal total,
                        Instant payBy, Instant paidAt, BigDecimal refundedAmount, String gatewayStatus, String method,
                        List<Attempt> attempts, List<Refund> refunds) {

        public Facts {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            refunds = refunds == null ? List.of() : List.copyOf(refunds);
        }
    }

    /**
     * @param situation        the case, for the model's reasoning
     * @param whatHappened     one or two sentences for the customer
     * @param nextStep         what the customer can do now (or that nothing is needed)
     * @param canPayNow        the customer can pay (or pay again) on the order page right now
     * @param minutesLeftToPay whole minutes until the window closes (rounded up), while it is open; else null
     */
    public record Assessment(Situation situation, String whatHappened, String nextStep, boolean canPayNow,
                             Long minutesLeftToPay) {
    }

    private static final Set<String> REFUND_STATUSES = Set.of("REFUND_PENDING", "REFUNDED", "PARTIALLY_REFUNDED");
    private static final String PAY_ON_ORDER_PAGE = "open the order in the app and tap Pay now";

    /**
     * The situation, checked in this order: refunds (they explain where money went), a cancelled order that still
     * holds money, paid, nothing to pay, the window ran out, still checking stock, and finally the open window
     * (payment arriving, last attempt failed, or not paid yet).
     *
     * @param refundTimeline how long banks usually take to show a refund (e.g. "5–7 working days"); null or blank
     *                       to say nothing about timing
     */
    public static Assessment assess(Facts facts, Instant now, String refundTimeline) {
        String paymentStatus = Objects.requireNonNullElse(facts.paymentStatus(), "");
        String orderStatus = Objects.requireNonNullElse(facts.orderStatus(), "");
        boolean cancelled = "CANCELLED".equals(orderStatus) || "REJECTED".equals(orderStatus);
        String method = StatusWords.paymentMethod(facts.method());
        String bankTime = refundTimeline == null || refundTimeline.isBlank() ? ""
                : " Banks usually take " + refundTimeline.strip() + " to show a refund in your account.";

        // ---- refunds -------------------------------------------------------------------------------------------
        // Every refund answer starts with the payment itself ("You paid ₹239.00 … by UPI."): told only "refunded"
        // and "cancelled", the small model said the payment "was not completed".
        Refund failed = newest(facts.refunds(), "FAILED");
        Refund pending = newest(facts.refunds(), "PENDING");
        if (failed != null && pending == null && !"REFUNDED".equals(paymentStatus)) {
            // Why it started and why it is late are separate sentences: with one sentence the small model read the
            // refund's reason ("the order was cancelled") as the cause of the failure.
            return result(Situation.REFUND_FAILED_RETRYING,
                    paid(facts, method) + " A refund of " + Money.inr(failed.amount()) + " was started"
                            + because(failed.reason(), facts.cancellationReason())
                            + ". The payment provider has not completed it yet.",
                    "No action is needed: failed refunds are retried automatically. If it stays like this, the "
                            + "Customer support assistant can raise a payment complaint.");
        }
        if (pending != null || "REFUND_PENDING".equals(paymentStatus)) {
            String what = paid(facts, method) + (pending != null
                    ? " A refund of " + Money.inr(pending.amount()) + " was started" + on(pending.createdAt())
                    + because(pending.reason(), facts.cancellationReason()) + ". It is being processed."
                    : " A refund is being processed.");
            return result(Situation.REFUND_IN_PROGRESS, what,
                    "It goes back to the payment method you used" + (method == null ? "" : " (" + method + ")")
                            + "; nothing to do." + bankTime);
        }
        if (REFUND_STATUSES.contains(paymentStatus) || hasProcessedRefund(facts.refunds())) {
            Refund last = newest(facts.refunds(), "PROCESSED");
            BigDecimal refunded = facts.refundedAmount() != null && facts.refundedAmount().signum() > 0
                    ? facts.refundedAmount() : last == null ? null : last.amount();
            String reason = last == null ? "" : because(last.reason(), facts.cancellationReason());
            String when = last == null ? "" : on(last.processedAt() != null ? last.processedAt() : last.createdAt());
            if ("PARTIALLY_REFUNDED".equals(paymentStatus)) {
                return result(Situation.PARTIALLY_REFUNDED,
                        Money.inr(refunded) + " of the " + Money.inr(facts.total()) + " you paid was refunded" + when
                                + reason + "; the rest of the order stays paid.",
                        "The refund went back to the payment method you used" + (method == null ? "" : " (" + method
                                + ")") + "; nothing to do." + bankTime);
            }
            boolean inFull = refunded != null && facts.total() != null && refunded.compareTo(facts.total()) == 0;
            return result(Situation.REFUNDED,
                    paid(facts, method) + " " + Money.inr(refunded) + " was refunded" + (inFull ? " in full" : "")
                            + when + reason + ".",
                    "It went back to the payment method you used" + (method == null ? "" : " (" + method + ")")
                            + "; nothing to do." + bankTime + " If it still hasn't arrived after that, the Customer "
                            + "support assistant can raise a payment complaint.");
        }

        // ---- paid ----------------------------------------------------------------------------------------------
        if ("PAID".equals(paymentStatus)) {
            if (cancelled) {
                return result(Situation.REFUND_IN_PROGRESS,
                        paid(facts, method) + " The order was then " + ("REJECTED".equals(orderStatus)
                                ? "rejected by the store" : "cancelled" + (facts.cancellationReason() == null ? ""
                                : " because " + StatusWords.cancellation(facts.cancellationReason()))) + ".",
                        "The refund starts automatically and goes back to the payment method you used; nothing to "
                                + "do." + bankTime);
            }
            return result(Situation.PAID,
                    paid(facts, method) + " The order is " + StatusWords.order(orderStatus) + ".",
                    "Nothing to do for the payment.");
        }

        // ---- nothing to pay, or too late ----------------------------------------------------------------------
        boolean providerTookMoney = "CAPTURED".equals(facts.gatewayStatus());
        if ("NOT_REQUIRED".equals(paymentStatus)) {
            return result(Situation.NOTHING_TO_PAY,
                    "Nothing was charged: the order was " + ("REJECTED".equals(orderStatus)
                            ? "rejected by the store" : "cancelled") + " before payment.",
                    "Nothing to do.");
        }
        boolean windowClosed = facts.payBy() != null && !now.isBefore(facts.payBy());
        if ("EXPIRED".equals(paymentStatus) || "PAYMENT_TIMEOUT".equals(facts.cancellationReason())
                || ("PENDING".equals(paymentStatus) && windowClosed && !providerTookMoney)) {
            if (providerTookMoney) {
                return result(Situation.REFUND_IN_PROGRESS,
                        "Your payment arrived after the time to pay had run out, so the order was cancelled.",
                        "The payment is refunded automatically to the method you used; nothing to do." + bankTime);
            }
            return result(Situation.PAYMENT_TIME_OVER,
                    "The time to pay ran out" + at(facts.payBy()) + ", so the order "
                            + (cancelled ? "was cancelled" : "is being cancelled automatically")
                            + ". Nothing was charged.",
                    "To still get these items, place the order again.");
        }
        if ("NOT_STARTED".equals(paymentStatus) || "CREATED".equals(orderStatus)) {
            return result(Situation.CHECKING_STOCK,
                    "The store is checking that everything is in stock.",
                    "Payment opens as soon as that is done: then " + PAY_ON_ORDER_PAGE + ".");
        }

        // ---- the payment window is open -----------------------------------------------------------------------
        if ("PENDING".equals(paymentStatus) || "AWAITING_PAYMENT".equals(orderStatus)) {
            Long minutesLeft = minutesLeft(facts.payBy(), now);
            String deadline = facts.payBy() == null ? "" : " before " + IndiaTime.format(facts.payBy())
                    + (minutesLeft == null ? "" : " (" + minutesLeft + (minutesLeft == 1 ? " minute" : " minutes")
                    + " left)");
            Attempt last = newestAttempt(facts.attempts());
            if (providerTookMoney || (last != null && Set.of("AUTHORIZED", "CAPTURED").contains(last.status()))) {
                return new Assessment(Situation.CONFIRMING_PAYMENT,
                        "Your payment of " + Money.inr(facts.total()) + " went through and is being confirmed.",
                        "Please don't pay again: the order updates within a minute or two (refresh the order page).",
                        false, null);
            }
            if (last != null && "FAILED".equals(last.status())) {
                String how = StatusWords.paymentMethod(last.method());
                return new Assessment(Situation.PAYMENT_FAILED_CAN_RETRY,
                        "Your last payment attempt" + (how == null ? "" : " by " + how) + at(last.at()) + " failed"
                                + (last.errorDescription() == null ? "" : ": " + last.errorDescription())
                                + ". A failed attempt is not charged to your order.",
                        "You can try again" + deadline + ": " + PAY_ON_ORDER_PAGE + ". Another method (for example "
                                + "UPI instead of a card) often helps. If your bank shows a debit for the failed "
                                + "attempt and it is not reversed, the Customer support assistant can raise a payment "
                                + "complaint.",
                        true, minutesLeft);
            }
            return new Assessment(Situation.WAITING_FOR_PAYMENT,
                    "The order is waiting for your payment of " + Money.inr(facts.total()) + ".",
                    "Pay" + deadline + ": " + PAY_ON_ORDER_PAGE + ". If the time runs out, the order is cancelled "
                            + "automatically and nothing is charged.",
                    true, minutesLeft);
        }
        return result(Situation.UNKNOWN,
                "The payment status is \"" + paymentStatus.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                        + "\" and the order is " + StatusWords.order(orderStatus) + ".",
                "The order page in the app shows the latest payment details.");
    }

    /** Whole minutes left until {@code payBy}, rounded up (59 s → 1); null when there is no deadline or it passed. */
    static Long minutesLeft(Instant payBy, Instant now) {
        if (payBy == null || !now.isBefore(payBy)) {
            return null;
        }
        long seconds = Duration.between(now, payBy).toSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    private static Assessment result(Situation situation, String whatHappened, String nextStep) {
        return new Assessment(situation, whatHappened, nextStep, false, null);
    }

    private static Refund newest(List<Refund> refunds, String status) {
        return refunds.stream().filter(refund -> status.equals(refund.status()))
                .max(Comparator.comparing(Refund::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static boolean hasProcessedRefund(List<Refund> refunds) {
        return refunds.stream().anyMatch(refund -> "PROCESSED".equals(refund.status()));
    }

    private static Attempt newestAttempt(List<Attempt> attempts) {
        // The API lists them in order; an attempt without a time keeps its place.
        Attempt newest = null;
        for (Attempt attempt : attempts) {
            if (newest == null || attempt.at() == null || newest.at() == null || !attempt.at().isBefore(newest.at())) {
                newest = attempt;
            }
        }
        return newest;
    }

    /** "You paid ₹260.00 on 19 Nov, 1:40 pm IST by UPI." (the time and the method when known). */
    private static String paid(Facts facts, String method) {
        return "You paid" + (facts.total() == null ? "" : " " + Money.inr(facts.total())) + on(facts.paidAt())
                + (method == null ? "" : " by " + method) + ".";
    }

    /**
     * " because the order was cancelled (the store could not accept it)", " because a return was approved", or ""
     * without a reason: a cancelled order's refund also says why the order was cancelled, when that is known.
     */
    private static String because(String refundReason, String cancellationReason) {
        if (refundReason == null) {
            return "";
        }
        String why = " because " + StatusWords.refundReason(refundReason);
        return "ORDER_CANCELLED".equals(refundReason) && cancellationReason != null
                ? why + " (" + StatusWords.cancellation(cancellationReason) + ")" : why;
    }

    /** " on 19 Nov, 2:32 pm IST", or "". */
    private static String on(Instant instant) {
        return instant == null ? "" : " on " + IndiaTime.format(instant);
    }

    /** " at 19 Nov, 2:32 pm IST", or "". */
    private static String at(Instant instant) {
        return instant == null ? "" : " at " + IndiaTime.format(instant);
    }
}
