package com.grocery.customer.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.customer.dto.CustomerDtos.CustomerProfile;
import com.grocery.customer.dto.CustomerDtos.ProfileUpdateRequest;
import com.grocery.customer.service.CustomerProfileService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's own profile. Ownership comes from the JWT, never from the URL. */
@RestController
@RequestMapping("/api/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

    private final CustomerProfileService profiles;

    public CustomerController(CustomerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public CustomerProfile me(CurrentUser user) {
        return profiles.myProfile(user);
    }

    @PutMapping
    public CustomerProfile update(CurrentUser user, @Valid @RequestBody ProfileUpdateRequest request) {
        return profiles.updateMyProfile(user, request);
    }
}
