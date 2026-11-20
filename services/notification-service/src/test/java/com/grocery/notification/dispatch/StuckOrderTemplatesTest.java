package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 10 §4: the STUCK_ORDER text, its audiences and the handler's plan, without Spring. */
class StuckOrderTemplatesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "STOCK_CHECK_OVERDUE    | CREATED          | 2026-11-11T04:32:00Z | stock check overdue    | CREATED since 10:02.",
            "PAYMENT_EXPIRY_OVERDUE | AWAITING_PAYMENT | 2026-11-11T18:29:59Z | payment expiry overdue | AWAITING_PAYMENT since 23:59.",
            "STORE_RESPONSE_OVERDUE | CONFIRMED        | 2026-11-11T18:30:00Z | store response overdue | CONFIRMED since 00:00.",
            "PREPARATION_OVERDUE    | ACCEPTED         | 2026-06-30T23:59:59Z | preparation overdue    | ACCEPTED since 05:29.",
            "PICKUP_OVERDUE         | READY_FOR_PICKUP | 2026-11-11T09:05:00Z | pickup overdue         | READY_FOR_PICKUP since 14:35.",
            "DELIVERY_OVERDUE       | OUT_FOR_DELIVERY | 2026-11-11T09:05:00Z | delivery overdue       | OUT_FOR_DELIVERY since 14:35."
    })
    void theTitleNamesTheReasonAndTheBodyTheStatusSinceWhenInIndiaTime(String reason, String status, String since,
                                                                     String label, String body) {
        UUID orderId = UUID.randomUUID();
        NotificationDraft draft = NotificationTemplates.stuckOrder(orderId, "GA-7KQ2M9XA", status, reason,
                Instant.parse(since));
        assertThat(draft.type()).isEqualTo(NotificationType.STUCK_ORDER);
        assertThat(draft.type().severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(draft.type().category()).isEqualTo(NotificationType.Category.ORDER);
        assertThat(draft.title()).isEqualTo("Order GA-7KQ2M9XA is stuck: " + label);
        assertThat(draft.body()).isEqualTo(body);
        assertThat(draft.link()).isEqualTo("/admin/orders");
        assertThat(draft.data()).containsExactly(Map.entry("orderId", orderId.toString()),
                Map.entry("orderNumber", "GA-7KQ2M9XA"), Map.entry("status", status), Map.entry("reason", reason));
    }

    @Test
    void anUnknownReasonIsShownAsItIsAndAMissingTimeIsLeftOut() {
        assertThat(NotificationTemplates.stuckReasonLabel("KITCHEN_ON_FIRE")).isEqualTo("KITCHEN_ON_FIRE");
        assertThat(NotificationTemplates.stuckReasonLabel(null)).isEqualTo("overdue");
        assertThat(NotificationTemplates.stuckOrder(UUID.randomUUID(), "GA-1", "CONFIRMED", "STORE_RESPONSE_OVERDUE",
                null).body()).isEqualTo("CONFIRMED.");
    }

    @Test
    void theAlertGoesToTheOrderAdminsAndEveryAdminScreenRefreshes() {
        StuckOrderEventHandler handler = new StuckOrderEventHandler(JsonMapper.builder().build());
        assertThat(handler.supports("order.events", "OrderStuckDetected")).isTrue();
        assertThat(handler.supports("order.events", "OrderConfirmed")).isFalse();
        assertThat(handler.supports("payment.events", "OrderStuckDetected")).isFalse();

        UUID orderId = UUID.randomUUID();
        EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), "OrderStuckDetected", 1, "Order",
                orderId.toString(), null, "order.events", """
                {"orderId":"%s","orderNumber":"GA-1","orderType":"GROCERY","customerId":"%s","storeId":"%s",
                 "status":"CREATED","reason":"STOCK_CHECK_OVERDUE","since":"2026-11-11T04:32:00Z",
                 "detectedAt":"2026-11-11T04:35:00Z"}""".formatted(orderId, UUID.randomUUID(), UUID.randomUUID()));
        DispatchPlan plan = new DispatchPlan(true, Instant.now());
        handler.handle(event, plan);

        assertThat(plan.notifications()).singleElement().satisfies(planned -> {
            assertThat(planned.draft().type()).isEqualTo(NotificationType.STUCK_ORDER);
            assertThat(planned.audiences()).containsExactly("role:OPS_ADMIN", "role:SUPER_ADMIN", "role:SUPPORT");
        });
        assertThat(plan.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(SignalType.ADMIN_ORDERS_CHANGED);
            assertThat(signal.data()).containsExactly(Map.entry("orderId", orderId.toString()),
                    Map.entry("event", "OrderStuckDetected"));
            assertThat(signal.audiences()).containsExactlyElementsOf(Audiences.allAdmins());
        });

        DispatchPlan old = new DispatchPlan(false, Instant.now());
        handler.handle(event, old);
        assertThat(old.isEmpty()).isTrue();
        assertThat(Audiences.stuckOrderAdmins()).isEqualTo(List.of("role:OPS_ADMIN", "role:SUPER_ADMIN",
                "role:SUPPORT"));
    }
}
