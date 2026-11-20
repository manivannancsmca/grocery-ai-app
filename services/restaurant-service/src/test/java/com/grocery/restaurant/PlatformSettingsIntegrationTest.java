package com.grocery.restaurant;

import com.grocery.restaurant.settings.PlatformSettingStore;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin platform settings, area {@code restaurants} (docs/api/platform-settings.md): the configured acceptance
 * time until an admin saves, an override used by new orders only, reset, the version check, validation, roles, the
 * audit trail and its history endpoint.
 */
class PlatformSettingsIntegrationTest extends StoreOrderTestSupport {

    private static final String URL = "/api/admin/settings/restaurants";
    /** Older than every other test's orders, so the poller here only finds this test's. */
    private static final Instant RECEIVED = Instant.now().minus(Duration.ofDays(500)).truncatedTo(ChronoUnit.MICROS);

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

    @Test
    void withoutAnOverrideNewOrdersGetTheConfiguredFiveMinutes() throws Exception {
        mvc.perform(get(URL).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("restaurants"))
                .andExpect(jsonPath("$.values.acceptanceTimeMinutes").value(5))
                .andExpect(jsonPath("$.defaults.acceptanceTimeMinutes").value(5))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(0));

        clock.set(RECEIVED);
        UUID orderId = confirm(newStore());
        assertThat(instantColumn(orderId, "accept_by")).isEqualTo(RECEIVED.plus(Duration.ofMinutes(5)));
    }

    @Test
    void anOverrideAppliesToNewOrdersAndWaitingOrdersKeepTheirDeadline() throws Exception {
        Store kitchen = newStore();
        clock.set(RECEIVED);
        UUID before = confirm(kitchen);

        save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":10}", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.acceptanceTimeMinutes").value(10))
                .andExpect(jsonPath("$.defaults.acceptanceTimeMinutes").value(5))
                .andExpect(jsonPath("$.overridden[0]").value("acceptanceTimeMinutes"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedBy").value("User " + admin.toString().substring(0, 8)));
        UUID after = confirm(kitchen);

        assertThat(instantColumn(before, "accept_by")).isEqualTo(RECEIVED.plus(Duration.ofMinutes(5)));
        assertThat(instantColumn(after, "accept_by")).isEqualTo(RECEIVED.plus(Duration.ofMinutes(10)));
        pollerAt(RECEIVED.plus(Duration.ofMinutes(6))).rejectExpired();
        assertThat(orderStatus(before)).isEqualTo("REJECTED");
        assertThat(orderStatus(after)).isEqualTo("NEW");

        assertThat(jdbc().sql("SELECT setting_key, old_value, new_value, BIN_TO_UUID(changed_by) AS changed_by "
                        + "FROM platform_setting_change").query().singleRow())
                .containsEntry("setting_key", "restaurants.acceptanceTimeMinutes")
                .containsEntry("old_value", null)
                .containsEntry("new_value", "10")
                .containsEntry("changed_by", admin.toString());
    }

    @Test
    void nullResetsToTheDefault() throws Exception {
        save("SUPER_ADMIN", "{\"acceptanceTimeMinutes\":2}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"acceptanceTimeMinutes\":null}", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.acceptanceTimeMinutes").value(5))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(2));
        clock.set(RECEIVED);
        UUID orderId = confirm(newStore());
        assertThat(instantColumn(orderId, "accept_by")).isEqualTo(RECEIVED.plus(Duration.ofMinutes(5)));
    }

    @Test
    void aSaveFromAnOlderVersionIsRefused() throws Exception {
        save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":7}", 0).andExpect(status().isOk());
        save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":9}", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SETTINGS_CHANGED"));
        mvc.perform(get(URL).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.values.acceptanceTimeMinutes").value(7));
    }

    @Test
    void invalidValuesAreRefused() throws Exception {
        for (String minutes : List.of("0", "61")) {
            save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":" + minutes + "}", 0)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("values.acceptanceTimeMinutes"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be between 1 and 60 minutes"));
        }
        mvc.perform(put(URL).with(as(admin, "OPS_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("values"));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting_change").query(Long.class).single()).isZero();
    }

    @Test
    void anyAdminMayReadOnlySuperAndOpsAdminsMayChange() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get(URL).with(as(admin, role))).andExpect(status().isOk());
        }
        mvc.perform(get(URL).with(as(admin, "RESTAURANT_OWNER"))).andExpect(status().isForbidden());
        for (String role : List.of("FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER", "RESTAURANT_OWNER")) {
            save(role, "{\"acceptanceTimeMinutes\":3}", 0).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void theHistoryListsEachChangeNewestFirst() throws Exception {
        Instant first = Instant.parse("2026-11-16T09:30:00Z");
        clock.set(first);
        save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":10}", 0).andExpect(status().isOk());
        clock.set(first.plus(Duration.ofHours(1)));
        save("SUPER_ADMIN", "{\"acceptanceTimeMinutes\":null}", 1).andExpect(status().isOk());

        mvc.perform(get(URL + "/history").with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].field").value("acceptanceTimeMinutes"))
                .andExpect(jsonPath("$.content[0].oldValue").value(10))
                .andExpect(jsonPath("$.content[0].newValue").value(nullValue()))
                .andExpect(jsonPath("$.content[0].changedAt").value(first.plus(Duration.ofHours(1)).toString()))
                .andExpect(jsonPath("$.content[0].changedBy.id").value(admin.toString()))
                .andExpect(jsonPath("$.content[0].changedBy.name").value("User " + admin.toString().substring(0, 8)))
                .andExpect(jsonPath("$.content[1].oldValue").value(nullValue()))
                .andExpect(jsonPath("$.content[1].newValue").value(10))
                .andExpect(jsonPath("$.content[1].changedAt").value(first.toString()));
        mvc.perform(get(URL + "/history?page=1&size=1").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].newValue").value(10))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(URL + "/history").with(as(admin, "RESTAURANT_OWNER"))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyChangeIsPublishedAsPlatformSettingChanged() throws Exception {
        Instant first = Instant.parse("2026-11-16T09:30:00Z");
        clock.set(first);
        save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":10}", 0).andExpect(status().isOk());
        clock.set(first.plus(Duration.ofHours(1)));
        save("SUPER_ADMIN", "{\"acceptanceTimeMinutes\":null}", 1).andExpect(status().isOk());
        // an unchanged value and a refused save publish nothing
        save("SUPER_ADMIN", "{\"acceptanceTimeMinutes\":null}", 2).andExpect(status().isOk());
        save("OPS_ADMIN", "{\"acceptanceTimeMinutes\":15}", 0).andExpect(status().isConflict());

        List<SettingEvent> events = settingEvents();
        assertThat(events).extracting(SettingEvent::change).containsExactly(
                "restaurants.acceptanceTimeMinutes: null -> 10", "restaurants.acceptanceTimeMinutes: 10 -> null");
        assertThat(events).allSatisfy(this::assertPublishedWithItsAuditRow);
        assertThat(events).extracting(SettingEvent::changedAt).containsExactly(first, first.plus(Duration.ofHours(1)));
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
     * One event: on restaurant.events, keyed by the setting key, with the eight fields of the schema, and the same key,
     * values, admin and time as the platform_setting_change row written with it.
     */
    private void assertPublishedWithItsAuditRow(SettingEvent event) {
        JsonNode payload = event.payload();
        String key = payload.get("settingKey").asString();
        assertThat(event.topic()).isEqualTo("restaurant.events");
        assertThat(event.aggregateType()).isEqualTo("PlatformSetting");
        assertThat(event.key()).isEqualTo(key);
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "settingKey", "area", "field", "oldValue", "newValue", "changedBy", "changedByName", "changedAt");
        assertThat(payload.get("area").asString()).isEqualTo("restaurants");
        assertThat(key).isEqualTo("restaurants." + payload.get("field").asString());
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
