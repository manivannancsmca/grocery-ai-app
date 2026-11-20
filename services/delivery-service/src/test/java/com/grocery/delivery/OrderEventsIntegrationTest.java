package com.grocery.delivery;

import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order.events consumer (contract §6, group delivery-service.order-events): OrderConfirmed → PENDING,
 * OrderAccepted → SEARCHING + first wave, OrderReadyForPickup → readyForPickup, OrderCancelled → CANCELLED (offers
 * withdrawn, partner released, DeliveryCancelled) unless already picked up; duplicates ignored.
 */
class OrderEventsIntegrationTest extends DispatchTestSupport {

    private long processed(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? "
                        + "AND consumer = 'delivery-service.order-events'")
                .param(eventId).query(Long.class).single();
    }

    @Test
    void orderConfirmedCreatesOnePendingDeliveryWithTheOrderSnapshot() throws Exception {
        Order order = newOrder();
        String eventId = newEventId();
        var record = orderEvent("OrderConfirmed", eventId, order.orderId(), 3, confirmedPayload(order, base, at(0, 3)));
        orderListener.on(record);
        orderListener.on(record);                                       // redelivered
        orderListener.on(orderEvent("OrderConfirmed", newEventId(), order.orderId(), 3,
                confirmedPayload(order, base, at(0, 3))));              // replayed with another event id
        assertThat(processed(eventId)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM delivery WHERE order_id = UUID_TO_BIN(?)")
                .param(order.orderId().toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(eventTypes(order)).isEmpty();                        // no event for a new delivery

        JsonNode view = body(mvc.perform(get("/api/admin/deliveries/{id}", deliveryId(order))
                .with(as(admin, "SUPPORT"))).andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("PENDING");
        assertThat(view.get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(view.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(view.get("readyForPickup").asBoolean()).isFalse();
        assertThat(view.get("pickup").get("name").asString()).isEqualTo("Green Basket");
        assertThat(view.get("pickup").get("city").asString()).isEqualTo("Bengaluru");
        assertThat(view.get("pickup").get("latitude").asDouble()).isEqualTo(base.latitude());
        assertThat(view.get("pickup").get("longitude").asDouble()).isEqualTo(base.longitude());
        JsonNode drop = view.get("drop");
        assertThat(fieldNames(drop)).containsExactly("recipientName", "phone", "line1", "line2", "landmark", "city",
                "postalCode", "latitude", "longitude");
        assertThat(drop.get("recipientName").asString()).isEqualTo("Ravi Kumar");
        assertThat(drop.get("phone").asString()).isEqualTo("+919876543210");
        assertThat(drop.get("postalCode").asString()).isEqualTo("560038");
        assertThat(drop.get("latitude").asDouble()).isEqualTo(at(0, 3).latitude());
        assertThat(view.get("note").asString()).isEqualTo("Ring twice");
        assertThat(view.get("itemCount").asInt()).isEqualTo(3);
        assertThat(view.get("total").decimalValue()).isEqualByComparingTo("245.00");
        assertThat(view.get("currency").asString()).isEqualTo("INR");
        assertThat(view.get("timeline")).hasSize(1);
        assertThat(view.get("timeline").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(view.get("partnerId").isNull()).isTrue();
        assertThat(view.get("offersSent").asInt()).isZero();
        assertThat(view.get("offers")).isEmpty();
    }

    @Test
    void orderAcceptedStartsTheSearchAndSendsTheFirstWave() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = confirmedOrder();
        assertThat(offeredPartners(order)).isEmpty();                   // PENDING: the store has not accepted yet

        clock.set(T0.plusSeconds(10));
        storeAccepted(order);
        assertThat(deliveryStatus(order)).isEqualTo("SEARCHING");
        assertThat(offeredPartners(order)).containsExactly(rider);
        JsonNode view = body(mvc.perform(get("/api/admin/deliveries/{id}", deliveryId(order))
                .with(as(admin, "OPS_ADMIN"))).andExpect(status().isOk()));
        assertThat(Instant.parse(view.get("readyBy").asString())).isEqualTo(T0.plusSeconds(910));
        assertThat(view.get("timeline")).extracting(entry -> entry.get("status").asString())
                .containsExactly("PENDING", "SEARCHING");

        // A second OrderAccepted (another event id) changes nothing
        storeAccepted(order);
        assertThat(offeredPartners(order)).containsExactly(rider);
    }

    @Test
    void anOlderOrderConfirmedWithoutStoreLocationIsSearchedAroundTheDropPoint() throws Exception {
        GeoPoint drop = at(0, 20);                                      // 20 km from the (unknown) store
        UUID nearStore = onlinePartnerAt("Near Store", at(0.5, 0));
        UUID nearDrop = onlinePartnerAt("Near Drop", at(1.0, 20));
        Order order = newOrder();
        confirm(order, null, drop);
        storeAccepted(order);

        assertThat(offeredPartners(order)).containsExactly(nearDrop);
        assertThat(offeredPartners(order)).doesNotContain(nearStore);
        assertThat(offerRows(order).getFirst().distanceKm()).isCloseTo(1.0, within(0.01));
        JsonNode created = event(order, DeliveryEvents.OFFER_CREATED);
        assertThat(created.get("storeName").isNull()).isTrue();
        assertThat(created.get("tripKm").isNull()).isTrue();
        JsonNode view = body(mvc.perform(get("/api/admin/deliveries/{id}", deliveryId(order))
                .with(as(admin, "SUPER_ADMIN"))).andExpect(status().isOk()));
        assertThat(view.get("pickup").get("latitude").isNull()).isTrue();
        assertThat(view.get("pickup").get("name").isNull()).isTrue();
    }

    @Test
    void orderReadyForPickupMarksTheDeliveryReady() {
        Order order = searchingOrder();
        assertThat(delivery(order)).containsEntry("ready_for_pickup", false);
        readyForPickup(order);
        assertThat(delivery(order)).containsEntry("ready_for_pickup", true).containsEntry("status", "SEARCHING");
        readyForPickup(order);                                          // again: unchanged
        assertThat(delivery(order)).containsEntry("ready_for_pickup", true);
    }

    @Test
    void cancellingAPendingOrSearchingDeliveryWithdrawsItsOffers() {
        Order pending = confirmedOrder();
        orderCancelled(pending, "CONFIRMED");
        assertThat(deliveryStatus(pending)).isEqualTo("CANCELLED");
        JsonNode cancelled = event(pending, DeliveryEvents.CANCELLED);
        assertThat(fieldNames(cancelled)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "partnerId",
                "cancelledAt");
        assertThat(cancelled.get("partnerId").isNull()).isTrue();
        assertThat(Instant.parse(cancelled.get("cancelledAt").asString())).isEqualTo(T0);

        UUID one = onlinePartnerAt("One", at(1.0, 0));
        UUID two = onlinePartnerAt("Two", at(2.0, 0));
        Order searching = searchingOrder();
        assertThat(offeredPartners(searching)).containsExactly(one, two);
        orderCancelled(searching, "ACCEPTED");
        assertThat(deliveryStatus(searching)).isEqualTo("CANCELLED");
        assertThat(offerRows(searching)).extracting(OfferRow::status).containsOnly("WITHDRAWN");
        assertThat(events(searching, DeliveryEvents.OFFER_CLOSED)).extracting(node -> node.get("outcome").asString())
                .containsExactly("WITHDRAWN", "WITHDRAWN");
        assertThat(eventTypes(searching).getLast()).isEqualTo(DeliveryEvents.CANCELLED);
        assertThat(offers.myOffers(one)).isEmpty();

        // An OrderAccepted arriving after the cancellation changes nothing; a second cancellation neither
        storeAccepted(searching);
        orderCancelled(searching, "ACCEPTED");
        assertThat(deliveryStatus(searching)).isEqualTo("CANCELLED");
        assertThat(events(searching, DeliveryEvents.CANCELLED)).hasSize(1);
    }

    @Test
    void cancellingAnAssignedDeliveryReleasesThePartner() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        assertThat(activeDelivery(rider)).isNotNull();

        orderCancelled(order, "READY_FOR_PICKUP");
        assertThat(deliveryStatus(order)).isEqualTo("CANCELLED");
        assertThat(event(order, DeliveryEvents.CANCELLED).get("partnerId").asString()).isEqualTo(rider.toString());
        assertThat(activeDelivery(rider)).isNull();
        mvc.perform(get("/api/delivery/deliveries/active").with(as(rider, PARTNER))).andExpect(status().isNoContent());
        // The partner still sees the cancelled delivery, and is free for the next one
        mvc.perform(get("/api/delivery/deliveries/{id}", deliveryId(order)).with(as(rider, PARTNER)))
                .andExpect(status().isOk());
        Order next = searchingOrder();
        assertThat(offeredPartners(next)).containsExactly(rider);
    }

    @Test
    void anOrderCancelledAfterPickupIsIgnored() {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        readyForPickup(order);
        partnerDeliveries.pickUp(rider, deliveryId(order));

        orderCancelled(order, "READY_FOR_PICKUP");
        Map<String, Object> delivery = delivery(order);
        assertThat(delivery).containsEntry("status", "PICKED_UP").containsEntry("partner_id", rider.toString());
        assertThat(events(order, DeliveryEvents.CANCELLED)).isEmpty();
        assertThat(activeDelivery(rider)).isEqualTo(delivery.get("id"));
    }
}
