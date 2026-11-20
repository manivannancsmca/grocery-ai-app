package com.grocery.order;

import com.grocery.order.settings.PlatformSettingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin platform settings, area {@code returns} (docs/api/platform-settings.md): the configured 48 h window and 24 h
 * store decision until an admin saves; an override used by the next return request (the window counts from delivery,
 * so it applies to every delivered order, while an open return keeps its deadline); reset, the version check,
 * validation, roles, the audit trail and its history endpoint.
 */
class PlatformSettingsIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/admin/settings/returns";

    @Autowired
    private PlatformSettingStore store;
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void noOverrides() {
        jdbc().sql("DELETE FROM platform_setting").update();
        jdbc().sql("DELETE FROM platform_setting_change").update();
        store.invalidate();
    }

    private ResultActions save(String role, String values, long expectedVersion) throws Exception {
        return mvc.perform(put(URL).with(as(admin, role)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":" + values + ",\"expectedVersion\":" + expectedVersion + "}"));
    }

    /** A delivered grocery order whose delivery was {@code hoursAgo} hours (and a minute) ago. */
    private UUID deliveredHoursAgo(UUID customerId, int hoursAgo) throws Exception {
        UUID orderId = deliveredGroceryOrder(customerId);
        movedBack(orderId, "delivered_at", null, Instant.now().minus(Duration.ofHours(hoursAgo)).minusSeconds(60));
        return orderId;
    }

    @Test
    void withoutOverridesTheConfiguredWindowsApply() throws Exception {
        mvc.perform(get(URL).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("returns"))
                .andExpect(jsonPath("$.values.returnWindowHours").value(48))
                .andExpect(jsonPath("$.values.storeDecisionHours").value(24))
                .andExpect(jsonPath("$.defaults.returnWindowHours").value(48))
                .andExpect(jsonPath("$.defaults.storeDecisionHours").value(24))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(0));

        UUID customerId = UUID.randomUUID();
        assertThat(order(customerId, deliveredHoursAgo(customerId, 47)).get("returnable").asBoolean()).isTrue();
        assertThat(order(customerId, deliveredHoursAgo(customerId, 48)).get("returnable").asBoolean()).isFalse();
    }

    @Test
    void anOverrideAppliesToTheNextRequestAndOpenReturnsKeepTheirDeadline() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID earlier = deliveredGroceryOrder(customerId);
        JsonNode open = body(requestReturn(customerId, earlier, Map.of(itemIds(customerId, earlier).getFirst(), 1),
                "DAMAGED", null).andExpect(status().isCreated()));
        Instant openDecideBy = Instant.parse(open.get("decideBy").asString());
        UUID late = deliveredHoursAgo(customerId, 60);
        assertThat(order(customerId, late).get("returnable").asBoolean()).isFalse();

        save("OPS_ADMIN", "{\"returnWindowHours\":72,\"storeDecisionHours\":12}", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.returnWindowHours").value(72))
                .andExpect(jsonPath("$.values.storeDecisionHours").value(12))
                .andExpect(jsonPath("$.overridden.length()").value(2))
                .andExpect(jsonPath("$.version").value(2));

        // 60 h after delivery is inside the new 72 h window; the store has 12 h to decide
        assertThat(order(customerId, late).get("returnable").asBoolean()).isTrue();
        JsonNode created = body(requestReturn(customerId, late, Map.of(itemIds(customerId, late).getFirst(), 1),
                "DAMAGED", null).andExpect(status().isCreated()));
        assertThat(Instant.parse(created.get("decideBy").asString()))
                .isEqualTo(Instant.parse(created.get("requestedAt").asString()).plus(Duration.ofHours(12)));
        // the return made before keeps its 24 h
        JsonNode stillOpen = body(mvc.perform(get("/api/orders/{id}/returns", earlier).with(customer(customerId)))
                .andExpect(status().isOk())).get(0);
        assertThat(Instant.parse(stillOpen.get("decideBy").asString())).isEqualTo(openDecideBy);

        assertThat(jdbc().sql("SELECT setting_key, new_value FROM platform_setting_change ORDER BY setting_key")
                .query((rs, n) -> rs.getString(1) + "=" + rs.getString(2)).list())
                .containsExactlyInAnyOrder("returns.returnWindowHours=72", "returns.storeDecisionHours=12");
    }

    @Test
    void nullResetsAFieldToTheDefault() throws Exception {
        save("SUPER_ADMIN", "{\"returnWindowHours\":100,\"storeDecisionHours\":null}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"returnWindowHours\":null,\"storeDecisionHours\":null}", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.returnWindowHours").value(48))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(2));
        UUID customerId = UUID.randomUUID();
        assertThat(order(customerId, deliveredHoursAgo(customerId, 60)).get("returnable").asBoolean()).isFalse();
    }

    @Test
    void aSaveFromAnOlderVersionIsRefused() throws Exception {
        save("OPS_ADMIN", "{\"storeDecisionHours\":36}", 0).andExpect(status().isOk());
        save("OPS_ADMIN", "{\"storeDecisionHours\":48}", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SETTINGS_CHANGED"));
        mvc.perform(get(URL).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.values.storeDecisionHours").value(36))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void invalidValuesAreRefusedWithTheirField() throws Exception {
        save("OPS_ADMIN", "{\"returnWindowHours\":721,\"storeDecisionHours\":0}", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'values.returnWindowHours')].message")
                        .value("must be between 1 and 720 hours"))
                .andExpect(jsonPath("$.errors[?(@.field == 'values.storeDecisionHours')].message")
                        .value("must be between 1 and 168 hours"));
        save("OPS_ADMIN", "{\"storeDecisionHours\":169}", 0).andExpect(status().isBadRequest());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void anyAdminMayReadOnlySuperAndOpsAdminsMayChange() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get(URL).with(as(admin, role))).andExpect(status().isOk());
        }
        mvc.perform(get(URL).with(customer(UUID.randomUUID()))).andExpect(status().isForbidden());
        for (String role : List.of("FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER", "CUSTOMER")) {
            save(role, "{\"returnWindowHours\":24}", 0).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void theHistoryListsEveryChangedFieldNewestFirst() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        save("OPS_ADMIN", "{\"returnWindowHours\":72,\"storeDecisionHours\":12}", 0).andExpect(status().isOk());
        // the window unchanged (no row), the decision time back to the default
        save("SUPER_ADMIN", "{\"returnWindowHours\":72,\"storeDecisionHours\":null}", 2).andExpect(status().isOk());

        JsonNode page = body(mvc.perform(get(URL + "/history").with(as(admin, "SUPPORT"))).andExpect(status().isOk()));
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("content")).extracting(change -> change.get("field").asString() + ": "
                        + change.get("oldValue") + " -> " + change.get("newValue"))
                .containsExactly("storeDecisionHours: 12 -> null", "storeDecisionHours: null -> 12",
                        "returnWindowHours: null -> 72");
        JsonNode newest = page.get("content").get(0);
        assertThat(Instant.parse(newest.get("changedAt").asString())).isAfter(before);
        assertThat(newest.get("changedBy").get("id").asString()).isEqualTo(admin.toString());
        assertThat(newest.get("changedBy").get("name").asString())
                .isEqualTo("User " + admin.toString().substring(0, 8));

        mvc.perform(get(URL + "/history?page=2&size=1").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].field").value("returnWindowHours"))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get(URL + "/history").with(customer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyChangedFieldIsPublishedAsPlatformSettingChanged() throws Exception {
        save("OPS_ADMIN", "{\"returnWindowHours\":72,\"storeDecisionHours\":12}", 0).andExpect(status().isOk());
        // the window unchanged (no event), the decision time back to the default
        save("SUPER_ADMIN", "{\"returnWindowHours\":72,\"storeDecisionHours\":null}", 2).andExpect(status().isOk());
        // a refused save publishes nothing
        save("OPS_ADMIN", "{\"returnWindowHours\":24}", 0).andExpect(status().isConflict());

        List<SettingEvent> events = settingEvents();
        assertThat(events).extracting(SettingEvent::change).containsExactlyInAnyOrder(
                "returns.returnWindowHours: null -> 72", "returns.storeDecisionHours: null -> 12",
                "returns.storeDecisionHours: 12 -> null");
        assertThat(events.getLast().change()).isEqualTo("returns.storeDecisionHours: 12 -> null");
        assertThat(events).allSatisfy(this::assertPublishedWithItsAuditRow);
        assertThat(events.get(0).changedAt()).as("one save, one time").isEqualTo(events.get(1).changedAt());
        assertThat(events).extracting(event -> event.payload().get("changedByName").asString())
                .containsOnly("User " + admin.toString().substring(0, 8));
    }

    // ---- PlatformSettingChanged: the central audit trail ----------------------------------------------------------

    /** One PlatformSettingChanged outbox row: where it goes (topic, aggregate, key) and its payload. */
    private record SettingEvent(String topic, String aggregateType, String key, JsonNode payload) {

        /** {@code <settingKey>: <old> -> <new>}, the values as stored (null = the default). */
        String change() {
            return payload.get("settingKey").asString() + ": " + text(payload.get("oldValue")) + " -> "
                    + text(payload.get("newValue"));
        }

        Instant changedAt() {
            return Instant.parse(payload.get("changedAt").asString());
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    /** The PlatformSettingChanged events of this test's admin (a new one for every test), oldest first. */
    private List<SettingEvent> settingEvents() {
        return jdbc().sql("""
                        SELECT topic, aggregate_type, aggregate_id, payload FROM outbox_event
                        WHERE event_type = 'PlatformSettingChanged'
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.changedBy')) = ?
                        ORDER BY occurred_at, id""")
                .param(admin.toString())
                .query((rs, n) -> new SettingEvent(rs.getString("topic"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), json.readTree(rs.getString("payload"))))
                .list();
    }

    /**
     * One event: on order.events, keyed by the setting key, with the eight fields of the schema, and the same key,
     * values, admin and time as the platform_setting_change row written with it.
     */
    private void assertPublishedWithItsAuditRow(SettingEvent event) {
        JsonNode payload = event.payload();
        String key = payload.get("settingKey").asString();
        assertThat(event.topic()).isEqualTo("order.events");
        assertThat(event.aggregateType()).isEqualTo("PlatformSetting");
        assertThat(event.key()).isEqualTo(key);
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "settingKey", "area", "field", "oldValue", "newValue", "changedBy", "changedByName", "changedAt");
        assertThat(payload.get("area").asString()).isEqualTo("returns");
        assertThat(key).isEqualTo("returns." + payload.get("field").asString());
        assertThat(payload.get("changedBy").asString()).isEqualTo(admin.toString());
        Instant rowChangedAt = jdbc().sql("""
                        SELECT changed_at FROM platform_setting_change
                        WHERE setting_key = :key AND old_value <=> :oldValue AND new_value <=> :newValue
                          AND changed_by = UUID_TO_BIN(:by) AND changed_by_name <=> :name""")
                .param("key", key)
                .param("oldValue", text(payload.get("oldValue")))
                .param("newValue", text(payload.get("newValue")))
                .param("by", admin.toString())
                .param("name", text(payload.get("changedByName")))
                .query((rs, n) -> rs.getObject("changed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                .single();
        assertThat(event.changedAt()).isCloseTo(rowChangedAt, within(1, ChronoUnit.MICROS));
    }
}
