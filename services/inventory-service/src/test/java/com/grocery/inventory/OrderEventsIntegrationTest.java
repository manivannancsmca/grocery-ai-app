package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * order.events → reservations and inventory.events (the listener is called directly, as Kafka would, with the
 * routing data in the record headers).
 */
class OrderEventsIntegrationTest extends AbstractIntegrationTest {

    private List<String> availability(UUID productId) {
        return events(productId, "ProductAvailabilityChanged").stream()
                .map(e -> e.get("inStock").asBoolean() + "@" + e.get("stockVersion").asLong())
                .toList();
    }

    @Test
    void orderCreatedReservesEveryLineAndPublishesStockReserved() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID dal = newProductWithStock(vendorId, "Dal", 6);
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        Instant before = Instant.now();

        orderListener.on(orderCreated(eventId, orderId, "GROCERY",
                List.of(Map.entry(rice, 2), Map.entry(dal, 6), Map.entry(rice, 1))));

        assertThat(reserved(rice)).isEqualTo(3);
        assertThat(reserved(dal)).isEqualTo(6);
        assertThat(reservationRow(orderId).get("status")).isEqualTo("RESERVED");
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved");
        assertThat(outboxRow(orderId, "StockReserved"))
                .containsEntry("topic", "inventory.events")
                .containsEntry("aggregate_type", "Reservation")
                .containsEntry("aggregate_id", orderId.toString());

