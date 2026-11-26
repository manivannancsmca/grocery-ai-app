package com.grocery.pricing;

import com.grocery.pricing.settings.PlatformSettingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin platform settings, area {@code delivery-fees} (docs/api/platform-settings.md), and the fees in force that
 * cart-service reads from {@code GET /internal/delivery-fees}: the config defaults until an admin saves, overrides,
 * reset, the version check, validation, roles, the audit trail and its history endpoint.
 */
class PlatformSettingsIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/admin/settings/delivery-fees";
    private static final String INTERNAL = "/internal/delivery-fees";
    private static final String ALL_DEFAULT = "{\"groceryBaseFee\":null,\"groceryIncludedKm\":null,"
            + "\"groceryPerExtraKm\":null,\"groceryFreeAbove\":null,\"groceryMinimumOrder\":null,"
            + "\"restaurantBaseFee\":null,\"restaurantIncludedKm\":null,\"restaurantPerExtraKm\":null}";

    @Autowired
    private PlatformSettingStore store;

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
    void withoutOverridesTheConfiguredFeesApply() throws Exception {
        mvc.perform(get(URL).with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("delivery-fees"))
                .andExpect(jsonPath("$.values.groceryBaseFee").value(25.00))
                .andExpect(jsonPath("$.values.groceryIncludedKm").value(3))
                .andExpect(jsonPath("$.values.groceryPerExtraKm").value(8.00))
                .andExpect(jsonPath("$.values.groceryFreeAbove").value(499.00))
                .andExpect(jsonPath("$.values.groceryMinimumOrder").value(99.00))
                .andExpect(jsonPath("$.values.restaurantBaseFee").value(30.00))
                .andExpect(jsonPath("$.values.restaurantIncludedKm").value(3))
                .andExpect(jsonPath("$.values.restaurantPerExtraKm").value(10.00))
                .andExpect(jsonPath("$.defaults.groceryBaseFee").value(25.00))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(0));

        mvc.perform(get(INTERNAL).with(customer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groceryBaseFee").value(25.00))
                .andExpect(jsonPath("$.groceryIncludedKm").value(3))
                .andExpect(jsonPath("$.groceryPerExtraKm").value(8.00))
                .andExpect(jsonPath("$.groceryFreeAbove").value(499.00))
                .andExpect(jsonPath("$.groceryMinimumOrder").value(99.00))
                .andExpect(jsonPath("$.restaurantBaseFee").value(30.00))
                .andExpect(jsonPath("$.restaurantIncludedKm").value(3))
                .andExpect(jsonPath("$.restaurantPerExtraKm").value(10.00));
    }

    @Test
    void theAssistantsReadTheSameFeesThroughTheGatewayPath() throws Exception {
        save("OPS_ADMIN", "{\"groceryMinimumOrder\":150,\"groceryFreeAbove\":600}", 0).andExpect(status().isOk());

        mvc.perform(get("/api/pricing/delivery-fees").with(customer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groceryMinimumOrder").value(150.00))
                .andExpect(jsonPath("$.groceryFreeAbove").value(600.00))
                .andExpect(jsonPath("$.groceryBaseFee").value(25.00));
        mvc.perform(get("/api/pricing/delivery-fees")).andExpect(status().isUnauthorized());
    }

    @Test
    void anOverrideIsWhatCartServiceReadsNext() throws Exception {
        save("OPS_ADMIN", "{\"groceryBaseFee\":40,\"groceryIncludedKm\":2.5,\"groceryMinimumOrder\":150.5}", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.groceryBaseFee").value(40.00))
                .andExpect(jsonPath("$.values.groceryIncludedKm").value(2.5))
                .andExpect(jsonPath("$.values.groceryMinimumOrder").value(150.50))
                .andExpect(jsonPath("$.values.restaurantBaseFee").value(30.00))
                .andExpect(jsonPath("$.defaults.groceryBaseFee").value(25.00))
                .andExpect(jsonPath("$.overridden.length()").value(3))
                .andExpect(jsonPath("$.overridden[0]").value("groceryBaseFee"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.updatedBy").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(get(INTERNAL).with(customer(UUID.randomUUID())))
                .andExpect(jsonPath("$.groceryBaseFee").value(40.00))
                .andExpect(jsonPath("$.groceryIncludedKm").value(2.5))
                .andExpect(jsonPath("$.groceryMinimumOrder").value(150.50))
                .andExpect(jsonPath("$.groceryFreeAbove").value(499.00));
        assertThat(jdbc().sql("SELECT new_value FROM platform_setting_change ORDER BY setting_key")
                .query(String.class).list()).containsExactlyInAnyOrder("40.00", "2.5", "150.50");

        // 40 and 40.00 are the same fee: nothing changes
        save("OPS_ADMIN", "{\"groceryBaseFee\":40.00,\"groceryIncludedKm\":2.5,\"groceryMinimumOrder\":150.50}", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting_change").query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    void nullResetsAFeeToTheDefault() throws Exception {
        save("SUPER_ADMIN", "{\"restaurantPerExtraKm\":12}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", ALL_DEFAULT, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.restaurantPerExtraKm").value(10.00))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(INTERNAL).with(customer(UUID.randomUUID())))
                .andExpect(jsonPath("$.restaurantPerExtraKm").value(10.00));
        assertThat(jdbc().sql("SELECT new_value FROM platform_setting_change ORDER BY id").query(String.class).list())
                .containsExactly("12.00", null);
    }

    @Test
    void aSaveFromAnOlderVersionIsRefused() throws Exception {
        save("OPS_ADMIN", "{\"groceryFreeAbove\":599}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"groceryFreeAbove\":699}", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SETTINGS_CHANGED"));
        mvc.perform(get(INTERNAL).with(customer(UUID.randomUUID())))
                .andExpect(jsonPath("$.groceryFreeAbove").value(599.00));
    }

    @Test
    void invalidFeesAreRefusedWithTheirField() throws Exception {
        save("OPS_ADMIN", "{\"groceryBaseFee\":501,\"groceryIncludedKm\":2.55,\"groceryPerExtraKm\":-1,"
                + "\"groceryFreeAbove\":10000.01,\"groceryMinimumOrder\":5001,\"restaurantBaseFee\":12.345}", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'values.groceryBaseFee')].message")
                        .value("must be between 0 and 500"))
                .andExpect(jsonPath("$.errors[?(@.field == 'values.groceryIncludedKm')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'values.groceryPerExtraKm')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'values.groceryFreeAbove')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'values.groceryMinimumOrder')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'values.restaurantBaseFee')]").exists());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void anyAdminMayReadOnlySuperAndOpsAdminsMayChange() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get(URL).with(as(admin, role))).andExpect(status().isOk());
        }
        mvc.perform(get(URL).with(customer(UUID.randomUUID()))).andExpect(status().isForbidden());
        for (String role : List.of("FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER", "VENDOR")) {
            save(role, "{\"groceryBaseFee\":30}", 0).andExpect(status().isForbidden());
        }
        mvc.perform(get(INTERNAL)).andExpect(status().isUnauthorized());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void theHistoryShowsTheFeesAsTheApiDoesNewestFirst() throws Exception {
        save("OPS_ADMIN", "{\"groceryBaseFee\":40,\"groceryIncludedKm\":2.5}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"groceryBaseFee\":45.5,\"groceryIncludedKm\":2.5}", 2).andExpect(status().isOk());

        mvc.perform(get(URL + "/history").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].field").value("groceryBaseFee"))
                .andExpect(jsonPath("$.content[0].oldValue").value(40.00))
                .andExpect(jsonPath("$.content[0].newValue").value(45.50))
                .andExpect(jsonPath("$.content[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].changedBy.id").value(admin.toString()))
                .andExpect(jsonPath("$.content[0].changedBy.name").value("User " + admin.toString().substring(0, 8)))
                .andExpect(jsonPath("$.content[1].field").value("groceryIncludedKm"))
                .andExpect(jsonPath("$.content[1].oldValue").value(nullValue()))
                .andExpect(jsonPath("$.content[2].field").value("groceryBaseFee"))
                // rupees with two decimals, km without trailing zeros: as GET shows them
                .andExpect(content().string(containsString("\"oldValue\":40.00,\"newValue\":45.50,")))
                .andExpect(content().string(containsString("\"oldValue\":null,\"newValue\":2.5,")));
        mvc.perform(get(URL + "/history?page=1&size=2").with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].newValue").value(40.00))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(URL + "/history").with(customer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyChangedFieldIsPublishedAsPlatformSettingChanged() throws Exception {
        save("OPS_ADMIN", "{\"groceryBaseFee\":40,\"groceryIncludedKm\":2.5}", 0).andExpect(status().isOk());
        // the included km unchanged (no event), a new base fee
        save("SUPER_ADMIN", "{\"groceryBaseFee\":45.5,\"groceryIncludedKm\":2.5}", 2).andExpect(status().isOk());
        // a refused save publishes nothing
        save("OPS_ADMIN", "{\"groceryBaseFee\":30}", 0).andExpect(status().isConflict());

        List<SettingEvent> events = settingEvents();
        assertThat(events).extracting(SettingEvent::change).containsExactlyInAnyOrder(
                "delivery-fees.groceryBaseFee: null -> 40.00", "delivery-fees.groceryIncludedKm: null -> 2.5",
                "delivery-fees.groceryBaseFee: 40.00 -> 45.50");
        assertThat(events.getLast().change()).isEqualTo("delivery-fees.groceryBaseFee: 40.00 -> 45.50");
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
     * One event: on pricing.events, keyed by the setting key, with the eight fields of the schema, and the same key,
     * values, admin and time as the platform_setting_change row written with it.
     */
    private void assertPublishedWithItsAuditRow(SettingEvent event) {
        JsonNode payload = event.payload();
        String key = payload.get("settingKey").asString();
        assertThat(event.topic()).isEqualTo("pricing.events");
        assertThat(event.aggregateType()).isEqualTo("PlatformSetting");
        assertThat(event.key()).isEqualTo(key);
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "settingKey", "area", "field", "oldValue", "newValue", "changedBy", "changedByName", "changedAt");
        assertThat(payload.get("area").asString()).isEqualTo("delivery-fees");
        assertThat(key).isEqualTo("delivery-fees." + payload.get("field").asString());
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
