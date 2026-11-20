package com.grocery.delivery;

import com.grocery.delivery.dispatch.event.DeliveryEvents;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin delivery monitor (contract §5): list and detail, reassignment with and without a partner, the online map. */
class AdminDeliveryIntegrationTest extends DispatchTestSupport {

    private ResultActions reassign(UUID deliveryId, String role, String body) throws Exception {
        var request = post("/api/admin/deliveries/{id}/reassign", deliveryId).with(as(admin, role));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static List<String> ids(JsonNode page) {
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(item -> item.get("deliveryId").asString()).toList();
    }

    @Test
    void theListFiltersAndShowsThePartnerAndTheSearch() throws Exception {
        Instant later = T0.plus(Duration.ofDays(10));                   // newer than every other test's delivery
        clock.set(later);
        UUID rider = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        Order assigned = searchingOrder();
        offers.accept(rider, offerOf(assigned, rider));
        clock.set(later.plusSeconds(1));
        Order failed = searchingOrder();                               // nobody else free: assignment failed
        clock.set(later.plusSeconds(2));
        Order pending = confirmedOrder();

        JsonNode page = body(mvc.perform(get("/api/admin/deliveries?size=3").with(as(admin, "SUPPORT")))
                .andExpect(status().isOk()));
        assertThat(ids(page)).containsExactly(deliveryId(pending).toString(), deliveryId(failed).toString(),
                deliveryId(assigned).toString());                     // newest first
        JsonNode item = StreamSupport.stream(page.get("content").spliterator(), false)
                .filter(node -> node.get("deliveryId").asString().equals(deliveryId(assigned).toString()))
                .findFirst().orElseThrow();
        assertThat(fieldNames(item)).containsExactly("deliveryId", "orderId", "orderNumber", "orderType", "status",
                "readyForPickup", "readyBy", "pickup", "drop", "note", "itemCount", "total", "currency", "assignedAt",
                "pickedUpAt", "deliveredAt", "failedAt", "failure", "timeline", "partnerId", "partnerName",
                "assignmentFailed", "offersSent");
        assertThat(item.get("partnerId").asString()).isEqualTo(rider.toString());
        assertThat(item.get("partnerName").asString()).isEqualTo("Ravi Kumar");
        assertThat(item.get("offersSent").asInt()).isEqualTo(1);
        assertThat(item.get("assignmentFailed").asBoolean()).isFalse();

        assertThat(ids(body(mvc.perform(get("/api/admin/deliveries?assignmentFailed=true&size=100")
                .with(as(admin, "OPS_ADMIN")))))).contains(deliveryId(failed).toString())
                .doesNotContain(deliveryId(assigned).toString(), deliveryId(pending).toString());
        assertThat(ids(body(mvc.perform(get("/api/admin/deliveries?status=PENDING&size=100")
                .with(as(admin, "FINANCE_ADMIN")))))).contains(deliveryId(pending).toString())
                .doesNotContain(deliveryId(failed).toString());
        assertThat(ids(body(mvc.perform(get("/api/admin/deliveries?partnerId=" + rider)
                .with(as(admin, "SUPPORT")))))).containsExactly(deliveryId(assigned).toString());
        String q = failed.orderNumber().substring(3).toLowerCase();
        assertThat(ids(body(mvc.perform(get("/api/admin/deliveries?q=" + q).with(as(admin, "SUPPORT"))))))
                .containsExactly(deliveryId(failed).toString());

        mvc.perform(get("/api/admin/deliveries?status=LOST").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/deliveries?partnerId=nope").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/deliveries").with(as(UUID.randomUUID(), CUSTOMER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/deliveries/{id}", UUID.randomUUID()).with(as(admin, "SUPPORT")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("DELIVERY_NOT_FOUND"));
    }

    @Test
    void theDetailListsEveryOffer() throws Exception {
        UUID one = onlinePartnerAt("One Rider", at(1.0, 0));
        UUID two = onlinePartnerAt("Two Rider", at(2.0, 0));
        Order order = searchingOrder();
        offers.reject(one, offerOf(order, one));
        clock.set(T0.plusSeconds(3));
        offers.accept(two, offerOf(order, two));

        JsonNode detail = body(mvc.perform(get("/api/admin/deliveries/{id}", deliveryId(order))
                .with(as(admin, "DELIVERY_MANAGER"))).andExpect(status().isOk()));
        assertThat(detail.get("partnerName").asString()).isEqualTo("Two Rider");
        assertThat(detail.get("offersSent").asInt()).isEqualTo(2);
        JsonNode offersNode = detail.get("offers");
        assertThat(offersNode).hasSize(2);
        assertThat(fieldNames(offersNode.get(0))).containsExactly("offerId", "partnerId", "partnerName", "distanceKm",
                "status", "sentAt", "closedAt");
        assertThat(offersNode).extracting(offer -> offer.get("partnerName").asString())
                .containsExactlyInAnyOrder("One Rider", "Two Rider");
        JsonNode accepted = StreamSupport.stream(offersNode.spliterator(), false)
                .filter(offer -> offer.get("partnerId").asString().equals(two.toString())).findFirst().orElseThrow();
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("distanceKm").asDouble()).isEqualTo(2.0);
        assertThat(Instant.parse(accepted.get("sentAt").asString())).isEqualTo(T0);
        assertThat(Instant.parse(accepted.get("closedAt").asString())).isEqualTo(T0.plusSeconds(3));
    }

    @Test
    void reassignToAChosenPartner() throws Exception {
        UUID first = onlinePartnerAt("First Rider", at(1.0, 0));
        Order order = searchingOrder();
        offers.accept(first, offerOf(order, first));
        UUID chosen = onlinePartnerAt("Chosen Rider", at(0, 5.0));
        Order elsewhere = searchingOrder();                            // the chosen partner holds an offer for it
        assertThat(offeredPartners(elsewhere)).containsExactly(chosen);
        UUID deliveryId = deliveryId(order);

        // Not allowed for every admin; not to an offline, busy, unapproved or unknown partner
        reassign(deliveryId, "SUPPORT", "{\"partnerId\":\"" + chosen + "\"}").andExpect(status().isForbidden());
        UUID offline = approvedPartner("Offline Olga");
        UUID draft = UUID.randomUUID();
        UUID busy = onlinePartnerAt("Busy Bee", at(0, -9));
        Order busyOrder = searchingOrder();
        offers.accept(busy, offerOf(busyOrder, busy));
        for (UUID unavailable : List.of(offline, draft, busy)) {
            reassign(deliveryId, "DELIVERY_MANAGER", "{\"partnerId\":\"" + unavailable + "\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PARTNER_UNAVAILABLE"));
        }
        assertThat(delivery(order)).containsEntry("partner_id", first.toString());

        clock.set(T0.plusSeconds(20));
        JsonNode view = body(reassign(deliveryId, "OPS_ADMIN",
                "{\"partnerId\":\"" + chosen + "\",\"reason\":\"First rider's scooter broke down\"}")
                .andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(view.get("partnerId").asString()).isEqualTo(chosen.toString());
        assertThat(view.get("partnerName").asString()).isEqualTo("Chosen Rider");
        assertThat(view.get("offers")).isNotNull();
        assertThat(activeDelivery(first)).isNull();
        assertThat(activeDelivery(chosen)).isEqualTo(deliveryId.toString());

        JsonNode unassigned = event(order, DeliveryEvents.PARTNER_UNASSIGNED);
        assertThat(fieldNames(unassigned)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "partnerId",
                "reason", "actorUserId", "unassignedAt");
        assertThat(unassigned.get("partnerId").asString()).isEqualTo(first.toString());
        assertThat(unassigned.get("reason").asString()).isEqualTo("First rider's scooter broke down");
        assertThat(unassigned.get("actorUserId").asString()).isEqualTo(admin.toString());
        assertThat(Instant.parse(unassigned.get("unassignedAt").asString())).isEqualTo(T0.plusSeconds(20));
        List<JsonNode> assigned = events(order, DeliveryEvents.PARTNER_ASSIGNED);
        assertThat(assigned).hasSize(2);
        assertThat(assigned.getLast().get("partnerId").asString()).isEqualTo(chosen.toString());
        assertThat(assigned.getLast().get("manual").asBoolean()).isTrue();
        // The chosen partner's other offer was withdrawn
        assertThat(offerRows(elsewhere)).extracting(OfferRow::status).containsExactly("WITHDRAWN");
        assertThat(event(elsewhere, DeliveryEvents.OFFER_CLOSED).get("outcome").asString()).isEqualTo("WITHDRAWN");

        // Again to the same partner: unchanged
        reassign(deliveryId, "SUPER_ADMIN", "{\"partnerId\":\"" + chosen + "\"}").andExpect(status().isOk());
        assertThat(events(order, DeliveryEvents.PARTNER_ASSIGNED)).hasSize(2);

        // Only SEARCHING or ASSIGNED deliveries can be reassigned
        readyForPickup(order);
        partnerDeliveries.pickUp(chosen, deliveryId);
        reassign(deliveryId, "DELIVERY_MANAGER", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DELIVERY_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"));
        Order pending = confirmedOrder();
        reassign(deliveryId(pending), "DELIVERY_MANAGER", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("PENDING"));
        reassign(UUID.randomUUID(), "DELIVERY_MANAGER", null).andExpect(status().isNotFound());
    }

    @Test
    void reassignWithoutAPartnerSearchesAgainWithoutTheFormerPartner() throws Exception {
        UUID nearest = onlinePartnerAt("Nearest Rider", at(0.5, 0));
        Order order = searchingOrder();
        offers.accept(nearest, offerOf(order, nearest));
        readyForPickup(order);
        UUID next = onlinePartnerAt("Next Rider", at(2.0, 0));
        UUID deliveryId = deliveryId(order);

        clock.set(T0.plusSeconds(40));
        JsonNode view = body(reassign(deliveryId, "DELIVERY_MANAGER", "{\"reason\":\"Not moving\"}")
                .andExpect(status().isOk()));
        assertThat(view.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(view.get("partnerId").isNull()).isTrue();
        assertThat(view.get("readyForPickup").asBoolean()).isTrue();
        assertThat(view.get("timeline")).extracting(entry -> entry.get("status").asString())
                .containsExactly("PENDING", "SEARCHING", "ASSIGNED", "SEARCHING");
        assertThat(activeDelivery(nearest)).isNull();
        assertThat(event(order, DeliveryEvents.PARTNER_UNASSIGNED).get("partnerId").asString())
                .isEqualTo(nearest.toString());
        // The former partner is free and nearest, but never offered this delivery again
        assertThat(offeredPartners(order)).containsExactly(nearest, next);
        mvc.perform(get("/api/delivery/deliveries/{id}", deliveryId).with(as(nearest, PARTNER)))
                .andExpect(status().isNotFound());

        offers.reject(next, offerOf(order, next));
        assertThat(offeredPartners(order)).containsExactly(nearest, next);
        assertThat(delivery(order)).containsEntry("assignment_failed", true);
        clock.set(T0.plusSeconds(101));
        report(nearest, at(0.5, 0));
        report(next, at(2.0, 0));
        poller.run();                                                  // the retry round: still not the former one
        assertThat(offeredPartners(order)).containsExactly(nearest, next, next);

        // A SEARCHING delivery searched again from scratch: the previous offers are withdrawn
        clock.set(T0.plusSeconds(102));
        JsonNode again = body(reassign(deliveryId, "SUPER_ADMIN", null).andExpect(status().isOk()));
        assertThat(again.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(offerRows(order)).extracting(OfferRow::status)
                .containsExactly("ACCEPTED", "REJECTED", "WITHDRAWN", "PENDING");
        assertThat(delivery(order)).containsEntry("assignment_failed", false);
    }

    @Test
    void theOnlinePartnerMap() throws Exception {
        UUID free = onlinePartnerAt("Free Rider", at(1.0, 0));
        UUID busy = onlinePartnerAt("Busy Rider", at(0.5, 0));
        Order order = searchingOrder();
        offers.accept(busy, offerOf(order, busy));
        UUID offline = approvedPartner("Offline Rider");

        JsonNode list = body(mvc.perform(get("/api/admin/delivery-partners/online").with(as(admin, "SUPPORT")))
                .andExpect(status().isOk()));
        List<String> partnerIds = StreamSupport.stream(list.spliterator(), false)
                .map(node -> node.get("partnerId").asString()).toList();
        assertThat(partnerIds).contains(free.toString(), busy.toString()).doesNotContain(offline.toString());
        JsonNode busyNode = StreamSupport.stream(list.spliterator(), false)
                .filter(node -> node.get("partnerId").asString().equals(busy.toString())).findFirst().orElseThrow();
        assertThat(fieldNames(busyNode)).containsExactly("partnerId", "name", "vehicleType", "latitude", "longitude",
                "locationAt", "busy", "activeDeliveryId");
        assertThat(busyNode.get("name").asString()).isEqualTo("Busy Rider");
        assertThat(busyNode.get("vehicleType").asString()).isEqualTo("SCOOTER");
        assertThat(busyNode.get("busy").asBoolean()).isTrue();
        assertThat(busyNode.get("activeDeliveryId").asString()).isEqualTo(deliveryId(order).toString());
        assertThat(busyNode.get("latitude").asDouble()).isEqualTo(at(0.5, 0).latitude());
        assertThat(Instant.parse(busyNode.get("locationAt").asString())).isEqualTo(T0);
        JsonNode freeNode = StreamSupport.stream(list.spliterator(), false)
                .filter(node -> node.get("partnerId").asString().equals(free.toString())).findFirst().orElseThrow();
        assertThat(freeNode.get("busy").asBoolean()).isFalse();
        assertThat(freeNode.get("activeDeliveryId").isNull()).isTrue();
        mvc.perform(get("/api/admin/delivery-partners/online").with(as(free, PARTNER)))
                .andExpect(status().isForbidden());
    }
}
