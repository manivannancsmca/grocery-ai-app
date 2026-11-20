package com.grocery.restaurant.service;

import com.grocery.common.web.NotFoundException;
import com.grocery.restaurant.config.RestaurantProperties;
import com.grocery.restaurant.dto.MenuQuoteDtos.MenuQuote;
import com.grocery.restaurant.dto.MenuQuoteDtos.QuotedItem;
import com.grocery.restaurant.entity.BusinessAddress;
import com.grocery.restaurant.entity.MenuItem;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.repository.MenuItemRepository;
import com.grocery.restaurant.repository.RestaurantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The internal menu quote (docs/api/phase-5-cart-and-checkout-preview.md §4): what the cart needs to price and
 * check restaurant lines, read from MySQL (authoritative, not the search copy). Read-only and lock-free — plain
 * consistent reads in one read-only transaction, so it never waits for (or blocks) an owner's menu change.
 * The restaurant is returned in any status; callers decide what is orderable.
 */
@Service
public class MenuQuoteService {

    private final RestaurantRepository restaurants;
    private final MenuItemRepository items;
    private final RestaurantProperties properties;
    private final Clock clock;

    public MenuQuoteService(RestaurantRepository restaurants, MenuItemRepository items,
                            RestaurantProperties properties, Clock clock) {
        this.restaurants = restaurants;
        this.items = items;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param itemIds 1–100 ids (validated by the controller); duplicates are quoted once
     * @throws NotFoundException 404 RESTAURANT_NOT_FOUND
     */
    @Transactional(readOnly = true)
    public MenuQuote quote(UUID restaurantId, List<UUID> itemIds) {
        Restaurant restaurant = restaurants.findById(restaurantId)
                .orElseThrow(() -> new NotFoundException("RESTAURANT_NOT_FOUND",
                        "Restaurant " + restaurantId + " was not found"));
        List<UUID> ids = itemIds.stream().distinct().toList();
        Map<UUID, MenuItem> onMenu = items.findByRestaurantIdAndIdIn(restaurantId, ids).stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));

        List<QuotedItem> quoted = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            MenuItem item = onMenu.get(id);
            if (item == null) {
                missing.add(id);
            } else {
                quoted.add(new QuotedItem(item.getId(), item.getName(), item.getPrice(), item.getFoodType().name(),
                        item.isAvailable(), item.getImageUrl()));
            }
        }

        boolean openNow = OpeningSchedule.isOpen(restaurant.getOpeningHours(), clock.instant(), properties.zone());
        BusinessAddress address = restaurant.getAddress();
        return new MenuQuote(restaurant.getId(), restaurant.getName(), restaurant.getStatus().name(),
                restaurant.isAcceptingOrders(), openNow,
                address == null ? null : address.getCity(),
                address == null ? null : address.getLatitude(),
                address == null ? null : address.getLongitude(),
                quoted, missing);
    }
}
