package com.grocery.order.settings;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * One entry of {@code GET /api/admin/settings/{area}/history} (docs/api/platform-settings.md): a field a save changed,
 * with its value before and after in the area's API units, as {@code GET} shows them (null = the default).
 *
 * @param field     the field's name in the API, e.g. {@code offersPerWave}
 * @param changedBy the admin's user id, and their name (or email) when they saved
 */
public record SettingChange(String field, Object oldValue, Object newValue, Instant changedAt, ChangedBy changedBy) {

    public record ChangedBy(UUID id, String name) {
    }

    /** {@code change} with its stored texts read by the field's reader in {@code apiValues} (field → reader). */
    static SettingChange of(PlatformSettingStore.Change change, Map<String, Function<String, Object>> apiValues) {
        Function<String, Object> reader = apiValues.get(change.field());
        return new SettingChange(change.field(), value(change.oldValue(), reader), value(change.newValue(), reader),
                change.changedAt(), new ChangedBy(change.changedBy(), change.changedByName()));
    }

    /**
     * A stored text as the API shows it. A text the reader refuses (edited by hand in the database), or of a field this
     * version no longer has, is shown as stored: the trail never hides a change.
     */
    private static Object value(String text, Function<String, Object> reader) {
        if (text == null || reader == null) {
            return text;
        }
        try {
            return reader.apply(text);
        } catch (RuntimeException unreadable) {
            return text;
        }
    }
}
