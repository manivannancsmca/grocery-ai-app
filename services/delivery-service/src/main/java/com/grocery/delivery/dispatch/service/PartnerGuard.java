package com.grocery.delivery.dispatch.service;

import com.grocery.delivery.partner.entity.PartnerStatus;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Contract §3: a partner who is not APPROVED (or has no profile) → 403 PARTNER_NOT_ACTIVE on every partner endpoint.
 * Reads the status as a scalar, so the partner entity can still be loaded under its lock later in the transaction.
 */
@Component
public class PartnerGuard {

    private final DeliveryPartnerRepository partners;

    public PartnerGuard(DeliveryPartnerRepository partners) {
        this.partners = partners;
    }

    public void requireApproved(UUID partnerId) {
        if (partners.findStatusById(partnerId).filter(status -> status == PartnerStatus.APPROVED).isEmpty()) {
            throw DispatchException.partnerNotActive();
        }
    }
}
