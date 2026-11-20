package com.grocery.notification.dispatch;

import com.grocery.notification.entity.AccountKind;
import com.grocery.notification.entity.NotificationType;
import com.grocery.notification.entity.StoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Money formatting, template fallbacks and the plan's merging rules, without Spring. */
class MoneyAndTemplatesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0          | ₹0.00",
            "5          | ₹5.00",
            "245        | ₹245.00",
            "999.999    | ₹1,000.00",
            "1234.5     | ₹1,234.50",
            "12345.67   | ₹12,345.67",
            "123456     | ₹1,23,456.00",
            "1234567.89 | ₹12,34,567.89",
            "123456789  | ₹12,34,56,789.00",
            "-1500      | -₹1,500.00"
    })
    void rupeesUseIndianGrouping(String amount, String expected) {
        assertThat(Money.inr(new BigDecimal(amount))).isEqualTo(expected);
    }

    @Test
    void noAmountIsNoText() {
        assertThat(Money.inr(null)).isNull();
    }

    @Test
    void templatesFallBackToNeutralWords() {
        UUID id = UUID.randomUUID();
        assertThat(NotificationTemplates.orderConfirmed(id, "GA-1", null, " ").body())
                .isEqualTo("Paid. The store will start preparing it soon.");
        assertThat(NotificationTemplates.newOrder(id, "GA-1", null, new BigDecimal("10"), null, StoreType.VENDOR)
                .body()).isEqualTo("₹10.00");
        assertThat(NotificationTemplates.orderAccepted(id, "GA-1", null, null).title())
                .isEqualTo("The store accepted order GA-1");
        assertThat(NotificationTemplates.orderAccepted(id, "GA-1", " ", null).body())
                .isEqualTo("It will be ready soon.");
        assertThat(NotificationTemplates.orderReady(id, "GA-1", null).body())
                .isEqualTo("Your order is ready and waiting for a delivery partner.");
        assertThat(NotificationTemplates.orderCancelledForCustomer(id, "GA-1", "STORE_REJECTED", null, "CONFIRMED",
                null).body()).isEqualTo("The store could not accept your order. A full refund is on its way.");
        assertThat(NotificationTemplates.paymentFailed(id, "GA-1", "...").body())
                .startsWith("Your bank declined the payment. ");
        assertThat(NotificationTemplates.refundFailed(id, "GA-1", null, null, null, null, null).body())
                .isEqualTo("The refund — unknown error. Retry it from the payment monitor.");
        assertThat(NotificationTemplates.refundCompleted(id, "GA-1", null, null).title()).isEqualTo("Refund processed");
        assertThat(NotificationTemplates.lowStock(id, " ", null, null).body()).isEqualTo("Stock is running low.");
        assertThat(NotificationTemplates.approvalRequested(AccountKind.DELIVERY_PARTNER, id, null).title())
                .isEqualTo("A delivery partner is waiting for approval");
        assertThat(NotificationTemplates.approvalRequested(AccountKind.RESTAURANT, id, "").title())
                .isEqualTo("A restaurant is waiting for approval");
        assertThat(NotificationTemplates.accountRejected(AccountKind.VENDOR, id, null).body()).isEmpty();
        assertThat(NotificationTemplates.orderCancelledForCustomer(id, "GA-1", "SOMETHING_NEW", null, "CREATED")
                .body()).isEqualTo("This order was cancelled.");
    }

    /** Contract §3: the ids the app needs on every notification type. */
    @Test
    void everyNotificationCarriesTheIdsTheAppNeeds() {
        UUID order = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        String no = "GA-12345678";
        Map<String, Object> orderData = Map.of("orderId", order.toString(), "orderNumber", no);

        // ORDER and PAYMENT: orderId + orderNumber
        for (NotificationDraft draft : List.of(
                NotificationTemplates.orderConfirmed(order, no, amount, "S"),
                NotificationTemplates.newOrder(order, no, 1, amount, Instant.now(), StoreType.RESTAURANT),
                NotificationTemplates.orderAccepted(order, no, "S", 20),
                NotificationTemplates.orderReady(order, no, StoreType.VENDOR),
                NotificationTemplates.orderAutoRejected(order, no, StoreType.RESTAURANT),
                NotificationTemplates.orderRejected(order, no),
                NotificationTemplates.orderCancelledForCustomer(order, no, "PAYMENT_TIMEOUT", null, "CREATED"),
                NotificationTemplates.orderCancelledForStore(order, no, StoreType.VENDOR),
                NotificationTemplates.paymentFailed(order, no, null),
                NotificationTemplates.refundCompleted(order, no, UUID.randomUUID(), amount),
                NotificationTemplates.refundFailed(order, no, UUID.randomUUID(), UUID.randomUUID(), amount, "ADMIN",
                        "X"))) {
            assertThat(draft.type().category().name()).as(draft.type().name()).isIn("ORDER", "PAYMENT");
            assertThat(draft.data()).as(draft.type().name()).containsAllEntriesOf(orderData);
        }

        // kind on APPROVAL_REQUESTED, PRICE_APPROVAL_REQUESTED and every ACCOUNT_*
        assertThat(NotificationTemplates.priceApprovalRequested(request, product, UUID.randomUUID(), "P", null,
                amount, amount).data())
                .containsEntry("kind", "PRICE_CHANGE").containsEntry("requestId", request.toString())
                .containsEntry("productId", product.toString());
        for (AccountKind kind : AccountKind.values()) {
            for (NotificationDraft draft : List.of(
                    NotificationTemplates.approvalRequested(kind, account, "N"),
                    NotificationTemplates.accountApproved(kind, account),
                    NotificationTemplates.accountRejected(kind, account, "r"),
                    NotificationTemplates.accountSuspended(kind, account, "r"),
                    NotificationTemplates.accountReinstated(kind, account))) {
                assertThat(draft.data()).as(draft.type().name()).containsEntry("kind", kind.name())
                        .containsEntry("id", account.toString());
            }
        }

        // productId (+ requestId for price changes) on STOCK and PRICING
        assertThat(NotificationTemplates.lowStock(product, "P", 1, 2).data())
                .containsEntry("productId", product.toString());
        assertThat(NotificationTemplates.priceChangeApproved(request, product).data())
                .containsEntry("productId", product.toString()).containsEntry("requestId", request.toString());
        assertThat(NotificationTemplates.priceChangeRejected(request, product, "r").data())
                .containsEntry("productId", product.toString()).containsEntry("requestId", request.toString());
    }

    /** Phase 8 §6: the new-order line, with the deadline in India time (UTC+5:30, no daylight saving). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-11-11T10:05:00Z | 3 | 245    | 3 items · ₹245.00 · accept by 15:35",
            "2026-11-11T18:30:00Z | 1 | 1359.5 | 1 item · ₹1,359.50 · accept by 00:00",
            "2026-06-30T23:59:59Z | 2 | 99.99  | 2 items · ₹99.99 · accept by 05:29"
    })
    void newOrderSaysWhenToAcceptByInIndiaTime(String acceptBy, int items, String total, String body) {
        UUID id = UUID.randomUUID();
        NotificationDraft draft = NotificationTemplates.newOrder(id, "GA-1", items, new BigDecimal(total),
                Instant.parse(acceptBy), StoreType.VENDOR);
        assertThat(draft.type()).isEqualTo(NotificationType.NEW_ORDER);
        assertThat(draft.title()).isEqualTo("New order GA-1");
        assertThat(draft.body()).isEqualTo(body);
        assertThat(draft.link()).isEqualTo("/vendor/orders");
        assertThat(draft.data()).containsExactly(Map.entry("orderId", id.toString()), Map.entry("orderNumber", "GA-1"),
                Map.entry("acceptBy", Instant.parse(acceptBy).toString()));
    }

    /** Phase 8 §6: "({reason label}{: note})" from the cancellation note "&lt;reason&gt;: &lt;note&gt;" or "&lt;reason&gt;". */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "OUT_OF_STOCK: Toor dal is over | Green Basket could not accept your order (items out of stock: Toor dal is over). A full refund is on its way.",
            "OUT_OF_STOCK                   | Green Basket could not accept your order (items out of stock). A full refund is on its way.",
            "STORE_CLOSED                   | Green Basket could not accept your order (store closed). A full refund is on its way.",
            "TOO_BUSY: Back at 8            | Green Basket could not accept your order (too busy right now: Back at 8). A full refund is on its way.",
            "OTHER: Gas leak                | Green Basket could not accept your order (Gas leak). A full refund is on its way.",
            "OTHER                          | Green Basket could not accept your order. A full refund is on its way.",
            "Kitchen flooded                | Green Basket could not accept your order (Kitchen flooded). A full refund is on its way."
    })
    void aStoreRejectionTellsTheCustomerWhy(String note, String body) {
        NotificationDraft draft = NotificationTemplates.orderCancelledForCustomer(UUID.randomUUID(), "GA-1",
                "STORE_REJECTED", note, "CONFIRMED", "Green Basket");
        assertThat(draft.type()).isEqualTo(NotificationType.ORDER_CANCELLED);
        assertThat(draft.title()).isEqualTo("Order GA-1 cancelled");
        assertThat(draft.body()).isEqualTo(body);
    }

    @Test
    void theStoreDecisionsAndLaterCancellationsMentionTheRefundOnce() {
        UUID id = UUID.randomUUID();
        assertThat(NotificationTemplates.orderCancelledForCustomer(id, "GA-1", "STORE_NO_RESPONSE", null, "CONFIRMED",
                "S").body()).isEqualTo("The store did not respond in time. A full refund is on its way.");
        for (String previous : List.of("CONFIRMED", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP")) {
            assertThat(NotificationTemplates.orderCancelledForCustomer(id, "GA-1", "ADMIN_CANCELLED", "Rider strike.",
                    previous).body())
                    .isEqualTo("Cancelled by our support team. Rider strike. A full refund is on its way.");
        }
        assertThat(NotificationTemplates.orderCancelledForCustomer(id, "GA-1", "CUSTOMER_CANCELLED", null,
                "AWAITING_PAYMENT").body()).isEqualTo("You cancelled this order.");
        assertThat(NotificationTemplates.orderAutoRejected(id, "GA-1", StoreType.RESTAURANT)).satisfies(draft -> {
            assertThat(draft.type().severity()).isEqualTo(NotificationType.Severity.WARNING);
            assertThat(draft.title()).isEqualTo("Order GA-1 was auto-rejected");
            assertThat(draft.body()).isEqualTo("It was not accepted in time. The customer has been refunded.");
            assertThat(draft.link()).isEqualTo("/restaurant/orders");
        });
    }

    @Test
    void dataLeavesOutNullsAndRendersIdsAsText() {
        UUID id = UUID.randomUUID();
        assertThat(NotificationTemplates.data("orderId", id, "status", null, "count", 3))
                .containsExactly(Map.entry("orderId", id.toString()), Map.entry("count", 3));
    }

    @Test
    void theSameDraftForSeveralAudiencesIsOneNotification() {
        DispatchPlan plan = new DispatchPlan(true, Instant.now());
        NotificationDraft draft = NotificationTemplates.priceChangeApproved(UUID.randomUUID(), UUID.randomUUID());
        plan.notify(draft, List.of("role:SUPER_ADMIN"));
        plan.notify(draft, List.of("role:FINANCE_ADMIN", "role:SUPER_ADMIN"));
        plan.notify(NotificationTemplates.priceChangeRejected(UUID.randomUUID(), UUID.randomUUID(), "x"),
                List.of("user:x"));
        plan.notify(draft, List.of());

        assertThat(plan.notifications()).hasSize(2);
        assertThat(plan.notifications().getFirst().audiences())
                .containsExactly("role:SUPER_ADMIN", "role:FINANCE_ADMIN");
        assertThat(plan.notifications().getFirst().draft().type()).isEqualTo(NotificationType.PRICE_CHANGE_APPROVED);
    }

    @Test
    void theSameSignalForSeveralAudiencesIsOneMessageButPublicOnesAreKeptApart() {
        DispatchPlan plan = new DispatchPlan(true, Instant.now());
        Map<String, Object> data = NotificationTemplates.data("orderId", UUID.randomUUID());
        plan.signal(SignalType.ORDER_UPDATED, data, List.of("user:a"));
        plan.signal(SignalType.ORDER_UPDATED, data, List.of("user:b"));
        plan.publicSignal(SignalType.STOCK_CHANGED, data, "STOCK_CHANGED:1");
        plan.publicSignal(SignalType.STOCK_CHANGED, data, "STOCK_CHANGED:1");

        assertThat(plan.signals()).hasSize(3);
        assertThat(plan.signals().getFirst().audiences()).containsExactly("user:a", "user:b");
        assertThat(plan.signals().get(1).audiences()).containsExactly("public");
    }

    @Test
    void anOldEventCannotNotifyAnybody() {
        DispatchPlan plan = new DispatchPlan(false, Instant.now());
        assertThatThrownBy(() -> plan.signal(SignalType.ORDER_UPDATED, Map.of(), List.of("user:a")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void audiencesComeFromTheTokenAndIgnoreMalformedRoles() {
        UUID user = UUID.randomUUID();
        assertThat(Audiences.stream(user, List.of("VENDOR", "SUPER_ADMIN", "bad role", "VENDOR")))
                .containsExactly("user:" + user, "role:SUPER_ADMIN", "role:VENDOR", "public");
        assertThat(Audiences.inbox(user, null)).containsExactly("user:" + user);
    }
}