        JsonNode reserved = events(orderId, "StockReserved").getFirst();
        assertThat(reserved.get("orderId").asString()).isEqualTo(orderId.toString());
        UUID reservationId = UUID.fromString(reserved.get("reservationId").asString());
        assertThat(jdbc().sql("SELECT BIN_TO_UUID(id) FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).single()).isEqualTo(reservationId.toString());
        assertThat(reserved.get("items")).hasSize(2);
        assertThat(reserved.get("items").valueStream()
                .map(i -> i.get("productId").asString() + "x" + i.get("quantity").asInt()))
                .containsExactlyInAnyOrder(rice + "x3", dal + "x6");
        // expiresAt = now + grocery.inventory.reservation-ttl (20 minutes), as an ISO-8601 string
        Instant expiresAt = Instant.parse(reserved.get("expiresAt").asString());
        assertThat(expiresAt).isBetween(before.plus(Duration.ofMinutes(20)).minusSeconds(1),
                Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(reserved.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("orderId", "reservationId", "items", "expiresAt");
    }

    @Test
    void redeliveriesNeverReserveOrPublishTwice() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        orderListener.on(orderCreated(eventId, orderId, "GROCERY", List.of(Map.entry(rice, 4))));
        long version = stockVersion(rice);

        // The same event again (Kafka redelivery): skipped by processed_event
        orderListener.on(orderCreated(eventId, orderId, "GROCERY", List.of(Map.entry(rice, 4))));
        // Another OrderCreated for the same, already reserved order (e.g. a replay): reserve's own idempotency
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of(Map.entry(rice, 4))));

        assertThat(reserved(rice)).isEqualTo(4);
        assertThat(stockVersion(rice)).isEqualTo(version);
        assertThat(reservationCount(orderId)).isEqualTo(1);
        assertThat(movements(orderId, "RESERVE")).isEqualTo(1);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved");
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void notEnoughStockPublishesStockReservationFailedAndHoldsNothing() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID salt = newProductWithStock(vendorId, "Salt", 1);
        UUID unknown = Ids.newId();
        UUID orderId = Ids.newId();
        String eventId = newEventId();
        long riceVersion = stockVersion(rice);

        orderListener.on(orderCreated(eventId, orderId, "GROCERY",
                List.of(Map.entry(rice, 2), Map.entry(salt, 2), Map.entry(unknown, 1))));

        assertThat(reserved(rice)).isZero();
        assertThat(reserved(salt)).isZero();
        assertThat(stockVersion(rice)).isEqualTo(riceVersion);
        assertThat(reservationCount(orderId)).isZero();
        assertThat(movements(orderId, "RESERVE")).isZero();
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReservationFailed");
        assertThat(outboxRow(orderId, "StockReservationFailed")).containsEntry("aggregate_type", "Reservation");
        JsonNode failed = events(orderId, "StockReservationFailed").getFirst();
        assertThat(failed.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("orderId", "unavailableItems");
        assertThat(failed.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(failed.get("unavailableItems").valueStream()
                .map(i -> i.get("productId").asString() + ":" + i.get("requested").asInt() + "/" + i.get("available").asInt()))
                .containsExactlyInAnyOrder(salt + ":2/1", unknown + ":1/0");

        // Redelivered: still one failure event; a cancel of the rejected order releases nothing
        orderListener.on(orderCreated(eventId, orderId, "GROCERY",
                List.of(Map.entry(rice, 2), Map.entry(salt, 2), Map.entry(unknown, 1))));
        orderListener.on(orderCancelled(newEventId(), orderId, "GROCERY"));
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReservationFailed");
        assertThat(reserved(rice)).isZero();
    }

    @Test
    void restaurantOrdersAndOtherEventTypesAreIgnored() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        String created = newEventId();
        orderListener.on(orderCreated(created, orderId, "RESTAURANT", List.of(Map.entry(Ids.newId(), 2))));
        orderListener.on(orderConfirmed(newEventId(), orderId, "RESTAURANT"));
        String cancelled = newEventId();
        orderListener.on(orderCancelled(cancelled, orderId, "RESTAURANT"));
        assertThat(reservationCount(orderId)).isZero();
        assertThat(reservationEventTypes(orderId)).isEmpty();
        assertThat(processed(created)).isZero();
        assertThat(processed(cancelled)).isZero();

        // Grocery order events this service does not use
        UUID groceryOrder = Ids.newId();
        orderListener.on(orderEvent("OrderAwaitingPayment", newEventId(), groceryOrder,
                Map.of("orderId", groceryOrder.toString(), "amount", 125.00, "currency", "INR")));
        orderListener.on(orderEvent("OrderRejected", newEventId(), groceryOrder,
                Map.of("orderId", groceryOrder.toString(), "orderType", "GROCERY", "reason", "OUT_OF_STOCK")));
        assertThat(reservationEventTypes(groceryOrder)).isEmpty();
        assertThat(reserved(rice)).isZero();
    }

    @Test
    void orderConfirmedHoldsTheStockWithoutExpiry() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of(Map.entry(rice, 2))));
        assertThat(reservationRow(orderId).get("expires_at")).isNotNull();
        long version = stockVersion(rice);

        String confirmed = newEventId();
        orderListener.on(orderConfirmed(confirmed, orderId, "GROCERY"));
        orderListener.on(orderConfirmed(confirmed, orderId, "GROCERY"));             // redelivery
        orderListener.on(orderConfirmed(newEventId(), orderId, "GROCERY"));          // already held

        assertThat(reservationRow(orderId).get("status")).isEqualTo("RESERVED");
        assertThat(reservationRow(orderId).get("expires_at")).isNull();
        assertThat(processed(confirmed)).isEqualTo(1);
        assertThat(reserved(rice)).isEqualTo(2);
        assertThat(stockVersion(rice)).isEqualTo(version);                           // no stock change
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved"); // publishes nothing

        // A confirmed order can still be cancelled (until the store accepts): the held stock goes back
        orderListener.on(orderCancelled(newEventId(), orderId, "GROCERY"));
        assertThat(reserved(rice)).isZero();
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockReleased");

        // Confirming an order that has no reservation (never reserved) changes nothing
        UUID unknownOrder = Ids.newId();
        orderListener.on(orderConfirmed(newEventId(), unknownOrder, "GROCERY"));
        assertThat(reservationCount(unknownOrder)).isZero();
    }

    @Test
    void orderCancelledReleasesOnceAndOnlyWhatWasHeld() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of(Map.entry(rice, 3))));
        UUID reservationId = UUID.fromString(events(orderId, "StockReserved").getFirst().get("reservationId").asString());

        String cancelled = newEventId();
        orderListener.on(orderCancelled(cancelled, orderId, "GROCERY"));

        assertThat(reserved(rice)).isZero();
        assertThat(onHand(rice)).isEqualTo(10);
        assertThat(reservationRow(orderId).get("status")).isEqualTo("RELEASED");
        assertThat(movements(orderId, "RELEASE")).isEqualTo(1);
        assertThat(processed(cancelled)).isEqualTo(1);
        JsonNode released = events(orderId, "StockReleased").getFirst();
        assertThat(released.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("orderId", "reservationId", "reason");
        assertThat(released.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(released.get("reservationId").asString()).isEqualTo(reservationId.toString());
        assertThat(released.get("reason").asString()).isEqualTo("ORDER_CANCELLED");

        // Redelivered, and a second cancel event of the same order: nothing more is released or published
        orderListener.on(orderCancelled(cancelled, orderId, "GROCERY"));
        orderListener.on(orderCancelled(newEventId(), orderId, "GROCERY"));
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockReleased");
        assertThat(movements(orderId, "RELEASE")).isEqualTo(1);
        // A redelivered OrderCreated of the cancelled order does not reserve it again
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of(Map.entry(rice, 3))));
        assertThat(reserved(rice)).isZero();
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockReleased");

        // Never reserved (e.g. still CREATED when cancelled and the reservation failed / never happened): no-op
        UUID neverReserved = Ids.newId();
        String unknownCancel = newEventId();
        orderListener.on(orderCancelled(unknownCancel, neverReserved, "GROCERY"));
        assertThat(reservationEventTypes(neverReserved)).isEmpty();
        assertThat(reservationCount(neverReserved)).isZero();
    }

    // ---------------------------------------------------------------- Phase 9: commit at pickup

    @Test
    void orderOutForDeliveryCommitsTheHeldStockAndPublishesStockCommitted() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID dal = newProductWithStock(vendorId, "Dal", 6);
        UUID orderId = Ids.newId();
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY",
                List.of(Map.entry(rice, 2), Map.entry(dal, 1), Map.entry(rice, 1))));
        orderListener.on(orderConfirmed(newEventId(), orderId, "GROCERY"));
        UUID reservationId = UUID.fromString(events(orderId, "StockReserved").getFirst().get("reservationId").asString());
        long riceVersion = stockVersion(rice);
        String eventId = newEventId();
        Instant before = Instant.now();

        orderListener.on(orderOutForDelivery(eventId, orderId, "GROCERY"));

        assertThat(reservationRow(orderId).get("status")).isEqualTo("COMMITTED");
        assertThat(onHand(rice)).isEqualTo(7);                     // the goods left the store
        assertThat(reserved(rice)).isZero();
        assertThat(onHand(dal)).isEqualTo(5);
        assertThat(reserved(dal)).isZero();
        assertThat(stockVersion(rice)).isEqualTo(riceVersion + 1);
        assertThat(movements(orderId, "COMMIT")).isEqualTo(2);
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockCommitted");
        assertThat(outboxRow(orderId, "StockCommitted"))
                .containsEntry("topic", "inventory.events")
                .containsEntry("aggregate_type", "Reservation")
                .containsEntry("aggregate_id", orderId.toString());

        JsonNode committed = events(orderId, "StockCommitted").getFirst();
        assertThat(committed.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("orderId", "reservationId", "items", "committedAt");
        assertThat(committed.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(committed.get("reservationId").asString()).isEqualTo(reservationId.toString());
        assertThat(committed.get("items").valueStream()
                .map(i -> i.get("productId").asString() + "x" + i.get("quantity").asInt()))
                .containsExactlyInAnyOrder(rice + "x3", dal + "x1");
        assertThat(Instant.parse(committed.get("committedAt").asString()))
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));

        // Redelivered, and a second pickup event of the same order: committed once, published once
        orderListener.on(orderOutForDelivery(eventId, orderId, "GROCERY"));
        String again = newEventId();
        orderListener.on(orderOutForDelivery(again, orderId, "GROCERY"));
        assertThat(processed(again)).isEqualTo(1);
        assertThat(onHand(rice)).isEqualTo(7);
        assertThat(movements(orderId, "COMMIT")).isEqualTo(2);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockCommitted");

        // A cancel after pickup gives nothing back (the goods are gone)
        orderListener.on(orderCancelled(newEventId(), orderId, "GROCERY"));
        assertThat(onHand(rice)).isEqualTo(7);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockCommitted");
    }

    @Test
    void aCommitForAnUnknownOrReleasedReservationIsLoggedAndIgnored() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);

        // never reserved: nothing, not even the processed mark (as for a release)
        UUID unknown = Ids.newId();
        String unknownEvent = newEventId();
        orderListener.on(orderOutForDelivery(unknownEvent, unknown, "GROCERY"));
        assertThat(reservationCount(unknown)).isZero();
        assertThat(reservationEventTypes(unknown)).isEmpty();
        assertThat(processed(unknownEvent)).isZero();

        // released (cancelled) before the pickup: its stock may be sold again, so nothing is committed
        UUID released = Ids.newId();
        orderListener.on(orderCreated(newEventId(), released, "GROCERY", List.of(Map.entry(rice, 4))));
        orderListener.on(orderCancelled(newEventId(), released, "GROCERY"));
        long version = stockVersion(rice);
        String releasedEvent = newEventId();
        orderListener.on(orderOutForDelivery(releasedEvent, released, "GROCERY"));
        assertThat(reservationRow(released).get("status")).isEqualTo("RELEASED");
        assertThat(onHand(rice)).isEqualTo(10);
        assertThat(reserved(rice)).isZero();
        assertThat(stockVersion(rice)).isEqualTo(version);
        assertThat(movements(released, "COMMIT")).isZero();
        assertThat(processed(releasedEvent)).isEqualTo(1);
        assertThat(reservationEventTypes(released)).containsExactly("StockReserved", "StockReleased");

        // a restaurant order holds no stock
        UUID restaurantOrder = Ids.newId();
        String restaurantEvent = newEventId();
        orderListener.on(orderOutForDelivery(restaurantEvent, restaurantOrder, "RESTAURANT"));
        assertThat(processed(restaurantEvent)).isZero();
        assertThat(reservationEventTypes(restaurantOrder)).isEmpty();

        // broken: no orderId
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderOutForDelivery", newEventId(), unknown,
                Map.of("orderType", "GROCERY"))))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void reservingAndReleasingStillPublishTheStockTransitions() throws Exception {
        UUID eggs = newProductWithStock(vendorId, "Eggs", 8);                          // threshold 5
        UUID orderId = Ids.newId();

        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of(Map.entry(eggs, 8))));
        assertThat(availability(eggs)).containsExactly("true@1", "false@2");          // 8 → 0 available
        assertThat(events(eggs, "LowStockDetected")).singleElement()
                .satisfies(e -> assertThat(e.get("available").asInt()).isZero());

        orderListener.on(orderCancelled(newEventId(), orderId, "GROCERY"));
        assertThat(availability(eggs)).containsExactly("true@1", "false@2", "true@3");
        assertThat(events(eggs, "StockAdjusted")).hasSize(1);                         // reservations are not adjustments
    }

    @Test
    void brokenEventsAreRejected() {
        UUID orderId = Ids.newId();
        assertThatThrownBy(() -> orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of())))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> orderListener.on(orderCreated(newEventId(), orderId, "GROCERY",
                List.of(Map.entry(Ids.newId(), 0)))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderCreated", newEventId(), orderId,
                Map.of("orderType", "GROCERY", "items", List.of()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> orderListener.on(record("order.events", "Order", "OrderCancelled", newEventId(),
                orderId.toString(), "not json")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> orderListener.on(orderEvent("OrderConfirmed", newEventId(), orderId,
                Map.of("orderType", "GROCERY"))))
                .isInstanceOf(InvalidEventException.class);
        assertThat(reservationCount(orderId)).isZero();
    }
}
