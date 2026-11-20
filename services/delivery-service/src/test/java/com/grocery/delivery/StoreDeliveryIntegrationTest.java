package com.grocery.delivery;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The store's deliveries (phase-9 contract, store addendum): a vendor or restaurant owner sees who is collecting each
 * of its orders still at the store (PENDING / SEARCHING / ASSIGNED), the partner's full name, phone and vehicle once
 * assigned, and while ASSIGNED their position, distance and ETA to the store. Only the caller's own store.
 */
class StoreDeliveryIntegrationTest extends DispatchTestSupport {

    private static final String PATH = "/api/delivery/store/deliveries";

    /** A store owner's token: {@code role} with the store's id in {@code claim}, or without the claim when null. */
    private static JwtRequestPostProcessor storeOwner(String role, String claim, UUID storeId) {
        UUID user = UUID.randomUUID();
        return jwt()
                .jwt(token -> {
                    token.subject(user.toString())
                            .claim("email", "store-" + user.toString().substring(0, 8) + "@example.com")
                            .claim("name", "Store Owner")
                            .claim("roles", List.of(role));
                    if (storeId != null) {
                        token.claim(claim, storeId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static JwtRequestPostProcessor vendor(UUID vendorId) {
        return storeOwner("VENDOR", "vendor_id", vendorId);
    }

    private static JwtRequestPostProcessor restaurantOwner(UUID restaurantId) {
        return storeOwner("RESTAURANT_OWNER", "restaurant_id", restaurantId);
    }

    private JsonNode board(JwtRequestPostProcessor caller) throws Exception {
        JsonNode body = body(mvc.perform(get(PATH).with(caller)).andExpect(status().isOk()));
        assertThat(body.isArray()).as("a JSON array, not a page").isTrue();
        return body;
    }

    private static List<String> orderIds(JsonNode board) {
        List<String> ids = new ArrayList<>();
        for (JsonNode element : board) {
            ids.add(element.get("orderId").asString());
        }
        return ids;
    }

    /** A new order of {@code storeId} (vendorId or restaurantId). */
    private static Order orderOf(UUID storeId) {
        UUID orderId = Ids.newId();
        return new Order(orderId, orderNumber(orderId), UUID.randomUUID(), storeId);
    }

    @Test
    void aVendorSeesWhoIsCollectingEachOrderUntilItIsPickedUp() throws Exception {
        UUID rider = onlinePartnerAt("Senthil Kumar", at(1.0, 0));    // 1 km north of the store
        Order order = confirmedOrder();
        UUID vendorId = order.storeId();

        JsonNode pending = board(vendor(vendorId));
        assertThat(pending).hasSize(1);
        JsonNode first = pending.get(0);
        assertThat(fieldNames(first)).containsExactly("deliveryId", "orderId", "orderNumber", "status",
                "assignmentFailed", "assignedAt", "partner", "partnerLocation", "distanceToStoreKm",
                "etaToStoreMinutes");
        assertThat(first.get("deliveryId").asString()).isEqualTo(deliveryId(order).toString());
        assertThat(first.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(first.get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(first.get("status").asString()).isEqualTo("PENDING");
        assertThat(first.get("partner").isNull()).isTrue();

        // SEARCHING: an offer is out to the rider, but nobody collects the order yet
        storeAccepted(order);
        JsonNode searching = board(vendor(vendorId)).get(0);
        assertThat(searching.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(searching.get("assignmentFailed").asBoolean()).isFalse();
        assertThat(searching.get("assignedAt").isNull()).isTrue();
        assertThat(searching.get("partner").isNull()).isTrue();
        assertThat(searching.get("partnerLocation").isNull()).isTrue();
        assertThat(searching.get("distanceToStoreKm").isNull()).isTrue();
        assertThat(searching.get("etaToStoreMinutes").isNull()).isTrue();

        // ASSIGNED: the full name, phone and vehicle; 1 km from the store × 1.3 / 20 km/h = 3.9 → 4 min
        clock.set(T0.plusSeconds(20));
        offers.accept(rider, offerOf(order, rider));
        JsonNode assigned = board(vendor(vendorId)).get(0);
        assertThat(assigned.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(assigned.get("assignmentFailed").asBoolean()).isFalse();
        assertThat(Instant.parse(assigned.get("assignedAt").asString())).isEqualTo(T0.plusSeconds(20));
        JsonNode partner = assigned.get("partner");
        assertThat(fieldNames(partner)).containsExactly("name", "phone", "vehicleType", "vehicleNumber");
        assertThat(partner.get("name").asString()).isEqualTo("Senthil Kumar");
        assertThat(partner.get("phone").asString()).isEqualTo("+919800000000");
        assertThat(partner.get("vehicleType").asString()).isEqualTo("SCOOTER");
        assertThat(partner.get("vehicleNumber").asString()).isEqualTo("KA01AB1234");
        JsonNode location = assigned.get("partnerLocation");
        assertThat(fieldNames(location)).containsExactly("latitude", "longitude", "heading", "at");
        assertThat(location.get("latitude").asDouble()).isEqualTo(at(1.0, 0).latitude());
        assertThat(location.get("longitude").asDouble()).isEqualTo(at(1.0, 0).longitude());
        assertThat(location.get("heading").asDouble()).isEqualTo(90.0);
        assertThat(Instant.parse(location.get("at").asString())).isEqualTo(T0);
        assertThat(assigned.get("distanceToStoreKm").asDouble()).isEqualTo(1.0);
        assertThat(assigned.get("etaToStoreMinutes").asInt()).isEqualTo(4);

        // Another vendor sees nothing of it
        assertThat(board(vendor(UUID.randomUUID()))).isEmpty();

        // The rider comes closer: 0.4 km → 1.56 → 2 min
        clock.set(T0.plusSeconds(60));
        report(rider, at(0.4, 0));
        JsonNode closer = board(vendor(vendorId)).get(0);
        assertThat(closer.get("distanceToStoreKm").asDouble()).isEqualTo(0.4);
        assertThat(closer.get("etaToStoreMinutes").asInt()).isEqualTo(2);
        assertThat(Instant.parse(closer.get("partnerLocation").get("at").asString())).isEqualTo(T0.plusSeconds(60));

        // PICKED_UP and DELIVERED leave the list
        readyForPickup(order);
        partnerDeliveries.pickUp(rider, deliveryId(order));
        assertThat(board(vendor(vendorId))).isEmpty();
        partnerDeliveries.deliver(rider, deliveryId(order));
        assertThat(deliveryStatus(order)).isEqualTo("DELIVERED");
        assertThat(board(vendor(vendorId))).isEmpty();
    }

    @Test
    void theStoresOpenDeliveriesOldestFirstWithoutOtherStoresOrFinishedOnes() throws Exception {
        UUID vendorId = UUID.randomUUID();
        Order waiting = orderOf(vendorId);
        confirm(waiting, base, at(0, 3));                             // PENDING

        clock.set(T0.plusSeconds(60));
        Order unassignable = orderOf(vendorId);
        confirm(unassignable, base, at(0, 3));
        storeAccepted(unassignable);                                  // nobody online: assignmentFailed, retried

        clock.set(T0.plusSeconds(120));
        Order cancelled = orderOf(vendorId);
        confirm(cancelled, base, at(0, 3));
        orderCancelled(cancelled, "CONFIRMED");
        Order otherStore = confirmedOrder();

        JsonNode board = board(vendor(vendorId));
        assertThat(orderIds(board)).containsExactly(waiting.orderId().toString(), unassignable.orderId().toString());
        assertThat(board.get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(board.get(0).get("assignmentFailed").asBoolean()).isFalse();
        assertThat(board.get(1).get("status").asString()).isEqualTo("SEARCHING");
        assertThat(board.get(1).get("assignmentFailed").asBoolean()).isTrue();
        assertThat(board.get(1).get("partner").isNull()).isTrue();
        assertThat(orderIds(board(vendor(otherStore.storeId())))).containsExactly(otherStore.orderId().toString());
    }

    @Test
    void atMostTheOldestHundredAreListed() throws Exception {
        UUID vendorId = UUID.randomUUID();
        List<String> orderIds = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            clock.set(T0.plusSeconds(i));
            Order order = orderOf(vendorId);
            confirm(order, base, at(0, 3));
            orderIds.add(order.orderId().toString());
        }
        assertThat(orderIds(board(vendor(vendorId)))).containsExactlyElementsOf(orderIds.subList(0, 100));
    }

    @Test
    void aRestaurantOwnerSeesItsRestaurantsDeliveries() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        Order order = orderOf(restaurantId);
        Map<String, Object> payload = confirmedPayload(order, base, at(0, 2));
        payload.put("orderType", "RESTAURANT");
        orderListener.on(orderEvent("OrderConfirmed", newEventId(), order.orderId(), 1, payload));

        JsonNode board = board(restaurantOwner(restaurantId));
        assertThat(orderIds(board)).containsExactly(order.orderId().toString());
        assertThat(board.get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(board(restaurantOwner(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void onlyStoreOwnersOfAnApprovedStoreMayReadIt() throws Exception {
        mvc.perform(get(PATH).with(as(UUID.randomUUID(), CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(as(UUID.randomUUID(), PARTNER))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(as(UUID.randomUUID(), "DELIVERY_MANAGER"))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(vendor(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get(PATH).with(restaurantOwner(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_ACTIVE"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
