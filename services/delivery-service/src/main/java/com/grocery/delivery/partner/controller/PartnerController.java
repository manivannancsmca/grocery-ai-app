package com.grocery.delivery.partner.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerUpdateRequest;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerView;
import com.grocery.delivery.partner.service.PartnerOnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in rider's own partner profile. Identity comes from the JWT (partnerId = userId), never from the URL. */
@RestController
@RequestMapping("/api/delivery/partners/me")
@PreAuthorize("hasRole('DELIVERY_PARTNER')")
public class PartnerController {

    private final PartnerOnboardingService onboarding;

    public PartnerController(PartnerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @GetMapping
    public PartnerView me(CurrentUser user) {
        return onboarding.myProfile(user);
    }

    @PutMapping
    public PartnerView update(CurrentUser user, @Valid @RequestBody PartnerUpdateRequest request) {
        return onboarding.updateMyProfile(user, request);
    }

    @PostMapping("/submit")
    public PartnerView submit(CurrentUser user) {
        return onboarding.submitMyProfile(user);
    }
}
