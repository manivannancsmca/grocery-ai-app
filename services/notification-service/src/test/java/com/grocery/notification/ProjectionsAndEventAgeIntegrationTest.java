package com.grocery.notification;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.repository.UuidBytes;
import com.grocery.notification.service.NotificationRetention;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract §5: projections, event age, idempotency, broken events and retention. */
class ProjectionsAndEventAgeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    NotificationRetention retention;

    @Test
    void vendorAndRestaurantEventsKeepTheStoreOwnerProjection() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dispatch(TestEvents.vendor("VendorRegistered", vendorId, owner, "Fresh Farms", null));
        assertThat(storeOwnerRow("VENDOR", vendorId)).get().satisfies(row -> {
            assertThat(row.get("owner")).isEqualTo(owner);
            assertThat(row.get("name")).isEqualTo("Fresh Farms");
        });

        dispatch(TestEvents.vendor("VendorSubmitted", vendorId, owner, null, null));
        assertThat(storeOwnerRow("VENDOR", vendorId)).get()
                .satisfies(row -> assertThat(row.get("name")).as("a missing name keeps the known one")
                        .isEqualTo("Fresh Farms"));

        dispatch(TestEvents.old(TestEvents.vendor("VendorApproved", vendorId, owner, "Fresh Farms Pvt Ltd", null)));
        assertThat(storeOwnerRow("VENDOR", vendorId)).get()
                .satisfies(row -> assertThat(row.get("name")).as("old events still update projections")
                        .isEqualTo("Fresh Farms Pvt Ltd"));

        UUID restaurantId = UUID.randomUUID();
        UUID restaurantOwner = UUID.randomUUID();
        dispatch(TestEvents.restaurant("RestaurantRegistered", restaurantId, restaurantOwner, "Spice Route", null));
        dispatch(TestEvents.restaurantListing(restaurantId, restaurantOwner, "Spice Route Express", "APPROVED", true));
        assertThat(storeOwnerRow("RESTAURANT", restaurantId)).get().satisfies(row -> {
            assertThat(row.get("owner")).isEqualTo(restaurantOwner);
            assertThat(row.get("name")).isEqualTo("Spice Route Express");
        });
        assertThat(storeOwnerRow("VENDOR", restaurantId)).isEmpty();
    }

    @Test
    void orderEventsKeepTheOrderProjection() {
        UUID vendorId = UUID.randomUUID();
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        ConsumerRecord<String, String> created = TestEvents.orderCreated(order);

        dispatch(created);
        assertThat(orderRefRow(order.orderId())).get().satisfies(row -> {
            assertThat(row.get("orderNumber")).isEqualTo(order.orderNumber());
            assertThat(row.get("orderType")).isEqualTo("GROCERY");
            assertThat(row.get("customerId")).isEqualTo(order.customerId());
            assertThat(row.get("storeId")).isEqualTo(vendorId);
            assertThat(row.get("storeName")).isEqualTo("Green Basket");
            assertThat((BigDecimal) row.get("total")).isEqualByComparingTo("245.00");
            assertThat(row.get("status")).isEqualTo("CREATED");
        });

        dispatch(TestEvents.orderAwaitingPayment(order));
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("AWAITING_PAYMENT"));
        dispatch(TestEvents.orderConfirmed(order, 1));
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("CONFIRMED"));

        // a replayed OrderCreated (another event id, e.g. from the DLT) never moves the status back
        dispatch(TestEvents.at(created, Instant.now()));
        assertThat(orderRefRow(order.orderId())).get().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("CONFIRMED");
            assertThat(row.get("storeName")).isEqualTo("Green Basket");
        });
    }

    @Test
    void anEventOlderThanMaxEventAgeOnlyUpdatesProjections() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), vendorId);
        knownOrder(order);

        ConsumerRecord<String, String> old = TestEvents.at(TestEvents.orderConfirmed(order, 1),
                Instant.now().minus(Duration.ofMinutes(16)));
        dispatch(old);

        assertThat(processed(old)).isTrue();
        assertThat(notificationsOf(old)).isEmpty();
        assertThat(orderRefRow(order.orderId())).get()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("CONFIRMED"));

        ConsumerRecord<String, String> recent = TestEvents.at(TestEvents.orderCancelled(order, "CONFIRMED",
                "ADMIN_CANCELLED", null), Instant.now().minus(Duration.ofMinutes(14)));
        dispatch(recent);
        assertThat(notificationsOf(recent)).hasSize(2);

        // the marker: everything published before it has arrived
        recorder.await(kind("notification").and(mentioning(order.orderId())), 2, WAIT);
        assertThat(recorder.matching(mentioning(order.orderId())))
                .noneMatch(m -> m.toString().contains("OrderConfirmed"));
    }

    @Test
    void anEventWithoutOccurredAtIsTreatedAsNew() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);

        ConsumerRecord<String, String> rejected = TestEvents.at(TestEvents.orderRejected(order), null);
        dispatch(rejected);

        assertThat(notificationOf(rejected, "ORDER_REJECTED").audiences())
                .containsExactly("user:" + order.customerId());
    }

    @Test
    void aDuplicateEventChangesAndPublishesNothing() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> rejected = TestEvents.orderRejected(order);

        dispatch(rejected);
        dispatch(rejected);

        assertThat(notificationsOf(rejected)).hasSize(1);
        assertThat(recorder.await(kind("notification").and(mentioning(order.orderId())), 1, WAIT)).hasSize(1);
        // the next event's messages arrive after anything the duplicate could have published
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        recorder.await(mentioning(marker), 1, WAIT);
        assertThat(recorder.matching(kind("notification").and(mentioning(order.orderId())))).hasSize(1);
        assertThat(recorder.matching(RealtimeRecorder.signal("ORDER_UPDATED").and(mentioning(order.orderId()))))
                .hasSize(1);
    }

    @Test
    void brokenEventsAreInvalidAndNotMarkedProcessed() {
        ConsumerRecord<String, String> noOrderId = TestEvents.event("order.events", "OrderConfirmed",
                UUID.randomUUID(), TestEvents.map("orderNumber", "GA-12345678", "customerId", UUID.randomUUID()));
        ConsumerRecord<String, String> unreadable = new ConsumerRecord<>("order.events", 0, 0L, "k", "{not json");
        unreadable.headers().add("eventId", UUID.randomUUID().toString().getBytes());
        unreadable.headers().add("eventType", "OrderRejected".getBytes());
        ConsumerRecord<String, String> noHeaders = new ConsumerRecord<>("order.events", 0, 0L, "k", "{}");
        ConsumerRecord<String, String> badUuid = TestEvents.event("vendor.events", "VendorApproved", "x",
                TestEvents.map("vendorId", "not-a-uuid", "ownerUserId", UUID.randomUUID()));

        assertThatThrownBy(() -> dispatch(noOrderId)).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("orderId");
        assertThatThrownBy(() -> dispatch(unreadable)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> dispatch(noHeaders)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> dispatch(badUuid)).isInstanceOf(InvalidEventException.class);
        assertThat(processed(noOrderId)).isFalse();
    }

    @Test
    void eventsThisServiceDoesNotUseAreIgnoredWithoutAMark() {
        ConsumerRecord<String, String> reserved = TestEvents.event("inventory.events", "StockReserved",
                UUID.randomUUID(), TestEvents.map("orderId", UUID.randomUUID()));
        // Phase 9's order and delivery events are used now (DeliveryNotificationsIntegrationTest); a later order event
        // type is not, nor is Phase 9's StockCommitted
        ConsumerRecord<String, String> phase9 = TestEvents.event("order.events", "OrderReturned",
                UUID.randomUUID(), TestEvents.map("orderId", UUID.randomUUID(), "vendorId", UUID.randomUUID()));
        ConsumerRecord<String, String> committed = TestEvents.event("inventory.events", "StockCommitted",
                UUID.randomUUID(), TestEvents.map("orderId", UUID.randomUUID(), "reservationId", UUID.randomUUID()));
        ConsumerRecord<String, String> otherStoreEvent = TestEvents.event("vendor.events", "VendorOrderArchived",
                UUID.randomUUID(), TestEvents.map("orderId", UUID.randomUUID(), "vendorId", UUID.randomUUID()));
        ConsumerRecord<String, String> delivery = TestEvents.event("delivery.events", "DeliveryCreated",
                UUID.randomUUID(), TestEvents.map("orderId", UUID.randomUUID()));

        dispatch(reserved);
        dispatch(phase9);
        dispatch(committed);
        dispatch(otherStoreEvent);
        dispatch(delivery);

        assertThat(processed(reserved)).isFalse();
        assertThat(processed(phase9)).isFalse();
        assertThat(processed(committed)).isFalse();
        assertThat(processed(otherStoreEvent)).isFalse();
        assertThat(processed(delivery)).isFalse();
    }

    @Test
    void retentionDeletesNotificationsOlderThanNinetyDaysWithTheirAudiencesAndReads() {
        Order oldOrder = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        Order newOrder = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> oldEvent = TestEvents.orderRejected(oldOrder);
        ConsumerRecord<String, String> newEvent = TestEvents.orderRejected(newOrder);
        dispatch(oldEvent);
        dispatch(newEvent);
        UUID oldId = notificationOf(oldEvent, "ORDER_REJECTED").id();
        jdbc.sql("INSERT INTO notification_read (user_id, notification_id, read_at) VALUES (:u, :n, NOW(6))")
                .param("u", UuidBytes.bytes(oldOrder.customerId())).param("n", UuidBytes.bytes(oldId)).update();
        jdbc.sql("UPDATE notification SET created_at = :at WHERE id = :id")
                .param("at", LocalDateTime.ofInstant(Instant.now().minus(Duration.ofDays(91)), ZoneOffset.UTC))
                .param("id", UuidBytes.bytes(oldId)).update();

        int deleted = retention.deleteOlderThan(Instant.now().minus(Duration.ofDays(90)));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(notificationsOf(oldEvent)).isEmpty();
        assertThat(notificationsOf(newEvent)).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_audience WHERE notification_id = :id")
                .param("id", UuidBytes.bytes(oldId)).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_read WHERE notification_id = :id")
                .param("id", UuidBytes.bytes(oldId)).query(Long.class).single()).isZero();
    }
}
