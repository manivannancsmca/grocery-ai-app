package com.grocery.review;

import com.grocery.review.settings.PlatformSettingStore;
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
 * Admin platform settings, area {@code reviews} (docs/api/platform-settings.md): the configured 30 days until an
 * admin saves; an override used by the next eligibility check and review (the window counts from delivery, so it
 * applies to every delivered order); reset, the version check, validation, roles, the audit trail and its history
 * endpoint.
 */
class PlatformSettingsIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/admin/settings/reviews";

    @Autowired
    private PlatformSettingStore store;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void noOverrides() {
        jdbc().sql("DELETE FROM platform_setting").update();
        jdbc().sql("DELETE FROM platform_setting_change").update();
        store.invalidate();
    }

    private ResultActions save(String role, String values, long expectedVersion) throws Exception {
        return mvc.perform(put(URL).with(admin(adminId, role)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":" + values + ",\"expectedVersion\":" + expectedVersion + "}"));
    }

    /** A grocery order of {@code customerId} delivered {@code daysAgo} days ago. */
    private Order deliveredDaysAgo(UUID customerId, int daysAgo) {
        Order order = groceryOrder(customerId, "Tomato");
        confirm(order);
        deliver(order, clock.instant().minus(Duration.ofDays(daysAgo)));
        return order;
    }

    private ResultActions reviewItem(Order order) throws Exception {
        return submit(order.customerId(), order.orderId(), "PRODUCT", order.item(0).itemId(), 5, null, null);
    }

    @Test
    void withoutAnOverrideTheConfiguredThirtyDaysApply() throws Exception {
        mvc.perform(get(URL).with(admin(adminId, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("reviews"))
                .andExpect(jsonPath("$.values.reviewWindowDays").value(30))
                .andExpect(jsonPath("$.defaults.reviewWindowDays").value(30))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(0));

        UUID shopper = UUID.randomUUID();
        reviewItem(deliveredDaysAgo(shopper, 29)).andExpect(status().isCreated());
        reviewItem(deliveredDaysAgo(shopper, 31))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("WINDOW_OVER"));
    }

    @Test
    void anOverrideAppliesToTheNextCheck() throws Exception {
        UUID shopper = UUID.randomUUID();
        Order monthOld = deliveredDaysAgo(shopper, 40);
        Order weekOld = deliveredDaysAgo(shopper, 10);

        save("OPS_ADMIN", "{\"reviewWindowDays\":60}", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.reviewWindowDays").value(60))
                .andExpect(jsonPath("$.defaults.reviewWindowDays").value(30))
                .andExpect(jsonPath("$.overridden[0]").value("reviewWindowDays"))
                .andExpect(jsonPath("$.updatedBy").value("Admin OPS_ADMIN"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(body(mvc.perform(get("/api/reviews/eligible").with(customer(shopper))
                .param("orderId", monthOld.orderId().toString())).andExpect(status().isOk()))).isNotEmpty();
        reviewItem(monthOld).andExpect(status().isCreated());

        save("OPS_ADMIN", "{\"reviewWindowDays\":7}", 1).andExpect(status().isOk());
        reviewItem(weekOld)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("WINDOW_OVER"));

        assertThat(jdbc().sql("SELECT new_value FROM platform_setting_change ORDER BY id").query(String.class).list())
                .containsExactly("60", "7");
    }

    @Test
    void nullResetsToTheDefault() throws Exception {
        save("SUPER_ADMIN", "{\"reviewWindowDays\":90}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"reviewWindowDays\":null}", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.reviewWindowDays").value(30))
                .andExpect(jsonPath("$.overridden").isEmpty())
                .andExpect(jsonPath("$.version").value(2));
        reviewItem(deliveredDaysAgo(UUID.randomUUID(), 31)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aSaveFromAnOlderVersionIsRefused() throws Exception {
        save("OPS_ADMIN", "{\"reviewWindowDays\":14}", 0).andExpect(status().isOk());
        save("OPS_ADMIN", "{\"reviewWindowDays\":21}", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SETTINGS_CHANGED"));
        mvc.perform(get(URL).with(admin(adminId, "OPS_ADMIN")))
                .andExpect(jsonPath("$.values.reviewWindowDays").value(14));
    }

    @Test
    void invalidValuesAreRefused() throws Exception {
        for (String days : List.of("0", "366")) {
            save("OPS_ADMIN", "{\"reviewWindowDays\":" + days + "}", 0)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("values.reviewWindowDays"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be between 1 and 365 days"));
        }
        save("OPS_ADMIN", "{\"reviewWindowDays\":30}", -1).andExpect(status().isBadRequest());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void anyAdminMayReadOnlySuperAndOpsAdminsMayChange() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get(URL).with(admin(adminId, role))).andExpect(status().isOk());
        }
        mvc.perform(get(URL).with(customer(customer))).andExpect(status().isForbidden());
        for (String role : List.of("FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER", "CUSTOMER")) {
            save(role, "{\"reviewWindowDays\":45}", 0).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM platform_setting").query(Long.class).single()).isZero();
    }

    @Test
    void theHistoryListsEachChangeNewestFirst() throws Exception {
        Instant before = clock.instant().minusSeconds(1);
        save("OPS_ADMIN", "{\"reviewWindowDays\":60}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"reviewWindowDays\":null}", 1).andExpect(status().isOk());

        mvc.perform(get(URL + "/history").with(admin(adminId, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].field").value("reviewWindowDays"))
                .andExpect(jsonPath("$.content[0].oldValue").value(60))
                .andExpect(jsonPath("$.content[0].newValue").value(nullValue()))
                .andExpect(jsonPath("$.content[0].changedBy.id").value(adminId.toString()))
                .andExpect(jsonPath("$.content[0].changedBy.name").value("Admin SUPER_ADMIN"))
                .andExpect(jsonPath("$.content[1].oldValue").value(nullValue()))
                .andExpect(jsonPath("$.content[1].newValue").value(60))
                .andExpect(jsonPath("$.content[1].changedBy.name").value("Admin OPS_ADMIN"));
        String changedAt = body(mvc.perform(get(URL + "/history?page=1&size=1").with(admin(adminId, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].newValue").value(60))
                .andExpect(jsonPath("$.totalPages").value(2))).get("content").get(0).get("changedAt").asString();
        assertThat(Instant.parse(changedAt)).isAfter(before);
        mvc.perform(get(URL + "/history").with(customer(customer))).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/history")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyChangeIsPublishedAsPlatformSettingChanged() throws Exception {
        save("OPS_ADMIN", "{\"reviewWindowDays\":60}", 0).andExpect(status().isOk());
        save("SUPER_ADMIN", "{\"reviewWindowDays\":null}", 1).andExpect(status().isOk());
        // an unchanged value and a refused save publish nothing
        save("SUPER_ADMIN", "{\"reviewWindowDays\":null}", 2).andExpect(status().isOk());
        save("OPS_ADMIN", "{\"reviewWindowDays\":21}", 0).andExpect(status().isConflict());

        List<SettingEvent> events = settingEvents();
        assertThat(events).extracting(SettingEvent::change).containsExactly(
                "reviews.reviewWindowDays: null -> 60", "reviews.reviewWindowDays: 60 -> null");
        assertThat(events).allSatisfy(this::assertPublishedWithItsAuditRow);
        assertThat(events).extracting(event -> event.payload().get("changedByName").asString())
                .containsExactly("Admin OPS_ADMIN", "Admin SUPER_ADMIN");
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
                .param(adminId.toString())
                .query((rs, n) -> new SettingEvent(rs.getString("topic"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), json.readTree(rs.getString("payload"))))
                .list();
    }

    /**
     * One event: on review.events, keyed by the setting key, with the eight fields of the schema, and the same key,
     * values, admin and time as the platform_setting_change row written with it.
     */
    private void assertPublishedWithItsAuditRow(SettingEvent event) {
        JsonNode payload = event.payload();
        String key = payload.get("settingKey").asString();
        assertThat(event.topic()).isEqualTo("review.events");
        assertThat(event.aggregateType()).isEqualTo("PlatformSetting");
        assertThat(event.key()).isEqualTo(key);
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "settingKey", "area", "field", "oldValue", "newValue", "changedBy", "changedByName", "changedAt");
        assertThat(payload.get("area").asString()).isEqualTo("reviews");
        assertThat(key).isEqualTo("reviews." + payload.get("field").asString());
        assertThat(payload.get("changedBy").asString()).isEqualTo(adminId.toString());
        Instant rowChangedAt = jdbc().sql("""
                        SELECT changed_at FROM platform_setting_change
                        WHERE setting_key = :key AND old_value <=> :oldValue AND new_value <=> :newValue
                          AND changed_by = UUID_TO_BIN(:by) AND changed_by_name <=> :name""")
                .param("key", key)
                .param("oldValue", text(payload.get("oldValue")))
                .param("newValue", text(payload.get("newValue")))
                .param("by", adminId.toString())
                .param("name", text(payload.get("changedByName")))
                .query((rs, n) -> rs.getObject("changed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                .single();
        assertThat(event.changedAt()).isCloseTo(rowChangedAt, within(1, ChronoUnit.MICROS));
    }
}
