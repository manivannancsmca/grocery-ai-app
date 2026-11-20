package com.grocery.notification;

import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 12 §3: the return events on order.events → RETURN_REQUESTED to the owner of the order's store (never another
 * store's), RETURN_ESCALATED to the moderators, RETURN_APPROVED / RETURN_REJECTED to the customer, and the signals
 * ORDER_UPDATED (customer), STORE_RETURNS_CHANGED (store owner), ADMIN_RETURNS_CHANGED and ADMIN_ORDERS_CHANGED
 * (admins). Events travel as Avro with the real schemas 1.5.0.
 */
class ReturnNotificationsIntegrationTest extends AbstractIntegrationTest {

    /** 14:35 India time. */
    private static final Instant DECIDE_BY = Instant.parse("2026-11-13T09:05:00Z");

    @Autowired
    AvroEventEncoder avroEncoder;
    @Autowired
    JsonAvroConverter avroConverter;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    private AvroRoundTrip avro;
    private UUID customer;
    private UUID vendorId;
    private UUID vendorOwner;
    private UUID otherVendorOwner;
    private Order order;
    private UUID returnId;
    private String returnNumber;

    @BeforeEach
    void deliveredOrder() {
        avro = new AvroRoundTrip(avroEncoder, avroConverter, consumerFactory);
        customer = UUID.randomUUID();
        vendorId = UUID.randomUUID();
        vendorOwner = knownVendor(vendorId, "Green Basket");
        otherVendorOwner = knownVendor(UUID.randomUUID(), "Other Mart");
        order = TestEvents.groceryOrder(customer, vendorId);
        knownOrder(order);
        returnId = UUID.randomUUID();
        returnNumber = TestEvents.returnNumber();
    }

