package com.grocery.notification;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 10 contract §4: {@code OrderStuckDetected} → {@code STUCK_ORDER} · WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT
 * ("Order {no} is stuck: {reason label}" · "{status} since {HH:mm}." · {@code /admin/orders}) and
 * {@code ADMIN_ORDERS_CHANGED} to every admin role; nothing for the customer or the store, no change to the order
 * projection; and the Phase 7 rules (event age, idempotency, broken events) for the new event.
 */
class StuckOrderNotificationsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> ALL_ADMINS = List.of("role:DELIVERY_MANAGER", "role:FINANCE_ADMIN",
            "role:OPS_ADMIN", "role:SUPER_ADMIN", "role:SUPPORT");

    private Order knownGroceryOrder() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(order);
        return order;
    }

    @Test
    void aStuckOrderAlertsTheAdminsWhoLookAfterOrders() throws Exception {
        Order order = knownGroceryOrder();
        ConsumerRecord<String, String> stuck = TestEvents.orderStuckDetected(order, "CONFIRMED",
                "STORE_RESPONSE_OVERDUE", Instant.parse("2026-11-11T09:05:00Z"));      // 14:35 India time

        dispatch(stuck);

        assertThat(notificationsOf(stuck)).hasSize(1);
        Stored alert = notificationOf(stuck, "STUCK_ORDER");
        assertThat(alert.category()).isEqualTo("ORDER");
        assertThat(alert.severity()).isEqualTo("WARNING");
        assertThat(alert.title()).isEqualTo("Order " + order.orderNumber() + " is stuck: store response overdue");
        assertThat(alert.body()).isEqualTo("CONFIRMED since 14:35.");
        assertThat(alert.link()).isEqualTo("/admin/orders");
        assertThat(alert.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN", "role:SUPPORT");
        assertThat(alert.data().get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(alert.data().get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(alert.data().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(alert.data().get("reason").asString()).isEqualTo("STORE_RESPONSE_OVERDUE");

        // one row: an admin holding several of the roles sees it once; the other admin roles and the customer never
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "SUPPORT", "OPS_ADMIN"))
                .extracting(n -> n.get("type").asString()).containsExactly("STUCK_ORDER");
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "FINANCE_ADMIN")).isEmpty();
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "DELIVERY_MANAGER")).isEmpty();
        assertThat(inboxAbout(order.orderId(), order.customerId(), "CUSTOMER")).isEmpty();

        // every admin's order screens refresh; the customer's order does not change
        JsonNode admin = recorder.await(signal("ADMIN_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(admin)).containsExactlyInAnyOrderElementsOf(ALL_ADMINS);
        assertThat(fieldNames(admin.at("/payload/data"))).containsExactly("orderId", "event");
        assertThat(admin.at("/payload/data/event").asString()).isEqualTo("OrderStuckDetected");
        awaitMarker();
        assertThat(recorder.matching(signal("ORDER_UPDATED").and(mentioning(order.orderId())))).isEmpty();
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("CREATED"));
    }

    @Test
    void everyReasonHasItsLabel() {
        Map<String, String> labels = Map.of(
                "STOCK_CHECK_OVERDUE", "stock check overdue",
                "PAYMENT_EXPIRY_OVERDUE", "payment expiry overdue",
                "STORE_RESPONSE_OVERDUE", "store response overdue",
                "PREPARATION_OVERDUE", "preparation overdue",
                "PICKUP_OVERDUE", "pickup overdue",
                "DELIVERY_OVERDUE", "delivery overdue");
        labels.forEach((reason, label) -> {
            Order order = knownGroceryOrder();
            ConsumerRecord<String, String> stuck = TestEvents.orderStuckDetected(order, "PREPARING", reason,
                    Instant.parse("2026-11-11T18:30:00Z"));                           // 00:00 India time
            dispatch(stuck);
            Stored alert = notificationOf(stuck, "STUCK_ORDER");
            assertThat(alert.title()).isEqualTo("Order " + order.orderNumber() + " is stuck: " + label);
            assertThat(alert.body()).isEqualTo("PREPARING since 00:00.");
        });
    }

    @Test
    void anOldOrRepeatedStuckEventAlertsNobodyTwice() {
        Order order = knownGroceryOrder();
        ConsumerRecord<String, String> old = TestEvents.old(TestEvents.orderStuckDetected(order, "CREATED",
                "STOCK_CHECK_OVERDUE", Instant.now().minusSeconds(7200)));
        dispatch(old);
        assertThat(processed(old)).isTrue();
        assertThat(notificationsOf(old)).isEmpty();

        ConsumerRecord<String, String> stuck = TestEvents.orderStuckDetected(order, "CREATED", "STOCK_CHECK_OVERDUE",
                Instant.now().minusSeconds(180));
        dispatch(stuck);
        dispatch(stuck);                                                              // redelivered
        assertThat(notificationsOf(stuck)).hasSize(1);
        awaitMarker();
        assertThat(recorder.matching(signal("ADMIN_ORDERS_CHANGED").and(mentioning(order.orderId())))).hasSize(1);
    }

    @Test
    void anUnknownOrderIsStillReported() {
        Order unknown = TestEvents.restaurantOrder(UUID.randomUUID(), UUID.randomUUID());   // projection never saw it
        ConsumerRecord<String, String> stuck = TestEvents.orderStuckDetected(unknown, "OUT_FOR_DELIVERY",
                "DELIVERY_OVERDUE", Instant.parse("2026-11-11T04:30:00Z"));
        dispatch(stuck);
        Stored alert = notificationOf(stuck, "STUCK_ORDER");
        assertThat(alert.title()).isEqualTo("Order " + unknown.orderNumber() + " is stuck: delivery overdue");
        assertThat(alert.body()).isEqualTo("OUT_FOR_DELIVERY since 10:00.");
        assertThat(orderRefRow(unknown.orderId())).isEmpty();                       // not created from this event
    }

    @Test
    void brokenStuckEventsAreInvalid() {
        Order order = knownGroceryOrder();
        for (String missing : List.of("orderId", "orderNumber", "status", "reason")) {
            Map<String, Object> payload = TestEvents.map("orderId", order.orderId(), "orderNumber",
                    order.orderNumber(), "orderType", order.orderType(), "customerId", order.customerId(), "storeId",
                    order.storeId(), "status", "CREATED", "reason", "STOCK_CHECK_OVERDUE", "since",
                    Instant.now().toString(), "detectedAt", Instant.now().toString());
            payload.put(missing, null);
            ConsumerRecord<String, String> broken = TestEvents.event("order.events", "OrderStuckDetected",
                    order.orderId(), payload);
            assertThatThrownBy(() -> dispatch(broken)).as("without %s", missing)
                    .isInstanceOf(InvalidEventException.class);
        }
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

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
