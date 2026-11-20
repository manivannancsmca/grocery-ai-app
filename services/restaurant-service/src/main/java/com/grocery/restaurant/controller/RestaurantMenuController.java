package com.grocery.restaurant.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.dto.MenuDtos.AvailabilityRequest;
import com.grocery.restaurant.dto.MenuDtos.MenuCategoryRequest;
import com.grocery.restaurant.dto.MenuDtos.MenuCategoryView;
import com.grocery.restaurant.dto.MenuDtos.MenuItemRequest;
import com.grocery.restaurant.dto.MenuDtos.MenuItemView;
import com.grocery.restaurant.dto.MenuDtos.MenuView;
import com.grocery.restaurant.service.RestaurantMenuService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in owner's menu. The restaurant comes from the JWT's user id (not the restaurant_id claim), so the
 * menu can be built before approval. Ids of another restaurant's categories/items return 404.
 */
@RestController
@RequestMapping("/api/restaurant/menu")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantMenuController {

    private final RestaurantMenuService menus;

    public RestaurantMenuController(RestaurantMenuService menus) {
        this.menus = menus;
    }

    @GetMapping
    public MenuView menu(CurrentUser user) {
        return menus.myMenu(user);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuCategoryView createCategory(CurrentUser user, @Valid @RequestBody MenuCategoryRequest request) {
        return menus.createCategory(user, request);
    }

    @PutMapping("/categories/{id}")
    public MenuCategoryView updateCategory(CurrentUser user, @PathVariable UUID id,
                                           @Valid @RequestBody MenuCategoryRequest request) {
        return menus.updateCategory(user, id, request);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(CurrentUser user, @PathVariable UUID id) {
        menus.deleteCategory(user, id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItemView createItem(CurrentUser user, @Valid @RequestBody MenuItemRequest request) {
        return menus.createItem(user, request);
    }

    @PutMapping("/items/{id}")
    public MenuItemView updateItem(CurrentUser user, @PathVariable UUID id,
                                   @Valid @RequestBody MenuItemRequest request) {
        return menus.updateItem(user, id, request);
    }

    @PatchMapping("/items/{id}/availability")
    public MenuItemView changeAvailability(CurrentUser user, @PathVariable UUID id,
                                           @Valid @RequestBody AvailabilityRequest request) {
        return menus.changeAvailability(user, id, request.available());
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(CurrentUser user, @PathVariable UUID id) {
        menus.deleteItem(user, id);
    }
}
