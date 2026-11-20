package com.grocery.notification;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 contract §6: the store's new-order alert from VendorOrderReceived / RestaurantOrderReceived, the board
 * signal STORE_ORDERS_CHANGED, ORDER_AUTO_REJECTED, the customer's ORDER_ACCEPTED / ORDER_READY / store cancellations,
 * and the Phase 7 rules (event age, idempotency, privacy) for the new events.
 */
class StoreOrderNotificationsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> ALL_ADMINS = List.of("role:DELIVERY_MANAGER", "role:FINANCE_ADMIN",
            "role:OPS_ADMIN", "role:SUPER_ADMIN", "role:SUPPORT");

    // ---------------------------------------------------------------- NEW_ORDER

    @Test
    void vendorOrderReceivedAlertsTheOwnerNamedInTheEvent() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();                          // the vendor's lifecycle events were never seen
        UUID otherVendorOwner = knownVendor(UUID.randomUUID(), "Other Mart");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> received = TestEvents.storeOrderReceived(order, owner, 3,
                Instant.parse("2026-11-11T10:05:00Z"));
        dispatch(received);

        Stored alert = notificationOf(received, "NEW_ORDER");
        assertThat(notificationsOf(received)).hasSize(1);
        assertThat(alert.category()).isEqualTo("ORDER");
        assertThat(alert.severity()).isEqualTo("INFO");
        assertThat(alert.title()).isEqualTo("New order " + order.orderNumber());
        assertThat(alert.body()).isEqualTo("3 items · ₹245.00 · accept by 15:35");
        assertThat(alert.link()).isEqualTo("/vendor/orders");
        assertThat(alert.audiences()).containsExactly("user:" + owner);
        assertThat(alert.data().get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(alert.data().get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(Instant.parse(alert.data().get("acceptBy").asString()))
                .isEqualTo(Instant.parse("2026-11-11T10:05:00Z"));
        assertThat(storeOwnerRow("VENDOR", vendorId)).get()
                .satisfies(row -> assertThat(row.get("owner")).isEqualTo(owner));

        JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(board)).containsExactly("user:" + owner);
        assertThat(board.at("/payload/data/orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(board.at("/payload/data/status").asString()).isEqualTo("NEW");
        assertThat(board.at("/payload/data").size()).isEqualTo(2);

        // only the owner sees it
        assertThat(inboxAbout(order.orderId(), owner, "VENDOR")).extracting(n -> n.get("type").asString())
                .containsExactly("NEW_ORDER");
        assertThat(inboxAbout(order.orderId(), otherVendorOwner, "VENDOR")).isEmpty();
        assertThat(inboxAbout(order.orderId(), customer, "CUSTOMER")).isEmpty();
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "SUPER_ADMIN", "OPS_ADMIN")).isEmpty();

        // a redelivery changes nothing
        dispatch(received);
        assertThat(notificationsOf(received)).hasSize(1);
    }

    @Test
    void aKnownOwnerKeepsItsNameAndRestaurantsGetTheirBoardLink() {
        UUID restaurantId = UUID.randomUUID();
        UUID owner = knownRestaurant(restaurantId, "Spice Route");
        Order order = TestEvents.restaurantOrder(UUID.randomUUID(), restaurantId);

        ConsumerRecord<String, String> received = TestEvents.storeOrderReceived(order, owner, 1,
                Instant.parse("2026-11-11T12:30:00Z"));
        dispatch(received);

        Stored alert = notificationOf(received, "NEW_ORDER");
        assertThat(alert.body()).isEqualTo("1 item · ₹1,359.50 · accept by 18:00");
        assertThat(alert.link()).isEqualTo("/restaurant/orders");
        assertThat(alert.audiences()).containsExactly("user:" + owner);
        assertThat(storeOwnerRow("RESTAURANT", restaurantId)).get()
                .satisfies(row -> assertThat(row.get("name")).isEqualTo("Spice Route"));
    }

    @Test
    void newOrderIsNoLongerSentOnOrderConfirmed() {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> v10 = TestEvents.orderConfirmed(order, 2, 1);
        dispatch(v10);
        Order other = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(other);
        ConsumerRecord<String, String> v11 = TestEvents.orderConfirmedV11(other, "Ring twice");
        dispatch(v11);

        for (ConsumerRecord<String, String> confirmed : List.of(v10, v11)) {
            assertThat(notificationsOf(confirmed)).extracting(Stored::type).containsExactly("ORDER_CONFIRMED");
            assertThat(notificationsOf(confirmed)).allSatisfy(n ->
                    assertThat(n.audiences()).containsExactly("user:" + customer));
        }
        // the Phase 7 ORDER_UPDATED to the store owner on OrderConfirmed stays, no board signal from it
        JsonNode update = recorder.await(signal("ORDER_UPDATED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(update)).containsExactlyInAnyOrder("user:" + customer, "user:" + owner);
        awaitMarker();
        assertThat(recorder.matching(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())))).isEmpty();
    }

    // ---------------------------------------------------------------- the board signal and auto-rejection

    @Test
    void everyStoreOrderEventSignalsTheBoardOfItsOwnerOnly() {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Green Basket");
        UUID otherOwner = knownVendor(UUID.randomUUID(), "Other Mart");
        Order order = TestEvents.groceryOrder(customer, vendorId);

        List<ConsumerRecord<String, String>> events = List.of(
                TestEvents.storeOrderReceived(order, owner, 2, Instant.now().plusSeconds(300)),
                TestEvents.storeOrderAccepted(order, 15),
                TestEvents.storeOrderPreparing(order),
                TestEvents.storeOrderReady(order));
        events.forEach(this::dispatch);

        List<JsonNode> board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 4,
                WAIT);
        assertThat(board).extracting(m -> m.at("/payload/data/status").asString())
                .containsExactly("NEW", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP");
        assertThat(board).allSatisfy(m -> assertThat(audiences(m)).containsExactly("user:" + owner));
        // only Received notifies; the customer and the admins hear about it through order.events instead
        assertThat(events.subList(1, 4)).allSatisfy(e -> {
            assertThat(processed(e)).isTrue();
            assertThat(notificationsOf(e)).isEmpty();
        });
        awaitMarker();
        assertThat(recorder.matching(mentioning(order.orderId())))
                .as("the NEW_ORDER notification and the board signals, to the owner only (not %s, %s or admins)",
                        otherOwner, customer)
                .hasSize(5)
                .allSatisfy(m -> assertThat(audiences(m)).containsExactly("user:" + owner));
        assertThat(orderRefRow(order.orderId())).as("the order projection follows order.events only").isEmpty();
    }

    @Test
    void aRejectionSignalsTheBoardAndAnAutomaticOneAlsoNotifiesTheOwner() {
        UUID restaurantId = UUID.randomUUID();
        UUID owner = knownRestaurant(restaurantId, "Spice Route");
        Order rejected = TestEvents.restaurantOrder(UUID.randomUUID(), restaurantId);
        Order auto = TestEvents.restaurantOrder(UUID.randomUUID(), restaurantId);

        ConsumerRecord<String, String> byTheStore = TestEvents.storeOrderRejected(rejected, "TOO_BUSY", null, false);
        ConsumerRecord<String, String> automatic = TestEvents.storeOrderRejected(auto, "NO_RESPONSE", null, true);
        dispatch(byTheStore);
        dispatch(automatic);

        assertThat(notificationsOf(byTheStore)).isEmpty();
        Stored alert = notificationOf(automatic, "ORDER_AUTO_REJECTED");
        assertThat(notificationsOf(automatic)).hasSize(1);
        assertThat(alert.category()).isEqualTo("ORDER");
        assertThat(alert.severity()).isEqualTo("WARNING");
        assertThat(alert.title()).isEqualTo("Order " + auto.orderNumber() + " was auto-rejected");
        assertThat(alert.body()).isEqualTo("It was not accepted in time. The customer has been refunded.");
        assertThat(alert.link()).isEqualTo("/restaurant/orders");
        assertThat(alert.audiences()).containsExactly("user:" + owner);
        assertThat(alert.data().get("orderId").asString()).isEqualTo(auto.orderId().toString());
        assertThat(alert.data().get("orderNumber").asString()).isEqualTo(auto.orderNumber());

        for (Order order : List.of(rejected, auto)) {
            JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                    .getFirst();
            assertThat(board.at("/payload/data/status").asString()).isEqualTo("REJECTED");
            assertThat(audiences(board)).containsExactly("user:" + owner);
        }
    }

    @Test
    void aStoreEventOfAnUnknownStoreNotifiesNobody() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> accepted = TestEvents.storeOrderAccepted(order, 10);
        ConsumerRecord<String, String> auto = TestEvents.storeOrderRejected(order, "NO_RESPONSE", null, true);

        dispatch(accepted);
        dispatch(auto);

        assertThat(processed(accepted)).isTrue();
        assertThat(processed(auto)).isTrue();
        assertThat(notificationsOf(auto)).isEmpty();
        awaitMarker();
        assertThat(recorder.matching(mentioning(order.orderId()))).isEmpty();
    }

    @Test
    void oldStoreEventsOnlyUpdateTheProjection() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);

        ConsumerRecord<String, String> received = TestEvents.old(TestEvents.storeOrderReceived(order, owner, 2,
                Instant.now()));
        ConsumerRecord<String, String> auto = TestEvents.old(TestEvents.storeOrderRejected(order, "NO_RESPONSE",
                null, true));
        dispatch(received);
        dispatch(auto);

        assertThat(processed(received)).isTrue();
        assertThat(notificationsOf(received)).isEmpty();
        assertThat(notificationsOf(auto)).isEmpty();
        assertThat(storeOwnerRow("VENDOR", vendorId)).get()
                .satisfies(row -> assertThat(row.get("owner")).isEqualTo(owner));
        awaitMarker();
        assertThat(recorder.matching(mentioning(order.orderId()))).isEmpty();
    }

    @Test
    void brokenStoreOrderEventsAreInvalid() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> dispatch(TestEvents.event("vendor.events", "VendorOrderReceived", order.orderId(),
                TestEvents.map("orderNumber", order.orderNumber(), "vendorId", order.storeId(),
                        "ownerUserId", UUID.randomUUID()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> dispatch(TestEvents.event("vendor.events", "VendorOrderReceived", order.orderId(),
                TestEvents.map("orderId", order.orderId(), "orderNumber", order.orderNumber(),
                        "vendorId", order.storeId()))))
                .isInstanceOf(InvalidEventException.class);                         // no ownerUserId
        assertThatThrownBy(() -> dispatch(TestEvents.event("restaurant.events", "RestaurantOrderAccepted",
                order.orderId(), TestEvents.map("orderId", order.orderId(), "orderNumber", order.orderNumber(),
                        "vendorId", order.storeId()))))
                .isInstanceOf(InvalidEventException.class);                         // no restaurantId
    }

    // ---------------------------------------------------------------- the customer (order.events)

    @Test
    void orderAcceptedTellsTheCustomerWhenItWillBeReady() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID owner = knownRestaurant(restaurantId, "Spice Route Kitchen");
        Order order = TestEvents.restaurantOrder(customer, restaurantId);
        knownOrder(order);

        ConsumerRecord<String, String> accepted = TestEvents.orderAccepted(order, 20);
        dispatch(accepted);

        Stored notification = notificationOf(accepted, "ORDER_ACCEPTED");
        assertThat(notificationsOf(accepted)).hasSize(1);
        assertThat(notification.category()).isEqualTo("ORDER");
        assertThat(notification.severity()).isEqualTo("SUCCESS");
        assertThat(notification.title()).isEqualTo("Spice Route accepted order " + order.orderNumber());
        assertThat(notification.body()).isEqualTo("Ready in about 20 min.");
        assertThat(notification.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(notification.audiences()).containsExactly("user:" + customer);
        assertThat(notification.data().get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(notification.data().get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("ACCEPTED"));
        assertThat(inboxAbout(order.orderId(), owner, "RESTAURANT_OWNER")).isEmpty();

        assertOrderSignals(order, "OrderAccepted", "ACCEPTED");
    }

    @Test
    void theStoreNameFallsBackToTheOwnersProjectionOrANeutralWord() {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        // neither OrderCreated (with the store name) nor, for the second, the vendor was seen
        Order known = new Order(UUID.randomUUID(), TestEvents.orderNumber(), "GROCERY", customer, vendorId, null,
                null);
        Order unknown = new Order(UUID.randomUUID(), TestEvents.orderNumber(), "GROCERY", customer,
                UUID.randomUUID(), null, null);

        ConsumerRecord<String, String> first = TestEvents.orderAccepted(known, 15);
        ConsumerRecord<String, String> second = TestEvents.orderAccepted(unknown, 15);
        dispatch(first);
        dispatch(second);

        assertThat(notificationOf(first, "ORDER_ACCEPTED").title())
                .isEqualTo("Fresh Farms accepted order " + known.orderNumber());
        assertThat(notificationOf(second, "ORDER_ACCEPTED").title())
                .isEqualTo("The store accepted order " + unknown.orderNumber());
    }

    @Test
    void orderPreparingOnlySignals() {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> preparing = TestEvents.orderPreparing(order);
        dispatch(preparing);

        assertThat(processed(preparing)).isTrue();
        assertThat(notificationsOf(preparing)).isEmpty();
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("PREPARING"));
        assertOrderSignals(order, "OrderPreparing", "PREPARING");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GROCERY    | Packed and waiting for a delivery partner.",
            "RESTAURANT | Your food is ready and waiting for a delivery partner."
    })
    void orderReadyForPickupTellsTheCustomer(String orderType, String body) {
        UUID customer = UUID.randomUUID();
        Order order = "GROCERY".equals(orderType)
                ? TestEvents.groceryOrder(customer, UUID.randomUUID())
                : TestEvents.restaurantOrder(customer, UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> ready = TestEvents.orderReadyForPickup(order);
        dispatch(ready);

        Stored notification = notificationOf(ready, "ORDER_READY");
        assertThat(notificationsOf(ready)).hasSize(1);
        assertThat(notification.category()).isEqualTo("ORDER");
        assertThat(notification.severity()).isEqualTo("INFO");
        assertThat(notification.title()).isEqualTo("Order " + order.orderNumber() + " is ready");
        assertThat(notification.body()).isEqualTo(body);
        assertThat(notification.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(notification.audiences()).containsExactly("user:" + customer);
        assertThat(notification.data().get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("READY_FOR_PICKUP"));
        assertOrderSignals(order, "OrderReadyForPickup", "READY_FOR_PICKUP");
    }

    @Test
    void aStoreRejectionTellsTheCustomerAndRefreshesTheBoardWithoutAStoreNotification() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> cancelled = TestEvents.orderCancelledBy(order, "CONFIRMED", "STORE_REJECTED",
                "STORE", "OUT_OF_STOCK: Toor dal is over");
        dispatch(cancelled);

        Stored notification = notificationOf(cancelled, "ORDER_CANCELLED");
        assertThat(notificationsOf(cancelled)).as("the store rejected it itself: no 'do not prepare it'").hasSize(1);
        assertThat(notification.severity()).isEqualTo("WARNING");
        assertThat(notification.title()).isEqualTo("Order " + order.orderNumber() + " cancelled");
        assertThat(notification.body()).isEqualTo("Green Basket could not accept your order "
                + "(items out of stock: Toor dal is over). A full refund is on its way.");
        assertThat(notification.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(notification.audiences()).containsExactly("user:" + customer);
        assertThat(inboxAbout(order.orderId(), owner, "VENDOR")).isEmpty();

        JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(board.at("/payload/data/status").asString()).isEqualTo("CANCELLED");
        assertThat(audiences(board)).containsExactly("user:" + owner);
        JsonNode update = recorder.await(signal("ORDER_UPDATED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(update)).containsExactlyInAnyOrder("user:" + customer, "user:" + owner);
    }

    @Test
    void noResponseFromTheStoreTellsTheCustomer() {
        UUID customer = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID owner = knownRestaurant(restaurantId, "Spice Route");
        Order order = TestEvents.restaurantOrder(customer, restaurantId);
        knownOrder(order);

        ConsumerRecord<String, String> cancelled = TestEvents.orderCancelledBy(order, "CONFIRMED",
                "STORE_NO_RESPONSE", "SYSTEM", null);
        dispatch(cancelled);

        assertThat(notificationsOf(cancelled)).hasSize(1);
        Stored notification = notificationOf(cancelled, "ORDER_CANCELLED");
        assertThat(notification.body()).isEqualTo("The store did not respond in time. A full refund is on its way.");
        assertThat(notification.audiences()).containsExactly("user:" + customer);
        JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(board)).containsExactly("user:" + owner);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"ACCEPTED", "PREPARING", "READY_FOR_PICKUP"})
    void anAdminCancellingAnAcceptedOrderTellsCustomerAndStore(String previousStatus) {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> cancelled = TestEvents.orderCancelledBy(order, previousStatus,
                "ADMIN_CANCELLED", "ADMIN", "Rider strike.");
        dispatch(cancelled);

        assertThat(notificationsOf(cancelled)).hasSize(2);
        assertThat(notificationsOf(cancelled)).filteredOn(n -> n.audiences().contains("user:" + customer))
                .singleElement().satisfies(n -> assertThat(n.body())
                        .isEqualTo("Cancelled by our support team. Rider strike. A full refund is on its way."));
        assertThat(notificationsOf(cancelled)).filteredOn(n -> n.audiences().contains("user:" + owner))
                .singleElement().satisfies(n -> {
                    assertThat(n.type()).isEqualTo("ORDER_CANCELLED");
                    assertThat(n.body()).isEqualTo("The customer's order was cancelled; do not prepare it.");
                    assertThat(n.link()).isEqualTo("/vendor");
                });
        JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(board.at("/payload/data/status").asString()).isEqualTo("CANCELLED");
        assertThat(audiences(board)).containsExactly("user:" + owner);
    }

    @Test
    void aCancellationBeforeTheStoreHadTheOrderDoesNotTouchTheBoard() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(order);

        dispatch(TestEvents.orderCancelled(order, "AWAITING_PAYMENT", "CUSTOMER_CANCELLED", null));

        recorder.await(signal("ORDER_UPDATED").and(mentioning(order.orderId())), 1, WAIT);
        awaitMarker();
        assertThat(recorder.matching(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())))).isEmpty();
        assertThat(recorder.matching(mentioning(order.orderId())))
                .noneSatisfy(m -> assertThat(audiences(m)).contains("user:" + owner));
    }

    @Test
    void oldOrderEventsOnlyKeepTheNewStatuses() {
        UUID customer = UUID.randomUUID();
        Order order = TestEvents.restaurantOrder(customer, UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> accepted = TestEvents.old(TestEvents.orderAccepted(order, 20));
        dispatch(accepted);
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("ACCEPTED"));
        ConsumerRecord<String, String> ready = TestEvents.old(TestEvents.orderReadyForPickup(order));
        dispatch(ready);
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("READY_FOR_PICKUP"));

        assertThat(notificationsOf(accepted)).isEmpty();
        assertThat(notificationsOf(ready)).isEmpty();
        awaitMarker();
        assertThat(recorder.matching(mentioning(order.orderId()))).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** ORDER_UPDATED to the customer only and ADMIN_ORDERS_CHANGED to every admin role, as for every order event. */
    private void assertOrderSignals(Order order, String event, String status) {
        JsonNode update = recorder.await(signal("ORDER_UPDATED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(update.at("/payload/data/event").asString()).isEqualTo(event);
        assertThat(update.at("/payload/data/status").asString()).isEqualTo(status);
        assertThat(audiences(update)).containsExactly("user:" + order.customerId());
        JsonNode admin = recorder.await(signal("ADMIN_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(admin)).containsExactlyInAnyOrderElementsOf(ALL_ADMINS);
        assertThat(admin.at("/payload/data/event").asString()).isEqualTo(event);
    }

    /** A marker after the events of a test: once it arrived, anything published before it has arrived too. */
    private void awaitMarker() {
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        assertThat(recorder.await(kind("signal").and(mentioning(marker)), 1, WAIT)).isNotEmpty();
    }

    private static List<String> audiences(JsonNode message) {
        List<String> audiences = new ArrayList<>();
        message.get("audiences").forEach(a -> audiences.add(a.asString()));
        return audiences;
    }
}
