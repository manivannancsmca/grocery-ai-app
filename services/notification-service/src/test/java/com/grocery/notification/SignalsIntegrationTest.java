package com.grocery.notification;

import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract §2 and §4 signals, checked on what is published on the Redis channel: type, data and audiences. Also
 * the notification and session messages, and the public signal throttle.
 */
class SignalsIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> ALL_ADMINS = List.of("role:DELIVERY_MANAGER", "role:FINANCE_ADMIN",
            "role:OPS_ADMIN", "role:SUPER_ADMIN", "role:SUPPORT");

    @Test
    void orderEventsSignalTheCustomerAndAdminsAndTheStoreOnlyFromConfirmation() {
        UUID customer = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        Order order = TestEvents.groceryOrder(customer, vendorId);

        dispatch(TestEvents.orderCreated(order));
        dispatch(TestEvents.orderAwaitingPayment(order));
        dispatch(TestEvents.orderConfirmed(order, 1));
        dispatch(TestEvents.orderCancelled(order, "CONFIRMED", "ADMIN_CANCELLED", null));

        List<JsonNode> updates = recorder.await(signal("ORDER_UPDATED").and(mentioning(order.orderId())), 4, WAIT);
        assertThat(updates).hasSize(4);
        assertSignal(updates.get(0), "OrderCreated", "CREATED", List.of("user:" + customer));
        assertSignal(updates.get(1), "OrderAwaitingPayment", "AWAITING_PAYMENT", List.of("user:" + customer));
        assertSignal(updates.get(2), "OrderConfirmed", "CONFIRMED", List.of("user:" + customer, "user:" + owner));
        assertSignal(updates.get(3), "OrderCancelled", "CANCELLED", List.of("user:" + customer, "user:" + owner));

        List<JsonNode> admin = recorder.await(signal("ADMIN_ORDERS_CHANGED").and(mentioning(order.orderId())), 4,
                WAIT);
        assertThat(admin).hasSize(4);
        assertThat(admin).allSatisfy(message -> {
            assertThat(audiences(message)).containsExactlyInAnyOrderElementsOf(ALL_ADMINS);
            assertThat(message.at("/payload/data/orderId").asString()).isEqualTo(order.orderId().toString());
            assertThat(message.at("/payload/data").has("status")).isFalse();
        });
        assertThat(admin).extracting(m -> m.at("/payload/data/event").asString())
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderCancelled");
    }

    @Test
    void cancelledOrRejectedBeforeConfirmationNeverSignalsTheStore() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        Order cancelled = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        Order rejected = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);

        dispatch(TestEvents.orderCancelled(cancelled, "AWAITING_PAYMENT", "PAYMENT_TIMEOUT", null));
        dispatch(TestEvents.orderRejected(rejected));

        JsonNode first = recorder.await(signal("ORDER_UPDATED").and(mentioning(cancelled.orderId())), 1, WAIT)
                .getFirst();
        JsonNode second = recorder.await(signal("ORDER_UPDATED").and(mentioning(rejected.orderId())), 1, WAIT)
                .getFirst();
        assertSignal(first, "OrderCancelled", "CANCELLED", List.of("user:" + cancelled.customerId()));
        assertSignal(second, "OrderRejected", "REJECTED", List.of("user:" + rejected.customerId()));
    }

    @Test
    void paymentEventsSignalTheCustomerAndAdminsOnly() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(order);

        dispatch(TestEvents.paymentInitiated(order.orderId()));

        JsonNode update = recorder.await(signal("ORDER_UPDATED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertSignal(update, "PaymentInitiated", "CREATED", List.of("user:" + order.customerId()));
        JsonNode admin = recorder.await(signal("ADMIN_ORDERS_CHANGED").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(admin)).containsExactlyInAnyOrderElementsOf(ALL_ADMINS);
        assertThat(admin.at("/payload/data/event").asString()).isEqualTo("PaymentInitiated");
    }

    @Test
    void stockEventsSignalTheVendorOwnerAndAvailabilityIsPublic() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        UUID productId = UUID.randomUUID();

        dispatch(TestEvents.stockAdjusted(productId, vendorId));
        dispatch(TestEvents.lowStock(productId, vendorId, "Rice", 2, 5));
        dispatch(TestEvents.availability(productId, vendorId, false));

        List<JsonNode> inventory = recorder.await(signal("INVENTORY_UPDATED").and(mentioning(productId)), 3, WAIT);
        assertThat(inventory).hasSize(3).allSatisfy(message -> {
            assertThat(audiences(message)).containsExactly("user:" + owner);
            assertThat(message.at("/payload/data").size()).isEqualTo(1);
            assertThat(message.at("/payload/data/productId").asString()).isEqualTo(productId.toString());
        });
        JsonNode stock = recorder.await(signal("STOCK_CHANGED").and(mentioning(productId)), 1, WAIT).getFirst();
        assertThat(audiences(stock)).containsExactly("public");
        assertThat(stock.at("/payload/data/productId").asString()).isEqualTo(productId.toString());
        assertThat(stock.at("/payload/data/vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(stock.at("/payload/data/inStock").asBoolean(true)).isFalse();
        assertThat(stock.at("/payload/data").size()).as("only public facts").isEqualTo(3);
        assertThat(stock.at("/payload/at").asString()).endsWith("Z");
    }

    @Test
    void pricingEventsSignalTheVendorOwnerThePriceAdminsAndThePublic() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        UUID productId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Predicate<JsonNode> priceApprovals = signal("APPROVALS_CHANGED")
                .and(m -> m.at("/payload/data/kind").asString().equals("PRICE_CHANGE"));
        int approvalsBefore = recorder.matching(priceApprovals).size();

        dispatch(TestEvents.priceChanged(productId, vendorId));
        dispatch(TestEvents.priceChangeRequested(requestId, productId, vendorId, "Rice", "50.00", "60.00", "20.00"));
        dispatch(TestEvents.priceChange("PriceChangeCancelled", requestId, productId, vendorId, "Product archived"));

        JsonNode price = recorder.await(signal("PRICE_CHANGED").and(mentioning(productId)), 1, WAIT).getFirst();
        assertThat(audiences(price)).containsExactly("public");
        assertThat(price.at("/payload/data").size()).isEqualTo(2);
        assertThat(price.at("/payload/data/vendorId").asString()).isEqualTo(vendorId.toString());

        List<JsonNode> pricing = recorder.await(signal("PRICING_UPDATED").and(mentioning(productId)), 3, WAIT);
        assertThat(pricing).hasSize(3).allSatisfy(m -> assertThat(audiences(m)).containsExactly("user:" + owner));
        assertThat(pricing.get(0).at("/payload/data").has("requestId")).isFalse();
        assertThat(pricing.get(1).at("/payload/data/requestId").asString()).isEqualTo(requestId.toString());

        List<JsonNode> approvals = recorder.await(priceApprovals, approvalsBefore + 2, WAIT);
        assertThat(approvals.subList(approvalsBefore, approvals.size())).as("requested + cancelled; not PriceChanged")
                .hasSize(2)
                .allSatisfy(m -> assertThat(audiences(m))
                        .containsExactlyInAnyOrder("role:FINANCE_ADMIN", "role:OPS_ADMIN", "role:SUPER_ADMIN"));
    }

    @Test
    void lifecycleEventsSignalTheOwnerAndTheAdminsOfTheKind() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID partnerId = UUID.randomUUID();
        int approvalsBefore = recorder.matching(signal("APPROVALS_CHANGED")).size();

        dispatch(TestEvents.vendor("VendorRegistered", vendorId, owner, "Fresh Farms", null));
        JsonNode draft = recorder.await(signal("ACCOUNT_UPDATED").and(audience("user:" + owner)), 1, WAIT).getFirst();
        assertThat(draft.at("/payload/data/kind").asString()).isEqualTo("VENDOR");
        assertThat(draft.at("/payload/data/status").asString()).isEqualTo("DRAFT");

        dispatch(TestEvents.vendor("VendorSubmitted", vendorId, owner, "Fresh Farms", null));
        dispatch(TestEvents.partner("DeliveryPartnerApproved", partnerId, "Ravi Kumar", null));

        List<JsonNode> mine = recorder.await(signal("ACCOUNT_UPDATED").and(audience("user:" + owner)), 2, WAIT);
        assertThat(mine).extracting(m -> m.at("/payload/data/status").asString())
                .containsExactly("DRAFT", "PENDING_APPROVAL");
        JsonNode partner = recorder.await(signal("ACCOUNT_UPDATED").and(audience("user:" + partnerId)), 1, WAIT)
                .getFirst();
        assertThat(partner.at("/payload/data/kind").asString()).isEqualTo("DELIVERY_PARTNER");
        assertThat(partner.at("/payload/data/status").asString()).isEqualTo("APPROVED");
        assertThat(audiences(partner)).containsExactly("user:" + partnerId);

        List<JsonNode> approvals = recorder.await(signal("APPROVALS_CHANGED"), approvalsBefore + 2, WAIT);
        List<JsonNode> newApprovals = approvals.subList(approvalsBefore, approvals.size());
        assertThat(newApprovals).extracting(m -> m.at("/payload/data/kind").asString())
                .contains("VENDOR", "DELIVERY_PARTNER");
        assertThat(newApprovals).filteredOn(m -> m.at("/payload/data/kind").asString().equals("DELIVERY_PARTNER"))
                .allSatisfy(m -> assertThat(audiences(m)).containsExactlyInAnyOrder("role:DELIVERY_MANAGER",
                        "role:OPS_ADMIN", "role:SUPER_ADMIN"));
        assertThat(newApprovals).filteredOn(m -> m.at("/payload/data/kind").asString().equals("VENDOR"))
                .allSatisfy(m -> assertThat(audiences(m)).containsExactlyInAnyOrder("role:OPS_ADMIN",
                        "role:SUPER_ADMIN"));
    }

    @Test
    void aSuspensionPublishesTheNotificationTheSignalsAndThenRevokesTheOwnersStreams() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");

        dispatch(TestEvents.vendor("VendorSuspended", vendorId, owner, "Fresh Farms", "Expired licence"));

        List<JsonNode> toOwner = recorder.await(audience("user:" + owner), 3, WAIT);
        assertThat(toOwner).extracting(m -> m.get("kind").asString())
                .containsExactly("notification", "signal", "session");
        assertThat(toOwner.get(0).at("/payload/type").asString()).isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(toOwner.get(0).at("/payload/read").asBoolean(true)).isFalse();
        assertThat(toOwner.get(1).at("/payload/type").asString()).isEqualTo("ACCOUNT_UPDATED");
        assertThat(toOwner.get(2).at("/payload/reason").asString()).isEqualTo("REVOKED");
        assertThat(audiences(toOwner.get(2))).containsExactly("user:" + owner);
    }

    @Test
    void userSuspendedRevokesTheUsersStreamsWithoutANotification() {
        UUID userId = UUID.randomUUID();

        dispatch(TestEvents.user("UserSuspended", userId));

        List<JsonNode> toUser = recorder.await(audience("user:" + userId), 2, WAIT);
        assertThat(toUser).extracting(m -> m.get("kind").asString()).containsExactly("signal", "session");
        assertThat(toUser.get(0).at("/payload/data/kind").asString()).isEqualTo("USER");
        assertThat(toUser.get(0).at("/payload/data/status").asString()).isEqualTo("SUSPENDED");
        assertThat(toUser.get(1).at("/payload/reason").asString()).isEqualTo("REVOKED");
    }

    @Test
    void aNotificationMessageCarriesTheInboxViewAndItsAudiences() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(order);
        ConsumerRecord<String, String> rejected = TestEvents.orderRejected(order);

        dispatch(rejected);

        Stored stored = notificationOf(rejected, "ORDER_REJECTED");
        JsonNode message = recorder.await(kind("notification").and(mentioning(order.orderId())), 1, WAIT)
                .getFirst();
        assertThat(audiences(message)).containsExactly("user:" + order.customerId());
        JsonNode view = message.get("payload");
        assertThat(view.get("id").asString()).isEqualTo(stored.id().toString());
        assertThat(view.get("type").asString()).isEqualTo("ORDER_REJECTED");
        assertThat(view.get("category").asString()).isEqualTo("ORDER");
        assertThat(view.get("severity").asString()).isEqualTo("ERROR");
        assertThat(view.get("title").asString()).isEqualTo(stored.title());
        assertThat(view.get("body").asString()).isEqualTo(stored.body());
        assertThat(view.get("link").asString()).isEqualTo(stored.link());
        assertThat(view.get("data").get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(view.get("read").asBoolean(true)).isFalse();
        assertThat(view.get("createdAt").asString()).endsWith("Z");
    }

    @Test
    void restaurantListingUpdatesArePublic() {
        UUID restaurantId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dispatch(TestEvents.restaurantListing(restaurantId, owner, "Spice Route", "APPROVED", false));

        JsonNode message = recorder.await(signal("RESTAURANT_UPDATED").and(mentioning(restaurantId)), 1, WAIT)
                .getFirst();
        assertThat(audiences(message)).containsExactly("public");
        assertThat(message.at("/payload/data/acceptingOrders").asBoolean(true)).isFalse();
        assertThat(message.at("/payload/data/status").asString()).isEqualTo("APPROVED");
        assertThat(message.at("/payload/data").size()).isEqualTo(3);
        assertThat(storeOwnerRow("RESTAURANT", restaurantId)).get()
                .satisfies(row -> assertThat(row.get("owner")).isEqualTo(owner));
    }

    @Test
    void publicSignalsAreThrottledPerEntityAndTheLatestStateIsSentAtTheEndOfTheWindow() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        UUID product = UUID.randomUUID();
        UUID otherProduct = UUID.randomUUID();
        Predicate<JsonNode> stockOfProduct = signal("STOCK_CHANGED").and(mentioning(product));

        dispatch(TestEvents.availability(product, vendorId, false));
        dispatch(TestEvents.availability(product, vendorId, true));
        dispatch(TestEvents.availability(product, vendorId, false));
        dispatch(TestEvents.availability(otherProduct, vendorId, true));

        assertThat(recorder.await(stockOfProduct, 1, WAIT)).hasSize(1);
        assertThat(recorder.await(signal("STOCK_CHANGED").and(mentioning(otherProduct)), 1, WAIT))
                .as("another product is not throttled").hasSize(1);
        RealtimeRecorder.sleep(1_000);
        List<JsonNode> withinWindow = recorder.matching(stockOfProduct);
        assertThat(withinWindow).as("at most one per product per 2 s").hasSize(1);
        assertThat(withinWindow.getFirst().at("/payload/data/inStock").asBoolean(true)).isFalse();

        List<JsonNode> all = recorder.await(stockOfProduct, 2, Duration.ofSeconds(5));
        assertThat(all).as("one trailing signal with the latest state").hasSize(2);
        assertThat(all.get(1).at("/payload/data/inStock").asBoolean(true)).isFalse();
        RealtimeRecorder.sleep(2_500);
        assertThat(recorder.matching(stockOfProduct)).hasSize(2);

        // the vendor's own (non-public) signals are not throttled
        assertThat(recorder.await(signal("INVENTORY_UPDATED").and(mentioning(product)), 3, WAIT)).hasSize(3)
                .allSatisfy(m -> assertThat(audiences(m)).containsExactly("user:" + owner));
    }

    @Test
    void oldEventsPublishNothing() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        UUID productId = UUID.randomUUID();
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);

        dispatch(TestEvents.old(TestEvents.orderCreated(order)));
        dispatch(TestEvents.old(TestEvents.orderConfirmed(order, 1)));
        dispatch(TestEvents.old(TestEvents.availability(productId, vendorId, true)));
        dispatch(TestEvents.old(TestEvents.vendor("VendorSuspended", vendorId, owner, "Fresh Farms", "x")));
        // a marker after them: once it arrived, anything published before it has arrived too
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        recorder.await(mentioning(marker), 1, WAIT);

        assertThat(recorder.matching(mentioning(order.orderId()))).isEmpty();
        assertThat(recorder.matching(mentioning(productId))).isEmpty();
        assertThat(recorder.matching(audience("user:" + owner))).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static Predicate<JsonNode> audience(String audience) {
        return message -> audiences(message).contains(audience);
    }

    private static List<String> audiences(JsonNode message) {
        List<String> audiences = new ArrayList<>();
        message.get("audiences").forEach(a -> audiences.add(a.asString()));
        return audiences;
    }

    private static void assertSignal(JsonNode message, String event, String status, List<String> audiences) {
        assertThat(message.at("/payload/type").asString()).isEqualTo("ORDER_UPDATED");
        assertThat(message.at("/payload/data/event").asString()).isEqualTo(event);
        assertThat(message.at("/payload/data/status").asString()).isEqualTo(status);
        assertThat(audiences(message)).containsExactlyInAnyOrderElementsOf(audiences);
    }
}
