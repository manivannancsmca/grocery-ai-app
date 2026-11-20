package com.grocery.delivery.settings;

import com.grocery.common.data.Pages;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.settings.PlatformSettingStore.AreaState;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Area {@code delivery} of the platform settings (docs/api/platform-settings.md): the partner search. The defaults
 * are {@link DispatchProperties} ({@code grocery.delivery.*}); an admin's saved value overrides one.
 */
@Service
public class DeliverySettingsService {

    public static final String AREA = "delivery";

    static final String SEARCH_RADII_KM = "searchRadiiKm";
    static final String OFFERS_PER_WAVE = "offersPerWave";
    static final String OFFER_TIME_LIMIT_SECONDS = "offerTimeLimitSeconds";
    static final String LOCATION_FRESHNESS_SECONDS = "locationFreshnessSeconds";

    /** Each field's stored text → its value in the API, the same units and types as {@link #view()} (history). */
    private static final Map<String, Function<String, Object>> API_VALUES = Map.of(
            SEARCH_RADII_KM, DeliverySettingsService::radii,
            OFFERS_PER_WAVE, Integer::valueOf,
            OFFER_TIME_LIMIT_SECONDS, Long::valueOf,
            LOCATION_FRESHNESS_SECONDS, Long::valueOf);

    private final PlatformSettingStore store;
    private final DispatchProperties defaults;

    public DeliverySettingsService(PlatformSettingStore store, DispatchProperties defaults) {
        this.store = store;
        this.defaults = defaults;
    }

    /** The settings in force now. Cheap (an in-memory snapshot): dispatch asks every time it searches. */
    public DeliverySettings current() {
        return current(store.area(AREA));
    }

    public SettingsView view() {
        AreaState state = store.area(AREA);
        DeliverySettings configured = new DeliverySettings(defaults.searchRadiiKm(), defaults.offerBatchSize(),
                defaults.offerTtl(), defaults.locationStaleAfter());
        return SettingsView.of(AREA, state, api(current(state)), api(configured));
    }

    /** Saves the whole area (null field = back to the default) and returns the new view. */
    public SettingsView update(DeliverySettings.Update request, CurrentUser user) {
        DeliverySettings.Values values = request.values();
        List<BigDecimal> radii = values.searchRadiiKm();
        if (radii != null) {
            for (int i = 1; i < radii.size(); i++) {
                if (radii.get(i).compareTo(radii.get(i - 1)) <= 0) {
                    throw SettingsException.invalid("values." + SEARCH_RADII_KM,
                            "must be strictly ascending (each step larger than the one before)");
                }
            }
        }
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put(SEARCH_RADII_KM, radii == null ? null
                : radii.stream().map(km -> km.stripTrailingZeros().toPlainString()).collect(Collectors.joining(",")));
        stored.put(OFFERS_PER_WAVE, text(values.offersPerWave()));
        stored.put(OFFER_TIME_LIMIT_SECONDS, text(values.offerTimeLimitSeconds()));
        stored.put(LOCATION_FRESHNESS_SECONDS, text(values.locationFreshnessSeconds()));
        store.save(AREA, stored, request.expectedVersion(), user);
        return view();
    }

    /** The area's changes, newest first, with the values in the units of {@link #view()}. */
    public PageResponse<SettingChange> history(Integer page, Integer size) {
        return store.history(AREA, Pages.request(page, size, Sort.unsorted()))
                .map(change -> SettingChange.of(change, API_VALUES));
    }

    private DeliverySettings current(AreaState state) {
        return new DeliverySettings(
                state.value(SEARCH_RADII_KM, DeliverySettingsService::radii, defaults.searchRadiiKm()),
                state.value(OFFERS_PER_WAVE, Integer::valueOf, defaults.offerBatchSize()),
                state.value(OFFER_TIME_LIMIT_SECONDS, seconds -> Duration.ofSeconds(Long.parseLong(seconds)),
                        defaults.offerTtl()),
                state.value(LOCATION_FRESHNESS_SECONDS, seconds -> Duration.ofSeconds(Long.parseLong(seconds)),
                        defaults.locationStaleAfter()));
    }

    /** The fields in the API's units, in the area's order. */
    private static Map<String, Object> api(DeliverySettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SEARCH_RADII_KM, settings.searchRadiiKm());
        values.put(OFFERS_PER_WAVE, settings.offersPerWave());
        values.put(OFFER_TIME_LIMIT_SECONDS, SettingsView.amount(settings.offerTimeLimit(), ChronoUnit.SECONDS));
        values.put(LOCATION_FRESHNESS_SECONDS, SettingsView.amount(settings.locationFreshness(), ChronoUnit.SECONDS));
        return values;
    }

    /** "3,6,10" → [3.0, 6.0, 10.0]; refuses what dispatch could not use (not positive or not ascending). */
    private static List<Double> radii(String text) {
        List<Double> radii = Arrays.stream(text.split(",")).map(String::trim).map(Double::valueOf).toList();
        double previous = 0;
        for (double radius : radii) {
            if (radius <= previous) {
                throw new IllegalArgumentException("radii must be positive and ascending");
            }
            previous = radius;
        }
        return radii;
    }

    private static String text(Integer value) {
        return value == null ? null : value.toString();
    }
}
