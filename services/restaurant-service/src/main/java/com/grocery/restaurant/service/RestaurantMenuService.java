package com.grocery.restaurant.service;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import com.grocery.common.web.NotFoundException;
import com.grocery.restaurant.dto.MenuDtos.MenuCategoryRequest;
import com.grocery.restaurant.dto.MenuDtos.MenuCategoryView;
import com.grocery.restaurant.dto.MenuDtos.MenuItemRequest;
import com.grocery.restaurant.dto.MenuDtos.MenuItemView;
import com.grocery.restaurant.dto.MenuDtos.MenuView;
import com.grocery.restaurant.entity.FoodType;
import com.grocery.restaurant.entity.MenuCategory;
import com.grocery.restaurant.entity.MenuItem;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.mapper.MenuMapper;
import com.grocery.restaurant.mapper.RestaurantMapper;
import com.grocery.restaurant.repository.MenuCategoryRepository;
import com.grocery.restaurant.repository.MenuItemRepository;
import com.grocery.restaurant.repository.RestaurantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The owner's menu (categories and items) plus the admin's read-only view. The menu can be built in any status
 * except SUSPENDED (409 RESTAURANT_SUSPENDED), so it is ready before approval. Every change follows the
 * lock → change → publish rule of {@link RestaurantListingPublisher}; the row lock also makes the limit and
 * name checks race-free.
 */
@Service
public class RestaurantMenuService {

    public static final int MAX_CATEGORIES = 30;
    public static final int MAX_ITEMS = 500;
    private static final String CATEGORY_NAME_KEY = "uk_menu_category_restaurant_name";

    private final RestaurantRepository restaurants;
    private final MenuCategoryRepository categories;
    private final MenuItemRepository items;
    private final RestaurantListingPublisher listings;
    private final Clock clock;

    public RestaurantMenuService(RestaurantRepository restaurants, MenuCategoryRepository categories,
                                 MenuItemRepository items, RestaurantListingPublisher listings, Clock clock) {
        this.restaurants = restaurants;
        this.categories = categories;
        this.items = items;
        this.listings = listings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MenuView myMenu(CurrentUser owner) {
        return menuOf(restaurants.findByOwnerUserId(owner.userId())
                .orElseThrow(RestaurantOnboardingService::notFound));
    }

    /** Any admin, any status: lets reviewers check the menu before approving. */
    @Transactional(readOnly = true)
    public MenuView menu(UUID restaurantId) {
        return menuOf(restaurants.findById(restaurantId).orElseThrow(RestaurantOnboardingService::notFound));
    }

    @Transactional
    public MenuCategoryView createCategory(CurrentUser owner, MenuCategoryRequest request) {
        Restaurant restaurant = lockForOwnerChange(owner);
        if (categories.countByRestaurantId(restaurant.getId()) >= MAX_CATEGORIES) {
            throw new BusinessRuleException("MENU_LIMIT_REACHED",
                    "A menu can have at most " + MAX_CATEGORIES + " categories");
        }
        String name = RestaurantMapper.trim(request.name());
        if (categories.existsByRestaurantIdAndNameIgnoreCase(restaurant.getId(), name)) {
            throw nameTaken(name);
        }
        MenuCategory category = MenuCategory.create(Ids.newId(), restaurant.getId(), name,
                RestaurantMapper.trim(request.description()), sortOrder(request.sortOrder()), clock.instant());
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw translateNameConflict(e, name);
        }
        listings.publishChange(restaurant);
        return MenuMapper.toView(category, List.of());
    }