    @Test
    void aReturnRequestTellsTheStoreOwnerOnly() throws Exception {
        ConsumerRecord<String, String> requested = avro.via(TestEvents.returnRequested(order, returnId, returnNumber,
                new BigDecimal("95.00"), DECIDE_BY, 2, 1));
        dispatch(requested);

        Stored notification = notificationOf(requested, "RETURN_REQUESTED");
        assertThat(notificationsOf(requested)).hasSize(1);
        assertThat(notification.category()).isEqualTo("ORDER");
        assertThat(notification.severity()).isEqualTo("WARNING");
        assertThat(notification.title()).isEqualTo("Return requested for order " + order.orderNumber());
        assertThat(notification.body()).isEqualTo("3 items · ₹95.00 · decide by 14:35");
        assertThat(notification.link()).isEqualTo("/vendor/returns");
        assertThat(notification.audiences()).containsExactly("user:" + vendorOwner);
        assertThat(notification.data().get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(notification.data().get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(notification.data().get("returnId").asString()).isEqualTo(returnId.toString());
        assertThat(notification.data().get("returnNumber").asString()).isEqualTo(returnNumber);
        assertThat(notification.data().get("decideBy").asString()).isEqualTo(DECIDE_BY.toString());

        // privacy: another store, the customer and the admins get no notification
        assertThat(inboxAbout(returnId, otherVendorOwner, "VENDOR")).isEmpty();
        assertThat(inboxAbout(returnId, customer, "CUSTOMER")).isEmpty();
        assertThat(inboxAbout(returnId, UUID.randomUUID(), "SUPER_ADMIN", "OPS_ADMIN", "SUPPORT")).isEmpty();

        JsonNode store = single(signal("STORE_RETURNS_CHANGED").and(mentioning(returnId)));
        assertThat(audiences(store)).containsExactly("user:" + vendorOwner);
        assertThat(store.at("/payload/data/status").asString()).isEqualTo("REQUESTED");
        assertThat(store.at("/payload/data/orderId").asString()).isEqualTo(order.orderId().toString());
        JsonNode customerSignal = single(signal("ORDER_UPDATED").and(mentioning(returnId)));
        assertThat(audiences(customerSignal)).containsExactly("user:" + customer);
        assertThat(customerSignal.at("/payload/data/event").asString()).isEqualTo("ReturnRequested");
        assertThat(customerSignal.at("/payload/data/orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(customerSignal.at("/payload/data/status").asString()).isEqualTo("CREATED");   // the order's
        assertThat(audiences(single(signal("ADMIN_RETURNS_CHANGED").and(mentioning(returnId)))))
                .containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN", "role:FINANCE_ADMIN",
                        "role:SUPPORT", "role:DELIVERY_MANAGER");
        assertThat(recorder.await(signal("ADMIN_ORDERS_CHANGED").and(mentioning("ReturnRequested"))
                .and(mentioning(order.orderId())), 1, WAIT)).hasSize(1);
    }

    @Test
    void aRestaurantReturnGoesToTheRestaurantsReturnsPage() {
        UUID restaurantId = UUID.randomUUID();
        UUID restaurantOwner = knownRestaurant(restaurantId, "Spice Route");
        Order meal = TestEvents.restaurantOrder(customer, restaurantId);
        ConsumerRecord<String, String> requested = avro.via(TestEvents.returnRequested(meal, returnId, returnNumber,
                new BigDecimal("1234.50"), DECIDE_BY, 1));
        dispatch(requested);

        Stored notification = notificationOf(requested, "RETURN_REQUESTED");
        assertThat(notification.body()).isEqualTo("1 item · ₹1,234.50 · decide by 14:35");
        assertThat(notification.link()).isEqualTo("/restaurant/returns");
        assertThat(notification.audiences()).containsExactly("user:" + restaurantOwner);
        // the order was never seen here: everything came from the event; no order status in the signal
        JsonNode customerSignal = single(signal("ORDER_UPDATED").and(mentioning(returnId)));
        assertThat(customerSignal.at("/payload/data").has("status")).isFalse();
    }

    @Test
    void anEscalatedReturnGoesToTheModerators() throws Exception {
        ConsumerRecord<String, String> escalated = avro.via(TestEvents.returnEscalated(order, returnId, returnNumber,
                new BigDecimal("95.00")));
        dispatch(escalated);

        Stored notification = notificationOf(escalated, "RETURN_ESCALATED");
        assertThat(notificationsOf(escalated)).hasSize(1);                  // one row for the three roles
        assertThat(notification.severity()).isEqualTo("WARNING");
        assertThat(notification.title()).isEqualTo("Return " + returnNumber + " needs a decision");
        assertThat(notification.body()).isEqualTo("Order " + order.orderNumber()
                + " · ₹95.00 · the store did not decide in time.");
        assertThat(notification.link()).isEqualTo("/admin/returns");
        assertThat(notification.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN",
                "role:SUPPORT");
        assertThat(inboxAbout(returnId, UUID.randomUUID(), "FINANCE_ADMIN")).isEmpty();
        assertThat(inboxAbout(returnId, vendorOwner, "VENDOR")).isEmpty();
        // the store's list still refreshes (the return is ESCALATED now)
        assertThat(single(signal("STORE_RETURNS_CHANGED").and(mentioning(returnId))).at("/payload/data/status")
                .asString()).isEqualTo("ESCALATED");
    }

    @Test
    void approvedAndRejectedReturnsTellTheCustomer() throws Exception {
        UUID rejectedId = UUID.randomUUID();
        String rejectedNumber = TestEvents.returnNumber();
        ConsumerRecord<String, String> approved = avro.via(TestEvents.returnApproved(order, returnId, returnNumber,
                new BigDecimal("1234.50")));
        ConsumerRecord<String, String> rejected = avro.via(TestEvents.returnRejected(order, rejectedId,
                rejectedNumber, "  The seal was opened.  "));
        dispatch(approved);
        dispatch(rejected);

        Stored yes = notificationOf(approved, "RETURN_APPROVED");
        assertThat(notificationsOf(approved)).hasSize(1);
        assertThat(yes.category()).isEqualTo("ORDER");
        assertThat(yes.severity()).isEqualTo("SUCCESS");
        assertThat(yes.title()).isEqualTo("Return " + returnNumber + " approved");
        assertThat(yes.body()).isEqualTo("₹1,234.50 will be refunded.");
        assertThat(yes.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(yes.audiences()).containsExactly("user:" + customer);
        assertThat(yes.data().get("refundAmount").decimalValue()).isEqualByComparingTo("1234.50");

        Stored no = notificationOf(rejected, "RETURN_REJECTED");
        assertThat(no.severity()).isEqualTo("WARNING");
        assertThat(no.title()).isEqualTo("Return " + rejectedNumber + " rejected");
        assertThat(no.body()).isEqualTo("The seal was opened.");
        assertThat(no.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(no.audiences()).containsExactly("user:" + customer);

        assertThat(inboxAbout(returnId, vendorOwner, "VENDOR")).isEmpty();
        assertThat(inboxAbout(returnId, UUID.randomUUID(), "CUSTOMER")).isEmpty();
        assertThat(single(signal("STORE_RETURNS_CHANGED").and(mentioning(rejectedId))).at("/payload/data/status")
                .asString()).isEqualTo("REJECTED");
        assertThat(single(signal("ADMIN_RETURNS_CHANGED").and(mentioning(returnId))).at("/payload/data/status")
                .asString()).isEqualTo("APPROVED");
    }

    @Test
    void theRefundOfAReturnCarriesItsReturnId() {
        ConsumerRecord<String, String> refund = TestEvents.refundCompletedForReturn(order.orderId(),
                new BigDecimal("95.00"), returnId);
        dispatch(refund);
        Stored notification = notificationOf(refund, "REFUND_COMPLETED");
        assertThat(notification.title()).isEqualTo("Refund of ₹95.00 processed");
        assertThat(notification.data().get("returnId").asString()).isEqualTo(returnId.toString());
    }

    @Test
    void anUnknownStoreOwnerSkipsOnlyTheStoresPart() {
        Order unknownStore = TestEvents.groceryOrder(customer, UUID.randomUUID());
        ConsumerRecord<String, String> requested = TestEvents.returnRequested(unknownStore, returnId, returnNumber,
                new BigDecimal("95.00"), DECIDE_BY, 1);
        dispatch(requested);

        assertThat(processed(requested)).isTrue();
        assertThat(notificationsOf(requested)).isEmpty();
        assertThat(recorder.await(signal("ORDER_UPDATED").and(mentioning(returnId)), 1, WAIT)).hasSize(1);
        awaitMarker();
        assertThat(recorder.matching(signal("STORE_RETURNS_CHANGED").and(mentioning(returnId)))).isEmpty();
    }

    @Test
    void oldDuplicateAndBrokenEvents() {
        ConsumerRecord<String, String> old = TestEvents.old(TestEvents.returnApproved(order, returnId, returnNumber,
                new BigDecimal("95.00")));
        dispatch(old);
        assertThat(processed(old)).isTrue();
        assertThat(notificationsOf(old)).isEmpty();

        ConsumerRecord<String, String> fresh = TestEvents.returnRejected(order, returnId, returnNumber, "No");
        dispatch(fresh);
        dispatch(fresh);
        assertThat(notificationsOf(fresh)).hasSize(1);

        for (String missing : List.of("returnId", "returnNumber", "orderId", "orderNumber", "customerId")) {
            ConsumerRecord<String, String> record = TestEvents.returnRejected(order, UUID.randomUUID(),
                    TestEvents.returnNumber(), "No");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> payload = TestEvents.JSON.readValue(record.value(), java.util.Map.class);
            payload.put(missing, null);
            ConsumerRecord<String, String> broken = TestEvents.event("order.events", "ReturnRejected",
                    order.orderId(), payload);
            assertThatThrownBy(() -> dispatch(broken)).as("without %s", missing)
                    .isInstanceOf(InvalidEventException.class).hasMessageContaining(missing);
            assertThat(processed(broken)).isFalse();
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode single(Predicate<JsonNode> predicate) {
        List<JsonNode> found = recorder.await(predicate, 1, WAIT);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static List<String> audiences(JsonNode message) {
        List<String> audiences = new ArrayList<>();
        message.get("audiences").forEach(a -> audiences.add(a.asString()));
        return audiences;
    }

    private void awaitMarker() {
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        assertThat(recorder.await(kind("signal").and(mentioning(marker)), 1, WAIT)).isNotEmpty();
    }
}
