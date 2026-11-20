package com.grocery.payment;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientEventProcessingException;
import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order.events → payments (contract §3.1): OrderAwaitingPayment creates exactly one payment and one Razorpay order
 * (redeliveries, replays, provider outages, a race with the checkout endpoint); OrderCancelled cancels or refunds.
 */
class OrderEventsIntegrationTest extends AbstractIntegrationTest {

    private long processed(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = 'payment-service.order-events'")
                .param(eventId).query(Long.class).single();
    }

    @Test
    void orderAwaitingPaymentCreatesThePaymentAndItsRazorpayOrder() {
        UUID orderId = Ids.newId();
        String eventId = newEventId();

        orderListener.on(awaitingPayment(eventId, orderId, customer, "125.00"));

        Map<String, Object> row = paymentRow(orderId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("125.00");
        assertThat(row.get("contact_phone")).isEqualTo("+919876543210");
        assertThat(row.get("refund_status")).isEqualTo("NONE");
        assertThat(processed(eventId)).isEqualTo(1);
        String razorpayOrderId = (String) row.get("razorpay_order_id");
        assertThat(razorpayOrderId).startsWith("order_");

        // POST /orders: amount in paise, receipt = order number, notes.orderId, basic auth with the test keys
        Map<String, Object> order = RAZORPAY.order(razorpayOrderId);
        assertThat(order.get("amount")).isEqualTo(12500L);
        assertThat(order.get("currency")).isEqualTo("INR");
        assertThat(order.get("receipt")).isEqualTo(orderNumber(orderId));
        @SuppressWarnings("unchecked")
        Map<String, Object> notes = (Map<String, Object>) order.get("notes");
        assertThat(notes).containsEntry("orderId", orderId.toString()).containsEntry("paymentId", row.get("id"));
        String basic = "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes());
        assertThat(RAZORPAY.requests()).filteredOn(r -> r.path().equals("/v1/orders"))
                .allMatch(r -> basic.equals(r.authorization()));

        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        JsonNode initiated = event(orderId, "PaymentInitiated");
        assertThat(initiated.get("razorpayOrderId").asString()).isEqualTo(razorpayOrderId);
        assertThat(initiated.get("paymentId").asString()).isEqualTo(row.get("id"));
    }

    @Test
    void redeliveriesAndReplaysNeverCreateASecondPaymentOrRazorpayOrder() {
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        orderListener.on(awaitingPayment(eventId, orderId, customer, "80.50"));
        String razorpayOrderId = razorpayOrderId(orderId);

        orderListener.on(awaitingPayment(eventId, orderId, customer, "80.50"));          // Kafka redelivery
        orderListener.on(awaitingPayment(newEventId(), orderId, customer, "80.50"));     // a replay, new event id

        assertThat(paymentCount(orderId)).isEqualTo(1);
        assertThat(razorpayOrderId(orderId)).isEqualTo(razorpayOrderId);
        assertThat(RAZORPAY.createOrderCalls(orderNumber(orderId))).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void razorpayUnreachableIsRetriedNotDeadLetteredAndCheckoutCreatesTheOrderOnDemand() throws Exception {
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        RAZORPAY.failNext(FakeRazorpay.CREATE_ORDER, 503, 1);

        assertThatThrownBy(() -> orderListener.on(awaitingPayment(eventId, orderId, customer, "60.00")))
                .isInstanceOf(TransientEventProcessingException.class)
                .satisfies(e -> assertThat(TransientFailures.isTransient(e)).isTrue());

        // The payment row is committed (CREATED, no Razorpay order); nothing was published
        assertThat(paymentStatus(orderId)).isEqualTo("CREATED");
        assertThat(razorpayOrderId(orderId)).isNull();
        assertThat(eventTypes(orderId)).isEmpty();

        // The checkout endpoint creates the Razorpay order on demand …
        JsonNode session = checkout(orderId);
        String razorpayOrderId = session.get("razorpayOrderId").asString();
        assertThat(paymentStatus(orderId)).isEqualTo("PENDING");

        // … and the retried event finds it: still one order, one PaymentInitiated
        orderListener.on(awaitingPayment(eventId, orderId, customer, "60.00"));
        assertThat(razorpayOrderId(orderId)).isEqualTo(razorpayOrderId);
        assertThat(RAZORPAY.ordersWithReceipt(orderNumber(orderId))).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
    }

    @Test
    void razorpayRefusingTheOrderDoesNotBlockTheConsumer() throws Exception {
        UUID orderId = Ids.newId();
        RAZORPAY.failNext(FakeRazorpay.CREATE_ORDER, 401, 2);

        orderListener.on(awaitingPayment(newEventId(), orderId, customer, "60.00"));   // logged, not retried

        assertThat(paymentStatus(orderId)).isEqualTo("CREATED");
        checkoutRequest(orderId, customer)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode")
                        .value("PAYMENT_PROVIDER_UNAVAILABLE"));
        assertThat(checkout(orderId).get("razorpayOrderId").asString()).startsWith("order_");
    }

    /**
     * The payment exists without a Razorpay order (the consumer could not reach Razorpay); then the redelivered event
     * and three checkout requests ask for the Razorpay order at the same time, while Razorpay answers slowly.
     */
    @Test
    void consumerAndCheckoutRacingCreateExactlyOneRazorpayOrder() throws Exception {
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        RAZORPAY.failNext(FakeRazorpay.CREATE_ORDER, 503, 1);
        assertThatThrownBy(() -> orderListener.on(awaitingPayment(eventId, orderId, customer, "99.00")))
                .isInstanceOf(TransientEventProcessingException.class);
        RAZORPAY.orderDelay = Duration.ofMillis(400);

        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> callers = new ArrayList<>();
        callers.add(() -> {
            start.await();
            orderListener.on(awaitingPayment(eventId, orderId, customer, "99.00"));
            return "consumer";
        });
        for (int i = 0; i < 3; i++) {
            callers.add(() -> {
                start.await();
                MvcResult result = checkoutRequest(orderId, customer).andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                return json.readTree(result.getResponse().getContentAsString()).get("razorpayOrderId").asString();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(callers.size());
        try {
            List<Future<String>> futures = callers.stream().map(pool::submit).toList();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            String razorpayOrderId = razorpayOrderId(orderId);
            assertThat(results.subList(1, results.size())).containsOnly(razorpayOrderId);
        } finally {
            pool.shutdownNow();
        }
        // one refused call before the race, exactly one successful creation during it
        assertThat(RAZORPAY.ordersWithReceipt(orderNumber(orderId))).isEqualTo(1);
        assertThat(RAZORPAY.createOrderCalls(orderNumber(orderId))).isEqualTo(2);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        assertThat(paymentCount(orderId)).isEqualTo(1);
    }

    @Test
    void consumerAndCheckoutStartingTogetherCreateExactlyOneRazorpayOrder() throws Exception {
        UUID orderId = Ids.newId();
        RAZORPAY.orderDelay = Duration.ofMillis(300);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> consumer = pool.submit(() -> orderListener.on(awaitingPayment(newEventId(), orderId, customer,
                    "45.00")));
            List<Future<String>> checkouts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                checkouts.add(pool.submit(() -> {
                    for (int attempt = 0; attempt < 200; attempt++) {
                        MvcResult result = checkoutRequest(orderId, customer).andReturn();
                        if (result.getResponse().getStatus() == 200) {
                            return json.readTree(result.getResponse().getContentAsString()).get("razorpayOrderId")
                                    .asString();
                        }
                        assertThat(json.readTree(result.getResponse().getContentAsString()).get("errorCode").asString())
                                .isEqualTo("PAYMENT_NOT_READY");
                        Thread.sleep(10);
                    }
                    throw new AssertionError("checkout never became ready");
                }));
            }
            consumer.get(30, TimeUnit.SECONDS);
            for (Future<String> checkout : checkouts) {
                assertThat(checkout.get(30, TimeUnit.SECONDS)).isEqualTo(razorpayOrderId(orderId));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(RAZORPAY.createOrderCalls(orderNumber(orderId))).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
    }

    @Test
    void anEventPastItsWindowCreatesThePaymentButNoRazorpayOrder() {
        UUID orderId = Ids.newId();
        orderListener.on(awaitingPayment(newEventId(), orderId, customer, "30.00",
                java.time.Instant.now().minusSeconds(5)));

        assertThat(paymentStatus(orderId)).isEqualTo("CREATED");
        assertThat(RAZORPAY.createOrderCalls(orderNumber(orderId))).isZero();
        assertThat(eventTypes(orderId)).isEmpty();
    }

    @Test
    void brokenEventsAreInvalidAndOtherEventTypesIgnored() {
        UUID orderId = Ids.newId();
        Map<String, Object> noCustomer = new LinkedHashMap<>();
        noCustomer.put("orderId", orderId.toString());
        noCustomer.put("orderNumber", "GA-X");
        noCustomer.put("amount", 10);
        noCustomer.put("currency", "INR");
        noCustomer.put("expiresAt", "2026-11-11T10:15:00Z");
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderAwaitingPayment", newEventId(), orderId, noCustomer)))
                .isInstanceOf(InvalidEventException.class);

        ConsumerRecord<String, String> zero = awaitingPayment(newEventId(), orderId, customer, "0.00");
        assertThatThrownBy(() -> orderListener.on(zero)).isInstanceOf(InvalidEventException.class);
        ConsumerRecord<String, String> fractions = awaitingPayment(newEventId(), orderId, customer, "10.005");
        assertThatThrownBy(() -> orderListener.on(fractions)).isInstanceOf(InvalidEventException.class);

        ConsumerRecord<String, String> noHeaders = new ConsumerRecord<>("order.events", 0, 0L, orderId.toString(), "{}");
        assertThatThrownBy(() -> orderListener.on(noHeaders)).isInstanceOf(InvalidEventException.class);

        orderListener.on(orderEvent("OrderCreated", newEventId(), orderId, Map.of("orderId", orderId.toString())));
        orderListener.on(orderEvent("OrderConfirmed", newEventId(), orderId, Map.of("orderId", orderId.toString())));
        assertThat(paymentCount(orderId)).isZero();
    }

    @Test
    void orderCancelledBeforePaymentCancelsTheSessionAndPublishesNothing() throws Exception {
        UUID orderId = payableOrder("125.00");
        String eventId = newEventId();

        orderListener.on(orderCancelled(eventId, orderId, "AWAITING_PAYMENT"));
        orderListener.on(orderCancelled(eventId, orderId, "AWAITING_PAYMENT"));        // redelivery

        assertThat(paymentStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated");
        assertThat(refundRows(orderId)).isEmpty();
        checkoutRequest(orderId, customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("PAYMENT_CANCELLED"));
    }

    @Test
    void orderCancelledWithoutAPaymentDoesNothing() {
        UUID orderId = Ids.newId();
        orderListener.on(orderCancelled(newEventId(), orderId, "CREATED"));
        assertThat(paymentCount(orderId)).isZero();
        assertThat(eventTypes(orderId)).isEmpty();
    }

    @Test
    void orderCancelledAfterCaptureRefundsEverythingOnce() throws Exception {
        UUID orderId = payableOrder("125.00");
        String razorpayOrderId = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(razorpayOrderId, "captured", "card", 12500);
        verifySigned(customer, razorpayOrderId, paymentId);
        String eventId = newEventId();

        orderListener.on(orderCancelled(eventId, orderId, "CONFIRMED"));
        orderListener.on(orderCancelled(eventId, orderId, "CONFIRMED"));               // redelivery
        orderListener.on(orderCancelled(newEventId(), orderId, "CONFIRMED"));          // replay: nothing left

        List<Map<String, Object>> refunds = refundRows(orderId);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.getFirst()).containsEntry("reason", "ORDER_CANCELLED").containsEntry("status", "PROCESSED");
        assertThat((BigDecimal) refunds.getFirst().get("amount")).isEqualByComparingTo("125.00");
        assertThat(paymentRow(orderId)).containsEntry("status", "CAPTURED").containsEntry("refund_status", "FULL");
        assertThat(eventTypes(orderId)).containsExactly("PaymentInitiated", "PaymentCaptured", "RefundCompleted");
        JsonNode completed = event(orderId, "RefundCompleted");
        assertThat(completed.get("reason").asString()).isEqualTo("ORDER_CANCELLED");
        assertThat(completed.get("totalRefunded").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(completed.get("fullyRefunded").asBoolean()).isTrue();
        // the provider got the refund in paise with our refund id as idempotency key
        assertThat(RAZORPAY.requests()).filteredOn(r -> r.path().equals("/v1/payments/" + paymentId + "/refund"))
                .singleElement()
                .satisfies(r -> assertThat(r.refundIdempotency()).isEqualTo(refunds.getFirst().get("id")))
                .satisfies(r -> assertThat(json.readTree(r.body()).get("amount").asLong()).isEqualTo(12500));
    }

    @Test
    void orderCancelledAfterCaptureWithTheProviderDownLeavesTheRefundPending() throws Exception {
        UUID orderId = payableOrder("70.00");
        String razorpayOrderId = razorpayOrderId(orderId);
        String paymentId = RAZORPAY.addPayment(razorpayOrderId, "captured", "upi", 7000);
        verifySigned(customer, razorpayOrderId, paymentId);
        RAZORPAY.failNext(FakeRazorpay.CREATE_REFUND, 503, 1);

        orderListener.on(orderCancelled(newEventId(), orderId, "CONFIRMED"));          // does not throw

        assertThat(refundRows(orderId)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("status", "PENDING").containsEntry("provider_attempts", 1))
                .satisfies(r -> assertThat(r.get("razorpay_refund_id")).isNull());
        assertThat(paymentRow(orderId)).containsEntry("refund_status", "PENDING");
        // the PaymentView shows it
        mvcView(orderId);
    }

    private void mvcView(UUID orderId) throws Exception {
        JsonNode view = body(mvc.perform(get("/api/payments/orders/{id}", orderId).with(customer(customer))));
        assertThat(view.get("refundStatus").asString()).isEqualTo("PENDING");
        assertThat(view.get("refunds").get(0).get("status").asString()).isEqualTo("PENDING");
    }
}
