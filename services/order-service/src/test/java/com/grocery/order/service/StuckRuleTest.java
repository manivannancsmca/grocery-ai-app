package com.grocery.order.service;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.order.config.OrderProperties;
import com.grocery.order.entity.Actor;
import com.grocery.order.entity.CancellationReason;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.ItemType;
import com.grocery.order.entity.OrderItem;
import com.grocery.order.entity.OrderStatus;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.StuckReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The stuck-order rules, the guard texts of the timeline and the consumed event's headers, without a database. */
class StuckRuleTest {

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
    private final OrderProperties.Watchdog settings = new OrderProperties().getWatchdog();

    private static CustomerOrder order() {
        OrderItem item = new OrderItem(UUID.randomUUID(), ItemType.MENU_ITEM, "Biryani", null, null, "NON_VEG", 1,
                new BigDecimal("249.00"), null, new BigDecimal("249.00"));
        return CustomerOrder.create(new CustomerOrder.Draft(UUID.randomUUID(), "GA-00000001", UUID.randomUUID(),
                OrderType.RESTAURANT, UUID.randomUUID(), "Spice Route", null, null, null, List.of(item), 1,
                new BigDecimal("249.00"), new BigDecimal("249.00"), new BigDecimal("0.00"), new BigDecimal("30.00"),
                new BigDecimal("279.00"), null, null, null, null, T0.plus(Duration.ofMinutes(15))), T0);
    }

