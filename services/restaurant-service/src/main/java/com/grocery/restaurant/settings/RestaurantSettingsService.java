package com.grocery.restaurant.settings;

import com.grocery.common.data.Pages;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.restaurant.config.FulfillmentProperties;
import com.grocery.restaurant.settings.PlatformSettingStore.AreaState;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Area {@code restaurants} of the platform settings (docs/api/platform-settings.md): the restaurant order board. The
 * default is {@link FulfillmentProperties#acceptanceTimeout()} ({@code grocery.fulfillment.acceptance-timeout});
 * an admin's saved value overrides it.
 */
@Service
public class RestaurantSettingsService {

    public static final String AREA = "restaurants";

    static final String ACCEPTANCE_TIME_MINUTES = "acceptanceTimeMinutes";

    /** Each field's stored text → its value in the API, the same units and types as {@link #view()} (history). */
    private static final Map<String, Function<String, Object>> API_VALUES = Map.of(
            ACCEPTANCE_TIME_MINUTES, Long::valueOf);

    private final PlatformSettingStore store;
    private final FulfillmentProperties defaults;

    public RestaurantSettingsService(PlatformSettingStore store, FulfillmentProperties defaults) {
        this.store = store;
        this.defaults = defaults;
    }

    /** The settings in force now. Cheap (an in-memory snapshot): asked for every new order. */
    public RestaurantSettings current() {
        return current(store.area(AREA));
    }

    public SettingsView view() {
        AreaState state = store.area(AREA);
        RestaurantSettings configured = new RestaurantSettings(defaults.acceptanceTimeout());
        return SettingsView.of(AREA, state, api(current(state)), api(configured));
    }

    /** Saves the whole area (null field = back to the default) and returns the new view. */
    public SettingsView update(RestaurantSettings.Update request, CurrentUser user) {
        Integer minutes = request.values().acceptanceTimeMinutes();
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put(ACCEPTANCE_TIME_MINUTES, minutes == null ? null : minutes.toString());
        store.save(AREA, stored, request.expectedVersion(), user);
        return view();
    }

    /** The area's changes, newest first, with the values in the units of {@link #view()}. */
    public PageResponse<SettingChange> history(Integer page, Integer size) {
        return store.history(AREA, Pages.request(page, size, Sort.unsorted()))
                .map(change -> SettingChange.of(change, API_VALUES));
    }

    private RestaurantSettings current(AreaState state) {
        return new RestaurantSettings(state.value(ACCEPTANCE_TIME_MINUTES,
                minutes -> Duration.ofMinutes(Long.parseLong(minutes)), defaults.acceptanceTimeout()));
    }

    private static Map<String, Object> api(RestaurantSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ACCEPTANCE_TIME_MINUTES, SettingsView.amount(settings.acceptanceTime(), ChronoUnit.MINUTES));
        return values;
    }
}
