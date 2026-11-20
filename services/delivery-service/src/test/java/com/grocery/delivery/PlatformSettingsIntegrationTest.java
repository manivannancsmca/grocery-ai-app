package com.grocery.delivery;

import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.settings.PlatformSettingStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin platform settings, area {@code delivery} (docs/api/platform-settings.md): the config defaults until an admin
 * saves, an override used by the next search, reset, the version check, validation, roles, the audit trail and its
 * history endpoint.
 */
class PlatformSettingsIntegrationTest extends DispatchTestSupport {

    private static final String URL = "/api/admin/settings/delivery";
    private static final String DEFAULTS =
            "{\"searchRadiiKm\":null,\"offersPerWave\":null,\"offerTimeLimitSeconds\":null,\"locationFreshnessSeconds\":null}";

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
    void withoutOverridesTheConfigValuesApply() throws Exception {
        mvc.perform(get(URL).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("delivery"))
                .andExpect(jsonPath("$.values.searchRadiiKm").value(contains(3.0, 6.0, 10.0)))
                .andExpect(jsonPath("$.values.offersPerWave").value(2))
                .andExpect(jsonPath("$.values.offerTimeLimitSeconds").value(30))
                .andExpect(jsonPath("$.values.locationFreshnessSeconds").value(60))
                .andExpect(jsonPath("$.defaults.offersPerWave").value(2))
                .andExpect(jsonPath("$.defaults.offerTimeLimitSeconds").value(30))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist());

