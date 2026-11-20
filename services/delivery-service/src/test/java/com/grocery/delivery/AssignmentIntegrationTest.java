package com.grocery.delivery;

import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.dispatch.geo.PartnerGeoIndex;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assignment rules of contract §2: who is available, waves of the nearest partners, radius steps, expiry and
 * rejection moving to the next wave, assignmentFailed with a single event and the retry.
 */
class AssignmentIntegrationTest extends DispatchTestSupport {

    @Test
    void settingsHaveTheContractDefaults() {
        assertThat(dispatch.offerTtl()).isEqualTo(Duration.ofSeconds(30));          // DELIVERY_OFFER_TTL
        assertThat(dispatch.offerBatchSize()).isEqualTo(2);                         // DELIVERY_OFFER_BATCH_SIZE
        assertThat(dispatch.searchRadiiKm()).containsExactly(3.0, 6.0, 10.0);       // DELIVERY_SEARCH_RADII_KM
        assertThat(dispatch.pollInterval()).isEqualTo(Duration.ofSeconds(5));       // DELIVERY_POLL_INTERVAL
        assertThat(dispatch.retryInterval()).isEqualTo(Duration.ofSeconds(60));     // DELIVERY_RETRY_INTERVAL
        assertThat(dispatch.locationStaleAfter()).isEqualTo(Duration.ofSeconds(60)); // DELIVERY_LOCATION_STALE_AFTER
        assertThat(dispatch.locationMinInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(dispatch.zone().getId()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void theTwoNearestAvailablePartnersGetTheFirstWave() {
        // A partner on another delivery
        UUID busy = onlinePartnerAt("Busy Bee", at(0.3, 0));
        Order first = searchingOrder();
        offers.accept(busy, offerOf(first, busy));
        // A partner holding an open offer for another delivery
        UUID holder = onlinePartnerAt("Holder Hari", at(0.7, 0));
        Order second = searchingOrder();
        assertThat(offeredPartners(second)).containsExactly(holder);
        // A partner whose last location is older than 60 s (still in the GEO set)
        clock.set(T0.minusSeconds(61));
        UUID stale = onlinePartnerAt("Stale Sam", at(0.5, 0));
        clock.set(T0);
        // A partner who went offline, and one who was suspended while online
        UUID offline = onlinePartnerAt("Offline Olga", at(0.4, 0));
        availability.setOnline(offline, false);
        UUID suspended = onlinePartnerAt("Suspended Sid", at(0.6, 0));
        partnerAdmin.suspend(suspended, "Three no-shows", adminUser(admin));
        // The available ones, and one outside every radius
        UUID near = onlinePartnerAt("Ravi Kumar", at(1.0, 0));
        UUID mid = onlinePartnerAt("Meena Rao", at(2.0, 0));
        UUID far = onlinePartnerAt("Farah Khan", at(2.5, 0));
        UUID outside = onlinePartnerAt("Outer Otto", at(12.0, 0));
        assertThat(redis.opsForGeo().position(PartnerGeoIndex.KEY, stale.toString())).doesNotContainNull();
        assertThat(redis.opsForGeo().position(PartnerGeoIndex.KEY, offline.toString())).containsOnlyNulls();

        Order order = searchingOrder();

        List<OfferRow> rows = offerRows(order);
        assertThat(rows).extracting(OfferRow::partnerId).containsExactly(near, mid);       // batch size 2, nearest first
        assertThat(rows).extracting(OfferRow::status).containsOnly("PENDING");
        assertThat(rows.get(0).distanceKm()).isCloseTo(1.0, within(0.001));
        assertThat(rows.get(1).distanceKm()).isCloseTo(2.0, within(0.001));
        assertThat(rows).extracting(OfferRow::wave).containsOnly(1);
        assertThat(offeredPartners(order)).doesNotContain(busy, holder, stale, offline, suspended, far, outside);
        Map<String, Object> delivery = delivery(order);
        assertThat(delivery).containsEntry("status", "SEARCHING").containsEntry("search_radius_index", 0)
                .containsEntry("offers_sent", 2).containsEntry("search_wave", 1).containsEntry("search_round", 1);

        assertThat(eventTypes(order)).containsExactly(DeliveryEvents.OFFER_CREATED, DeliveryEvents.OFFER_CREATED);
        assertThat(outboxRows(order)).allSatisfy(row -> assertThat(row)
                .containsEntry("topic", "delivery.events").containsEntry("aggregate_type", "Delivery")
                .containsEntry("aggregate_id", order.orderId().toString()));
        JsonNode created = events(order, DeliveryEvents.OFFER_CREATED).getFirst();
        assertThat(fieldNames(created)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "offerId",
                "partnerId", "storeName", "distanceKm", "tripKm", "expiresAt");
        assertThat(created.get("deliveryId").asString()).isEqualTo(delivery.get("id"));
        assertThat(created.get("orderId").asString()).isEqualTo(order.orderId().toString());
        assertThat(created.get("orderNumber").asString()).isEqualTo(order.orderNumber());
        assertThat(created.get("offerId").asString()).isEqualTo(rows.get(0).offerId().toString());
        assertThat(created.get("partnerId").asString()).isEqualTo(near.toString());
        assertThat(created.get("storeName").asString()).isEqualTo("Green Basket");
        assertThat(created.get("distanceKm").asDouble()).isEqualTo(1.0);
        assertThat(created.get("tripKm").asDouble()).isEqualTo(3.0);
        assertThat(Instant.parse(created.get("expiresAt").asString())).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    void anEmptyRadiusMovesTheSearchToTheNextOne() {
        UUID fourKm = onlinePartnerAt("Four Km", at(4.0, 0));
        UUID eightKm = onlinePartnerAt("Eight Km", at(0, 8.0));
        Order order = searchingOrder();

        // Nobody within 3 km: the 6 km radius
        assertThat(offeredPartners(order)).containsExactly(fourKm);
        assertThat(delivery(order)).containsEntry("search_radius_index", 1);
        assertThat(offerRows(order).getFirst().distanceKm()).isCloseTo(4.0, within(0.001));

        // Rejected: nobody else within 6 km, so 10 km
        offers.reject(fourKm, offerOf(order, fourKm));
        assertThat(offeredPartners(order)).containsExactly(fourKm, eightKm);
        assertThat(delivery(order)).containsEntry("search_radius_index", 2).containsEntry("search_wave", 2);

        // Rejected too: nobody left inside the largest radius
        offers.reject(eightKm, offerOf(order, eightKm));
        assertThat(delivery(order)).containsEntry("status", "SEARCHING").containsEntry("assignment_failed", true)
                .containsEntry("search_radius_index", 3);
        JsonNode failed = event(order, DeliveryEvents.ASSIGNMENT_FAILED);
        assertThat(fieldNames(failed)).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "attempts",
                "radiusKm", "failedAt");
        assertThat(failed.get("attempts").asInt()).isEqualTo(2);
        assertThat(failed.get("radiusKm").asDouble()).isEqualTo(10.0);
        assertThat(Instant.parse(failed.get("failedAt").asString())).isEqualTo(T0);
        assertThat(eventTypes(order)).containsExactly(DeliveryEvents.OFFER_CREATED, DeliveryEvents.OFFER_CLOSED,
                DeliveryEvents.OFFER_CREATED, DeliveryEvents.OFFER_CLOSED, DeliveryEvents.ASSIGNMENT_FAILED);
    }

    @Test
    void expiredOffersAreClosedAndTheNextWaveGoesToTheNextNearest() throws Exception {
        UUID first = onlinePartnerAt("First", at(1.0, 0));
        UUID second = onlinePartnerAt("Second", at(0, 2.0));
        UUID third = onlinePartnerAt("Third", at(-2.5, 0));
        Order order = searchingOrder();
        assertThat(offeredPartners(order)).containsExactly(first, second);

        clock.set(T0.plusSeconds(29));
        poller.run();
        assertThat(offerRows(order)).extracting(OfferRow::status).containsOnly("PENDING");

        clock.set(T0.plusSeconds(30));
        poller.run();
        List<OfferRow> rows = offerRows(order);
        assertThat(rows).extracting(OfferRow::partnerId).containsExactly(first, second, third);
        assertThat(rows).extracting(OfferRow::status).containsExactly("EXPIRED", "EXPIRED", "PENDING");
        assertThat(rows.get(2).wave()).isEqualTo(2);
        List<JsonNode> closed = events(order, DeliveryEvents.OFFER_CLOSED);
        assertThat(closed).hasSize(2);
        assertThat(fieldNames(closed.getFirst())).containsExactlyInAnyOrder("deliveryId", "orderId", "orderNumber", "offerId",
                "partnerId", "outcome", "closedAt");
        assertThat(closed).extracting(node -> node.get("outcome").asString()).containsOnly("EXPIRED");
        assertThat(closed).extracting(node -> node.get("partnerId").asString())
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(Instant.parse(closed.getFirst().get("closedAt").asString())).isEqualTo(T0.plusSeconds(30));

        // Too late: the expired offer cannot be accepted
        mvc.perform(post("/api/delivery/offers/{id}/accept", offerOf(order, first)).with(as(first, PARTNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OFFER_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.currentStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.status").value(409));

        // An offer accepted after its expiry but before the poller ran: closed as EXPIRED, the search moves on
        UUID fourth = onlinePartnerAt("Fourth", at(0, -2.8));
        clock.set(T0.plusSeconds(61));
        mvc.perform(post("/api/delivery/offers/{id}/accept", offerOf(order, third)).with(as(third, PARTNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("EXPIRED"));
        assertThat(offerRows(order)).extracting(OfferRow::status)
                .containsExactly("EXPIRED", "EXPIRED", "EXPIRED", "PENDING");
        assertThat(offeredPartners(order).getLast()).isEqualTo(fourth);
    }

    @Test
    void aRejectionStartsTheNextWaveOnlyWhenTheWholeWaveIsClosed() throws Exception {
        UUID first = onlinePartnerAt("First", at(1.0, 0));
        UUID second = onlinePartnerAt("Second", at(2.0, 0));
        UUID third = onlinePartnerAt("Third", at(2.9, 0));
        Order order = searchingOrder();

        mvc.perform(post("/api/delivery/offers/{id}/reject", offerOf(order, first)).with(as(first, PARTNER)))
                .andExpect(status().isNoContent());
        assertThat(offerRows(order)).extracting(OfferRow::status).containsExactly("REJECTED", "PENDING");
        // Idempotent
        mvc.perform(post("/api/delivery/offers/{id}/reject", offerOf(order, first)).with(as(first, PARTNER)))
                .andExpect(status().isNoContent());
        assertThat(events(order, DeliveryEvents.OFFER_CLOSED)).hasSize(1);
        assertThat(event(order, DeliveryEvents.OFFER_CLOSED).get("outcome").asString()).isEqualTo("REJECTED");

        offers.reject(second, offerOf(order, second));
        assertThat(offeredPartners(order)).containsExactly(first, second, third);
        assertThat(offerRows(order).getLast().status()).isEqualTo("PENDING");

        // The first partner is not offered this delivery again in this round
        offers.reject(third, offerOf(order, third));
        assertThat(offeredPartners(order)).containsExactly(first, second, third);
        assertThat(delivery(order)).containsEntry("assignment_failed", true);
    }

    @Test
    void afterAFailedSearchItIsRetriedEveryIntervalIncludingPartnersOfferedBefore() {
        Order order = searchingOrder();                         // nobody online at all
        assertThat(delivery(order)).containsEntry("assignment_failed", true).containsEntry("offers_sent", 0);
        assertThat(event(order, DeliveryEvents.ASSIGNMENT_FAILED).get("attempts").asInt()).isZero();

        clock.set(T0.plusSeconds(30));
        poller.run();
        UUID rider = onlinePartnerAt("Late Rider", at(1.0, 0));
        clock.set(T0.plusSeconds(59));
        poller.run();
        assertThat(offerRows(order)).isEmpty();                // the retry is not due yet

        clock.set(T0.plusSeconds(60));
        poller.run();
        assertThat(offerRows(order)).extracting(OfferRow::partnerId, OfferRow::round).containsExactly(
                tuple(rider, 2));
        assertThat(delivery(order)).containsEntry("assignment_failed", true);

        // Rejected: the round is exhausted again — no second DeliveryAssignmentFailed
        clock.set(T0.plusSeconds(65));
        offers.reject(rider, offerOf(order, rider));
        assertThat(delivery(order)).containsEntry("search_radius_index", 3);
        assertThat(events(order, DeliveryEvents.ASSIGNMENT_FAILED)).hasSize(1);

        // The next retry offers the same partner again (fresh location needed)
        clock.set(T0.plusSeconds(110));
        report(rider, at(1.0, 0));
        clock.set(T0.plusSeconds(124));
        poller.run();
        assertThat(offerRows(order)).hasSize(1);
        clock.set(T0.plusSeconds(125));
        poller.run();
        List<OfferRow> rows = offerRows(order);
        assertThat(rows).extracting(OfferRow::partnerId).containsExactly(rider, rider);
        assertThat(rows.getLast().round()).isEqualTo(3);

        offers.accept(rider, rows.getLast().offerId());
        assertThat(delivery(order)).containsEntry("status", "ASSIGNED").containsEntry("assignment_failed", false)
                .containsEntry("partner_id", rider.toString());
        assertThat(events(order, DeliveryEvents.ASSIGNMENT_FAILED)).hasSize(1);
    }

    @Test
    void aPartnerHoldsAtMostOneOpenOfferAndAnAcceptedPartnerIsNoLongerOffered() {
        UUID only = onlinePartnerAt("Only One", at(1.0, 0));
        Order first = searchingOrder();
        Order second = searchingOrder();
        assertThat(offeredPartners(first)).containsExactly(only);
        assertThat(offeredPartners(second)).isEmpty();
        assertThat(delivery(second)).containsEntry("assignment_failed", true);

        offers.accept(only, offerOf(first, only));
        clock.set(T0.plusSeconds(61));
        report(only, at(1.0, 0));
        poller.run();                                          // the second delivery's retry: the partner is busy
        assertThat(offeredPartners(second)).isEmpty();
        assertThat(activeDelivery(only)).isEqualTo(delivery(first).get("id"));
    }
}
