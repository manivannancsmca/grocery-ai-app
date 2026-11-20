package com.grocery.vendor.service;

import com.grocery.vendor.config.FulfillmentProperties;
import com.grocery.vendor.entity.PurchaseOrder;
import com.grocery.vendor.entity.PurchaseOrder.Receiving;
import com.grocery.vendor.entity.PurchaseOrderLine;
import com.grocery.vendor.entity.PurchaseOrderStatus;
import com.grocery.vendor.entity.PurchaseReceipt;
import com.grocery.vendor.entity.Supplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The purchase order status machine, receiving arithmetic, numbers and {@code expectedAt} parsing (no Spring). */
class PurchaseOrderRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final UUID VENDOR = UUID.randomUUID();
    private static final UUID RICE = UUID.randomUUID();
    private static final UUID DAL = UUID.randomUUID();

    private static PurchaseOrder draft() {
        Supplier supplier = Supplier.create(UUID.randomUUID(), VENDOR,
                new Supplier.Details("S", null, null, null, null, null, null, null), true, NOW);
        return PurchaseOrder.draft(UUID.randomUUID(), "PO-0000000A", VENDOR, supplier, null, null,
                List.of(new PurchaseOrderLine(RICE, "Rice", 10, new BigDecimal("40.00")),
                        new PurchaseOrderLine(DAL, "Dal", 5, new BigDecimal("90.50"))),
                UUID.randomUUID(), NOW);
    }

    @Test
    void aDraftCanBeEditedSentOrCancelledOnly() {
        PurchaseOrder order = draft();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.totalCost()).isEqualByComparingTo("852.50");
        assertThat(order.orderedUnits()).isEqualTo(15);
        assertThatThrownBy(() -> order.close(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.receive(UUID.randomUUID(), List.of(new Receiving(RICE, 1)), null,
                UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
        order.send(NOW);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SENT);
        assertThat(order.getSentAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> order.edit(order.getSupplier(), null, null, order.getLines(), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.send(NOW)).isInstanceOf(IllegalStateException.class);
        order.cancel(NOW);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThatThrownBy(() -> order.cancel(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void receivingMovesToPartiallyReceivedThenReceived() {
        PurchaseOrder order = draft();
        order.send(NOW);
        PurchaseReceipt first = order.receive(UUID.randomUUID(), List.of(new Receiving(DAL, 5), new Receiving(RICE, 4)),
                "Van", UUID.randomUUID(), NOW);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(first.units()).isEqualTo(9);
        assertThat(first.totalCost()).isEqualByComparingTo("612.50");
        assertThat(first.getLines()).extracting(line -> line.getProductName()).containsExactly("Dal", "Rice");
        assertThat(order.line(RICE).orElseThrow().openQuantity()).isEqualTo(6);
        assertThat(order.receivedCost()).isEqualByComparingTo("612.50");
        assertThatThrownBy(() -> order.cancel(NOW)).isInstanceOf(IllegalStateException.class);
        // more than open is refused by the line (the service answers 422 before)
        assertThatThrownBy(() -> order.receive(UUID.randomUUID(), List.of(new Receiving(RICE, 7)), null,
                UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);

        Instant later = NOW.plusSeconds(3600);
        order.receive(UUID.randomUUID(), List.of(new Receiving(RICE, 6)), null, UUID.randomUUID(), later);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(order.getFullyReceivedAt()).isEqualTo(later);
        assertThat(order.openUnits()).isZero();
        assertThat(order.getReceipts()).hasSize(2);
        assertThatThrownBy(() -> order.close(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPartiallyReceivedOrderCanBeClosedShort() {
        PurchaseOrder order = draft();
        order.send(NOW);
        order.receive(UUID.randomUUID(), List.of(new Receiving(RICE, 1)), null, UUID.randomUUID(), NOW);
        order.close(NOW);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(order.getClosedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> order.receive(UUID.randomUUID(), List.of(new Receiving(RICE, 1)), null,
                UUID.randomUUID(), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theActionSetsMatchTheContract() {
        assertThat(PurchaseOrderStatus.OPEN).containsExactlyInAnyOrder(PurchaseOrderStatus.DRAFT,
                PurchaseOrderStatus.SENT, PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(PurchaseOrderStatus.CANCELLABLE).containsExactlyInAnyOrder(PurchaseOrderStatus.DRAFT,
                PurchaseOrderStatus.SENT);
        assertThat(PurchaseOrderStatus.CLOSABLE).containsExactly(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(PurchaseOrderStatus.RECEIVABLE).containsExactlyInAnyOrder(PurchaseOrderStatus.SENT,
                PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    @Test
    void numbersArePoAndEightCrockfordCharacters() {
        assertThat(PurchaseOrderNumbers.format(0)).isEqualTo("PO-00000000");
        assertThat(PurchaseOrderNumbers.format(0xFF_FFFF_FFFFL)).isEqualTo("PO-ZZZZZZZZ");
        PurchaseOrderNumbers numbers = new PurchaseOrderNumbers();
        Set<String> drawn = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String number = numbers.next();
            assertThat(number).matches(PurchaseOrderNumbers.PATTERN);
            drawn.add(number);
        }
        assertThat(drawn).hasSizeGreaterThan(990);
    }

    @Test
    void expectedAtIsAnInstantOrAnIndianDate() {
        FulfillmentProperties properties = new FulfillmentProperties(Duration.ofMinutes(5), Duration.ofSeconds(15),
                20, false, 15, "Asia/Kolkata");
        PurchaseOrderService service = new PurchaseOrderService(null, null, null, null, null, properties,
                Clock.systemUTC());
        assertThat(service.expectedAt(null)).isNull();
        assertThat(service.expectedAt("  ")).isNull();
        assertThat(service.expectedAt("2026-11-20")).isEqualTo(Instant.parse("2026-11-19T18:30:00Z"));
        assertThat(service.expectedAt("2026-11-20T09:00:00Z")).isEqualTo(Instant.parse("2026-11-20T09:00:00Z"));
        assertThat(service.expectedAt("2026-11-20T09:00:00.1234567Z"))
                .isEqualTo(Instant.parse("2026-11-20T09:00:00.123456Z"));
        assertThatThrownBy(() -> service.expectedAt("20/11/2026"))
                .isInstanceOf(PurchasingException.class)
                .satisfies(e -> assertThat(((PurchasingException) e).errorCode()).isEqualTo("VALIDATION_FAILED"));
    }
}
