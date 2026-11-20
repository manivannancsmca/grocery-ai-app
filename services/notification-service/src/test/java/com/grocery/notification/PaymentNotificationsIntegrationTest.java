package com.grocery.notification;

import com.grocery.common.security.Roles;
import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract §4, payment.events rows (payment events before their order: {@link ParkedPaymentEventsIntegrationTest}). */
class PaymentNotificationsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aRetryablePaymentFailureTellsTheCustomer() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), "Card declined by bank.",
                true);
        dispatch(failed);

        Stored notification = notificationOf(failed, "PAYMENT_FAILED");
        assertThat(notification.category()).isEqualTo("PAYMENT");
        assertThat(notification.severity()).isEqualTo("ERROR");
        assertThat(notification.title()).isEqualTo("Payment declined for order " + order.orderNumber());
        assertThat(notification.body())
                .isEqualTo("Card declined by bank. You can try again before the payment window closes.");
        assertThat(notification.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(notification.audiences()).containsExactly("user:" + order.customerId());
    }

    @Test
    void aPaymentFailureWithoutDescriptionUsesTheBankSentence() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), null, true);
        dispatch(failed);

        assertThat(notificationOf(failed, "PAYMENT_FAILED").body()).isEqualTo(
                "Your bank declined the payment. You can try again before the payment window closes.");
    }

    @Test
    void aFinalPaymentFailureAndTheOtherPaymentEventsNotifyNobody() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);
        List<ConsumerRecord<String, String>> events = List.of(
                TestEvents.paymentFailed(order.orderId(), "Expired card", false),
                TestEvents.paymentInitiated(order.orderId()),
                TestEvents.payment("PaymentCaptured", order.orderId(), TestEvents.map("razorpayPaymentId", "pay_1",
                        "amount", new BigDecimal("245.00"), "method", "upi",
                        "capturedAt", Instant.now().toString(), "late", false)),
                TestEvents.payment("PaymentExpired", order.orderId(), TestEvents.map()));

        events.forEach(this::dispatch);

        events.forEach(event -> {
            assertThat(processed(event)).isTrue();
            assertThat(notificationsOf(event)).isEmpty();
        });
    }

    @Test
    void refundCompletedTellsTheCustomer() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> refunded = TestEvents.refundCompleted(order.orderId(),
                new BigDecimal("125000.00"));
        dispatch(refunded);

        Stored notification = notificationOf(refunded, "REFUND_COMPLETED");
        assertThat(notification.category()).isEqualTo("PAYMENT");
        assertThat(notification.severity()).isEqualTo("SUCCESS");
        assertThat(notification.title()).isEqualTo("Refund of ₹1,25,000.00 processed");
        assertThat(notification.body()).isEqualTo("For order " + order.orderNumber()
                + ". Banks usually take 5–7 working days to show it.");
        assertThat(notification.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(notification.audiences()).containsExactly("user:" + order.customerId());
    }

    @Test
    void refundFailedIsOneNotificationForSuperAndFinanceAdmins() throws Exception {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> failed = TestEvents.refundFailed(order.orderId(), new BigDecimal("245.00"),
                "ORDER_CANCELLED", "GATEWAY_ERROR");
        dispatch(failed);

        assertThat(notificationsOf(failed)).hasSize(1);
        Stored notification = notificationOf(failed, "REFUND_FAILED");
        assertThat(notification.severity()).isEqualTo("ERROR");
        assertThat(notification.title()).isEqualTo("Refund failed for order " + order.orderNumber());
        assertThat(notification.body()).isEqualTo(
                "₹245.00 (ORDER_CANCELLED) — GATEWAY_ERROR. Retry it from the payment monitor.");
        assertThat(notification.link()).isEqualTo("/admin/payments");
        assertThat(notification.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:FINANCE_ADMIN");

        // an admin holding both roles sees it once; other admin roles and the customer do not see it
        List<JsonNode> both = inboxAbout(order.orderId(), UUID.randomUUID(), Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN);
        assertThat(both).hasSize(1);
        assertThat(both.getFirst().get("id").asString()).isEqualTo(notification.id().toString());
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), Roles.FINANCE_ADMIN)).hasSize(1);
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), Roles.OPS_ADMIN, Roles.SUPPORT,
                Roles.DELIVERY_MANAGER)).isEmpty();
        assertThat(inboxAbout(order.orderId(), order.customerId(), Roles.CUSTOMER)).isEmpty();
    }

}
