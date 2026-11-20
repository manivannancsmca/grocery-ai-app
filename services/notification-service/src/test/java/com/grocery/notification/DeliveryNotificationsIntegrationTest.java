package com.grocery.notification;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.repository.UuidBytes;
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
 * Phase 9 contract §7, every row of the notification table: delivery offers and their signals for partners, the
 * assignment for the customer / partner / store, reassignment, pickup readiness, the delivery's outcome for customers
 * and admins, no partner found, a cancelled delivery, ADMIN_DELIVERIES_CHANGED for every delivery event; the partner
 * in the order projection; and the Phase 7 / 8 rules (event age, idempotency, parking, privacy) for the new events.
 */
class DeliveryNotificationsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> DELIVERY_ADMINS = List.of("role:DELIVERY_MANAGER", "role:OPS_ADMIN",
            "role:SUPER_ADMIN");
    private static final List<String> ALL_ADMINS = List.of("role:DELIVERY_MANAGER", "role:FINANCE_ADMIN",
            "role:OPS_ADMIN", "role:SUPER_ADMIN", "role:SUPPORT");

    /** A grocery order known to the projection (status CREATED), its vendor's owner known too. */
    private record Setup(Order order, UUID storeOwner) {
    }

    private Setup groceryOrder() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(order);
        return new Setup(order, owner);
    }

    // ---------------------------------------------------------------- offers

    @Test
    void anOfferNotifiesOnlyThePartnerWithWhatTheOfferViewShows() throws Exception {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID partner = UUID.randomUUID();
        UUID otherPartner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();

        ConsumerRecord<String, String> offered = TestEvents.offerCreated(order, deliveryId, offerId, partner,
                "Green Basket", 1.234, Instant.now().plusSeconds(30));
        dispatch(offered);

        Stored offer = notificationOf(offered, "DELIVERY_OFFER");
        assertThat(notificationsOf(offered)).hasSize(1);
        assertThat(offer.category()).isEqualTo("ORDER");
        assertThat(offer.severity()).isEqualTo("INFO");
        assertThat(offer.title()).isEqualTo("New delivery from Green Basket");
        assertThat(offer.body()).isEqualTo("1.2 km away · respond within 30 s");
        assertThat(offer.link()).isEqualTo("/delivery");
        assertThat(offer.audiences()).containsExactly("user:" + partner);
        assertThat(fieldNames(offer.data())).containsExactlyInAnyOrder("orderId", "orderNumber", "deliveryId", "offerId");
        assertThat(offer.data().get("offerId").asString()).isEqualTo(offerId.toString());
        assertThat(offer.data().get("deliveryId").asString()).isEqualTo(deliveryId.toString());
        // privacy: nothing about the customer (who they are, where they live) reaches the offered partner
        assertThat(offer.title() + offer.body() + offer.data()).doesNotContain(order.customerId().toString());

        JsonNode changed = recorder.await(signal("DELIVERY_OFFERS_CHANGED").and(mentioning(offerId)), 1, WAIT)
                .getFirst();
        assertThat(audiences(changed)).containsExactly("user:" + partner);
        assertThat(fieldNames(changed.at("/payload/data"))).containsExactly("deliveryId", "offerId");
        assertAdminSignal(deliveryId, order.orderId());

        assertThat(inboxAbout(offerId, partner, "DELIVERY_PARTNER")).extracting(n -> n.get("type").asString())
                .containsExactly("DELIVERY_OFFER");
        assertThat(inboxAbout(offerId, otherPartner, "DELIVERY_PARTNER")).isEmpty();
        assertThat(inboxAbout(offerId, order.customerId(), "CUSTOMER")).isEmpty();
        assertThat(inboxAbout(offerId, setup.storeOwner(), "VENDOR")).isEmpty();
        assertThat(inboxAbout(offerId, UUID.randomUUID(), "SUPER_ADMIN", "OPS_ADMIN", "DELIVERY_MANAGER")).isEmpty();

        // a redelivery changes nothing
        dispatch(offered);
        assertThat(notificationsOf(offered)).hasSize(1);
    }

    @Test
    void anOfferWithoutAStoreNameUsesTheOrdersAndAnExpiredOneOnlySignals() {
        Setup setup = groceryOrder();
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        ConsumerRecord<String, String> unnamed = TestEvents.offerCreated(setup.order(), deliveryId,
                UUID.randomUUID(), partner, null, 6.0, Instant.now().plusSeconds(12));
        dispatch(unnamed);
        Stored offer = notificationOf(unnamed, "DELIVERY_OFFER");
        assertThat(offer.title()).isEqualTo("New delivery from Green Basket");          // OrderCreated's store name
        assertThat(offer.body()).isEqualTo("6.0 km away · respond within 12 s");

        UUID expiredOffer = UUID.randomUUID();
        ConsumerRecord<String, String> expired = TestEvents.offerCreated(setup.order(), deliveryId, expiredOffer,
                partner, "Green Basket", 2.0, Instant.now().minusSeconds(5));
        dispatch(expired);
        assertThat(notificationsOf(expired)).isEmpty();
        assertThat(processed(expired)).isTrue();
        assertThat(recorder.await(signal("DELIVERY_OFFERS_CHANGED").and(mentioning(expiredOffer)), 1, WAIT))
                .singleElement().satisfies(m -> assertThat(audiences(m)).containsExactly("user:" + partner));
    }

    @Test
    void aClosedOfferOnlyRefreshesThePartnersOffers() {
        Setup setup = groceryOrder();
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();

        for (String outcome : List.of("EXPIRED", "REJECTED", "WITHDRAWN")) {
            ConsumerRecord<String, String> closed = TestEvents.offerClosed(setup.order(), deliveryId, offerId,
                    partner, outcome);
            dispatch(closed);
            assertThat(notificationsOf(closed)).as(outcome).isEmpty();
        }
        assertThat(recorder.await(signal("DELIVERY_OFFERS_CHANGED").and(mentioning(offerId)), 1, WAIT))
                .allSatisfy(m -> assertThat(audiences(m)).containsExactly("user:" + partner));
        assertThat(recorder.await(signal("ADMIN_DELIVERIES_CHANGED").and(mentioning(deliveryId)), 3, WAIT))
                .hasSize(3);
    }

    // ---------------------------------------------------------------- assignment

    @Test
    void anAssignmentTellsTheCustomerThePartnerAndTheStoreAndIsProjected() throws Exception {
        Setup setup = groceryOrder();
        Order order = setup.order();
        dispatch(TestEvents.old(TestEvents.orderAccepted(order, 15)));
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        ConsumerRecord<String, String> assigned = TestEvents.partnerAssigned(order, deliveryId, partner,
                "Ravi Kumar");
        dispatch(assigned);

        Stored told = notificationOf(assigned, "PARTNER_ASSIGNED");
        assertThat(notificationsOf(assigned)).hasSize(1);
        assertThat(told.severity()).isEqualTo("INFO");
        assertThat(told.title()).isEqualTo("Ravi will deliver order " + order.orderNumber());   // first name only
        assertThat(told.body()).isEmpty();
        assertThat(told.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(told.audiences()).containsExactly("user:" + order.customerId());
        assertThat(fieldNames(told.data())).containsExactlyInAnyOrder("orderId", "orderNumber", "deliveryId");
        assertThat(partnerOf(order.orderId())).isEqualTo(partner);

        JsonNode updated = recorder.await(signal("DELIVERY_UPDATED").and(mentioning(deliveryId)), 1, WAIT)
                .getFirst();
        assertThat(audiences(updated)).containsExactly("user:" + partner);
        assertThat(fieldNames(updated.at("/payload/data"))).containsExactly("deliveryId", "orderId");
        JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(board)).containsExactly("user:" + setup.storeOwner());
        assertThat(board.at("/payload/data/status").asString()).isEqualTo("ACCEPTED");
        assertAdminSignal(deliveryId, order.orderId());
        awaitMarker();
        assertThat(recorder.matching(mentioning(deliveryId)))
                .as("the partner's signal and the admins' only (the customer's notification carries the id in data)")
                .filteredOn(kind("signal"))
                .allSatisfy(m -> assertThat(audiences(m)).doesNotContain("user:" + order.customerId()));

        assertThat(inboxAbout(order.orderId(), partner, "DELIVERY_PARTNER")).isEmpty();
        assertThat(inboxAbout(order.orderId(), setup.storeOwner(), "VENDOR")).isEmpty();
    }

    @Test
    void anUnnamedPartnerAndAnUnknownOrderIsParkedUntilTheOrderIsKnown() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Green Basket");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        ConsumerRecord<String, String> assigned = TestEvents.partnerAssigned(order, deliveryId, partner, null);
        dispatch(assigned);
        assertThat(processed(assigned)).isTrue();
        assertThat(notificationsOf(assigned)).isEmpty();
        assertThat(parked(order.orderId())).containsExactly(TestEvents.eventId(assigned));

        dispatch(TestEvents.orderCreated(order));                  // the order becomes known: the event is applied
        assertThat(parked(order.orderId())).isEmpty();
        Stored told = notificationOf(assigned, "PARTNER_ASSIGNED");
        assertThat(told.title()).isEqualTo("A delivery partner will deliver order " + order.orderNumber());
        assertThat(told.audiences()).containsExactly("user:" + order.customerId());
        assertThat(partnerOf(order.orderId())).isEqualTo(partner);
    }

    @Test
    void anUnassignmentWarnsThePartnerAndClearsOnlyTheRecordedPartner() {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID ravi = UUID.randomUUID();
        UUID meena = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        dispatch(TestEvents.partnerAssigned(order, deliveryId, ravi, "Ravi"));

        ConsumerRecord<String, String> stale = TestEvents.partnerUnassigned(order, deliveryId, meena);
        dispatch(stale);
        assertThat(partnerOf(order.orderId())).isEqualTo(ravi);
        assertThat(notificationOf(stale, "DELIVERY_REASSIGNED").audiences()).containsExactly("user:" + meena);

        ConsumerRecord<String, String> unassigned = TestEvents.partnerUnassigned(order, deliveryId, ravi);
        dispatch(unassigned);
        Stored warned = notificationOf(unassigned, "DELIVERY_REASSIGNED");
        assertThat(notificationsOf(unassigned)).hasSize(1);
        assertThat(warned.severity()).isEqualTo("WARNING");
        assertThat(warned.title()).isEqualTo("Order " + order.orderNumber() + " was reassigned");
        assertThat(warned.body()).isEmpty();
        assertThat(warned.link()).isEqualTo("/delivery");
        assertThat(warned.audiences()).containsExactly("user:" + ravi);
        assertThat(fieldNames(warned.data())).containsExactlyInAnyOrder("orderId", "orderNumber", "deliveryId");
        assertThat(partnerOf(order.orderId())).isNull();
        assertAdminSignal(deliveryId, order.orderId());
    }

    // ---------------------------------------------------------------- pickup readiness

    @Test
    void readyForPickupTellsTheAssignedPartnerOnly() {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        dispatch(TestEvents.partnerAssigned(order, deliveryId, partner, "Ravi Kumar"));

        ConsumerRecord<String, String> ready = TestEvents.orderReadyForPickup(order);
        dispatch(ready);

        assertThat(notificationsOf(ready)).extracting(Stored::type)
                .containsExactlyInAnyOrder("ORDER_READY", "PICKUP_READY");
        Stored pickup = notificationOf(ready, "PICKUP_READY");
        assertThat(pickup.severity()).isEqualTo("INFO");
        assertThat(pickup.title()).isEqualTo("Order " + order.orderNumber() + " is ready at Green Basket");
        assertThat(pickup.body()).isEmpty();
        assertThat(pickup.link()).isEqualTo("/delivery");
        assertThat(pickup.audiences()).containsExactly("user:" + partner);
        assertThat(pickup.data().get("deliveryId").asString()).isEqualTo(deliveryId.toString());
        assertThat(notificationOf(ready, "ORDER_READY").audiences()).containsExactly("user:" + order.customerId());

        // nobody assigned (never, or taken away): nobody to tell
        Setup alone = groceryOrder();
        ConsumerRecord<String, String> unassignedReady = TestEvents.orderReadyForPickup(alone.order());
        dispatch(unassignedReady);
        assertThat(notificationsOf(unassignedReady)).extracting(Stored::type).containsExactly("ORDER_READY");
        Setup released = groceryOrder();
        dispatch(TestEvents.partnerAssigned(released.order(), deliveryId, partner, "Ravi"));
        dispatch(TestEvents.partnerUnassigned(released.order(), deliveryId, partner));
        ConsumerRecord<String, String> releasedReady = TestEvents.orderReadyForPickup(released.order());
        dispatch(releasedReady);
        assertThat(notificationsOf(releasedReady)).extracting(Stored::type).containsExactly("ORDER_READY");
    }

    // ---------------------------------------------------------------- the order's delivery events

    @Test
    void outForDeliveryTellsTheCustomerAndTakesTheOrderOffTheBoard() {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID partner = UUID.randomUUID();

        ConsumerRecord<String, String> onTheWay = TestEvents.orderOutForDelivery(order, partner);
        dispatch(onTheWay);

        Stored told = notificationOf(onTheWay, "OUT_FOR_DELIVERY");
        assertThat(notificationsOf(onTheWay)).hasSize(1);
        assertThat(told.severity()).isEqualTo("INFO");
        assertThat(told.title()).isEqualTo("Order " + order.orderNumber() + " is on the way");
        assertThat(told.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(told.audiences()).containsExactly("user:" + order.customerId());
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("OUT_FOR_DELIVERY"));

        JsonNode board = recorder.await(signal("STORE_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(board)).containsExactly("user:" + setup.storeOwner());
        assertThat(board.at("/payload/data/status").asString()).isEqualTo("PICKED_UP");
        assertOrderSignals(order, "OrderOutForDelivery", "OUT_FOR_DELIVERY");
    }

    @Test
    void aDeliveredOrderThanksTheCustomer() {
        Setup setup = groceryOrder();
        Order order = setup.order();

        ConsumerRecord<String, String> delivered = TestEvents.orderDelivered(order, UUID.randomUUID());
        dispatch(delivered);

        Stored told = notificationOf(delivered, "ORDER_DELIVERED");
        assertThat(notificationsOf(delivered)).hasSize(1);
        assertThat(told.severity()).isEqualTo("SUCCESS");
        assertThat(told.title()).isEqualTo("Order " + order.orderNumber() + " delivered");
        assertThat(told.body()).isEqualTo("Enjoy!");
        assertThat(told.link()).isEqualTo("/orders/" + order.orderId());
        assertThat(told.audiences()).containsExactly("user:" + order.customerId());
        assertOrderSignals(order, "OrderDelivered", "DELIVERED");
    }

    @Test
    void aFailedDeliveryTellsTheCustomerAndTheAdminsWhoDecideOnARefund() throws Exception {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID deliveryId = UUID.randomUUID();
        dispatch(TestEvents.old(TestEvents.partnerAssigned(order, deliveryId, UUID.randomUUID(), "Ravi")));

        ConsumerRecord<String, String> failed = TestEvents.orderDeliveryFailed(order, UUID.randomUUID(),
                "CUSTOMER_UNREACHABLE", "Called three times.");
        dispatch(failed);

        assertThat(notificationsOf(failed)).hasSize(2);
        Stored customer = notificationOf(failed, "DELIVERY_FAILED");
        assertThat(customer.severity()).isEqualTo("ERROR");
        assertThat(customer.title()).isEqualTo("We could not deliver order " + order.orderNumber());
        assertThat(customer.body()).isEqualTo("Our support team will contact you about a refund.");
        assertThat(customer.audiences()).containsExactly("user:" + order.customerId());
        Stored admins = notificationOf(failed, "DELIVERY_FAILED_ADMIN");
        assertThat(admins.severity()).isEqualTo("ERROR");
        assertThat(admins.title()).isEqualTo("Delivery failed for order " + order.orderNumber());
        assertThat(admins.body())
                .isEqualTo("Customer unreachable: Called three times. Decide on a refund in the payment monitor.");
        assertThat(admins.link()).isEqualTo("/admin/orders");
        assertThat(admins.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN",
                "role:SUPPORT", "role:DELIVERY_MANAGER");
        assertThat(admins.data().get("deliveryId").asString()).isEqualTo(deliveryId.toString());
        assertOrderSignals(order, "OrderDeliveryFailed", "DELIVERY_FAILED");

        // one row: an admin holding several of the roles sees it once; FINANCE_ADMIN is not among them
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "SUPPORT", "DELIVERY_MANAGER"))
                .extracting(n -> n.get("type").asString()).containsExactly("DELIVERY_FAILED_ADMIN");
        assertThat(inboxAbout(order.orderId(), UUID.randomUUID(), "FINANCE_ADMIN")).isEmpty();

        // a reason without a note
        Setup other = groceryOrder();
        ConsumerRecord<String, String> refused = TestEvents.orderDeliveryFailed(other.order(), UUID.randomUUID(),
                "CUSTOMER_REFUSED", null);
        dispatch(refused);
        assertThat(notificationOf(refused, "DELIVERY_FAILED_ADMIN").body())
                .isEqualTo("Customer refused the order. Decide on a refund in the payment monitor.");
    }

    // ---------------------------------------------------------------- no partner, cancelled, the rest

    @Test
    void noPartnerFoundAlertsTheDeliveryManagers() throws Exception {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID deliveryId = UUID.randomUUID();

        ConsumerRecord<String, String> failed = TestEvents.assignmentFailed(order, deliveryId);
        dispatch(failed);

        Stored alert = notificationOf(failed, "NO_DELIVERY_PARTNER");
        assertThat(notificationsOf(failed)).hasSize(1);
        assertThat(alert.severity()).isEqualTo("WARNING");
        assertThat(alert.title()).isEqualTo("No delivery partner for order " + order.orderNumber());
        assertThat(alert.body()).isEqualTo("Assign one by hand.");
        assertThat(alert.link()).isEqualTo("/admin/deliveries");
        assertThat(alert.audiences()).containsExactlyInAnyOrderElementsOf(DELIVERY_ADMINS);
        assertThat(fieldNames(alert.data())).containsExactlyInAnyOrder("orderId", "orderNumber", "deliveryId");
        assertAdminSignal(deliveryId, order.orderId());
        assertThat(inboxAbout(deliveryId, UUID.randomUUID(), "SUPPORT")).isEmpty();
        assertThat(inboxAbout(deliveryId, UUID.randomUUID(), "DELIVERY_MANAGER")).hasSize(1);
    }

    @Test
    void aCancelledDeliveryReleasesItsPartner() {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        dispatch(TestEvents.partnerAssigned(order, deliveryId, partner, "Ravi"));

        ConsumerRecord<String, String> cancelled = TestEvents.deliveryCancelled(order, deliveryId, partner);
        dispatch(cancelled);

        Stored warned = notificationOf(cancelled, "DELIVERY_CANCELLED");
        assertThat(notificationsOf(cancelled)).hasSize(1);
        assertThat(warned.severity()).isEqualTo("WARNING");
        assertThat(warned.title()).isEqualTo("Order " + order.orderNumber() + " was cancelled");
        assertThat(warned.body()).isEqualTo("Do not pick it up.");
        assertThat(warned.link()).isEqualTo("/delivery");
        assertThat(warned.audiences()).containsExactly("user:" + partner);
        assertThat(partnerOf(order.orderId())).isNull();

        // without a partner: the admins' signal only
        Setup searching = groceryOrder();
        UUID otherDelivery = UUID.randomUUID();
        ConsumerRecord<String, String> nobody = TestEvents.deliveryCancelled(searching.order(), otherDelivery, null);
        dispatch(nobody);
        assertThat(notificationsOf(nobody)).isEmpty();
        assertAdminSignal(otherDelivery, searching.order().orderId());
    }

    @Test
    void pickupCompletionAndFailureOfTheDeliveryOnlySignalTheAdmins() {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        List<ConsumerRecord<String, String>> events = List.of(
                TestEvents.deliveryStep("DeliveryPickedUp", order, deliveryId, partner),
                TestEvents.deliveryStep("DeliveryCompleted", order, deliveryId, partner),
                TestEvents.deliveryFailed(order, deliveryId, partner));
        for (ConsumerRecord<String, String> event : events) {
            dispatch(event);
            assertThat(notificationsOf(event)).isEmpty();
            assertThat(processed(event)).isTrue();
        }
        List<JsonNode> signals = recorder.await(signal("ADMIN_DELIVERIES_CHANGED").and(mentioning(deliveryId)), 3,
                WAIT);
        assertThat(signals).hasSize(3).allSatisfy(m -> {
            assertThat(audiences(m)).containsExactlyInAnyOrderElementsOf(DELIVERY_ADMINS);
            assertThat(fieldNames(m.at("/payload/data"))).containsExactly("deliveryId", "orderId");
        });
        awaitMarker();
        assertThat(recorder.matching(mentioning(deliveryId))).hasSize(3);
    }

    // ---------------------------------------------------------------- Phase 7 rules for the new events

    @Test
    void oldDeliveryEventsOnlyUpdateTheProjection() {
        Setup setup = groceryOrder();
        Order order = setup.order();
        UUID partner = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        List<ConsumerRecord<String, String>> old = List.of(
                TestEvents.old(TestEvents.offerCreated(order, deliveryId, UUID.randomUUID(), partner, "Green Basket",
                        1.0, Instant.now().plusSeconds(30))),
                TestEvents.old(TestEvents.partnerAssigned(order, deliveryId, partner, "Ravi")),
                TestEvents.old(TestEvents.assignmentFailed(order, deliveryId)),
                TestEvents.old(TestEvents.orderOutForDelivery(order, partner)),
                TestEvents.old(TestEvents.orderDeliveryFailed(order, partner, "OTHER", "Gate locked")));
        for (ConsumerRecord<String, String> event : old) {
            dispatch(event);
            assertThat(notificationsOf(event)).isEmpty();
            assertThat(processed(event)).isTrue();
        }
        assertThat(partnerOf(order.orderId())).isEqualTo(partner);
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("DELIVERY_FAILED"));
        awaitMarker();
        assertThat(recorder.matching(mentioning(deliveryId))).isEmpty();
        assertThat(recorder.matching(mentioning(order.orderId()))).isEmpty();
    }

    @Test
    void thePartnerLifecycleStaysAnAccountEvent() {
        UUID partner = UUID.randomUUID();
        ConsumerRecord<String, String> approved = TestEvents.partner("DeliveryPartnerApproved", partner, "Ravi Kumar",
                null);
        dispatch(approved);
        assertThat(notificationOf(approved, "ACCOUNT_APPROVED").audiences()).containsExactly("user:" + partner);
        assertThat(recorder.matching(signal("ADMIN_DELIVERIES_CHANGED").and(mentioning(partner)))).isEmpty();
    }

    @Test
    void brokenDeliveryEventsAreInvalid() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> dispatch(TestEvents.event("delivery.events", "DeliveryOfferCreated",
                order.orderId(), TestEvents.map("orderId", order.orderId(), "orderNumber", order.orderNumber(),
                        "offerId", UUID.randomUUID(), "partnerId", UUID.randomUUID(), "distanceKm", 1.0))))
                .isInstanceOf(InvalidEventException.class);                                     // no deliveryId
        assertThatThrownBy(() -> dispatch(TestEvents.delivery("DeliveryOfferCreated", order, UUID.randomUUID(),
                TestEvents.map("offerId", UUID.randomUUID(), "distanceKm", 1.0,
                        "expiresAt", Instant.now().plusSeconds(30).toString()))))
                .isInstanceOf(InvalidEventException.class);                                     // no partnerId
        assertThatThrownBy(() -> dispatch(TestEvents.delivery("DeliveryPartnerAssigned", order, UUID.randomUUID(),
                TestEvents.map("partnerName", "Ravi"))))
                .isInstanceOf(InvalidEventException.class);                                     // no partnerId
        assertThatThrownBy(() -> dispatch(TestEvents.event("delivery.events", "DeliveryCancelled", order.orderId(),
                TestEvents.map("deliveryId", UUID.randomUUID(), "orderId", order.orderId()))))
                .isInstanceOf(InvalidEventException.class);                                     // no orderNumber
    }

    // ---------------------------------------------------------------- helpers

    private void assertAdminSignal(UUID deliveryId, UUID orderId) {
        JsonNode admin = recorder.await(signal("ADMIN_DELIVERIES_CHANGED").and(mentioning(deliveryId)), 1, WAIT)
                .getFirst();
        assertThat(audiences(admin)).containsExactlyInAnyOrderElementsOf(DELIVERY_ADMINS);
        assertThat(fieldNames(admin.at("/payload/data"))).containsExactly("deliveryId", "orderId");
        assertThat(admin.at("/payload/data/orderId").asString()).isEqualTo(orderId.toString());
    }

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

    private UUID partnerOf(UUID orderId) {
        List<byte[]> partner = jdbc.sql("SELECT partner_id FROM order_ref WHERE order_id = :id")
                .param("id", UuidBytes.bytes(orderId))
                .query((rs, rowNum) -> rs.getBytes("partner_id")).list();
        return partner.isEmpty() ? null : UuidBytes.uuid(partner.getFirst());
    }

    private List<String> parked(UUID orderId) {
        return jdbc.sql("SELECT event_id FROM pending_event WHERE order_id = :orderId ORDER BY seq")
                .param("orderId", UuidBytes.bytes(orderId))
                .query(String.class).list();
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
