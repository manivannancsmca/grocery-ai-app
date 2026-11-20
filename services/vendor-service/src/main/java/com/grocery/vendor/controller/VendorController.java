package com.grocery.vendor.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.vendor.dto.VendorDtos.VendorUpdateRequest;
import com.grocery.vendor.dto.VendorDtos.VendorView;
import com.grocery.vendor.service.VendorOnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in vendor's own profile. Ownership comes from the JWT, never from the URL. */
@RestController
@RequestMapping("/api/vendors/me")
@PreAuthorize("hasRole('VENDOR')")
public class VendorController {

    private final VendorOnboardingService onboarding;

    public VendorController(VendorOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @GetMapping
    public VendorView me(CurrentUser user) {
        return onboarding.myProfile(user);
    }

    @PutMapping
    public VendorView update(CurrentUser user, @Valid @RequestBody VendorUpdateRequest request) {
        return onboarding.updateMyProfile(user, request);
    }

    @PostMapping("/submit")
    public VendorView submit(CurrentUser user) {
        return onboarding.submitMyProfile(user);
    }
}
