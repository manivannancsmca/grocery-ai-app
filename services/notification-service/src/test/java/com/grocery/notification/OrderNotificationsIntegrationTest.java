package com.grocery.notification;

import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract §4, order.events rows: texts, links, severities and exactly the right audiences. */
class OrderNotificationsIntegrationTest extends AbstractIntegrationTest {

    /**
     * Phase 8 §6: OrderConfirmed notifies the customer only; the store's NEW_ORDER now comes from
     * VendorOrderReceived / RestaurantOrderReceived (see {@code StoreOrderNotificationsIntegrationTest}).
     */
    @Test
    void orderConfirmedNotifiesTheCustomerOnlyAndNoLongerSendsNewOrder() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID vendorOwner = knownVendor(vendorId, "Green Basket Stores");
        UUID otherCustomer = UUID.randomUUID();
        UUID otherVendorOwner = knownVendor(UUID.randomUUID(), "Other Store");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 2, 1);
        dispatch(confirmed);

        assertThat(notificationsOf(confirmed)).extracting(Stored::type).containsExactly("ORDER_CONFIRMED");
        Stored forCustomer = notificationOf(confirmed, "ORDER_CONFIRMED");
        assertThat(forCustomer.category()).isEqualTo("ORDER");
        assertThat(forCustomer.severity()).isEqualTo("SUCCESS");
        assertThat(forCustomer.title()).isEqualTo("Order " + order.orderNumber() + " confirmed");
        assertThat(forCustomer.body()).isEqualTo("Paid ₹245.00. Green Basket will start preparing it soon.");
        assertThat(forCustomer.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(forCustomer.data().get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(forCustomer.data().get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(forCustomer.audiences()).containsExactly("user:" + customer);

        // through the API: the right people see it, nobody else does — and the vendor gets nothing from it
        assertThat(inboxAbout(order.orderId(), customer, "CUSTOMER")).extracting(n -> n.get("type").asString())
                .containsExactly("ORDER_CONFIRMED");
        assertThat(inboxAbout(order.orderId(), vendorOwner, "VENDOR")).isEmpty();
        assertThat(inboxAbout(order.orderId(), otherCustomer, "CUSTOMER")).isEmpty();
        assertThat(inboxAbout(order.orderId(), otherVendorOwner, "VENDOR")).isEmpty();
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN",
                "SUPPORT", "DELIVERY_MANAGER")).isEmpty();
    }

    @Test
    void restaurantOrdersGoToTheRestaurantOwnerWithIndianMoneyFormatAndOneItem() {
        UUID customer = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID restaurantOwner = knownRestaurant(restaurantId, "Spice Route");
        Order order = TestEvents.restaurantOrder(customer, restaurantId);
        knownOrder(order);

        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);
        // Phase 8: the restaurant's alert comes with the store order (accept by 23:59 India time = 18:29 UTC)
        ConsumerRecord<String, String> received = TestEvents.storeOrderReceived(order, restaurantOwner, 1,
                Instant.parse("2026-11-11T18:29:00Z"));
        dispatch(received);

        assertThat(notificationsOf(confirmed)).extracting(Stored::type).containsExactly("ORDER_CONFIRMED");
        Stored forStore = notificationOf(received, "NEW_ORDER");
        assertThat(forStore.body()).isEqualTo("1 item · ₹1,359.50 · accept by 23:59");
        assertThat(forStore.link()).isEqualTo("/restaurant/orders");
        assertThat(forStore.audiences()).containsExactly("user:" + restaurantOwner);
        assertThat(notificationOf(confirmed, "ORDER_CONFIRMED").body())
                .isEqualTo("Paid ₹1,359.50. Spice Route will start preparing it soon.");
    }

    @Test
    void orderRejectedNotifiesTheCustomerOnly() {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> rejected = TestEvents.orderRejected(order);
        dispatch(rejected);

        Stored notification = notificationOf(rejected, "ORDER_REJECTED");
        assertThat(notificationsOf(rejected)).hasSize(1);
        assertThat(notification.severity()).isEqualTo("ERROR");
        assertThat(notification.category()).isEqualTo("ORDER");
        assertThat(notification.title()).isEqualTo("Order " + order.orderNumber() + " could not be placed");
        assertThat(notification.body()).isEqualTo("Some items ran out of stock. You have not been charged.");
        assertThat(notification.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(notification.audiences()).containsExactly("user:" + customer);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PAYMENT_TIMEOUT     | AWAITING_PAYMENT | | Payment was not completed in time.",
            "CUSTOMER_CANCELLED  | CREATED          | | You cancelled this order.",
            "ADMIN_CANCELLED     | AWAITING_PAYMENT | Duplicate order. | Cancelled by our support team. Duplicate order.",
            "ADMIN_CANCELLED     | CREATED          | | Cancelled by our support team.",
            "RESERVATION_EXPIRED | AWAITING_PAYMENT | | The items could not be held any longer.",
            "CUSTOMER_CANCELLED  | CONFIRMED        | | You cancelled this order. A full refund is on its way.",
            "ADMIN_CANCELLED     | CONFIRMED        | Store closed. | Cancelled by our support team. Store closed. A full refund is on its way.",
            "RESERVATION_EXPIRED | CONFIRMED        | | The items could not be held any longer. A full refund is on its way."
    })
    void orderCancelledTellsTheCustomerWhy(String reason, String previousStatus, String note, String body) {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID vendorOwner = knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> cancelled = TestEvents.orderCancelled(order, previousStatus, reason, note);
        dispatch(cancelled);

        Stored forCustomer = notificationsOf(cancelled).stream()
                .filter(n -> n.audiences().equals(Set.of("user:" + customer))).findFirst().orElseThrow();
        assertThat(forCustomer.type()).isEqualTo("ORDER_CANCELLED");
        assertThat(forCustomer.severity()).isEqualTo("WARNING");
        assertThat(forCustomer.title()).isEqualTo("Order " + order.orderNumber() + " cancelled");
        assertThat(forCustomer.body()).isEqualTo(body);
        assertThat(forCustomer.link()).isEqualTo("/orders/" + order.orderId());

        if ("CONFIRMED".equals(previousStatus)) {
            // the store was told to prepare it: now it is told not to
            assertThat(notificationsOf(cancelled)).hasSize(2);
            Stored forStore = notificationsOf(cancelled).stream()
                    .filter(n -> n.audiences().equals(Set.of("user:" + vendorOwner))).findFirst().orElseThrow();
            assertThat(forStore.type()).isEqualTo("ORDER_CANCELLED");
            assertThat(forStore.severity()).isEqualTo("WARNING");
            assertThat(forStore.title()).isEqualTo("Order " + order.orderNumber() + " cancelled");
            assertThat(forStore.body()).isEqualTo("The customer's order was cancelled; do not prepare it.");
            assertThat(forStore.link()).isEqualTo("/vendor");
        } else {
            assertThat(notificationsOf(cancelled)).hasSize(1);
        }
    }

    @Test
    void cancellingAConfirmedRestaurantOrderTellsTheRestaurant() {
        UUID customer = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID restaurantOwner = knownRestaurant(restaurantId, "Spice Route");
        Order order = TestEvents.restaurantOrder(customer, restaurantId);
        knownOrder(order);

        ConsumerRecord<String, String> cancelled = TestEvents.orderCancelled(order, "CONFIRMED", "ADMIN_CANCELLED",
                null);
        dispatch(cancelled);

        assertThat(notificationsOf(cancelled)).extracting(Stored::audiences).containsExactlyInAnyOrder(
                Set.of("user:" + customer), Set.of("user:" + restaurantOwner));
        assertThat(notificationsOf(cancelled)).extracting(Stored::link)
                .containsExactlyInAnyOrder("/orders/" + order.orderId(), "/restaurant");
    }

    @Test
    void orderCreatedAndAwaitingPaymentNotifyNobody() {
        UUID customer = UUID.randomUUID();
        Order order = TestEvents.groceryOrder(customer, UUID.randomUUID());
        ConsumerRecord<String, String> created = TestEvents.orderCreated(order);
        ConsumerRecord<String, String> awaiting = TestEvents.orderAwaitingPayment(order);

        dispatch(created);
        dispatch(awaiting);

        assertThat(notificationsOf(created)).isEmpty();
        assertThat(notificationsOf(awaiting)).isEmpty();
        assertThat(processed(created)).isTrue();
        assertThat(processed(awaiting)).isTrue();
    }

    @Test
    void anUnknownStoreOwnerStillNotifiesTheCustomer() {
        UUID customer = UUID.randomUUID();
        Order order = new Order(UUID.randomUUID(), TestEvents.orderNumber(), "GROCERY", customer, UUID.randomUUID(),
                "Corner Shop", new BigDecimal("99.00"));
        knownOrder(order);

        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);

        assertThat(notificationsOf(confirmed)).extracting(Stored::type).containsExactly("ORDER_CONFIRMED");
        assertThat(notificationOf(confirmed, "ORDER_CONFIRMED").body())
                .isEqualTo("Paid ₹99.00. Corner Shop will start preparing it soon.");
    }

    @Test
    void aMissingStoreNameFallsBackToANeutralWord() {
        UUID customer = UUID.randomUUID();
        // neither OrderCreated nor the store's events were seen: the store name is unknown
        Order order = new Order(UUID.randomUUID(), TestEvents.orderNumber(), "GROCERY", customer, UUID.randomUUID(),
                null, new BigDecimal("1234.50"));

        ConsumerRecord<String, String> confirmed = TestEvents.orderConfirmed(order, 1);
        dispatch(confirmed);

        assertThat(notificationOf(confirmed, "ORDER_CONFIRMED").body())
                .isEqualTo("Paid ₹1,234.50. The store will start preparing it soon.");
        assertThat(orderRefRow(order.orderId())).get().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("customerId")).isEqualTo(customer);
        });
    }
}
