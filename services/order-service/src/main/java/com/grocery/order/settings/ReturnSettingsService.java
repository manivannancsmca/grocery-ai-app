package com.grocery.order.settings;

import com.grocery.common.data.Pages;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.order.config.OrderProperties;
import com.grocery.order.settings.PlatformSettingStore.AreaState;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Area {@code returns} of the platform settings (docs/api/platform-settings.md). The defaults are
 * {@link OrderProperties.Returns} ({@code grocery.order.returns.*}); an admin's saved value overrides one.
 */
@Service
public class ReturnSettingsService {

    public static final String AREA = "returns";

    static final String RETURN_WINDOW_HOURS = "returnWindowHours";
    static final String STORE_DECISION_HOURS = "storeDecisionHours";

    /** Each field's stored text → its value in the API, the same units and types as {@link #view()} (history). */
    private static final Map<String, Function<String, Object>> API_VALUES = Map.of(
            RETURN_WINDOW_HOURS, Long::valueOf,
            STORE_DECISION_HOURS, Long::valueOf);

    private final PlatformSettingStore store;
    private final OrderProperties.Returns defaults;

    public ReturnSettingsService(PlatformSettingStore store, OrderProperties properties) {
        this.store = store;
        this.defaults = properties.getReturns();
    }

    /** The settings in force now. Cheap (an in-memory snapshot): asked for every return request and order view. */
    public ReturnSettings current() {
        return current(store.area(AREA));
    }

    public SettingsView view() {
        AreaState state = store.area(AREA);
        ReturnSettings configured = new ReturnSettings(defaults.getWindow(), defaults.getDecisionTimeout());
        return SettingsView.of(AREA, state, api(current(state)), api(configured));
    }

    /** Saves the whole area (null field = back to the default) and returns the new view. */
    public SettingsView update(ReturnSettings.Update request, CurrentUser user) {
        ReturnSettings.Values values = request.values();
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put(RETURN_WINDOW_HOURS, text(values.returnWindowHours()));
        stored.put(STORE_DECISION_HOURS, text(values.storeDecisionHours()));
        store.save(AREA, stored, request.expectedVersion(), user);
        return view();
    }

    /** The area's changes, newest first, with the values in the units of {@link #view()}. */
    public PageResponse<SettingChange> history(Integer page, Integer size) {
        return store.history(AREA, Pages.request(page, size, Sort.unsorted()))
                .map(change -> SettingChange.of(change, API_VALUES));
    }

    private ReturnSettings current(AreaState state) {
        return new ReturnSettings(
                state.value(RETURN_WINDOW_HOURS, hours -> Duration.ofHours(Long.parseLong(hours)),
                        defaults.getWindow()),
                state.value(STORE_DECISION_HOURS, hours -> Duration.ofHours(Long.parseLong(hours)),
                        defaults.getDecisionTimeout()));
    }

    private static Map<String, Object> api(ReturnSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(RETURN_WINDOW_HOURS, SettingsView.amount(settings.returnWindow(), ChronoUnit.HOURS));
        values.put(STORE_DECISION_HOURS, SettingsView.amount(settings.storeDecisionTime(), ChronoUnit.HOURS));
        return values;
    }

    private static String text(Integer value) {
        return value == null ? null : value.toString();
    }
}
