package com.grocery.delivery.dispatch.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.ForbiddenOperationException;
import com.grocery.delivery.dispatch.dto.DispatchDtos.StoreDeliveryView;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The store's deliveries (phase-9 contract, store addendum): who is collecting each order still at the store. The
 * store comes from the token, never a parameter: a VENDOR's {@code vendor_id} claim, a RESTAURANT_OWNER's
 * {@code restaurant_id} claim; without it (not approved yet) → 403 VENDOR_NOT_ACTIVE / RESTAURANT_NOT_ACTIVE.
 */
@Service
public class StoreDeliveryService {

    /** Still at the store: not picked up yet (and not cancelled). */
    static final Set<DeliveryStatus> AT_STORE =
            EnumSet.of(DeliveryStatus.PENDING, DeliveryStatus.SEARCHING, DeliveryStatus.ASSIGNED);
    static final int MAX_DELIVERIES = 100;

    private final DeliveryRepository deliveries;
    private final DeliveryPartnerRepository partners;

    public StoreDeliveryService(DeliveryRepository deliveries, DeliveryPartnerRepository partners) {
        this.deliveries = deliveries;
        this.partners = partners;
    }

    /** The caller's store's PENDING / SEARCHING / ASSIGNED deliveries, oldest first, at most 100. */
    @Transactional(readOnly = true)
    public List<StoreDeliveryView> atStore(CurrentUser user) {
        List<Delivery> open = deliveries.findOfStore(storeId(user), AT_STORE, PageRequest.of(0, MAX_DELIVERIES));
        Set<UUID> partnerIds = open.stream().map(Delivery::getPartnerId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, DeliveryPartner> byId = partnerIds.isEmpty() ? Map.of()
                : partners.findAllById(partnerIds).stream()
                .collect(Collectors.toMap(DeliveryPartner::getId, Function.identity()));
        return open.stream().map(delivery -> DeliveryMapper.toStoreView(delivery,
                delivery.getPartnerId() == null ? null : byId.get(delivery.getPartnerId()))).toList();
    }

    /** The caller's store, like order-service's store endpoints: a VENDOR's vendorId, else a restaurant's id. */
    static UUID storeId(CurrentUser user) {
        if (user.hasRole(Roles.VENDOR)) {
            return user.requireVendorId();
        }
        if (user.hasRole(Roles.RESTAURANT_OWNER)) {
            return user.requireRestaurantId();
        }
        throw new ForbiddenOperationException("STORE_REQUIRED", "A vendor or restaurant account is required");
    }
}
