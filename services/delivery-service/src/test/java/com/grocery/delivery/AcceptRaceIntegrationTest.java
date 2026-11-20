package com.grocery.delivery;

import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliveryView;
import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import com.grocery.delivery.dispatch.service.DispatchException;
import com.grocery.delivery.dispatch.service.DispatchPoller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract §2 "Accept locks the delivery row: exactly one accept wins; every other accept of that delivery gets 409
 * OFFER_NOT_AVAILABLE (currentStatus = the offer's status)" — and the poller racing an accept.
 */
class AcceptRaceIntegrationTest extends DispatchTestSupport {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    /** Runs {@code task} once {@code start} opens; an expected DispatchException is the outcome instead of thrown. */
    private Callable<Object> outcome(CountDownLatch start, Callable<Object> task) {
        return () -> {
            start.await();
            try {
                return task.call();
            } catch (DispatchException e) {
                return e;
            }
        };
    }

    private void newPlace() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        base = new GeoPoint(random.nextDouble(-55, 55), random.nextDouble(-170, 170));
    }

    @Test
    void twoPartnersAcceptingAtTheSameMomentHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 6; round++) {
            newPlace();
            UUID one = onlinePartnerAt("One " + round, at(1.0, 0));
            UUID two = onlinePartnerAt("Two " + round, at(0, 1.5));
            Order order = searchingOrder();
            assertThat(offeredPartners(order)).containsExactlyInAnyOrder(one, two);
            UUID offerOne = offerOf(order, one);
            UUID offerTwo = offerOf(order, two);

            CountDownLatch start = new CountDownLatch(1);
            Future<Object> first = executor.submit(outcome(start, () -> offers.accept(one, offerOne)));
            Future<Object> second = executor.submit(outcome(start, () -> offers.accept(two, offerTwo)));
            start.countDown();
            List<Object> results = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

            List<DeliveryView> winners = results.stream().filter(DeliveryView.class::isInstance)
                    .map(DeliveryView.class::cast).toList();
            List<DispatchException> losers = results.stream().filter(DispatchException.class::isInstance)
                    .map(DispatchException.class::cast).toList();
            assertThat(winners).as("round %d", round).hasSize(1);
            assertThat(losers).as("round %d", round).hasSize(1);
            assertThat(winners.getFirst().status()).isEqualTo("ASSIGNED");
            assertThat(losers.getFirst().errorCode()).isEqualTo("OFFER_NOT_AVAILABLE");
            assertThat(losers.getFirst().properties()).containsEntry("currentStatus", "WITHDRAWN");

            UUID winner = results.get(0) instanceof DeliveryView ? one : two;
            UUID loser = winner.equals(one) ? two : one;
            assertThat(delivery(order)).containsEntry("status", "ASSIGNED")
                    .containsEntry("partner_id", winner.toString());
            assertThat(activeDelivery(winner)).isEqualTo(delivery(order).get("id"));
            assertThat(activeDelivery(loser)).isNull();
            assertThat(offerRows(order)).filteredOn(row -> row.partnerId().equals(winner))
                    .extracting(OfferRow::status).containsExactly("ACCEPTED");
            assertThat(offerRows(order)).filteredOn(row -> row.partnerId().equals(loser))
                    .extracting(OfferRow::status).containsExactly("WITHDRAWN");
            assertThat(events(order, DeliveryEvents.PARTNER_ASSIGNED)).hasSize(1);
            assertThat(event(order, DeliveryEvents.PARTNER_ASSIGNED).get("partnerId").asString())
                    .isEqualTo(winner.toString());
            assertThat(event(order, DeliveryEvents.OFFER_CLOSED).get("outcome").asString()).isEqualTo("WITHDRAWN");
            assertThat(event(order, DeliveryEvents.OFFER_CLOSED).get("partnerId").asString())
                    .isEqualTo(loser.toString());
        }
    }

    @Test
    void anAcceptRacingTheExpiryPollerEitherWinsOrFindsTheOfferExpired() throws Exception {
        for (int round = 0; round < 6; round++) {
            newPlace();
            clock.set(T0);
            UUID rider = onlinePartnerAt("Racer " + round, at(1.0, 0));
            Order order = searchingOrder();
            UUID offer = offerOf(order, rider);
            // The rider is still in time on the service clock; the poller already runs at the expiry
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> accept = executor.submit(outcome(start, () -> offers.accept(rider, offer)));
            DispatchPoller expiryPoller = new DispatchPoller(engine, dispatch,
                    Clock.fixed(T0.plusSeconds(30), ZoneOffset.UTC));
            Future<Object> poll = executor.submit(outcome(start, expiryPoller::run));
            start.countDown();
            Object accepted = accept.get(60, TimeUnit.SECONDS);
            poll.get(60, TimeUnit.SECONDS);

            if (accepted instanceof DeliveryView view) {
                assertThat(view.status()).isEqualTo("ASSIGNED");
                assertThat(offerRows(order)).extracting(OfferRow::status).containsExactly("ACCEPTED");
                assertThat(eventTypes(order)).containsExactly(DeliveryEvents.OFFER_CREATED,
                        DeliveryEvents.PARTNER_ASSIGNED);
            } else {
                assertThat(((DispatchException) accepted).properties()).containsEntry("currentStatus", "EXPIRED");
                assertThat(offerRows(order).getFirst().status()).isEqualTo("EXPIRED");
                assertThat(delivery(order)).containsEntry("status", "SEARCHING");
                assertThat(events(order, DeliveryEvents.PARTNER_ASSIGNED)).isEmpty();
            }
        }
    }
}
