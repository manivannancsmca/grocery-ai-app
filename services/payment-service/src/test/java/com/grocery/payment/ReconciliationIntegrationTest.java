package com.grocery.payment;

import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.service.PaymentReconciler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payment reconciliation (Phase 10 contract §3) in Razorpay mode: PENDING payments whose Razorpay order is older than
 * {@code reconcile-after} (2 min) are asked about at the provider and brought in line through the webhook's code —
 * a capture nobody reported, an authorization, a decline — and {@code GET /api/admin/payments/reconciliation}. The tests
 * run the job themselves "in the future" (the test clock), so every older pending payment of the shared database is
 * checked too: assertions look at the tests' own orders and at the runs' own numbers.
 */
class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    /** Past {@code reconcile-after} (2 min), well inside the 15-minute payment window. */
    private static final Duration LATER = Duration.ofMinutes(3);

    @Autowired
    private PaymentReconciler reconciler;
    @Autowired
    private Resilience4JCircuitBreakerFactory breakers;
    @Autowired
    private PaymentProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    private final UUID admin = UUID.randomUUID();

    /** Older pending payments of other test classes are brought in line first, so the runs below are about ours. */
    @BeforeEach
    void drainOlderPendingPayments() {
        clock.advance(LATER);
        reconciler.run();
        clock.reset();
    }

    private long listCalls(String razorpayOrderId) {
        return RAZORPAY.calls("GET", "/v1/orders/" + razorpayOrderId + "/payments");
    }

    private PaymentReconciler.Run runLater() {
        clock.advance(LATER);
        try {
            return reconciler.run();
        } finally {
            clock.reset();
        }
    }

    /** Phase 13: {@code grocery_payments_reconciliation_recovered_total} / {@code _failures_total}. */
    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private JsonNode summary() throws Exception {
        return body(mvc.perform(get("/api/admin/payments/reconciliation").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk()));
    }

    private List<JsonNode> recoveriesOf(JsonNode summary, UUID orderId) {
        return summary.get("lastRecovered").valueStream()
                .filter(r -> r.get("orderId").asString().equals(orderId.toString())).toList();
    }

    @Test
    void aCaptureWhoseVerifyAndWebhookWereLostIsRecovered() throws Exception {
        UUID orderId = payableOrder("249.00");
        String rzp = razorpayOrderId(orderId);
        RAZORPAY.addPayment(rzp, "failed", "card", 24900);                  // a declined attempt first
        String paid = RAZORPAY.addPayment(rzp, "captured", "upi", 24900);  // then paid: browser closed, no webhook
        JsonNode before = summary();
        double recoveredBefore = counter(PaymentReconciler.RECOVERED_METRIC);
        double failuresBefore = counter(PaymentReconciler.FAILURES_METRIC);

        PaymentReconciler.Run run = runLater();

        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED").containsEntry("razorpay_payment_id", paid)
                .containsEntry("method", "upi").containsEntry("late_capture", false);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured", "PaymentFailed");
        JsonNode captured = event(orderId, "PaymentCaptured");
        assertThat(captured.get("late").asBoolean()).isFalse();
        assertThat(captured.get("razorpayPaymentId").asString()).isEqualTo(paid);
        // the decline is recorded after the capture: no longer retryable, so the customer is not told about it
        assertThat(event(orderId, "PaymentFailed").get("retryable").asBoolean()).isFalse();
        assertThat(attemptRows(orderId)).extracting(row -> row.get("status")).containsExactlyInAnyOrder("FAILED",
                "CAPTURED");
        assertThat(refundRows(orderId)).isEmpty();
        assertThat(listCalls(rzp)).isEqualTo(1);
        assertThat(paymentRow(orderId).get("poll_lease_until")).isNull();

        JsonNode after = summary();
        assertThat(run.recovered()).isGreaterThanOrEqualTo(2);
        assertThat(after.get("recovered").asLong() - before.get("recovered").asLong()).isEqualTo(run.recovered());
        assertThat(after.get("checked").asLong() - before.get("checked").asLong()).isEqualTo(run.checked());
        assertThat(after.get("failures").asLong() - before.get("failures").asLong()).isEqualTo(run.failures());
        // the same numbers as the run's, on the Prometheus counters
        assertThat(counter(PaymentReconciler.RECOVERED_METRIC) - recoveredBefore).isEqualTo(run.recovered());
        assertThat(counter(PaymentReconciler.FAILURES_METRIC) - failuresBefore).isEqualTo(run.failures());
        List<JsonNode> mine = recoveriesOf(after, orderId);
        assertThat(mine).extracting(r -> r.get("outcome").asString())
                .containsExactly("FAILED", "CAPTURED");                   // newest first
        assertThat(mine.getFirst().get("orderNumber").asString()).isEqualTo(orderNumber(orderId));

        // nothing more to do: the payment is captured and is no longer checked
        runLater();
        assertThat(listCalls(rzp)).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured", "PaymentFailed");
    }

    @Test
    void anAuthorizedPaymentIsCapturedAtTheProvider() throws Exception {
        UUID orderId = payableOrder("80.00");
        String rzp = razorpayOrderId(orderId);
        String authorized = RAZORPAY.addPayment(rzp, "authorized", "card", 8000);

        runLater();

        assertThat(RAZORPAY.payment(authorized).get("status")).isEqualTo("captured");
        assertThat(RAZORPAY.calls("POST", "/v1/payments/" + authorized + "/capture")).isEqualTo(1);
        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED")
                .containsEntry("razorpay_payment_id", authorized);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured");
        // the outcome says what was found: an authorization, captured by us
        assertThat(recoveriesOf(summary(), orderId)).extracting(r -> r.get("outcome").asString())
                .containsExactly("AUTHORIZED");
    }

    @Test
    void lastRunAtIsNullUntilTheFirstRun() throws Exception {
        // as on a fresh database (the counters are cumulative: the other tests compare differences only)
        jdbc().sql("UPDATE reconciliation_stats SET last_run_at = NULL WHERE id = 1").update();
        JsonNode never = summary();
        assertThat(never.has("lastRunAt")).isTrue();
        assertThat(never.get("lastRunAt").isNull()).isTrue();
        assertThat(never.get("lastRecovered").isArray()).isTrue();

        reconciler.run();

        assertThat(Instant.parse(summary().get("lastRunAt").asString()))
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aDeclineNobodyReportedIsAFailedAttemptAndThePaymentStaysOpen() throws Exception {
        UUID orderId = payableOrder("45.00");
        String rzp = razorpayOrderId(orderId);
        String declined = RAZORPAY.addPayment(rzp, "failed", "card", 4500);

        PaymentReconciler.Run first = runLater();

        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");                 // the customer may try again
        JsonNode failed = event(orderId, "PaymentFailed");
        assertThat(failed.get("razorpayPaymentId").asString()).isEqualTo(declined);
        assertThat(failed.get("errorCode").asString()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(failed.get("retryable").asBoolean()).isTrue();
        assertThat(attemptRows(orderId)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "FAILED"));
        assertThat(recoveriesOf(summary(), orderId)).extracting(r -> r.get("outcome").asString())
                .containsExactly("FAILED");
        assertThat(first.recovered()).isGreaterThanOrEqualTo(1);

        // the next run asks again (still pending) but the known decline is nothing new
        runLater();
        assertThat(listCalls(rzp)).isEqualTo(2);
        assertThat(events(orderId, "PaymentFailed")).hasSize(1);
        assertThat(recoveriesOf(summary(), orderId)).hasSize(1);

        // and once the customer pays (webhook lost again), the next run captures it
        String paid = RAZORPAY.addPayment(rzp, "captured", "upi", 4500);
        runLater();
        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED").containsEntry("razorpay_payment_id", paid);
        assertThat(recoveriesOf(summary(), orderId)).extracting(r -> r.get("outcome").asString())
                .containsExactly("CAPTURED", "FAILED");
    }

    @Test
    void aProviderOutageIsCountedAndRetriedInTheNextRun() throws Exception {
        UUID orderId = payableOrder("120.00");
        String rzp = razorpayOrderId(orderId);
        String paid = RAZORPAY.addPayment(rzp, "captured", "upi", 12000);
        RAZORPAY.failNext(FakeRazorpay.LIST_ORDER_PAYMENTS, 503, 1_000);     // Razorpay is down
        JsonNode before = summary();
        double failuresBefore = counter(PaymentReconciler.FAILURES_METRIC);

        PaymentReconciler.Run down = runLater();

        assertThat(down.failures()).isGreaterThanOrEqualTo(1);
        assertThat(counter(PaymentReconciler.FAILURES_METRIC) - failuresBefore)
                .as("grocery_payments_reconciliation_failures_total (alert ReconciliationFailures)")
                .isEqualTo(down.failures());
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        Map<String, Object> row = jdbc().sql("SELECT reconcile_lease_until, reconciled_at FROM payment "
                + "WHERE order_id = UUID_TO_BIN(?)").param(orderId.toString()).query().singleRow();
        assertThat(row.get("reconcile_lease_until")).isNull();                    // released for the next run
        assertThat(row.get("reconciled_at")).isNotNull();
        JsonNode during = summary();
        assertThat(during.get("failures").asLong() - before.get("failures").asLong()).isEqualTo(down.failures());

        RAZORPAY.reset();                                                         // Razorpay is back
        breakers.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(cb -> cb.reset());
        runLater();

        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED").containsEntry("razorpay_payment_id", paid);
        assertThat(recoveriesOf(summary(), orderId)).extracting(r -> r.get("outcome").asString())
                .containsExactly("CAPTURED");
    }

    @Test
    void aYoungPaymentIsNotCheckedYet() {
        UUID orderId = payableOrder("30.00");
        String rzp = razorpayOrderId(orderId);
        RAZORPAY.addPayment(rzp, "captured", "upi", 3000);

        clock.advance(Duration.ofSeconds(90));                                    // 1.5 of 2 minutes
        try {
            reconciler.run();
        } finally {
            clock.reset();
        }

        assertThat(listCalls(rzp)).isZero();
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");

        runLater();                                                               // now it is due
        assertThat(paymentStatus(orderId)).isEqualTo("CAPTURED");
    }

    @Test
    void capturedExpiredAndOrderlessPaymentsAreNotChecked() throws Exception {
        UUID verified = payableOrder("60.00");
        String verifiedOrder = razorpayOrderId(verified);
        String paid = RAZORPAY.addPayment(verifiedOrder, "captured", "upi", 6000);
        verifySigned(customer, verifiedOrder, paid).andExpect(status().isOk());   // already captured by verify
        UUID windowOver = payableOrder("70.00");
        endWindow(windowOver);                                                    // the expiry poller's job
        UUID cancelled = payableOrder("75.00");
        cancelOrder(cancelled);
        UUID noRazorpayOrder = UUID.randomUUID();
        RAZORPAY.failNext(FakeRazorpay.CREATE_ORDER, 401, 1);                    // refused: stays CREATED
        orderListener.on(awaitingPayment(newEventId(), noRazorpayOrder, customer, "15.00"));
        assertThat(paymentStatus(noRazorpayOrder)).isEqualTo("CREATED");

        runLater();

        assertThat(listCalls(verifiedOrder)).isZero();
        assertThat(listCalls(razorpayOrderId(windowOver))).isZero();
        assertThat(listCalls(razorpayOrderId(cancelled))).isZero();
        assertThat(paymentStatus(windowOver)).isEqualTo("PENDING");
        assertThat(paymentStatus(noRazorpayOrder)).isEqualTo("CREATED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM payment WHERE order_id = UUID_TO_BIN(?) AND reconciled_at IS NULL")
                .param(noRazorpayOrder.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(eventTypes(verified)).containsExactly("PaymentInitiated", "PaymentCaptured");
    }

    @Test
    void aRunChecksEveryDuePaymentOnceAndTheNextRunAgain() {
        List<String> razorpayOrders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            razorpayOrders.add(razorpayOrderId(payableOrder("10.00")));         // nobody paid yet
        }

        runLater();
        razorpayOrders.forEach(rzp -> assertThat(listCalls(rzp)).isEqualTo(1));
        runLater();
        razorpayOrders.forEach(rzp -> assertThat(listCalls(rzp)).isEqualTo(2));
    }

    /** Two replicas reconciling at the same moment: every capture is applied and reported once. */
    @Test
    void twoReconcilersAtOnceRecoverEveryPaymentOnce() throws Exception {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID orderId = payableOrder("10.00");
            RAZORPAY.addPayment(razorpayOrderId(orderId), "captured", "upi", 1000);
            orders.add(orderId);
        }
        clock.advance(LATER);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<PaymentReconciler.Run>> runs = new ArrayList<>();
            for (int replica = 0; replica < 2; replica++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return reconciler.run();
                }));
            }
            start.countDown();
            for (Future<PaymentReconciler.Run> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            clock.reset();
        }
        JsonNode summary = summary();
        for (UUID orderId : orders) {
            assertThat(paymentStatus(orderId)).isEqualTo("CAPTURED");
            assertThat(events(orderId, "PaymentCaptured")).hasSize(1);
            assertThat(recoveriesOf(summary, orderId)).hasSize(1);
        }
    }

    // ---------------------------------------------------------------- GET /api/admin/payments/reconciliation

    @Test
    void theSummaryShowsTheCountersAndTheLastTwentyRecoveriesNewestFirst() throws Exception {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            UUID orderId = payableOrder("10.00");
            RAZORPAY.addPayment(razorpayOrderId(orderId), "captured", "upi", 1000);
            orders.add(orderId);
        }
        Instant ranAt = clock.instant().plus(LATER);

        runLater();

        JsonNode summary = summary();
        assertThat(fields(summary)).containsExactly("lastRunAt", "checked", "recovered", "failures",
                "lastRecovered");
        assertThat(Instant.parse(summary.get("lastRunAt").asString())).isCloseTo(ranAt, within(1, ChronoUnit.MINUTES));
        assertThat(summary.get("checked").asLong()).isGreaterThanOrEqualTo(22);
        assertThat(summary.get("recovered").asLong()).isGreaterThanOrEqualTo(22);
        List<JsonNode> last = summary.get("lastRecovered").valueStream().toList();
        assertThat(last).hasSize(20);
        assertThat(fields(last.getFirst())).containsExactly("orderId", "orderNumber", "outcome", "at");
        // newest first: the 20 most recent are the last ones recovered (oldest Razorpay order first)
        List<String> newest = orders.subList(2, 22).reversed().stream().map(UUID::toString).toList();
        assertThat(last).extracting(r -> r.get("orderId").asString()).containsExactlyElementsOf(newest);
        assertThat(last).allSatisfy(r -> assertThat(r.get("outcome").asString()).isEqualTo("CAPTURED"));
        Instant previous = Instant.MAX;
        for (JsonNode recovery : last) {
            Instant at = Instant.parse(recovery.get("at").asString());
            assertThat(at).isBeforeOrEqualTo(previous);
            previous = at;
        }
        assertThat(last.getFirst().get("orderNumber").asString()).isEqualTo(orderNumber(orders.getLast()));
    }

    @Test
    void anyAdminMayReadTheSummary() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get("/api/admin/payments/reconciliation").with(as(admin, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastRecovered").isArray());
        }
        mvc.perform(get("/api/admin/payments/reconciliation").with(customer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/reconciliation")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRazorpayOrderTimeIsRecordedAndTheDefaultsAreTheContracts() {
        LocalDateTime orderedAt = jdbc().sql("SELECT provider_order_at FROM payment WHERE order_id = UUID_TO_BIN(?)")
                .param(payableOrder("5.00").toString()).query(LocalDateTime.class).single();
        assertThat(orderedAt.toInstant(ZoneOffset.UTC)).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        // env PAYMENT_RECONCILE_INTERVAL / PAYMENT_RECONCILE_AFTER, both 2 minutes by default
        assertThat(properties.reconcileInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.reconcileAfter()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.reconcileBatchSize()).isEqualTo(50);
    }

    private static List<String> fields(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
