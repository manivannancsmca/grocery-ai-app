package com.grocery.delivery;

import com.grocery.delivery.dispatch.entity.FailureReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customer tracking (contract §4): ownership (customerId = the token's subject; admins see all), the partner's first
 * name, and their phone, number plate, live location and the ETA only while ASSIGNED or PICKED_UP.
 */
class TrackingIntegrationTest extends DispatchTestSupport {

    private JsonNode track(Order order, UUID user, String role) throws Exception {
        return body(mvc.perform(get("/api/delivery/orders/{orderId}/tracking", order.orderId()).with(as(user, role)))
                .andExpect(status().isOk()));
    }

    @Test
    void onlyTheOrdersCustomerAndAdminsCanTrackIt() throws Exception {
        Order order = confirmedOrder();
        track(order, order.customerId(), CUSTOMER);
        track(order, admin, "SUPPORT");
        track(order, admin, "FINANCE_ADMIN");
        mvc.perform(get("/api/delivery/orders/{orderId}/tracking", order.orderId())
                        .with(as(UUID.randomUUID(), CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRACKING_NOT_FOUND"));
        mvc.perform(get("/api/delivery/orders/{orderId}/tracking", UUID.randomUUID())
                        .with(as(order.customerId(), CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRACKING_NOT_FOUND"));
        mvc.perform(get("/api/delivery/orders/{orderId}/tracking", order.orderId())
                        .with(as(UUID.randomUUID(), PARTNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePartnersPositionAndTheEtaAreSharedOnlyWhileTheyAreOnTheWay() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));       // 1 km north of the store
        Order order = confirmedOrder();                               // drop 3 km east of the store

        JsonNode pending = track(order, order.customerId(), CUSTOMER);
        assertThat(fieldNames(pending)).containsExactly("orderId", "status", "partner", "pickup", "drop",
                "partnerLocation", "etaMinutes", "timeline");
        assertThat(pending.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("partner").isNull()).isTrue();
        assertThat(pending.get("partnerLocation").isNull()).isTrue();
        assertThat(pending.get("etaMinutes").isNull()).isTrue();
        assertThat(fieldNames(pending.get("pickup"))).containsExactly("name", "latitude", "longitude");
        assertThat(pending.get("pickup").get("name").asString()).isEqualTo("Green Basket");
        assertThat(pending.get("pickup").get("latitude").asDouble()).isEqualTo(base.latitude());
        assertThat(fieldNames(pending.get("drop"))).containsExactly("latitude", "longitude");
        assertThat(pending.get("drop").get("longitude").asDouble()).isEqualTo(at(0, 3).longitude());

        storeAccepted(order);
        JsonNode searching = track(order, order.customerId(), CUSTOMER);
        assertThat(searching.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(searching.get("partner").isNull()).isTrue();
        assertThat(searching.get("partnerLocation").isNull()).isTrue();

        // ASSIGNED: partner → store (1 km) → customer (3 km) = 4 km × 1.3 / 20 km/h = 15.6 → 16 min
        offers.accept(rider, offerOf(order, rider));
        JsonNode assigned = track(order, order.customerId(), CUSTOMER);
        assertThat(assigned.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(fieldNames(assigned.get("partner"))).containsExactly("name", "vehicleType", "phone",
                "vehicleNumber");
        assertThat(assigned.get("partner").get("name").asString()).isEqualTo("Ravi");
        assertThat(assigned.get("partner").get("vehicleType").asString()).isEqualTo("SCOOTER");
        assertThat(assigned.get("partner").get("phone").asString()).isEqualTo("+919800000000");
        assertThat(assigned.get("partner").get("vehicleNumber").asString()).isEqualTo("KA01AB1234");
        JsonNode location = assigned.get("partnerLocation");
        assertThat(fieldNames(location)).containsExactly("latitude", "longitude", "heading", "at");
        assertThat(location.get("latitude").asDouble()).isEqualTo(at(1.0, 0).latitude());
        assertThat(location.get("heading").asDouble()).isEqualTo(90.0);
        assertThat(Instant.parse(location.get("at").asString())).isEqualTo(T0);
        assertThat(assigned.get("etaMinutes").asInt()).isEqualTo(16);
        assertThat(assigned.toString()).doesNotContain("Kumar");          // first name only

        // PICKED_UP, partner 1 km east of the store: partner → customer = 2 km → 7.8 → 8 min
        readyForPickup(order);
        partnerDeliveries.pickUp(rider, deliveryId(order));
        clock.set(T0.plusSeconds(300));
        report(rider, at(0, 1.0));
        JsonNode pickedUp = track(order, order.customerId(), CUSTOMER);
        assertThat(pickedUp.get("status").asString()).isEqualTo("PICKED_UP");
        assertThat(pickedUp.get("etaMinutes").asInt()).isEqualTo(8);
        assertThat(Instant.parse(pickedUp.get("partnerLocation").get("at").asString()))
                .isEqualTo(T0.plusSeconds(300));
        assertThat(pickedUp.get("partner").get("phone").asString()).isEqualTo("+919800000000");

        // DELIVERED: the partner is still named, but no longer located, and the customer no longer gets their number
        partnerDeliveries.deliver(rider, deliveryId(order));
        JsonNode delivered = track(order, order.customerId(), CUSTOMER);
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("partner").get("name").asString()).isEqualTo("Ravi");
        assertThat(delivered.get("partner").get("vehicleType").asString()).isEqualTo("SCOOTER");
        assertThat(delivered.get("partner").get("phone").isNull()).isTrue();
        assertThat(delivered.get("partner").get("vehicleNumber").isNull()).isTrue();
        assertThat(delivered.get("partnerLocation").isNull()).isTrue();
        assertThat(delivered.get("etaMinutes").isNull()).isTrue();
        assertThat(delivered.get("timeline")).extracting(entry -> entry.get("status").asString())
                .containsExactly("PENDING", "SEARCHING", "ASSIGNED", "PICKED_UP", "DELIVERED");
    }

    @Test
    void thePartnersPhoneGoesOnlyToTheOrdersCustomerAndOnlyWhileThePartnerHoldsTheDelivery() throws Exception {
        UUID rider = onlinePartnerAt("Suresh Babu", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));

        JsonNode assigned = track(order, order.customerId(), CUSTOMER).get("partner");
        assertThat(assigned.get("name").asString()).isEqualTo("Suresh");
        assertThat(assigned.get("phone").asString()).isEqualTo("+919800000000");
        assertThat(assigned.get("vehicleNumber").asString()).isEqualTo("KA01AB1234");
        // Another customer still learns nothing, not even that the delivery exists.
        mvc.perform(get("/api/delivery/orders/{orderId}/tracking", order.orderId())
                        .with(as(UUID.randomUUID(), CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TRACKING_NOT_FOUND"))
                .andExpect(jsonPath("$.partner").doesNotExist());

        // FAILED: the partner no longer holds the delivery, so the number goes away
        readyForPickup(order);
        partnerDeliveries.pickUp(rider, deliveryId(order));
        partnerDeliveries.fail(rider, deliveryId(order), FailureReason.CUSTOMER_UNREACHABLE, null);
        JsonNode failed = track(order, order.customerId(), CUSTOMER);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("partner").get("name").asString()).isEqualTo("Suresh");
        assertThat(failed.get("partner").get("phone").isNull()).isTrue();
        assertThat(failed.get("partner").get("vehicleNumber").isNull()).isTrue();

        // CANCELLED while the (now free) partner was heading to the store: still named, without the number
        Order cancelled = searchingOrder();
        offers.accept(rider, offerOf(cancelled, rider));
        assertThat(track(cancelled, cancelled.customerId(), CUSTOMER).get("partner").get("phone").asString())
                .isEqualTo("+919800000000");
        orderCancelled(cancelled, "ACCEPTED");
        JsonNode afterCancel = track(cancelled, cancelled.customerId(), CUSTOMER);
        assertThat(afterCancel.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(afterCancel.get("partner").get("name").asString()).isEqualTo("Suresh");
        assertThat(afterCancel.get("partner").get("phone").isNull()).isTrue();
        assertThat(afterCancel.get("partner").get("vehicleNumber").isNull()).isTrue();
    }
}
