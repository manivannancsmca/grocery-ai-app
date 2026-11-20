package com.grocery.vendor.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.NotFoundException;
import com.grocery.vendor.dto.VendorDtos.VendorUpdateRequest;
import com.grocery.vendor.dto.VendorDtos.VendorView;
import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.entity.VendorStatus;
import com.grocery.vendor.event.VendorEvents;
import com.grocery.vendor.mapper.VendorMapper;
import com.grocery.vendor.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** The vendor's own onboarding: draft creation, editing, submitting for approval. */
@Service
public class VendorOnboardingService {

    public static final String IDENTITY_CONSUMER = "vendor-service.identity-events";
    private static final Logger log = LoggerFactory.getLogger(VendorOnboardingService.class);

    private final VendorRepository vendors;
    private final VendorLifecycleRecorder recorder;
    private final ProcessedEventGuard processedEvents;
    private final Clock clock;

    public VendorOnboardingService(VendorRepository vendors, VendorLifecycleRecorder recorder,
                                   ProcessedEventGuard processedEvents, Clock clock) {
        this.vendors = vendors;
        this.recorder = recorder;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /** UserRegistered(role=VENDOR) → DRAFT vendor profile (idempotent). */
    @Transactional
    public void onVendorUserRegistered(String eventId, UUID userId, String fullName, String email, String phone) {
        if (!processedEvents.markProcessed(eventId, IDENTITY_CONSUMER)) {
            return;
        }
        if (vendors.existsByOwnerUserId(userId)) {
            return; // already created on demand by the vendor's first save
        }
        createDraft(userId, fullName, email, phone);
        log.info("Created draft vendor profile for user {}", userId);
    }

    @Transactional(readOnly = true)
    public VendorView myProfile(CurrentUser user) {
        return VendorMapper.toView(vendors.findByOwnerUserId(user.userId()).orElseThrow(VendorOnboardingService::notFound));
    }

    /**
     * Saves the draft. If the registration event has not arrived yet the profile is created here,
     * so onboarding never depends on event timing.
     */
    @Transactional
    public VendorView updateMyProfile(CurrentUser user, VendorUpdateRequest request) {
        Vendor vendor = vendors.findByOwnerUserId(user.userId())
                .orElseGet(() -> createDraft(user.userId(), user.name(), user.email(), null));
        vendor.updateDetails(VendorMapper.trim(request.businessName()), VendorMapper.trim(request.legalName()),
                VendorMapper.trim(request.gstin()), VendorMapper.trim(request.fssaiLicense()),
                VendorMapper.trim(request.contactEmail()), VendorMapper.trim(request.contactPhone()),
                VendorMapper.toEntity(request.address()), clock.instant());
        return VendorMapper.toView(vendor);
    }

    @Transactional
    public VendorView submitMyProfile(CurrentUser user) {
        Vendor vendor = vendors.findByOwnerUserId(user.userId()).orElseThrow(VendorOnboardingService::notFound);
        VendorStatus from = vendor.submit(clock.instant());
        recorder.record(vendor, from, VendorEvents.SUBMITTED, null, user.userId());
        return VendorMapper.toView(vendor);
    }

    private Vendor createDraft(UUID userId, String name, String email, String phone) {
        Instant now = clock.instant();
        Vendor vendor = vendors.saveAndFlush(Vendor.draft(Ids.newId(), userId, name, email, phone, now));
        recorder.record(vendor, null, VendorEvents.REGISTERED, null, userId);
        return vendor;
    }

    static NotFoundException notFound() {
        return new NotFoundException("VENDOR_NOT_FOUND", "Vendor profile not found");
    }
}
