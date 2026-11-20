package com.grocery.delivery.settings;

import com.grocery.delivery.settings.PlatformSettingStore.AreaState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/admin/settings/{area}} (docs/api/platform-settings.md): the values in force, the config defaults,
 * which fields an admin overrode, the last change and the version a save must name.
 *
 * @param values     field → effective value (the override, else the default), in the area's field order
 * @param defaults   field → the value from application.yml / env
 * @param overridden the fields with an admin override
 * @param updatedAt  the last change of any field (null: never changed)
 * @param updatedBy  the name (or email) of who made it
 * @param version    the area's version: 0 until the first change; {@code expectedVersion} of the next save
 */
public record SettingsView(String area, Map<String, Object> values, Map<String, Object> defaults,
                           List<String> overridden, Instant updatedAt, String updatedBy, long version) {

    public static SettingsView of(String area, AreaState state, Map<String, Object> values,
                                  Map<String, Object> defaults) {
        List<String> overridden = values.keySet().stream().filter(state.overrides()::containsKey).toList();
        return new SettingsView(area, values, defaults, overridden, state.updatedAt(), state.updatedBy(),
                state.version());
    }

    /**
     * {@code duration} in {@code unit}: an integer for whole units, else two decimals (a config value such as
     * {@code 30s} for a setting in minutes is shown as it is, not rounded into a different setting).
     */
    public static Number amount(Duration duration, ChronoUnit unit) {
        long perUnit = unit.getDuration().toNanos();
        long nanos = duration.toNanos();
        return nanos % perUnit == 0
                ? nanos / perUnit
                : BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(perUnit), 2, RoundingMode.HALF_UP);
    }
}
