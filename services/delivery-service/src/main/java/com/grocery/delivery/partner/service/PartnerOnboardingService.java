package com.grocery.delivery.partner.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.NotFoundException;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerUpdateRequest;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerView;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.entity.PartnerStatus;
import com.grocery.delivery.partner.event.PartnerEvents;
import com.grocery.delivery.partner.mapper.PartnerMapper;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** The partner's own onboarding: draft creation, editing, submitting for approval. */
@Service
public class PartnerOnboardingService {

    public static final String IDENTITY_CONSUMER = "delivery-service.identity-events";
    private static final Logger log = LoggerFactory.getLogger(PartnerOnboardingService.class);

    private final DeliveryPartnerRepository partners;
    private final PartnerLifecycleRecorder recorder;
    private final ProcessedEventGuard processedEvents;
    private final Clock clock;

    public PartnerOnboardingService(DeliveryPartnerRepository partners, PartnerLifecycleRecorder recorder,
                                    ProcessedEventGuard processedEvents, Clock clock) {
        this.partners = partners;
        this.recorder = recorder;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /** UserRegistered(role=DELIVERY_PARTNER) → DRAFT partner profile with id = userId (idempotent). */
    @Transactional
    public void onDeliveryPartnerRegistered(String eventId, UUID userId, String fullName, String email, String phone) {
        if (!processedEvents.markProcessed(eventId, IDENTITY_CONSUMER)) {
            return;
        }
        if (partners.existsById(userId)) {
            return; // already created on demand by the partner's first save
        }
        createDraft(userId, fullName, email, phone);
        log.info("Created draft delivery partner profile for user {}", userId);
    }

    @Transactional(readOnly = true)
    public PartnerView myProfile(CurrentUser user) {
        return PartnerMapper.toView(partners.findById(user.userId()).orElseThrow(PartnerOnboardingService::notFound));
    }

    /**
     * Saves the draft. If the registration event has not arrived yet the profile is created here,
     * so onboarding never depends on event timing.
     */
    @Transactional
    public PartnerView updateMyProfile(CurrentUser user, PartnerUpdateRequest request) {
        DeliveryPartner partner = partners.findById(user.userId())
                .orElseGet(() -> createDraft(user.userId(), user.name(), user.email(), null));
        partner.updateDetails(PartnerMapper.trim(request.fullName()), PartnerMapper.trim(request.phone()),
                PartnerMapper.trim(request.city()), PartnerMapper.toVehicleType(request.vehicleType()),
                PartnerMapper.toCode(request.vehicleNumber()), PartnerMapper.toCode(request.drivingLicenseNumber()),
                clock.instant());
        return PartnerMapper.toView(partner);
    }

    @Transactional
    public PartnerView submitMyProfile(CurrentUser user) {
        DeliveryPartner partner = partners.findById(user.userId()).orElseThrow(PartnerOnboardingService::notFound);
        PartnerStatus from = partner.submit(clock.instant());
        recorder.record(partner, from, PartnerEvents.SUBMITTED, null, user.userId());
        return PartnerMapper.toView(partner);
    }

    private DeliveryPartner createDraft(UUID userId, String fullName, String email, String phone) {
        DeliveryPartner partner = partners.saveAndFlush(DeliveryPartner.draft(userId, fullName, email, phone, clock.instant()));
        recorder.record(partner, null, PartnerEvents.REGISTERED, null, userId);
        return partner;
    }

    static NotFoundException notFound() {
        return new NotFoundException("PARTNER_NOT_FOUND", "Delivery partner profile not found");
    }
}
