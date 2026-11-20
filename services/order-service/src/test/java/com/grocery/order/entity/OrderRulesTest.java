package com.grocery.order.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The status and payment rules of {@link CustomerOrder} without a database. */
class OrderRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private static CustomerOrder order(OrderType type) {
        OrderItem item = new OrderItem(UUID.randomUUID(), ItemType.PRODUCT, "Rice", "1 kg", null, "VEG", 2,
                new BigDecimal("50.00"), new BigDecimal("60.00"), new BigDecimal("100.00"));
        return CustomerOrder.create(new CustomerOrder.Draft(UUID.randomUUID(), "GA-00000001", UUID.randomUUID(), type,
                UUID.randomUUID(), "Store", "Bengaluru", 12.9716, 77.5946, List.of(item), 2, new BigDecimal("100.00"),
                new BigDecimal("120.00"),
                new BigDecimal("20.00"), new BigDecimal("25.00"), new BigDecimal("125.00"), null, null, null, null,
                NOW.plusSeconds(900)), NOW);
    }

    @Test
    void theHappyPathMovesThePaymentAlong() {
        CustomerOrder order = order(OrderType.GROCERY);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_STARTED);
        assertThat(order.getRefundedAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getFirstItemName()).isEqualTo("Rice");

        order.awaitPayment(NOW.plusSeconds(1));
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        order.paymentFailed("BAD_REQUEST_ERROR", "Declined", NOW.plusSeconds(2));
        assertThat(order.getLastFailureCode()).isEqualTo("BAD_REQUEST_ERROR");

        order.confirm(null, null, NOW.plusSeconds(3));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(order.getConfirmedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(order.getLastFailureCode()).isNull();
        assertThat(order.getHistory()).extracting(StatusChange::getStatus)
                .containsExactly(OrderStatus.CREATED, OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED);
    }

    @Test
    void transitionsOutOfOrderAreRefused() {
        CustomerOrder order = order(OrderType.GROCERY);
        assertThatThrownBy(() -> order.confirm(null, null, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.expirePayment(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.paymentFailed("X", "Y", NOW)).isInstanceOf(IllegalStateException.class);

        order.rejectOutOfStock(List.of(new RejectedItem(UUID.randomUUID(), "Rice", 2, 0)), NOW);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_REQUIRED);
        assertThat(order.getStatus().isCancellable()).isFalse();
        assertThatThrownBy(() -> order.cancel(CancellationReason.CUSTOMER_CANCELLED, Actor.CUSTOMER, null, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.awaitPayment(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingAPaidOrderMeansARefundAnUnpaidOneNeedsNoPayment() {
        CustomerOrder unpaid = order(OrderType.RESTAURANT);
        unpaid.awaitPayment(NOW);
        assertThat(unpaid.cancel(CancellationReason.CUSTOMER_CANCELLED, Actor.CUSTOMER, null, null, NOW))
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(unpaid.getPaymentStatus()).isEqualTo(PaymentStatus.NOT_REQUIRED);
        assertThat(unpaid.paymentInitiated(UUID.randomUUID(), NOW)).isFalse();
        assertThat(unpaid.lateCapture(UUID.randomUUID(), NOW)).isTrue();
        assertThat(unpaid.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);

        CustomerOrder paid = order(OrderType.RESTAURANT);
        paid.awaitPayment(NOW);
        paid.confirm(UUID.randomUUID(), NOW, NOW);
        assertThat(paid.refundCompleted(new BigDecimal("25"), false, NOW)).isTrue();
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        paid.cancel(CancellationReason.ADMIN_CANCELLED, Actor.ADMIN, UUID.randomUUID(), "Damaged", NOW);
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(paid.refundCompleted(new BigDecimal("10.00"), false, NOW)).isFalse();      // stale total
        assertThat(paid.refundCompleted(new BigDecimal("125.00"), true, NOW)).isTrue();
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paid.getRefundedAmount()).isEqualByComparingTo("125.00");
    }

    private static CustomerOrder confirmed() {
        CustomerOrder order = order(OrderType.RESTAURANT);
        order.awaitPayment(NOW);
        order.confirm(UUID.randomUUID(), NOW, NOW);
        return order;
    }

    @Test
    void theStoreMovesAConfirmedOrderToReady() {
        CustomerOrder order = confirmed();
        assertThat(order.getStatus().isCancellable()).isTrue();
        order.accept(20, NOW.plusSeconds(1_260), NOW.plusSeconds(60), NOW.plusSeconds(61));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(order.getReadyInMinutes()).isEqualTo(20);
        assertThat(order.getReadyBy()).isEqualTo(NOW.plusSeconds(1_260));
        assertThat(order.getAcceptedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(order.getUpdatedAt()).isEqualTo(NOW.plusSeconds(61));
        assertThat(order.getStatus().isCancellable()).isFalse();
        assertThat(order.getStatus().isAdminCancellable()).isTrue();

        order.startPreparing(NOW.plusSeconds(120), NOW.plusSeconds(121));
        assertThat(order.getPreparingAt()).isEqualTo(NOW.plusSeconds(120));
        order.readyForPickup(NOW.plusSeconds(900), NOW.plusSeconds(901));
        assertThat(order.getReadyAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(order.getStatus().isAdminCancellable()).isTrue();
        assertThat(order.getHistory()).extracting(StatusChange::getStatus).containsExactly(OrderStatus.CREATED,
                OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP);

        // an admin may still cancel it: the payment is refunded
        assertThat(order.cancel(CancellationReason.ADMIN_CANCELLED, Actor.ADMIN, UUID.randomUUID(), "x", NOW))
                .isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    @Test
    void storeTransitionsOutOfOrderAreRefused() {
        CustomerOrder created = order(OrderType.GROCERY);
        assertThatThrownBy(() -> created.accept(15, NOW, NOW, NOW)).isInstanceOf(IllegalStateException.class);

        CustomerOrder order = confirmed();
        assertThatThrownBy(() -> order.startPreparing(NOW, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.readyForPickup(NOW, NOW)).isInstanceOf(IllegalStateException.class);
        order.accept(15, NOW, NOW, NOW);
        assertThatThrownBy(() -> order.accept(15, NOW, NOW, NOW)).isInstanceOf(IllegalStateException.class);
        order.readyForPickup(NOW, NOW);                                       // ACCEPTED → READY_FOR_PICKUP directly
        assertThatThrownBy(() -> order.startPreparing(NOW, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.readyForPickup(NOW, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getHistory()).extracting(StatusChange::getStatus).doesNotContain(OrderStatus.PREPARING);
    }

    @Test
    void theCancellationRulesPerStatus() {
        assertThat(OrderStatus.values()).filteredOn(OrderStatus::isCancellable)
                .containsExactly(OrderStatus.CREATED, OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED);
        assertThat(OrderStatus.values()).filteredOn(OrderStatus::isAdminCancellable)
                .containsExactly(OrderStatus.CREATED, OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED,
                        OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP);
        assertThat(OrderStatus.values()).filteredOn(OrderStatus::isTerminal)
                .containsExactly(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED, OrderStatus.REJECTED,
                        OrderStatus.CANCELLED);
        // Phase 9: tracking from the store's acceptance until the order is delivered or fails
        assertThat(OrderStatus.values()).filteredOn(OrderStatus::isTrackable)
                .containsExactly(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP,
                        OrderStatus.OUT_FOR_DELIVERY);
    }

    private static CustomerOrder ready() {
        CustomerOrder order = confirmed();
        order.accept(20, NOW, NOW, NOW);
        order.readyForPickup(NOW, NOW);
        return order;
    }

    @Test
    void theDeliveryTakesAReadyOrderToTheCustomer() {
        CustomerOrder order = confirmed();
        assertThat(order.getStoreCity()).isEqualTo("Bengaluru");
        assertThat(order.getStoreLatitude()).isEqualTo(12.9716);
        assertThat(order.awaitsPickup()).isFalse();
        UUID ravi = UUID.randomUUID();
        assertThatThrownBy(() -> order.assignPartner(ravi, "Ravi", NOW, NOW))
                .isInstanceOf(IllegalStateException.class);                // not accepted yet

        order.accept(20, NOW, NOW, NOW);
        order.assignPartner(ravi, "Ravi Kumar", NOW.plusSeconds(10), NOW.plusSeconds(11));
        assertThat(order.getPartnerId()).isEqualTo(ravi);
        assertThat(order.getPartnerName()).isEqualTo("Ravi Kumar");
        assertThat(order.getPartnerAssignedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);       // no status change, no history entry
        assertThat(order.getHistory()).hasSize(4);
        assertThat(order.unassignPartner(UUID.randomUUID(), NOW)).isFalse(); // not the recorded partner
        assertThat(order.unassignPartner(ravi, NOW)).isTrue();
        assertThat(order.getPartnerId()).isNull();
        assertThat(order.getPartnerName()).isNull();
        assertThat(order.unassignPartner(ravi, NOW)).isFalse();             // nothing left to clear
        UUID meena = UUID.randomUUID();
        order.assignPartner(meena, "Meena", NOW.plusSeconds(20), NOW.plusSeconds(20));

        assertThatThrownBy(() -> order.pickUp(meena, NOW, NOW)).isInstanceOf(IllegalStateException.class);
        order.readyForPickup(NOW.plusSeconds(30), NOW.plusSeconds(30));
        order.pickUp(meena, NOW.plusSeconds(40), NOW.plusSeconds(41));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
        assertThat(order.getPickedUpAt()).isEqualTo(NOW.plusSeconds(40));
        assertThat(order.getPartnerName()).isEqualTo("Meena");
        assertThat(order.getStatus().isAdminCancellable()).isFalse();        // never after pickup
        assertThatThrownBy(() -> order.cancel(CancellationReason.ADMIN_CANCELLED, Actor.ADMIN, null, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.assignPartner(ravi, "Ravi", NOW, NOW))
                .isInstanceOf(IllegalStateException.class);

        order.deliver(meena, NOW.plusSeconds(900), NOW.plusSeconds(901));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.getHistory()).extracting(StatusChange::getStatus).endsWith(OrderStatus.READY_FOR_PICKUP,
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);
        assertThatThrownBy(() -> order.failDelivery(meena, "OTHER", "x", NOW, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedDeliveryKeepsThePaymentAndTheReason() {
        CustomerOrder order = ready();
        UUID partner = UUID.randomUUID();
        order.pickUp(partner, NOW, NOW);                                       // assignment missed: adopted, no name
        assertThat(order.getPartnerId()).isEqualTo(partner);
        assertThat(order.getPartnerName()).isNull();

        order.failDelivery(partner, "CUSTOMER_UNREACHABLE", "  " + "n".repeat(400) + " ", NOW.plusSeconds(60),
                NOW.plusSeconds(61));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(order.getDeliveryFailureReason()).isEqualTo("CUSTOMER_UNREACHABLE");
        assertThat(order.getDeliveryFailureNote()).hasSize(CustomerOrder.DELIVERY_FAILURE_NOTE_MAX);
        assertThat(order.getDeliveryFailedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);   // an admin decides on a refund
        assertThat(order.getHistory().getLast().getReason()).isEqualTo("CUSTOMER_UNREACHABLE");
        assertThat(order.getStatus().isCancellable()).isFalse();
        assertThat(order.getStatus().isAdminCancellable()).isFalse();
        assertThatThrownBy(() -> order.deliver(partner, NOW, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStoreRejectionCancelsByTheStoreAndKeepsALongNote() {
        CustomerOrder order = confirmed();
        String note = "OTHER: " + "n".repeat(400);
        assertThat(order.cancel(CancellationReason.STORE_REJECTED, Actor.STORE, UUID.randomUUID(), note, NOW))
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getCancelledBy()).isEqualTo(Actor.STORE);
        assertThat(order.getCancellationNote()).hasSize(CustomerOrder.CANCELLATION_NOTE_MAX);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(order.getHistory().getLast().getReason()).isEqualTo("STORE_REJECTED");
        assertThatThrownBy(() -> order.cancel(CancellationReason.ADMIN_CANCELLED, Actor.ADMIN, null, null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredPaymentCancelsByTheSystem() {
        CustomerOrder order = order(OrderType.GROCERY);
        order.awaitPayment(NOW);
        assertThat(order.expirePayment(NOW.plusSeconds(900))).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(order.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_TIMEOUT);
        assertThat(order.getCancelledBy()).isEqualTo(Actor.SYSTEM);
        assertThat(order.getHistory().getLast().getReason()).isEqualTo("PAYMENT_TIMEOUT");
    }
}
