package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.web.ConflictException;
import com.grocery.common.web.NotFoundException;
import com.grocery.inventory.entity.ReservationStatus;
import com.grocery.inventory.service.ReservationFailedException;
import com.grocery.inventory.service.ReservationService;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import com.grocery.inventory.service.ReservationService.ReservationView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** reserve / release / commit maths, idempotency, ledger and events (single-threaded). */
class ReservationIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Autowired
    ReservationService reservations;

    private List<String> availability(UUID productId) {
        return events(productId, "ProductAvailabilityChanged").stream()
                .map(e -> e.get("inStock").asBoolean() + "@" + e.get("stockVersion").asLong())
                .toList();
    }

    @Test
    void reserveReleaseAndCommitKeepOnHandAndReservedRight() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);                       // threshold 5
        UUID salt = newProductWithStock(vendorId, "Salt", 4);                        // already low
        UUID orderA = Ids.newId();

        ReservationView a = reservations.reserve(orderA,
                List.of(new ReservationLine(rice, 2), new ReservationLine(salt, 4), new ReservationLine(rice, 1)), TTL);
        assertThat(a.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(a.orderId()).isEqualTo(orderA);
        assertThat(a.expiresAt()).isAfter(java.time.Instant.now().plus(Duration.ofMinutes(14)));
        assertThat(a.items()).containsExactlyInAnyOrder(new ReservationLine(rice, 3), new ReservationLine(salt, 4));
        JsonNode riceView = stock(vendorId, rice);
        assertThat(riceView.get("onHand").asInt()).isEqualTo(10);
        assertThat(riceView.get("reserved").asInt()).isEqualTo(3);
        assertThat(riceView.get("available").asInt()).isEqualTo(7);
        assertThat(reserved(salt)).isEqualTo(4);
        assertThat(availability(salt)).containsExactly("true@1", "false@2");        // all of it is held
        assertThat(events(rice, "LowStockDetected")).isEmpty();                     // 10 → 7 stays above 5
        assertThat(events(rice, "StockAdjusted")).hasSize(1);                       // reservations are not adjustments

        // All or nothing: one short product fails the whole reservation and changes nothing
        UUID orderB = Ids.newId();
        long riceVersion = stockVersion(rice);
        assertThatThrownBy(() -> reservations.reserve(orderB,
                List.of(new ReservationLine(rice, 1), new ReservationLine(salt, 1), new ReservationLine(Ids.newId(), 1)), TTL))
                .isInstanceOfSatisfying(ReservationFailedException.class, e -> {
                    assertThat(e.orderId()).isEqualTo(orderB);
                    assertThat(e.errorCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(e.unavailableItems()).hasSize(2);
                    assertThat(e.unavailableItems().getFirst().productId()).isEqualTo(salt);
                    assertThat(e.unavailableItems().getFirst().requested()).isEqualTo(1);
                    assertThat(e.unavailableItems().getFirst().available()).isZero();
                });
        assertThat(reserved(rice)).isEqualTo(3);
        assertThat(stockVersion(rice)).isEqualTo(riceVersion);
        assertThat(reservations.find(orderB)).isEmpty();

        // Same order again (redelivery): the existing reservation, nothing held twice
        ReservationView again = reservations.reserve(orderA, List.of(new ReservationLine(rice, 3)), TTL);
        assertThat(again.reservationId()).isEqualTo(a.reservationId());
        assertThat(reserved(rice)).isEqualTo(3);

        // Release gives everything back, once
        ReservationView released = reservations.release(orderA).orElseThrow();
        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reserved(rice)).isZero();
        assertThat(reserved(salt)).isZero();
        assertThat(onHand(rice)).isEqualTo(10);
        assertThat(availability(salt)).containsExactly("true@1", "false@2", "true@3");
        long afterRelease = stockVersion(rice);
        assertThat(reservations.release(orderA).orElseThrow().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(stockVersion(rice)).isEqualTo(afterRelease);
        assertThat(reservations.release(Ids.newId())).isEmpty();                    // nothing reserved: no-op
        // A released order is not reserved again by a redelivered OrderCreated
        assertThat(reservations.reserve(orderA, List.of(new ReservationLine(rice, 3)), TTL).status())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reserved(rice)).isZero();
        assertThatThrownBy(() -> reservations.commit(orderA))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("RESERVATION_RELEASED"));

        // Commit removes the held stock from on hand and reserved
        UUID orderC = Ids.newId();
        reservations.reserve(orderC, List.of(new ReservationLine(rice, 6)), TTL);    // 10 → 4: low
        assertThat(events(rice, "LowStockDetected")).singleElement()
                .satisfies(e -> assertThat(e.get("available").asInt()).isEqualTo(4));
        ReservationView committed = reservations.commit(orderC);
        assertThat(committed.status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(onHand(rice)).isEqualTo(4);
        assertThat(reserved(rice)).isZero();
        assertThat(stock(vendorId, rice).get("available").asInt()).isEqualTo(4);
        long afterCommit = stockVersion(rice);
        assertThat(reservations.commit(orderC).status()).isEqualTo(ReservationStatus.COMMITTED);   // idempotent
        assertThat(reservations.release(orderC).orElseThrow().status())                            // no-op
                .isEqualTo(ReservationStatus.COMMITTED);
        assertThat(stockVersion(rice)).isEqualTo(afterCommit);
        assertThat(reserved(rice)).isZero();
        assertThatThrownBy(() -> reservations.commit(Ids.newId()))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("RESERVATION_NOT_FOUND"));

        // The vendor's ledger shows every step, newest first
        JsonNode movements = body(mvc.perform(get("/api/vendor/inventory/{id}/movements", rice).with(vendor(vendorId))));
        assertThat(movements.get("content")).extracting(m -> m.get("type").asString())
                .containsExactly("COMMIT", "RESERVE", "RELEASE", "RESERVE", "INCREASE");
        JsonNode commit = movements.get("content").get(0);
        assertThat(commit.get("quantityDelta").asInt()).isEqualTo(-6);
        assertThat(commit.get("onHandAfter").asInt()).isEqualTo(4);
        assertThat(commit.get("reservedAfter").asInt()).isZero();
        assertThat(commit.get("reference").asString()).isEqualTo(orderC.toString());
        assertThat(commit.get("actorUserId").isNull()).isTrue();
        assertThat(commit.get("reason").isNull()).isTrue();
        JsonNode reserve = movements.get("content").get(3);
        assertThat(reserve.get("quantityDelta").asInt()).isZero();
        assertThat(reserve.get("reservedAfter").asInt()).isEqualTo(3);
        assertThat(reserve.get("reference").asString()).isEqualTo(orderA.toString());
        // stock_version: 1 increase + reserve + release + reserve + commit
        assertThat(stockVersion(rice)).isEqualTo(5);
    }

    @Test
    void reservationInputIsChecked() {
        UUID productId = newProduct(vendorId, "Sugar");
        assertThatThrownBy(() -> reservations.reserve(Ids.newId(), List.of(), TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.reserve(Ids.newId(), List.of(new ReservationLine(productId, 0)), TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.reserve(Ids.newId(), List.of(new ReservationLine(productId, 1)), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