    @Test
    void everyOpenStatusHasItsRuleAndTheTerminalOnesNone() {
        assertThat(StuckRule.of(OrderStatus.CREATED)).contains(StuckRule.CREATED);
        assertThat(StuckRule.of(OrderStatus.PREPARING).orElseThrow().reason())
                .isEqualTo(StuckReason.PREPARATION_OVERDUE);
        assertThat(StuckRule.of(OrderStatus.ACCEPTED).orElseThrow().reason())
                .isEqualTo(StuckReason.PREPARATION_OVERDUE);
        for (OrderStatus terminal : List.of(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED, OrderStatus.REJECTED,
                OrderStatus.CANCELLED)) {
            assertThat(StuckRule.of(terminal)).isEmpty();
        }
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(StuckRule.of(status).isPresent()).isEqualTo(!status.isTerminal());
        }
    }

    @Test
    void theDueTimeIsTheReferenceTimePlusTheThreshold() {
        CustomerOrder order = order();
        assertThat(StuckRule.CREATED.dueAt(order, settings)).isEqualTo(T0.plus(Duration.ofMinutes(2)));
        assertThat(StuckRule.CREATED.since(order)).isEqualTo(T0);

        Instant awaiting = T0.plusSeconds(5);
        order.awaitPayment(awaiting);
        assertThat(StuckRule.AWAITING_PAYMENT.dueAt(order, settings)).isEqualTo(T0.plus(Duration.ofMinutes(17)));
        assertThat(StuckRule.AWAITING_PAYMENT.since(order)).isEqualTo(awaiting);

        Instant confirmed = T0.plusSeconds(60);
        order.confirm(UUID.randomUUID(), confirmed, confirmed);
        assertThat(StuckRule.CONFIRMED.dueAt(order, settings)).isEqualTo(confirmed.plus(Duration.ofMinutes(15)));

        Instant accepted = T0.plusSeconds(120);
        order.accept(20, accepted.plus(Duration.ofMinutes(20)), accepted, accepted);
        assertThat(StuckRule.ACCEPTED.dueAt(order, settings)).isEqualTo(accepted.plus(Duration.ofMinutes(50)));
        assertThat(StuckRule.ACCEPTED.since(order)).isEqualTo(accepted);

        Instant preparing = T0.plusSeconds(180);
        order.startPreparing(preparing, preparing);
        assertThat(StuckRule.PREPARING.dueAt(order, settings)).isEqualTo(accepted.plus(Duration.ofMinutes(50)));
        assertThat(StuckRule.PREPARING.since(order)).isEqualTo(preparing);

        Instant ready = T0.plusSeconds(900);
        order.readyForPickup(ready, ready);
        assertThat(StuckRule.READY_FOR_PICKUP.dueAt(order, settings)).isEqualTo(ready.plus(Duration.ofMinutes(30)));

        Instant pickedUp = T0.plusSeconds(1200);
        order.pickUp(UUID.randomUUID(), pickedUp, pickedUp);
        assertThat(StuckRule.OUT_FOR_DELIVERY.dueAt(order, settings))
                .isEqualTo(pickedUp.plus(Duration.ofMinutes(90)));
        assertThat(StuckRule.OUT_FOR_DELIVERY.since(order)).isEqualTo(pickedUp);
    }

    @Test
    void anOrderWithoutTheReferenceTimeIsNeverDue() {
        CustomerOrder order = order();
        assertThat(StuckRule.CONFIRMED.dueAt(order, settings)).isNull();                // never confirmed
        assertThat(StuckRule.READY_FOR_PICKUP.dueAt(order, settings)).isNull();
    }

    @Test
    void theThresholdsFollowTheSettings() {
        OrderProperties.Watchdog custom = new OrderProperties().getWatchdog();
        custom.setCreatedAfter(Duration.ofSeconds(30));
        custom.setDeliveryAfter(Duration.ofHours(3));
        assertThat(StuckRule.CREATED.threshold(custom)).isEqualTo(Duration.ofSeconds(30));
        assertThat(StuckRule.OUT_FOR_DELIVERY.threshold(custom)).isEqualTo(Duration.ofHours(3));
        assertThat(StuckRule.PREPARING.threshold(custom)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void theGuardReasonNamesTheStatusAndWhatWasExpected() {
        CustomerOrder order = order();
        assertThat(OrderEventService.requireStatus(order, OrderStatus.CREATED)).isNull();
        assertThat(OrderEventService.requireStatus(order, OrderStatus.AWAITING_PAYMENT))
                .isEqualTo("order is CREATED, not AWAITING_PAYMENT");
        assertThat(OrderEventService.requireStatus(order, OrderStatus.AWAITING_PAYMENT, OrderStatus.CONFIRMED))
                .isEqualTo("order is CREATED, not AWAITING_PAYMENT or CONFIRMED");
        order.cancel(CancellationReason.ADMIN_CANCELLED, Actor.ADMIN, null, null, T0);
        assertThat(OrderEventService.requireStatus(order, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP))
                .isEqualTo("order is CANCELLED, not ACCEPTED, PREPARING or READY_FOR_PICKUP");
    }

    @Test
    void theEventsOwnTimeIsReadFromItsOccurredAtHeader() {
        assertThat(EventSource.occurredAt("1762855500123456")).isEqualTo(Instant.parse("2025-11-11T10:05:00.123456Z"));
        assertThat(EventSource.occurredAt("1762855500123")).isEqualTo(Instant.parse("2025-11-11T10:05:00.123Z"));
        assertThat(EventSource.occurredAt("1762855500")).isEqualTo(Instant.parse("2025-11-11T10:05:00Z"));
        assertThat(EventSource.occurredAt(" 2026-11-11T10:05:00.5Z ")).isEqualTo(Instant.parse("2026-11-11T10:05:00.5Z"));
        assertThat(EventSource.occurredAt("yesterday")).isNull();
        assertThat(EventSource.occurredAt("")).isNull();
        assertThat(EventSource.occurredAt(null)).isNull();
    }

    @Test
    void theCorrelationIdComesFromItsHeaderOrIsTheEventId() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.events", 0, 0L, "k", "{}");
        header(record, "eventId", "0195f7a0-0000-7000-8000-000000000001");
        header(record, "eventType", "PaymentCaptured");
        assertThat(EventSource.of(record, EventEnvelope.from(record)).correlationId())
                .isEqualTo("0195f7a0-0000-7000-8000-000000000001");
        header(record, "correlationId", "bad id with spaces");                          // not safe to keep
        assertThat(EventSource.of(record, EventEnvelope.from(record)).correlationId())
                .isEqualTo("0195f7a0-0000-7000-8000-000000000001");
        header(record, "correlationId", "checkout-12345678");
        header(record, "occurredAt", "1762855500123456");
        EventSource source = EventSource.of(record, EventEnvelope.from(record));
        assertThat(source.correlationId()).isEqualTo("checkout-12345678");
        assertThat(source.topic()).isEqualTo("payment.events");
        assertThat(source.eventType()).isEqualTo("PaymentCaptured");
        assertThat(source.occurredAt()).isEqualTo(Instant.parse("2025-11-11T10:05:00.123456Z"));
    }

    private static void header(ConsumerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
