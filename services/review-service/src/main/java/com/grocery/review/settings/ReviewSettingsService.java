package com.grocery.review.settings;

import com.grocery.common.data.Pages;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.review.config.ReviewProperties;
import com.grocery.review.settings.PlatformSettingStore.AreaState;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Area {@code reviews} of the platform settings (docs/api/platform-settings.md). The default is
 * {@link ReviewProperties#reviewWindow()} ({@code grocery.review.review-window}); an admin's saved value overrides it.
 */
@Service
public class ReviewSettingsService {

    public static final String AREA = "reviews";

    static final String REVIEW_WINDOW_DAYS = "reviewWindowDays";

    /** Each field's stored text → its value in the API, the same units and types as {@link #view()} (history). */
    private static final Map<String, Function<String, Object>> API_VALUES = Map.of(
            REVIEW_WINDOW_DAYS, Long::valueOf);

    private final PlatformSettingStore store;
    private final ReviewProperties defaults;

    public ReviewSettingsService(PlatformSettingStore store, ReviewProperties defaults) {
        this.store = store;
        this.defaults = defaults;
    }

    /** The settings in force now. Cheap (an in-memory snapshot): asked for every eligibility check and review. */
    public ReviewSettings current() {
        return current(store.area(AREA));
    }

    public SettingsView view() {
        AreaState state = store.area(AREA);
        return SettingsView.of(AREA, state, api(current(state)), api(new ReviewSettings(defaults.reviewWindow())));
    }

    /** Saves the whole area (null field = back to the default) and returns the new view. */
    public SettingsView update(ReviewSettings.Update request, CurrentUser user) {
        Integer days = request.values().reviewWindowDays();
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put(REVIEW_WINDOW_DAYS, days == null ? null : days.toString());
        store.save(AREA, stored, request.expectedVersion(), user);
        return view();
    }

    /** The area's changes, newest first, with the values in the units of {@link #view()}. */
    public PageResponse<SettingChange> history(Integer page, Integer size) {
        return store.history(AREA, Pages.request(page, size, Sort.unsorted()))
                .map(change -> SettingChange.of(change, API_VALUES));
    }

    private ReviewSettings current(AreaState state) {
        return new ReviewSettings(state.value(REVIEW_WINDOW_DAYS, days -> Duration.ofDays(Long.parseLong(days)),
                defaults.reviewWindow()));
    }

    private static Map<String, Object> api(ReviewSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(REVIEW_WINDOW_DAYS, SettingsView.amount(settings.reviewWindow(), ChronoUnit.DAYS));
        return values;
    }
}
