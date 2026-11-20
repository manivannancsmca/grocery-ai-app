package com.grocery.delivery;

import com.grocery.common.security.CurrentUser;
import com.grocery.delivery.dispatch.entity.FailureReason;
import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The partner API (contract §3): status, availability, offers (privacy), accept, the delivery's steps with their
 * events and idempotent repeats, history (with its filters) and summary, and every error code.
 */
class PartnerApiIntegrationTest extends DispatchTestSupport {

    private ResultActions step(UUID partner, UUID deliveryId, String step) throws Exception {
        return mvc.perform(post("/api/delivery/deliveries/{id}/" + step, deliveryId).with(as(partner, PARTNER)));
    }

    private ResultActions fail(UUID partner, UUID deliveryId, String body) throws Exception {
        return mvc.perform(post("/api/delivery/deliveries/{id}/fail", deliveryId).with(as(partner, PARTNER))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions availability(UUID partner, boolean online) throws Exception {
        return mvc.perform(put("/api/delivery/partners/me/availability").with(as(partner, PARTNER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"online\":" + online + "}"));
    }

    @Test
    void statusAndAvailability() throws Exception {
        UUID rider = approvedPartner("Ravi Kumar");
        mvc.perform(get("/api/delivery/partners/me/status").with(as(rider, PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerId").value(rider.toString()))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.location").isEmpty())
                .andExpect(jsonPath("$.activeDeliveryId").isEmpty())
                .andExpect(jsonPath("$.pendingOffers").value(0));

        JsonNode online = body(availability(rider, true).andExpect(status().isOk()));
        assertThat(fieldNames(online)).containsExactly("partnerId", "online", "location", "activeDeliveryId",
                "pendingOffers");
        assertThat(online.get("online").asBoolean()).isTrue();

        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":" + at(1, 0).latitude() + ",\"longitude\":" + at(1, 0).longitude()
                                + ",\"accuracyMeters\":5,\"heading\":45.5,\"speedKmh\":12}"))
                .andExpect(status().isNoContent());
        Order order = searchingOrder();
        JsonNode status = body(mvc.perform(get("/api/delivery/partners/me/status").with(as(rider, PARTNER))));
        assertThat(status.get("location").get("latitude").asDouble()).isEqualTo(at(1, 0).latitude());
        assertThat(fieldNames(status.get("location"))).containsExactly("latitude", "longitude", "at");
        assertThat(Instant.parse(status.get("location").get("at").asString())).isEqualTo(T0);
        assertThat(status.get("pendingOffers").asInt()).isEqualTo(1);

        offers.accept(rider, offerOf(order, rider));
        availability(rider, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACTIVE_DELIVERY"));
        mvc.perform(get("/api/delivery/partners/me/status").with(as(rider, PARTNER)))
                .andExpect(jsonPath("$.activeDeliveryId").value(deliveryId(order).toString()))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.pendingOffers").value(0));
    }

    @Test
    void offersShowNoCustomerDetailsAndOnlyTheAssignedPartnerSeesTheDelivery() throws Exception {
        UUID winner = onlinePartnerAt("Arjun Singh", at(1.0, 0));
        UUID other = onlinePartnerAt("Meena Rao", at(2.0, 0));
        UUID stranger = onlinePartnerAt("Far Away", at(-50, 0));
        Order order = searchingOrder();
        UUID deliveryId = deliveryId(order);

        String offersJson = mvc.perform(get("/api/delivery/offers").with(as(winner, PARTNER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(offersJson);
        assertThat(list).hasSize(1);
        JsonNode offer = list.get(0);
        assertThat(fieldNames(offer)).containsExactly("offerId", "deliveryId", "orderNumber", "orderType", "store",
                "dropArea", "distanceKm", "tripKm", "itemCount", "expiresAt");
        assertThat(fieldNames(offer.get("store"))).containsExactly("name", "city", "latitude", "longitude");
        assertThat(offer.get("store").get("name").asString()).isEqualTo("Green Basket");
        assertThat(offer.get("dropArea").asString()).isEqualTo("Near Metro, Bengaluru");
        assertThat(offer.get("distanceKm").asDouble()).isEqualTo(1.0);
        assertThat(offer.get("tripKm").asDouble()).isEqualTo(3.0);
        assertThat(offer.get("itemCount").asInt()).isEqualTo(3);
        assertThat(offer.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(Instant.parse(offer.get("expiresAt").asString())).isEqualTo(T0.plusSeconds(30));
        assertThat(offersJson).doesNotContain("Ravi Kumar", "+919876543210", "42 HAL", "560038", "Ring twice");
        mvc.perform(get("/api/delivery/offers").with(as(stranger, PARTNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // Before acceptance nobody sees the delivery itself
        mvc.perform(get("/api/delivery/deliveries/{id}", deliveryId).with(as(winner, PARTNER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("DELIVERY_NOT_FOUND"));
        // Another partner's offer does not exist for me
        mvc.perform(post("/api/delivery/offers/{id}/accept", offerOf(order, other)).with(as(stranger, PARTNER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("OFFER_NOT_FOUND"));
        mvc.perform(post("/api/delivery/offers/{id}/reject", offerOf(order, other)).with(as(winner, PARTNER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("OFFER_NOT_FOUND"));
        mvc.perform(post("/api/delivery/offers/{id}/accept", UUID.randomUUID()).with(as(winner, PARTNER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("OFFER_NOT_FOUND"));
        mvc.perform(post("/api/delivery/offers/{id}/accept", "not-a-uuid").with(as(winner, PARTNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        clock.set(T0.plusSeconds(5));
        JsonNode accepted = body(mvc.perform(post("/api/delivery/offers/{id}/accept", offerOf(order, winner))
                .with(as(winner, PARTNER))).andExpect(status().isOk()));
        assertThat(fieldNames(accepted)).containsExactly("deliveryId", "orderId", "orderNumber", "orderType", "status",
                "readyForPickup", "readyBy", "pickup", "drop", "note", "itemCount", "total", "currency", "assignedAt",
                "pickedUpAt", "deliveredAt", "failedAt", "failure", "timeline");
        assertThat(accepted.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(accepted.get("drop").get("recipientName").asString()).isEqualTo("Ravi Kumar");
        assertThat(accepted.get("drop").get("phone").asString()).isEqualTo("+919876543210");
        assertThat(accepted.get("drop").get("line1").asString()).isEqualTo("42 HAL 2nd Stage");
        assertThat(accepted.get("note").asString()).isEqualTo("Ring twice");
        assertThat(Instant.parse(accepted.get("assignedAt").asString())).isEqualTo(T0.plusSeconds(5));
        assertThat(accepted.get("timeline")).extracting(entry -> entry.get("status").asString())
                .containsExactly("PENDING", "SEARCHING", "ASSIGNED");
        JsonNode assigned = event(order, DeliveryEvents.PARTNER_ASSIGNED);
        assertThat(fieldNames(assigned)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "partnerId",
                "partnerName", "vehicleType", "manual", "assignedAt");
        assertThat(assigned.get("partnerName").asString()).isEqualTo("Arjun Singh");
        assertThat(assigned.get("vehicleType").asString()).isEqualTo("SCOOTER");
        assertThat(assigned.get("manual").asBoolean()).isFalse();

        // The other partner's offer was withdrawn: accepting it now is a conflict, and they cannot see the delivery
        mvc.perform(post("/api/delivery/offers/{id}/accept", offerOf(order, other)).with(as(other, PARTNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFER_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.currentStatus").value("WITHDRAWN"));
        mvc.perform(get("/api/delivery/deliveries/{id}", deliveryId).with(as(other, PARTNER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("DELIVERY_NOT_FOUND"));
        step(other, deliveryId, "pickup").andExpect(status().isNotFound());
        // Accepting my own offer twice: it is no longer PENDING
        mvc.perform(post("/api/delivery/offers/{id}/accept", offerOf(order, winner)).with(as(winner, PARTNER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.currentStatus").value("ACCEPTED"));

        mvc.perform(get("/api/delivery/deliveries/active").with(as(winner, PARTNER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryId").value(deliveryId.toString()));
        mvc.perform(get("/api/delivery/deliveries/active").with(as(other, PARTNER)))
                .andExpect(status().isNoContent());
    }

    @Test
    void pickupWaitsForTheStoreThenDeliverWithIdempotentRepeats() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        UUID deliveryId = deliveryId(order);

        step(rider, deliveryId, "deliver").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DELIVERY_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("ASSIGNED"));
        step(rider, deliveryId, "pickup").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_READY"))
                .andExpect(jsonPath("$.currentStatus").value("ASSIGNED"))
                .andExpect(jsonPath("$.status").value(409));

        readyForPickup(order);
        clock.set(T0.plusSeconds(600));
        step(rider, deliveryId, "pickup").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.readyForPickup").value(true))
                .andExpect(jsonPath("$.pickedUpAt").value(T0.plusSeconds(600).toString()));
        step(rider, deliveryId, "pickup").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED_UP"));
        JsonNode pickedUp = event(order, DeliveryEvents.PICKED_UP);
        assertThat(fieldNames(pickedUp)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "partnerId",
                "pickedUpAt");
        assertThat(pickedUp.get("partnerId").asString()).isEqualTo(rider.toString());
        assertThat(Instant.parse(pickedUp.get("pickedUpAt").asString())).isEqualTo(T0.plusSeconds(600));

        clock.set(T0.plusSeconds(1500));
        step(rider, deliveryId, "deliver").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveredAt").value(T0.plusSeconds(1500).toString()))
                .andExpect(jsonPath("$.timeline.length()").value(5));
        step(rider, deliveryId, "deliver").andExpect(status().isOk());
        JsonNode completed = event(order, DeliveryEvents.COMPLETED);
        assertThat(fieldNames(completed)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "partnerId",
                "deliveredAt");
        assertThat(eventTypes(order)).containsExactly(DeliveryEvents.OFFER_CREATED, DeliveryEvents.PARTNER_ASSIGNED,
                DeliveryEvents.PICKED_UP, DeliveryEvents.COMPLETED);

        // Finished: the partner is free (and may go offline); no further transitions
        mvc.perform(get("/api/delivery/deliveries/active").with(as(rider, PARTNER))).andExpect(status().isNoContent());
        assertThat(activeDelivery(rider)).isNull();
        step(rider, deliveryId, "pickup").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("DELIVERED"));
        fail(rider, deliveryId, "{\"reason\":\"CUSTOMER_REFUSED\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DELIVERY_TRANSITION"));
        availability(rider, false).andExpect(status().isOk()).andExpect(jsonPath("$.online").value(false));
    }

    @Test
    void aFailedDeliveryRecordsTheReason() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        UUID deliveryId = deliveryId(order);
        fail(rider, deliveryId, "{\"reason\":\"ADDRESS_NOT_FOUND\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("ASSIGNED"));
        readyForPickup(order);
        partnerDeliveries.pickUp(rider, deliveryId);

        fail(rider, deliveryId, "{\"reason\":\"OTHER\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        fail(rider, deliveryId, "{\"reason\":\"LOST\",\"note\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        fail(rider, deliveryId, "{\"reason\":\"OTHER\",\"note\":\"" + "x".repeat(301) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));

        clock.set(T0.plusSeconds(900));
        fail(rider, deliveryId, "{\"reason\":\"CUSTOMER_UNREACHABLE\",\"note\":\"Called three times\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.reason").value("CUSTOMER_UNREACHABLE"))
                .andExpect(jsonPath("$.failure.note").value("Called three times"))
                .andExpect(jsonPath("$.failedAt").value(T0.plusSeconds(900).toString()));
        fail(rider, deliveryId, "{\"reason\":\"OTHER\",\"note\":\"again\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.failure.reason").value("CUSTOMER_UNREACHABLE"));
        JsonNode failed = event(order, DeliveryEvents.FAILED);
        assertThat(fieldNames(failed)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "partnerId", "reason",
                "note", "failedAt");
        assertThat(failed.get("reason").asString()).isEqualTo("CUSTOMER_UNREACHABLE");
        assertThat(failed.get("note").asString()).isEqualTo("Called three times");
        assertThat(activeDelivery(rider)).isNull();
        step(rider, deliveryId, "deliver").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("FAILED"));
    }

    @Test
    void historyAndSummaryCountIndiaTimeDays() throws Exception {
        UUID rider = onlinePartnerAt("Ravi Kumar", at(0.5, 0));
        // T0 = 2026-11-10 12:00 India time
        Instant eightDaysAgo = T0.minus(Duration.ofDays(8));
        Instant threeDaysAgo = T0.minus(Duration.ofDays(3));
        Instant twoDaysAgo = T0.minus(Duration.ofDays(2));
        Instant justBeforeMidnight = Instant.parse("2026-11-09T18:20:00Z");    // 23:50 on Nov 9 in India
        Instant justAfterMidnight = Instant.parse("2026-11-09T18:40:00Z");     // 00:10 on Nov 10 in India
        UUID d1 = finish(rider, eightDaysAgo, false);
        UUID d2 = finish(rider, threeDaysAgo, false);
        UUID d3 = finish(rider, twoDaysAgo, true);
        UUID d4 = finish(rider, justBeforeMidnight, false);
        UUID d5 = finish(rider, justAfterMidnight, false);
        clock.set(T0);

        mvc.perform(get("/api/delivery/history/summary").with(as(rider, PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredToday").value(1))
                .andExpect(jsonPath("$.deliveredThisWeek").value(3))
                .andExpect(jsonPath("$.failedThisWeek").value(1));

        JsonNode page = body(mvc.perform(get("/api/delivery/history?size=3").with(as(rider, PARTNER)))
                .andExpect(status().isOk()));
        assertThat(page.get("totalElements").asInt()).isEqualTo(5);
        assertThat(page.get("content")).extracting(item -> item.get("deliveryId").asString())
                .containsExactly(d5.toString(), d4.toString(), d3.toString());
        JsonNode first = page.get("content").get(0);
        assertThat(fieldNames(first)).containsExactly("deliveryId", "orderNumber", "status", "store", "dropArea",
                "tripKm", "pickedUpAt", "finishedAt");
        assertThat(first.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(first.get("store").asString()).isEqualTo("Green Basket");
        assertThat(first.get("dropArea").asString()).isEqualTo("Near Metro, Bengaluru");
        assertThat(first.get("tripKm").asDouble()).isEqualTo(3.0);
        assertThat(Instant.parse(first.get("finishedAt").asString())).isEqualTo(justAfterMidnight);
        assertThat(Instant.parse(first.get("pickedUpAt").asString())).isEqualTo(justAfterMidnight.minusSeconds(600));
        assertThat(page.get("content").get(2).get("status").asString()).isEqualTo("FAILED");
        JsonNode second = body(mvc.perform(get("/api/delivery/history?page=1&size=3").with(as(rider, PARTNER))));
        assertThat(second.get("content")).extracting(item -> item.get("deliveryId").asString())
                .containsExactly(d2.toString(), d1.toString());

        // Another partner's history is their own
        UUID other = approvedPartner("Other Rider");
        mvc.perform(get("/api/delivery/history").with(as(other, PARTNER)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/delivery/history/summary").with(as(other, PARTNER)))
                .andExpect(jsonPath("$.deliveredToday").value(0));
    }

    @Test
    void historyFiltersByOutcomeAndByIndiaTimeDaysOfTheFinish() throws Exception {
        UUID rider = onlinePartnerAt("Kavin Raj", at(0.5, 0));
        // T0 = 2026-11-10 12:00 India time
        UUID nov2 = finish(rider, T0.minus(Duration.ofDays(8)), false);
        UUID nov7Failed = finish(rider, T0.minus(Duration.ofDays(3)), true);
        UUID nov9Late = finish(rider, Instant.parse("2026-11-09T18:20:00Z"), false);      // 23:50 on Nov 9 in India
        UUID nov10Early = finish(rider, Instant.parse("2026-11-09T18:40:00Z"), false);    // 00:10 on Nov 10 in India
        UUID nov10Failed = finish(rider, T0.minusSeconds(600), true);
        clock.set(T0);

        assertThat(historyIds(rider, "")).containsExactly(nov10Failed, nov10Early, nov9Late, nov7Failed, nov2);
        assertThat(historyIds(rider, "?status=FAILED")).containsExactly(nov10Failed, nov7Failed);
        assertThat(historyIds(rider, "?status=DELIVERED")).containsExactly(nov10Early, nov9Late, nov2);
        // Both days included, India time: Nov 10 starts at 18:30 UTC on Nov 9
        assertThat(historyIds(rider, "?from=2026-11-10&to=2026-11-10")).containsExactly(nov10Failed, nov10Early);
        assertThat(historyIds(rider, "?from=2026-11-09&to=2026-11-09")).containsExactly(nov9Late);
        assertThat(historyIds(rider, "?from=2026-11-07"))
                .containsExactly(nov10Failed, nov10Early, nov9Late, nov7Failed);
        assertThat(historyIds(rider, "?to=2026-11-07")).containsExactly(nov7Failed, nov2);
        assertThat(historyIds(rider, "?status=DELIVERED&from=2026-11-04&to=2026-11-10"))
                .containsExactly(nov10Early, nov9Late);
        assertThat(historyIds(rider, "?status=FAILED&from=2026-11-08&to=2026-11-09")).isEmpty();
        // The count and the pages follow the filters
        mvc.perform(get("/api/delivery/history?status=DELIVERED&from=2026-11-04&size=1").with(as(rider, PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        // The summary does not take filters: today and the last 7 days, as before
        mvc.perform(get("/api/delivery/history/summary?status=FAILED").with(as(rider, PARTNER)))
                .andExpect(jsonPath("$.deliveredToday").value(1))
                .andExpect(jsonPath("$.failedThisWeek").value(2));

        for (String query : List.of("status=ASSIGNED", "status=delivered", "status=LOST", "from=10-11-2026",
                "to=2026-13-01")) {
            mvc.perform(get("/api/delivery/history?" + query).with(as(rider, PARTNER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/delivery/history?from=2026-11-10&to=2026-11-09").with(as(rider, PARTNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("from"));
    }

    /** The delivery ids of the first page of {@code rider}'s history with {@code query}, newest first. */
    private List<UUID> historyIds(UUID rider, String query) throws Exception {
        JsonNode page = body(mvc.perform(get("/api/delivery/history" + query).with(as(rider, PARTNER)))
                .andExpect(status().isOk()));
        List<UUID> ids = new ArrayList<>();
        page.get("content").forEach(item -> ids.add(UUID.fromString(item.get("deliveryId").asString())));
        return ids;
    }

    /** One delivery by {@code rider} accepted 20 min and picked up 10 min before it ends at {@code finishedAt}. */
    private UUID finish(UUID rider, Instant finishedAt, boolean failed) {
        clock.set(finishedAt.minusSeconds(1200));
        report(rider, at(0.5, 0));
        Order order = searchingOrder();
        offers.accept(rider, offerOf(order, rider));
        readyForPickup(order);
        UUID deliveryId = deliveryId(order);
        clock.set(finishedAt.minusSeconds(600));
        partnerDeliveries.pickUp(rider, deliveryId);
        clock.set(finishedAt);
        if (failed) {
            partnerDeliveries.fail(rider, deliveryId, FailureReason.CUSTOMER_REFUSED, null);
        } else {
            partnerDeliveries.deliver(rider, deliveryId);
        }
        return deliveryId;
    }

    @Test
    void everyPartnerEndpointNeedsAnApprovedPartner() throws Exception {
        UUID draft = UUID.randomUUID();
        onboarding.updateMyProfile(new CurrentUser(draft, "d@example.com", "Draft", Set.of(PARTNER), null, null),
                new PartnerUpdateRequest("Draft Rider", null, null, null, null, null));
        UUID nobody = UUID.randomUUID();
        UUID suspended = approvedPartner("Suspended Sid");
        partnerAdmin.suspend(suspended, "No-shows", adminUser(admin));
        for (UUID user : List.of(draft, nobody, suspended)) {
            for (var request : List.of(
                    get("/api/delivery/partners/me/status"),
                    put("/api/delivery/partners/me/availability").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"online\":true}"),
                    put("/api/delivery/partners/me/location").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"latitude\":12.9,\"longitude\":77.6}"),
                    get("/api/delivery/offers"),
                    post("/api/delivery/offers/{id}/accept", UUID.randomUUID()),
                    post("/api/delivery/offers/{id}/reject", UUID.randomUUID()),
                    get("/api/delivery/deliveries/active"),
                    get("/api/delivery/deliveries/{id}", UUID.randomUUID()),
                    post("/api/delivery/deliveries/{id}/pickup", UUID.randomUUID()),
                    post("/api/delivery/deliveries/{id}/deliver", UUID.randomUUID()),
                    get("/api/delivery/history"),
                    get("/api/delivery/history/summary"))) {
                mvc.perform(request.with(as(user, PARTNER)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("PARTNER_NOT_ACTIVE"));
            }
        }
        // Not a delivery partner at all
        mvc.perform(get("/api/delivery/offers").with(as(UUID.randomUUID(), CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/delivery/offers")).andExpect(status().isUnauthorized());
    }

    @Test
    void availabilityAndLocationRequestsAreValidated() throws Exception {
        UUID rider = approvedPartner("Ravi Kumar");
        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"latitude\":12.9,\"longitude\":77.6}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PARTNER_OFFLINE"));
        availability(rider, true).andExpect(status().isOk());
        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"latitude\":91,\"longitude\":77.6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("latitude"));
        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"latitude\":12.9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("longitude"));
        mvc.perform(put("/api/delivery/partners/me/location").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":12.9,\"longitude\":77.6,\"heading\":361}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("heading"));
        mvc.perform(put("/api/delivery/partners/me/availability").with(as(rider, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("online"));
    }
}
