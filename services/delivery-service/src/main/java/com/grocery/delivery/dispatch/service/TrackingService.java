package com.grocery.delivery.dispatch.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TrackingView;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Customer tracking (contract §4): the order's customer (customerId = the token's subject) or any admin. No delivery
 * yet, or another customer's order → 404 TRACKING_NOT_FOUND (a customer never learns that it exists).
 */
@Service
public class TrackingService {

    private final DeliveryRepository deliveries;
    private final DeliveryPartnerRepository partners;

    public TrackingService(DeliveryRepository deliveries, DeliveryPartnerRepository partners) {
        this.deliveries = deliveries;
        this.partners = partners;
    }

    @Transactional(readOnly = true)
    public TrackingView track(CurrentUser user, UUID orderId) {
        return deliveries.findByOrderId(orderId)
                .filter(delivery -> user.isAdmin() || delivery.getCustomerId().equals(user.userId()))
                .map(delivery -> {
                    DeliveryPartner partner = delivery.getPartnerId() == null ? null
                            : partners.findById(delivery.getPartnerId()).orElse(null);
                    return DeliveryMapper.toTracking(delivery, partner);
                })
                .orElseThrow(DispatchException::trackingNotFound);
    }
}