        // The two nearest, offers valid 30 s: exactly as before platform settings existed
        UUID near = onlinePartnerAt("Near", at(1.0, 0));
        UUID mid = onlinePartnerAt("Mid", at(2.0, 0));
        onlinePartnerAt("Far", at(2.5, 0));
        Order order = searchingOrder();
        assertThat(offeredPartners(order)).containsExactly(near, mid);
        assertThat(offerExpiries(order)).containsOnly(T0.plusSeconds(30));
    }

    @Test
    void anOverrideAppliesToTheNextSearchAndOffersAlreadySentKeepTheirExpiry() throws Exception {
        onlinePartnerAt("Near", at(1.0, 0));
        onlinePartnerAt("Mid", at(2.0, 0));
        Order before = searchingOrder();
        assertThat(offerExpiries(before)).containsOnly(T0.plusSeconds(30));

        JsonNode saved = body(save("OPS_ADMIN", "{\"searchRadiiKm\":[1.5,4],\"offersPerWave\":1,"
                + "\"offerTimeLimitSeconds\":45,\"locationFreshnessSeconds\":null}", 0).andExpect(status().isOk()));
        assertThat(saved.get("values").get("offersPerWave").asInt()).isEqualTo(1);
        assertThat(saved.get("values").get("offerTimeLimitSeconds").asInt()).isEqualTo(45);
        assertThat(saved.get("values").get("searchRadiiKm").toString()).isEqualTo("[1.5,4.0]");
        assertThat(saved.get("defaults").get("offersPerWave").asInt()).isEqualTo(2);
        assertThat(saved.get("overridden").toString())
                .isEqualTo("[\"searchRadiiKm\",\"offersPerWave\",\"offerTimeLimitSeconds\"]");
        assertThat(saved.get("version").asLong()).isEqualTo(3);
        assertThat(saved.get("updatedBy").asString()).startsWith("User ");
        assertThat(saved.get("updatedAt").isNull()).isFalse();

        // Offers already sent keep their 30 s
        assertThat(offerExpiries(before)).containsOnly(T0.plusSeconds(30));
        // The next search (Near and Mid hold offers): one partner per wave, 45 s to accept, steps of 1.5 and 4 km
        UUID nearest = onlinePartnerAt("Nearest free", at(-0.8, 0));
        onlinePartnerAt("Second free", at(0, 1.2));
        Order after = searchingOrder();
        assertThat(offeredPartners(after)).containsExactly(nearest);
        assertThat(offerExpiries(after)).containsOnly(T0.plusSeconds(45));

        // Nobody within 4 km of a store far away: assignment fails at the largest radius in force
        base = at(50, 0);
        Order lonely = searchingOrder();
        assertThat(event(lonely, DeliveryEvents.ASSIGNMENT_FAILED).get("radiusKm").asDouble()).isEqualTo(4.0);

        // The audit trail: one row per changed key, NULL = the default
        assertThat(auditRows()).containsExactlyInAnyOrder(
                Map.of("setting_key", "delivery.offerTimeLimitSeconds", "new_value", "45"),
                Map.of("setting_key", "delivery.offersPerWave", "new_value", "1"),
                Map.of("setting_key", "delivery.searchRadiiKm", "new_value", "1.5,4"));
    }

    @Test
    void locationFreshnessDecidesWhoIsAvailable() throws Exception {
        clock.set(T0.minusSeconds(45));
        UUID quiet = onlinePartnerAt("Quiet", at(1.0, 0));
        clock.set(T0);
        Order withDefault = searchingOrder();
        assertThat(offeredPartners(withDefault)).containsExactly(quiet);      // 45 s old < 60 s

        save("SUPER_ADMIN", "{\"searchRadiiKm\":null,\"offersPerWave\":null,\"offerTimeLimitSeconds\":null,"
                + "\"locationFreshnessSeconds\":30}", 0).andExpect(status().isOk());
        offers.reject(quiet, offerOf(withDefault, quiet));
        Order withOverride = searchingOrder();
        assertThat(offeredPartners(withOverride)).doesNotContain(quiet);      // 45 s old > 30 s
    }

    @Test
    void nullResetsAFieldToTheDefault() throws Exception {
        save("OPS_ADMIN", "{\"searchRadiiKm\":null,\"offersPerWave\":4,\"offerTimeLimitSeconds\":null,"
                + "\"locationFreshnessSeconds\":null}", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        save("OPS_ADMIN", DEFAULTS, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.offersPerWave").value(2))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(auditRows()).extracting(row -> row.get("new_value")).containsExactly("4", null);

        // Saving the same values again changes nothing
        save("OPS_ADMIN", DEFAULTS, 2).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(auditRows()).hasSize(2);
    }

    @Test
    void aSaveFromAnOlderVersionIsRefused() throws Exception {
        save("OPS_ADMIN", "{\"offersPerWave\":3}", 0).andExpect(status().isOk());

        save("SUPER_ADMIN", "{\"offersPerWave\":5}", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SETTINGS_CHANGED"));
        mvc.perform(get(URL).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.values.offersPerWave").value(3))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(auditRows()).hasSize(1);
    }

    @Test
    void invalidValuesAreRefusedWithTheirField() throws Exception {
        save("OPS_ADMIN", "{\"offersPerWave\":11,\"offerTimeLimitSeconds\":5,\"locationFreshnessSeconds\":20}", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'values.offersPerWave')].message")
                        .value("must be between 1 and 10"))
                .andExpect(jsonPath("$.errors[?(@.field == 'values.offerTimeLimitSeconds')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'values.locationFreshnessSeconds')]").exists());
        save("OPS_ADMIN", "{\"searchRadiiKm\":[6,3]}", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("values.searchRadiiKm"));
        save("OPS_ADMIN", "{\"searchRadiiKm\":[0.2,3]}", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(startsWith("values.searchRadiiKm[0]")));
        save("OPS_ADMIN", "{\"searchRadiiKm\":[]}", 0).andExpect(status().isBadRequest());
        save("OPS_ADMIN", "{\"searchRadiiKm\":[1,2,3,4,5,6]}", 0).andExpect(status().isBadRequest());
        mvc.perform(put(URL).with(as(admin, "OPS_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"offersPerWave\":3}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expectedVersion"));
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void anyAdminMayReadOnlySuperAndOpsAdminsMayChange() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get(URL).with(as(admin, role))).andExpect(status().isOk());
        }
        mvc.perform(get(URL).with(as(admin, "DELIVERY_PARTNER"))).andExpect(status().isForbidden());
        for (String role : List.of("FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER", "CUSTOMER")) {
            save(role, "{\"offersPerWave\":3}", 0).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void theHistoryListsEveryChangedFieldNewestFirstInTheApiUnits() throws Exception {
        mvc.perform(get(URL + "/history").with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        save("OPS_ADMIN", "{\"searchRadiiKm\":[1.5,4],\"offersPerWave\":3}", 0).andExpect(status().isOk());
        clock.set(T0.plusSeconds(90));
        // radii unchanged (no row), offers per wave back to the default, a new time limit
        save("SUPER_ADMIN", "{\"searchRadiiKm\":[1.5,4],\"offerTimeLimitSeconds\":45}", 2).andExpect(status().isOk());

        JsonNode page = body(mvc.perform(get(URL + "/history").with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(status().isOk()));
        assertThat(page.get("totalElements").asInt()).isEqualTo(4);
        JsonNode content = page.get("content");
        assertThat(content).extracting(change -> change.get("field").asString())
                .containsExactly("offerTimeLimitSeconds", "offersPerWave", "offersPerWave", "searchRadiiKm");
        JsonNode newest = content.get(0);
        assertThat(fieldNames(newest)).containsExactly("field", "oldValue", "newValue", "changedAt", "changedBy");
        assertThat(newest.get("oldValue").isNull()).isTrue();
        assertThat(newest.get("newValue").asInt()).isEqualTo(45);
        assertThat(Instant.parse(newest.get("changedAt").asString())).isEqualTo(T0.plusSeconds(90));
        assertThat(newest.get("changedBy").get("id").asString()).isEqualTo(admin.toString());
        assertThat(newest.get("changedBy").get("name").asString())
                .isEqualTo("User " + admin.toString().substring(0, 8));
        assertThat(content.get(1).get("oldValue").asInt()).isEqualTo(3);
        assertThat(content.get(1).get("newValue").isNull()).isTrue();
        assertThat(content.get(2).get("oldValue").isNull()).isTrue();
        assertThat(Instant.parse(content.get(3).get("changedAt").asString())).isEqualTo(T0);
        assertThat(content.get(3).get("newValue").toString()).isEqualTo("[1.5,4.0]");

        mvc.perform(get(URL + "/history?page=1&size=3").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].field").value("searchRadiiKm"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(URL + "/history").with(as(admin, "DELIVERY_PARTNER"))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/history")).andExpect(status().isUnauthorized());
    }

    private List<Instant> offerExpiries(Order order) {
        return jdbc().sql("""
                        SELECT o.expires_at FROM delivery_offer o JOIN delivery d ON d.id = o.delivery_id
                        WHERE d.order_id = UUID_TO_BIN(?)""")
                .param(order.orderId().toString())
                .query(LocalDateTime.class).list().stream().map(at -> at.toInstant(ZoneOffset.UTC)).toList();
    }

    /** The audit rows (key, new value), oldest first (UUIDv7 ids). */
    private List<Map<String, Object>> auditRows() {
        return jdbc().sql("SELECT setting_key, new_value FROM platform_setting_change ORDER BY id")
                .query((rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("setting_key", rs.getString("setting_key"));
                    row.put("new_value", rs.getString("new_value"));
                    return row;
                }).list();
    }

    @Test
    void everyChangedFieldIsPublishedAsPlatformSettingChanged() throws Exception {
        save("OPS_ADMIN", "{\"searchRadiiKm\":[1.5,4],\"offersPerWave\":3}", 0).andExpect(status().isOk());
        clock.set(T0.plusSeconds(90));
        // radii unchanged (no event), offers per wave back to the default
        save("SUPER_ADMIN", "{\"searchRadiiKm\":[1.5,4]}", 2).andExpect(status().isOk());
        // a refused save publishes nothing
        save("OPS_ADMIN", "{\"offersPerWave\":5}", 0).andExpect(status().isConflict());

        List<SettingEvent> events = settingEvents();
        assertThat(events).extracting(SettingEvent::change).containsExactlyInAnyOrder(
                "delivery.searchRadiiKm: null -> 1.5,4", "delivery.offersPerWave: null -> 3",
                "delivery.offersPerWave: 3 -> null");
        assertThat(events.getLast().change()).isEqualTo("delivery.offersPerWave: 3 -> null");
        assertThat(events).allSatisfy(this::assertPublishedWithItsAuditRow);
        assertThat(events).extracting(SettingEvent::changedAt).containsExactly(T0, T0, T0.plusSeconds(90));
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
     * One event: on delivery.events, keyed by the setting key, with the eight fields of the schema, and the same key,
     * values, admin and time as the platform_setting_change row written with it.
     */
    private void assertPublishedWithItsAuditRow(SettingEvent event) {
        JsonNode payload = event.payload();
        String key = payload.get("settingKey").asString();
        assertThat(event.topic()).isEqualTo("delivery.events");
        assertThat(event.aggregateType()).isEqualTo("PlatformSetting");
        assertThat(event.key()).isEqualTo(key);
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "settingKey", "area", "field", "oldValue", "newValue", "changedBy", "changedByName", "changedAt");
        assertThat(payload.get("area").asString()).isEqualTo("delivery");
        assertThat(key).isEqualTo("delivery." + payload.get("field").asString());
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
