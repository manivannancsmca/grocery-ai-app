package com.grocery.restaurant.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantUpdateRequest;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantView;
import com.grocery.restaurant.service.RestaurantOnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in owner's own restaurant profile. Ownership comes from the JWT, never from the URL. */
@RestController
@RequestMapping("/api/restaurants/me")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantController {

    private final RestaurantOnboardingService onboarding;

    public RestaurantController(RestaurantOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @GetMapping
    public RestaurantView me(CurrentUser user) {
        return onboarding.myProfile(user);
    }

    @PutMapping
    public RestaurantView update(CurrentUser user, @Valid @RequestBody RestaurantUpdateRequest request) {
        return onboarding.updateMyProfile(user, request);
    }

    @PostMapping("/submit")
    public RestaurantView submit(CurrentUser user) {
        return onboarding.submitMyProfile(user);
    }
}
