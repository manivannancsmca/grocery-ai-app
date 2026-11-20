package com.grocery.delivery;

import com.grocery.delivery.dispatch.entity.FailureReason;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/delivery/stats}: a partner's own dashboard statistics over India-time days — deliveries by outcome,
 * offers by answer, delivery times, distance, estimated earnings (25.00 + 6.00 per km by default) and online time.
 * T0 is 2026-11-10 12:00 in India.
 */
class PartnerStatsIntegrationTest extends DispatchTestSupport {

    @Test
    void countsDeliveriesOffersTimesDistanceAndEarningsOfTheChosenDays() throws Exception {
        UUID rider = onlinePartnerAt("Kavya Raman", at(0.5, 0));
        finish(rider, T0.minus(Duration.ofDays(2)), false);       // Nov 8: delivered
        finish(rider, T0.minus(Duration.ofDays(1)), true);        // Nov 9: not delivered
        finish(rider, T0.minus(Duration.ofHours(1)), false);      // Nov 10, 11:00: delivered
        finish(rider, T0.minus(Duration.ofDays(9)), false);       // Nov 1: outside the range

        // Today: one offer rejected, one accepted and then cancelled by support before the pickup.
        clock.set(T0.minusSeconds(300));
        report(rider, at(0.5, 0));
        Order rejected = searchingOrder();
        offers.reject(rider, offerOf(rejected, rider));
        Order cancelled = searchingOrder();
        offers.accept(rider, offerOf(cancelled, rider));
        orderCancelled(cancelled, "ACCEPTED");
        clock.set(T0);

        JsonNode stats = body(mvc.perform(get("/api/delivery/stats?from=2026-11-04&to=2026-11-10")
                .with(as(rider, PARTNER))).andExpect(status().isOk()));
        assertThat(stats.get("from").asString()).isEqualTo("2026-11-04");
        assertThat(stats.get("to").asString()).isEqualTo("2026-11-10");
        assertThat(stats.get("timezone").asString()).isEqualTo("Asia/Kolkata");

        JsonNode totals = stats.get("totals");
        assertThat(totals.get("assigned").asInt()).isEqualTo(4);
        assertThat(totals.get("delivered").asInt()).isEqualTo(2);
        assertThat(totals.get("failed").asInt()).isEqualTo(1);
        assertThat(totals.get("cancelled").asInt()).isEqualTo(1);
        assertThat(totals.get("inProgress").asInt()).isZero();
        assertThat(totals.get("offersReceived").asInt()).isEqualTo(5);
        assertThat(totals.get("offersAccepted").asInt()).isEqualTo(4);
        assertThat(totals.get("offersRejected").asInt()).isEqualTo(1);
        assertThat(totals.get("offersExpired").asInt()).isZero();
        assertThat(totals.get("successRate").asDouble()).isEqualTo(0.6667);
        assertThat(totals.get("acceptanceRate").asDouble()).isEqualTo(0.8);
        // Two trips of 3.0 km, each 25.00 + 6.00 × 3.0 = 43.00
        assertThat(totals.get("distanceKm").asDouble()).isEqualTo(6.0);
        assertThat(totals.get("earnings").decimalValue()).isEqualByComparingTo("86.00");
        // finish(): accepted 20 min and picked up 10 min before the end
        assertThat(totals.get("avgPickupMinutes").asDouble()).isEqualTo(10.0);
        assertThat(totals.get("avgRideMinutes").asDouble()).isEqualTo(10.0);
        assertThat(totals.get("avgTotalMinutes").asDouble()).isEqualTo(20.0);

        JsonNode days = stats.get("days");
        assertThat(days).hasSize(7);
        assertThat(days.get(0).get("date").asString()).isEqualTo("2026-11-04");
        JsonNode nov8 = days.get(4);
        assertThat(nov8.get("date").asString()).isEqualTo("2026-11-08");
        assertThat(nov8.get("delivered").asInt()).isEqualTo(1);
        assertThat(nov8.get("earnings").decimalValue()).isEqualByComparingTo("43.00");
        assertThat(nov8.get("distanceKm").asDouble()).isEqualTo(3.0);
        assertThat(days.get(5).get("failed").asInt()).isEqualTo(1);
        JsonNode nov10 = days.get(6);
        assertThat(nov10.get("delivered").asInt()).isEqualTo(1);
        assertThat(nov10.get("cancelled").asInt()).isEqualTo(1);

        assertThat(stats.get("payRate").get("basePerDelivery").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(stats.get("payRate").get("perKm").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(stats.get("payRate").get("currency").asString()).isEqualTo("INR");
    }

    @Test
    void onlineTimeIsSplitOverIndiaDaysAndALongSilenceIsNotCounted() throws Exception {
        UUID rider = approvedPartner("Nila Devi");
        GeoPoint here = at(0, 0);

        // Nov 9, 23:30 India time: online for an hour, reporting every minute, then offline at 00:30.
        Instant lateEvening = Instant.parse("2026-11-09T18:00:00Z");
        clock.set(lateEvening);
        availability.setOnline(rider, true);
        reportEveryMinute(rider, here, lateEvening, 60);
        availability.setOnline(rider, false);

        // Nov 10, 14:00: online, reports for 10 min, then the phone goes quiet; a report at 15:00 starts a new stretch,
        // offline at 15:20. The 50 silent minutes do not count.
        Instant afternoon = Instant.parse("2026-11-10T08:30:00Z");
        clock.set(afternoon);
        availability.setOnline(rider, true);
        reportEveryMinute(rider, here, afternoon, 10);
        clock.set(afternoon.plus(Duration.ofMinutes(60)));
        report(rider, here);
        clock.set(afternoon.plus(Duration.ofMinutes(80)));
        availability.setOnline(rider, false);
        clock.set(Instant.parse("2026-11-10T12:00:00Z"));

        JsonNode stats = body(mvc.perform(get("/api/delivery/stats?from=2026-11-09&to=2026-11-10")
                .with(as(rider, PARTNER))).andExpect(status().isOk()));
        assertThat(stats.get("days").get(0).get("onlineMinutes").asLong()).isEqualTo(30);
        assertThat(stats.get("days").get(1).get("onlineMinutes").asLong()).isEqualTo(30 + 10 + 20);
        assertThat(stats.get("totals").get("onlineMinutes").asLong()).isEqualTo(90);
        // Nothing delivered: no rates, no averages
        assertThat(stats.get("totals").get("successRate").isNull()).isTrue();
        assertThat(stats.get("totals").get("avgTotalMinutes").isNull()).isTrue();
    }

    @Test
    void eachPartnerSeesOnlyTheirOwnNumbersAndTheRangeIsChecked() throws Exception {
        UUID rider = onlinePartnerAt("Arun Kumar", at(0.5, 0));
        finish(rider, T0.minus(Duration.ofHours(2)), false);
        clock.set(T0);

        // Another partner: their own (empty) numbers; without dates, the last 7 days up to today.
        UUID other = approvedPartner("Other Rider");
        JsonNode theirs = body(mvc.perform(get("/api/delivery/stats").with(as(other, PARTNER)))
                .andExpect(status().isOk()));
        assertThat(theirs.get("from").asString()).isEqualTo("2026-11-04");
        assertThat(theirs.get("to").asString()).isEqualTo("2026-11-10");
        assertThat(theirs.get("totals").get("delivered").asInt()).isZero();
        assertThat(theirs.get("totals").get("earnings").decimalValue()).isEqualByComparingTo("0");
        mvc.perform(get("/api/delivery/stats").with(as(rider, PARTNER)))
                .andExpect(jsonPath("$.totals.delivered").value(1));

        // Not an approved partner, not a partner at all, bad dates, too long a range
        mvc.perform(get("/api/delivery/stats").with(as(UUID.randomUUID(), PARTNER))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PARTNER_NOT_ACTIVE"));
        mvc.perform(get("/api/delivery/stats").with(as(rider, CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/delivery/stats?from=yesterday").with(as(rider, PARTNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/delivery/stats?from=2026-11-10&to=2026-11-01").with(as(rider, PARTNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/delivery/stats?from=2026-08-01&to=2026-11-10").with(as(rider, PARTNER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    private void reportEveryMinute(UUID rider, GeoPoint at, Instant from, int minutes) {
        for (int minute = 1; minute <= minutes; minute++) {
            clock.set(from.plus(Duration.ofMinutes(minute)));
            report(rider, at);
        }
    }

    /** One delivery by {@code rider} accepted 20 min and picked up 10 min before it ends at {@code finishedAt}. */
    private void finish(UUID rider, Instant finishedAt, boolean failed) {
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
    }
}