    @Transactional
    public MenuCategoryView updateCategory(CurrentUser owner, UUID categoryId, MenuCategoryRequest request) {
        Restaurant restaurant = lockForOwnerChange(owner);
        MenuCategory category = category(restaurant, categoryId);
        String name = RestaurantMapper.trim(request.name());
        if (categories.existsByRestaurantIdAndNameIgnoreCaseAndIdNot(restaurant.getId(), name, categoryId)) {
            throw nameTaken(name);
        }
        category.update(name, RestaurantMapper.trim(request.description()), sortOrder(request.sortOrder()),
                clock.instant());
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            throw translateNameConflict(e, name);
        }
        listings.publishChange(restaurant);
        return MenuMapper.toView(category, items.findByCategoryId(categoryId));
    }

    @Transactional
    public void deleteCategory(CurrentUser owner, UUID categoryId) {
        Restaurant restaurant = lockForOwnerChange(owner);
        MenuCategory category = category(restaurant, categoryId);
        if (items.existsByCategoryId(categoryId)) {
            throw new ConflictException("MENU_CATEGORY_NOT_EMPTY",
                    "Move or delete the category's items before deleting it");
        }
        categories.delete(category);
        listings.publishChange(restaurant);
    }

    @Transactional
    public MenuItemView createItem(CurrentUser owner, MenuItemRequest request) {
        Restaurant restaurant = lockForOwnerChange(owner);
        MenuCategory category = category(restaurant, request.categoryId());
        if (items.countByRestaurantId(restaurant.getId()) >= MAX_ITEMS) {
            throw new BusinessRuleException("MENU_LIMIT_REACHED", "A menu can have at most " + MAX_ITEMS + " items");
        }
        MenuItem item = items.save(MenuItem.create(Ids.newId(), restaurant.getId(), category.getId(),
                RestaurantMapper.trim(request.name()), RestaurantMapper.trim(request.description()), request.price(),
                FoodType.valueOf(request.foodType()), RestaurantMapper.trim(request.imageUrl()),
                request.available() == null || request.available(), sortOrder(request.sortOrder()),
                clock.instant()));
        listings.publishChange(restaurant);
        return MenuMapper.toView(item);
    }

    /** Full replacement (PUT); may move the item to another category of the same restaurant. */
    @Transactional
    public MenuItemView updateItem(CurrentUser owner, UUID itemId, MenuItemRequest request) {
        Restaurant restaurant = lockForOwnerChange(owner);
        MenuItem item = item(restaurant, itemId);
        MenuCategory category = category(restaurant, request.categoryId());
        item.update(category.getId(), RestaurantMapper.trim(request.name()),
                RestaurantMapper.trim(request.description()), request.price(), FoodType.valueOf(request.foodType()),
                RestaurantMapper.trim(request.imageUrl()), request.available() == null || request.available(),
                sortOrder(request.sortOrder()), clock.instant());
        listings.publishChange(restaurant);
        return MenuMapper.toView(item);
    }

    /** "Sold out" toggle. Setting the value it already has changes nothing and publishes nothing. */
    @Transactional
    public MenuItemView changeAvailability(CurrentUser owner, UUID itemId, boolean available) {
        Restaurant restaurant = lockForOwnerChange(owner);
        MenuItem item = item(restaurant, itemId);
        if (item.changeAvailability(available, clock.instant())) {
            listings.publishChange(restaurant);
        }
        return MenuMapper.toView(item);
    }

    @Transactional
    public void deleteItem(CurrentUser owner, UUID itemId) {
        Restaurant restaurant = lockForOwnerChange(owner);
        items.delete(item(restaurant, itemId));
        listings.publishChange(restaurant);
    }

    private Restaurant lockForOwnerChange(CurrentUser owner) {
        Restaurant restaurant = listings.lockByOwner(owner.userId());
        restaurant.requireOwnerChangesAllowed();
        return restaurant;
    }

    private MenuView menuOf(Restaurant restaurant) {
        return MenuMapper.toView(restaurant, categories.findByRestaurantId(restaurant.getId()),
                items.findByRestaurantId(restaurant.getId()));
    }

    /** Unknown and other restaurants' categories look the same: 404. */
    private MenuCategory category(Restaurant restaurant, UUID categoryId) {
        return categories.findByIdAndRestaurantId(categoryId, restaurant.getId())
                .orElseThrow(() -> new NotFoundException("MENU_CATEGORY_NOT_FOUND", "Menu category not found"));
    }

    private MenuItem item(Restaurant restaurant, UUID itemId) {
        return items.findByIdAndRestaurantId(itemId, restaurant.getId())
                .orElseThrow(() -> new NotFoundException("MENU_ITEM_NOT_FOUND", "Menu item not found"));
    }

    private static int sortOrder(Integer sortOrder) {
        return sortOrder == null ? 0 : sortOrder;
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException("MENU_CATEGORY_NAME_TAKEN", "The menu already has a category named '" + name + "'");
    }

    /**
     * The unique key is the last line of defence (the pre-check runs under the restaurant lock, and the
     * collation also treats accented variants as equal).
     */
    private static RuntimeException translateNameConflict(DataIntegrityViolationException e, String name) {
        String cause = e.getMostSpecificCause().getMessage();
        return cause != null && cause.contains(CATEGORY_NAME_KEY) ? nameTaken(name) : e;
    }
}
