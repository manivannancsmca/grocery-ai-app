package com.grocery.restaurant.mapper;

import com.grocery.restaurant.dto.MenuDtos.MenuCategoryView;
import com.grocery.restaurant.dto.MenuDtos.MenuItemView;
import com.grocery.restaurant.dto.MenuDtos.MenuView;
import com.grocery.restaurant.entity.BusinessAddress;
import com.grocery.restaurant.entity.MenuCategory;
import com.grocery.restaurant.entity.MenuItem;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.event.RestaurantListingUpdated;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Menu entities → API views and → the {@link RestaurantListingUpdated} snapshot. Both use the same order:
 * sortOrder, then name (case-insensitive), then id so that equal names stay stable.
 */
public final class MenuMapper {

    public static final Comparator<MenuCategory> CATEGORY_ORDER = Comparator
            .comparingInt(MenuCategory::getSortOrder)
            .thenComparing(MenuCategory::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(MenuCategory::getId);

    public static final Comparator<MenuItem> ITEM_ORDER = Comparator
            .comparingInt(MenuItem::getSortOrder)
            .thenComparing(MenuItem::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(MenuItem::getId);

    private MenuMapper() {
    }

    public static MenuView toView(Restaurant restaurant, List<MenuCategory> categories, List<MenuItem> items) {
        Map<UUID, List<MenuItem>> byCategory = groupByCategory(items);
        List<MenuCategoryView> views = categories.stream().sorted(CATEGORY_ORDER)
                .map(c -> toView(c, byCategory.getOrDefault(c.getId(), List.of())))
                .toList();
        return new MenuView(restaurant.getId(), restaurant.getStatus().name(), restaurant.isAcceptingOrders(),
                items.size(), views);
    }

    /** {@code items}: the category's items in any order. */
    public static MenuCategoryView toView(MenuCategory category, List<MenuItem> items) {
        return new MenuCategoryView(category.getId(), category.getName(), category.getDescription(),
                category.getSortOrder(), items.stream().sorted(ITEM_ORDER).map(MenuMapper::toView).toList());
    }

    public static MenuItemView toView(MenuItem item) {
        return new MenuItemView(item.getId(), item.getCategoryId(), item.getName(), item.getDescription(),
                item.getPrice(), item.getFoodType().name(), item.getImageUrl(), item.isAvailable(),
                item.getSortOrder(), item.getUpdatedAt());
    }

    /** The complete public listing as it is now (unavailable items included, empty categories kept). */
    public static RestaurantListingUpdated toListing(Restaurant r, List<MenuCategory> categories,
                                                     List<MenuItem> items) {
        Map<UUID, List<MenuItem>> byCategory = groupByCategory(items);
        List<RestaurantListingUpdated.Category> menu = categories.stream().sorted(CATEGORY_ORDER)
                .map(c -> new RestaurantListingUpdated.Category(c.getId(), c.getName(), c.getDescription(),
                        c.getSortOrder(), byCategory.getOrDefault(c.getId(), List.of()).stream()
                        .sorted(ITEM_ORDER).map(MenuMapper::toListingItem).toList()))
                .toList();
        List<RestaurantListingUpdated.Hours> hours = RestaurantMapper.sortedHours(r.getOpeningHours()).stream()
                .map(h -> new RestaurantListingUpdated.Hours(h.getDayOfWeek().name(),
                        RestaurantMapper.formatTime(h.getOpensAt()), RestaurantMapper.formatTime(h.getClosesAt())))
                .toList();
        return new RestaurantListingUpdated(r.getId(), r.getOwnerUserId(), r.getStatus().name(), r.getName(),
                r.getDescription(), r.getCuisines(), toListingAddress(r.getAddress()), hours, r.isAcceptingOrders(),
                menu, r.getListingVersion(), r.getUpdatedAt());
    }

    private static RestaurantListingUpdated.Item toListingItem(MenuItem i) {
        return new RestaurantListingUpdated.Item(i.getId(), i.getName(), i.getDescription(), i.getPrice(),
                i.getFoodType().name(), i.getImageUrl(), i.isAvailable(), i.getSortOrder());
    }

    private static RestaurantListingUpdated.Address toListingAddress(BusinessAddress a) {
        if (a == null) {
            return null;
        }
        return new RestaurantListingUpdated.Address(a.getLine1(), a.getLine2(), a.getCity(), a.getState(),
                a.getPostalCode(), a.getCountry(), a.getLatitude(), a.getLongitude());
    }

    private static Map<UUID, List<MenuItem>> groupByCategory(List<MenuItem> items) {
        return items.stream().collect(Collectors.groupingBy(MenuItem::getCategoryId));
    }
}
