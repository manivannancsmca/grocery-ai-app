package com.grocery.notification.dispatch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 9 texts and helpers without a database. */
class DeliveryTemplatesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    @Test
    void theSecondsLeftToRespondAreRoundedUp() {
        assertThat(DeliveryEventHandler.secondsLeft(NOW.plusSeconds(30), NOW)).isEqualTo(30);
        assertThat(DeliveryEventHandler.secondsLeft(NOW.plusMillis(29_001), NOW)).isEqualTo(30);
        assertThat(DeliveryEventHandler.secondsLeft(NOW.plusMillis(1), NOW)).isEqualTo(1);
        assertThat(DeliveryEventHandler.secondsLeft(NOW, NOW)).isZero();
        assertThat(DeliveryEventHandler.secondsLeft(NOW.minusSeconds(3), NOW)).isZero();
        assertThat(DeliveryEventHandler.secondsLeft(null, NOW)).isZero();
    }

    @Test
    void anOfferShowsTheDistanceWithOneDecimal() {
        NotificationDraft offer = NotificationTemplates.deliveryOffer(UUID.randomUUID(), "GA-1", UUID.randomUUID(),
                UUID.randomUUID(), "  Spice Route ", new BigDecimal("0.95"), 30);
        assertThat(offer.title()).isEqualTo("New delivery from Spice Route");
        assertThat(offer.body()).isEqualTo("1.0 km away · respond within 30 s");
        assertThat(NotificationTemplates.deliveryOffer(UUID.randomUUID(), "GA-1", UUID.randomUUID(),
                UUID.randomUUID(), null, new BigDecimal("12"), 5).title()).isEqualTo("New delivery from a store");
    }

    @Test
    void customersSeeThePartnersFirstNameOnly() {
        assertThat(NotificationTemplates.firstName("  Ravi   Kumar ")).isEqualTo("Ravi");
        assertThat(NotificationTemplates.firstName("Meena")).isEqualTo("Meena");
        assertThat(NotificationTemplates.firstName(" ")).isNull();
        assertThat(NotificationTemplates.firstName(null)).isNull();
        assertThat(NotificationTemplates.partnerAssigned(UUID.randomUUID(), "GA-7", UUID.randomUUID(),
                "Ravi Kumar").title()).isEqualTo("Ravi will deliver order GA-7");
    }

    @Test
    void adminsReadTheFailureReasonAndThePartnersNote() {
        assertThat(NotificationTemplates.deliveryFailureReason("CUSTOMER_UNREACHABLE", null))
                .isEqualTo("Customer unreachable");
        assertThat(NotificationTemplates.deliveryFailureReason("ADDRESS_NOT_FOUND", "  No such house... "))
                .isEqualTo("Address not found: No such house");
        assertThat(NotificationTemplates.deliveryFailureReason("CUSTOMER_REFUSED", " "))
                .isEqualTo("Customer refused the order");
        assertThat(NotificationTemplates.deliveryFailureReason("OTHER", "Gate locked"))
                .isEqualTo("Other reason: Gate locked");
        assertThat(NotificationTemplates.deliveryFailureReason("FLAT_TYRE", null)).isEqualTo("FLAT_TYRE");
        assertThat(NotificationTemplates.deliveryFailureReason(null, null)).isEqualTo("Unknown reason");
    }
}
