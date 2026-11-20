package com.grocery.notification;

import com.grocery.common.security.Roles;
import com.grocery.notification.TestEvents.Order;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract §3: the inbox API, per-user read state, ownership, validation and authentication. */
class InboxApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void listsTheCallersNotificationsNewestFirstWithPagesAndTheUnreadFilter() throws Exception {
        UUID customer = UUID.randomUUID();
        List<UUID> ids = rejectedOrders(customer, 3);

        JsonNode page = body(mvc.perform(get("/api/notifications").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isOk()));
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(ids(page)).containsExactly(ids.get(2), ids.get(1), ids.get(0));
        JsonNode newest = page.get("content").get(0);
        assertThat(newest.get("type").asString()).isEqualTo("ORDER_REJECTED");
        assertThat(newest.get("category").asString()).isEqualTo("ORDER");
        assertThat(newest.get("severity").asString()).isEqualTo("ERROR");
        assertThat(newest.get("title").asString()).startsWith("Order GA-");
        assertThat(newest.get("link").asString()).startsWith("/orders/");
        assertThat(newest.get("data").get("orderId").asString()).isNotBlank();
        assertThat(newest.get("read").asBoolean(true)).isFalse();
        assertThat(newest.get("createdAt").asString()).endsWith("Z");

        JsonNode second = body(mvc.perform(get("/api/notifications").param("page", "1").param("size", "2")
                .with(as(customer, Roles.CUSTOMER))).andExpect(status().isOk()));
        assertThat(ids(second)).containsExactly(ids.get(0));
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
        assertThat(second.get("totalElements").asLong()).isEqualTo(3);

        mvc.perform(post("/api/notifications/{id}/read", ids.get(1)).with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isNoContent());
        JsonNode unread = body(mvc.perform(get("/api/notifications").param("unread", "true")
                .with(as(customer, Roles.CUSTOMER))).andExpect(status().isOk()));
        assertThat(ids(unread)).containsExactly(ids.get(2), ids.get(0));
        JsonNode all = body(mvc.perform(get("/api/notifications").param("unread", "false")
                .with(as(customer, Roles.CUSTOMER))).andExpect(status().isOk()));
        assertThat(all.get("content").get(1).get("read").asBoolean(false)).isTrue();
    }

    @Test
    void unreadCountReadAndReadAll() throws Exception {
        UUID customer = UUID.randomUUID();
        List<UUID> ids = rejectedOrders(customer, 3);

        mvc.perform(get("/api/notifications/unread-count").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));

        mvc.perform(post("/api/notifications/{id}/read", ids.get(0)).with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isNoContent());
        // idempotent
        mvc.perform(post("/api/notifications/{id}/read", ids.get(0)).with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").with(as(customer, Roles.CUSTOMER)))
                .andExpect(jsonPath("$.count").value(2));

        mvc.perform(post("/api/notifications/read-all").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        mvc.perform(post("/api/notifications/read-all").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(0));
        mvc.perform(get("/api/notifications/unread-count").with(as(customer, Roles.CUSTOMER)))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void readStateIsPerUserAlsoForRoleNotifications() throws Exception {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        knownOrder(order);
        ConsumerRecord<String, String> failed = TestEvents.refundFailed(order.orderId(), new BigDecimal("99.00"),
                "ADMIN", "BAD_REQUEST");
        dispatch(failed);
        UUID notificationId = notificationOf(failed, "REFUND_FAILED").id();
        UUID adminA = UUID.randomUUID();
        UUID adminB = UUID.randomUUID();

        mvc.perform(post("/api/notifications/{id}/read", notificationId).with(as(adminA, Roles.SUPER_ADMIN)))
                .andExpect(status().isNoContent());

        assertThat(inboxAbout(order.orderId(), adminA, Roles.SUPER_ADMIN)).singleElement()
                .satisfies(n -> assertThat(n.get("read").asBoolean(false)).isTrue());
        assertThat(inboxAbout(order.orderId(), adminB, Roles.SUPER_ADMIN)).singleElement()
                .satisfies(n -> assertThat(n.get("read").asBoolean(true)).as("not read for admin B").isFalse());
        assertThat(inboxAbout(order.orderId(), adminB, Roles.FINANCE_ADMIN)).singleElement()
                .satisfies(n -> assertThat(n.get("read").asBoolean(true)).isFalse());

        // read-all by admin B marks it read for B only once, even with both roles
        long unreadBefore = body(mvc.perform(get("/api/notifications/unread-count")
                .with(as(adminB, Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN)))).get("count").asLong();
        JsonNode readAll = body(mvc.perform(post("/api/notifications/read-all")
                .with(as(adminB, Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN))).andExpect(status().isOk()));
        assertThat(readAll.get("updated").asLong()).isEqualTo(unreadBefore);
        assertThat(inboxAbout(order.orderId(), adminB, Roles.FINANCE_ADMIN)).singleElement()
                .satisfies(n -> assertThat(n.get("read").asBoolean(false)).isTrue());
    }

    @Test
    void somebodyElsesUnknownOrMalformedIdsAreNotFound() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID id = rejectedOrders(customer, 1).getFirst();

        mvc.perform(post("/api/notifications/{id}/read", id).with(as(other, Roles.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));
        // an admin role does not see a customer's notification either
        mvc.perform(post("/api/notifications/{id}/read", id).with(as(other, Roles.SUPER_ADMIN, Roles.SUPPORT)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID()).with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));
        mvc.perform(post("/api/notifications/{id}/read", "not-a-uuid").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));

        assertThat(inbox(other, Roles.CUSTOMER)).isEmpty();
        mvc.perform(get("/api/notifications/unread-count").with(as(customer, Roles.CUSTOMER)))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void pageSizeIsCappedAndBadParametersAreRejected() throws Exception {
        UUID customer = UUID.randomUUID();
        mvc.perform(get("/api/notifications").param("size", "500").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/notifications").param("size", "0").param("page", "-3")
                        .with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.page").value(0));
        mvc.perform(get("/api/notifications").param("unread", "maybe").with(as(customer, Roles.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    /** {@code count} ORDER_REJECTED notifications for the customer; their ids, oldest first. */
    private List<UUID> rejectedOrders(UUID customer, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ConsumerRecord<String, String> rejected = TestEvents.orderRejected(
                    TestEvents.groceryOrder(customer, UUID.randomUUID()));
            dispatch(rejected);
            ids.add(notificationOf(rejected, "ORDER_REJECTED").id());
        }
        return ids;
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(UUID.fromString(n.get("id").asString())));
        return ids;
    }
}
