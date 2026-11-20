package com.grocery.pricing.settings;

import com.grocery.common.data.Pages;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.config.DeliveryFeeProperties;
import com.grocery.pricing.settings.PlatformSettingStore.AreaState;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Area {@code delivery-fees} of the platform settings (docs/api/platform-settings.md). This service owns the money
 * rules, so it keeps the fees; cart-service, which charges them in the checkout preview, reads the values in force
 * from {@code GET /internal/delivery-fees}. The defaults are {@link DeliveryFeeProperties}
 * ({@code grocery.pricing.delivery-fees.*}); an admin's saved value overrides one.
 */
@Service
public class DeliveryFeeSettingsService {

    public static final String AREA = "delivery-fees";

    static final String GROCERY_BASE_FEE = "groceryBaseFee";
    static final String GROCERY_INCLUDED_KM = "groceryIncludedKm";
    static final String GROCERY_PER_EXTRA_KM = "groceryPerExtraKm";
    static final String GROCERY_FREE_ABOVE = "groceryFreeAbove";
    static final String GROCERY_MINIMUM_ORDER = "groceryMinimumOrder";
    static final String RESTAURANT_BASE_FEE = "restaurantBaseFee";
    static final String RESTAURANT_INCLUDED_KM = "restaurantIncludedKm";
    static final String RESTAURANT_PER_EXTRA_KM = "restaurantPerExtraKm";

    /**
     * Each field's stored text → its value in the API, as {@link #view()} shows it (history). The texts are already
     * normalised when saved (money with two decimals, km without trailing zeros).
     */
    private static final Map<String, Function<String, Object>> API_VALUES = Map.of(
            GROCERY_BASE_FEE, BigDecimal::new,
            GROCERY_INCLUDED_KM, BigDecimal::new,
            GROCERY_PER_EXTRA_KM, BigDecimal::new,
            GROCERY_FREE_ABOVE, BigDecimal::new,
            GROCERY_MINIMUM_ORDER, BigDecimal::new,
            RESTAURANT_BASE_FEE, BigDecimal::new,
            RESTAURANT_INCLUDED_KM, BigDecimal::new,
            RESTAURANT_PER_EXTRA_KM, BigDecimal::new);

    private final PlatformSettingStore store;
    private final DeliveryFeeProperties defaults;

    public DeliveryFeeSettingsService(PlatformSettingStore store, DeliveryFeeProperties defaults) {
        this.store = store;
        this.defaults = defaults;
    }

    /** The fees in force now. Cheap (an in-memory snapshot). */
    public DeliveryFeeSettings current() {
        return current(store.area(AREA));
    }

    public SettingsView view() {
        AreaState state = store.area(AREA);
        DeliveryFeeSettings configured = new DeliveryFeeSettings(money(defaults.groceryBaseFee()),
                km(defaults.groceryIncludedKm()), money(defaults.groceryPerExtraKm()),
                money(defaults.groceryFreeAbove()), money(defaults.groceryMinimumOrder()),
                money(defaults.restaurantBaseFee()), km(defaults.restaurantIncludedKm()),
                money(defaults.restaurantPerExtraKm()));
        return SettingsView.of(AREA, state, api(current(state)), api(configured));
    }

    /** Saves the whole area (null field = back to the default) and returns the new view. */
    public SettingsView update(DeliveryFeeSettings.Update request, CurrentUser user) {
        DeliveryFeeSettings.Values values = request.values();
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put(GROCERY_BASE_FEE, text(values.groceryBaseFee(), DeliveryFeeSettingsService::money));
        stored.put(GROCERY_INCLUDED_KM, text(values.groceryIncludedKm(), DeliveryFeeSettingsService::km));
        stored.put(GROCERY_PER_EXTRA_KM, text(values.groceryPerExtraKm(), DeliveryFeeSettingsService::money));
        stored.put(GROCERY_FREE_ABOVE, text(values.groceryFreeAbove(), DeliveryFeeSettingsService::money));
        stored.put(GROCERY_MINIMUM_ORDER, text(values.groceryMinimumOrder(), DeliveryFeeSettingsService::money));
        stored.put(RESTAURANT_BASE_FEE, text(values.restaurantBaseFee(), DeliveryFeeSettingsService::money));
        stored.put(RESTAURANT_INCLUDED_KM, text(values.restaurantIncludedKm(), DeliveryFeeSettingsService::km));
        stored.put(RESTAURANT_PER_EXTRA_KM, text(values.restaurantPerExtraKm(), DeliveryFeeSettingsService::money));
        store.save(AREA, stored, request.expectedVersion(), user);
        return view();
    }

    /** The area's changes, newest first, with the values as {@link #view()} shows them. */
    public PageResponse<SettingChange> history(Integer page, Integer size) {
        return store.history(AREA, Pages.request(page, size, Sort.unsorted()))
                .map(change -> SettingChange.of(change, API_VALUES));
    }

    private DeliveryFeeSettings current(AreaState state) {
        return new DeliveryFeeSettings(
                state.value(GROCERY_BASE_FEE, BigDecimal::new, money(defaults.groceryBaseFee())),
                state.value(GROCERY_INCLUDED_KM, BigDecimal::new, km(defaults.groceryIncludedKm())),
                state.value(GROCERY_PER_EXTRA_KM, BigDecimal::new, money(defaults.groceryPerExtraKm())),
                state.value(GROCERY_FREE_ABOVE, BigDecimal::new, money(defaults.groceryFreeAbove())),
                state.value(GROCERY_MINIMUM_ORDER, BigDecimal::new, money(defaults.groceryMinimumOrder())),
                state.value(RESTAURANT_BASE_FEE, BigDecimal::new, money(defaults.restaurantBaseFee())),
                state.value(RESTAURANT_INCLUDED_KM, BigDecimal::new, km(defaults.restaurantIncludedKm())),
                state.value(RESTAURANT_PER_EXTRA_KM, BigDecimal::new, money(defaults.restaurantPerExtraKm())));
    }

    private static Map<String, Object> api(DeliveryFeeSettings fees) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(GROCERY_BASE_FEE, fees.groceryBaseFee());
        values.put(GROCERY_INCLUDED_KM, fees.groceryIncludedKm());
        values.put(GROCERY_PER_EXTRA_KM, fees.groceryPerExtraKm());
        values.put(GROCERY_FREE_ABOVE, fees.groceryFreeAbove());
        values.put(GROCERY_MINIMUM_ORDER, fees.groceryMinimumOrder());
        values.put(RESTAURANT_BASE_FEE, fees.restaurantBaseFee());
        values.put(RESTAURANT_INCLUDED_KM, fees.restaurantIncludedKm());
        values.put(RESTAURANT_PER_EXTRA_KM, fees.restaurantPerExtraKm());
        return values;
    }

    /** Rupees with two decimals, so 25 and 25.00 are the same stored value (no audit row for "no change"). */
    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Kilometres without trailing zeros (3, 2.5). */
    private static BigDecimal km(BigDecimal distance) {
        return distance == null ? null : new BigDecimal(distance.stripTrailingZeros().toPlainString());
    }

    private static String text(BigDecimal value, Function<BigDecimal, BigDecimal> normalise) {
        return value == null ? null : normalise.apply(value).toPlainString();
    }
}
