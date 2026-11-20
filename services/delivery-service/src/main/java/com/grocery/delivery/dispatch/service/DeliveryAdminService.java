package com.grocery.delivery.dispatch.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.dispatch.dto.DeliveryExports;
import com.grocery.delivery.dispatch.dto.DispatchDtos.AdminDeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.AdminOfferView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.OnlinePartnerView;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.entity.OfferStatus;
import com.grocery.delivery.dispatch.event.DeliveryEventPublisher;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryOfferRepository;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The admin delivery monitor and reassignment, and the online partner map (contract §5). */
@Service
public class DeliveryAdminService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAdminService.class);

    private final DeliveryRepository deliveries;
    private final DeliveryOfferRepository offers;
    private final DeliveryPartnerRepository partners;
    private final AssignmentEngine engine;
    private final DeliveryEventPublisher events;
    private final ExportQueries exports;
    private final Clock clock;

    public DeliveryAdminService(DeliveryRepository deliveries, DeliveryOfferRepository offers,
                                DeliveryPartnerRepository partners, AssignmentEngine engine,
                                DeliveryEventPublisher events, ExportQueries exports, Clock clock) {
        this.deliveries = deliveries;
        this.offers = offers;
        this.partners = partners;
        this.engine = engine;
        this.events = events;
        this.exports = exports;
        this.clock = clock;
    }

    /** Newest first; {@code q} = (part of) the order number, case-insensitive. */
    @Transactional(readOnly = true)
    public PageResponse<AdminDeliveryView> search(String status, Boolean assignmentFailed, UUID partnerId, String q,
                                                  Integer page, Integer size) {
        DeliveryStatus statusFilter = BadRequestException.parseEnum(DeliveryStatus.class, "status", status);
        String orderNumber = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase(Locale.ROOT) + "%";
        Page<Delivery> result = deliveries.search(statusFilter, assignmentFailed, partnerId, orderNumber,
                Pages.request(page, size, Sort.unsorted()));
        Map<UUID, String> names = partnerNames(result.getContent().stream().map(Delivery::getPartnerId).toList());
        return Pages.toResponse(result, d -> DeliveryMapper.toAdminView(d, names.get(d.getPartnerId()), null));
    }

    /**
     * {@code GET /api/admin/deliveries/export}: the monitor's filters on every page, in keyset batches; the partner
     * names are looked up once per batch, as the list does per page. No transaction here: each batch has its own.
     */
    public RowSource<AdminDeliveryView> searchExport(String status, Boolean assignmentFailed, UUID partnerId,
                                                     String q, String sort) {
        DeliveryStatus statusFilter = BadRequestException.parseEnum(DeliveryStatus.class, "status", status);
        String orderNumber = q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase(Locale.ROOT) + "%";
        return exports.jpql(Delivery.class, "d", DeliveryRepository.ADMIN_SEARCH)
                .param("status", statusFilter)
                .param("assignmentFailed", assignmentFailed)
                .param("partnerId", partnerId)
                .param("orderNumber", orderNumber)
                .orderBy(DeliveryExports.ADMIN_SORT.resolve(sort))
                .batches(batch -> {
                    Map<UUID, String> names = partnerNames(batch.stream().map(Delivery::getPartnerId).toList());
                    return batch.stream()
                            .map(d -> DeliveryMapper.toAdminView(d, names.get(d.getPartnerId()), null))
                            .toList();
                });
    }

    /** One delivery with every offer it got. */
    @Transactional(readOnly = true)
    public AdminDeliveryView get(UUID deliveryId) {
        return detail(deliveries.findById(deliveryId).orElseThrow(DispatchException::deliveryNotFound));
    }

    /**
     * Reassignment (SEARCHING or ASSIGNED only, else 409 INVALID_DELIVERY_TRANSITION). With {@code partnerId}: that
     * partner now (APPROVED, online, free; else 409 PARTNER_UNAVAILABLE) — the current partner (if any) gets
     * DeliveryPartnerUnassigned, open offers of the delivery and the new partner's open offers elsewhere are
     * WITHDRAWN, DeliveryPartnerAssigned with {@code manual: true}. Without: the current partner (if any) is dropped
     * (DeliveryPartnerUnassigned) and never offered this delivery again, open offers are WITHDRAWN, and a fresh search
     * starts at once. Lock order: this delivery, the other deliveries concerned, then the partners (ascending).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminDeliveryView reassign(UUID deliveryId, UUID targetPartnerId, String reason, CurrentUser admin) {
        Delivery delivery = deliveries.lockById(deliveryId).orElseThrow(DispatchException::deliveryNotFound);
        if (delivery.getStatus() != DeliveryStatus.SEARCHING && delivery.getStatus() != DeliveryStatus.ASSIGNED) {
            throw DispatchException.invalidTransition(delivery.getStatus(), "reassigned");
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        Instant now = clock.instant();
        if (targetPartnerId != null) {
            assignManually(delivery, targetPartnerId, cleanReason, admin, now);
        } else {
            searchAgain(delivery, cleanReason, admin, now);
        }
        return detail(delivery);
    }

    private void assignManually(Delivery delivery, UUID targetId, String reason, CurrentUser admin, Instant now) {
        if (delivery.getStatus() == DeliveryStatus.ASSIGNED && delivery.isAssignedTo(targetId)) {
            return;                                             // already theirs: unchanged
        }
        // Deliveries first: those on which the new partner holds an open offer (withdrawn below)
        List<UUID> otherIds = offers.deliveriesWithPendingOffersOf(targetId).stream()
                .filter(id -> !id.equals(delivery.getId())).sorted().toList();
        List<Delivery> others = otherIds.isEmpty() ? List.of() : deliveries.lockAllById(otherIds);
        // Then the partners, ascending
        TreeSet<UUID> partnerIds = new TreeSet<>();
        partnerIds.add(targetId);
        UUID formerId = delivery.getStatus() == DeliveryStatus.ASSIGNED ? delivery.getPartnerId() : null;
        if (formerId != null) {
            partnerIds.add(formerId);
        }
        Map<UUID, DeliveryPartner> locked = partners.lockAllById(partnerIds).stream()
                .collect(Collectors.toMap(DeliveryPartner::getId, Function.identity()));
        DeliveryPartner target = locked.get(targetId);
        if (target == null) {
            throw DispatchException.partnerUnavailable("unknown partner");
        }
        if (!target.isApproved()) {
            throw DispatchException.partnerUnavailable("the partner is not approved");
        }
        if (!target.isOnline()) {
            throw DispatchException.partnerUnavailable("the partner is offline");
        }
        if (target.getActiveDeliveryId() != null) {
            throw DispatchException.partnerUnavailable("the partner is on another delivery");
        }
        if (formerId != null) {
            DeliveryPartner former = locked.get(formerId);
            if (former != null) {
                former.finishDelivery(delivery.getId());
            }
            events.partnerUnassigned(delivery, formerId, reason, admin.userId(), now);
        }
        engine.closePendingOffers(delivery, OfferStatus.WITHDRAWN, now);
        for (Delivery other : others) {
            for (DeliveryOffer offer : offers.findByDeliveryIdAndStatus(other.getId(), OfferStatus.PENDING)) {
                if (offer.getPartnerId().equals(targetId)) {
                    offer.close(offer.isOpen(now) ? OfferStatus.WITHDRAWN : OfferStatus.EXPIRED, now);
                    events.offerClosed(other, offer);
                }
            }
            if (other.getStatus() == DeliveryStatus.SEARCHING) {
                other.retrySearchAt(now);                       // the poller sends its next wave if nothing is open
            }
        }
        engine.assign(delivery, target, true, admin.userId(), now);
        log.info("Admin {} assigned delivery {} (order {}) to partner {}{}", admin.userId(), delivery.getId(),
                delivery.getOrderNumber(), targetId, formerId != null ? " instead of " + formerId : "");
    }

    private void searchAgain(Delivery delivery, String reason, CurrentUser admin, Instant now) {
        if (delivery.getStatus() == DeliveryStatus.ASSIGNED) {
            UUID formerId = delivery.getPartnerId();
            partners.lockById(formerId).ifPresent(former -> former.finishDelivery(delivery.getId()));
            delivery.unassign(reason, admin.userId(), now);
            events.partnerUnassigned(delivery, formerId, reason, admin.userId(), now);
            log.info("Admin {} took delivery {} (order {}) away from partner {}", admin.userId(), delivery.getId(),
                    delivery.getOrderNumber(), formerId);
        }
        engine.closePendingOffers(delivery, OfferStatus.WITHDRAWN, now);
        delivery.restartSearch(now);
        engine.runWave(delivery, now);
    }

    /** APPROVED partners who are online, with their last location and whether they are on a delivery. */
    @Transactional(readOnly = true)
    public List<OnlinePartnerView> onlinePartners() {
        return partners.findOnline().stream().map(DeliveryMapper::toOnlineView).toList();
    }

    private AdminDeliveryView detail(Delivery delivery) {
        List<DeliveryOffer> all = offers.findByDeliveryIdOrderBySentAtAscIdAsc(delivery.getId());
        List<UUID> ids = new ArrayList<>(all.stream().map(DeliveryOffer::getPartnerId).toList());
        ids.add(delivery.getPartnerId());
        Map<UUID, String> names = partnerNames(ids);
        List<AdminOfferView> offerViews = all.stream()
                .map(offer -> DeliveryMapper.toAdminOfferView(offer, names.get(offer.getPartnerId()))).toList();
        return DeliveryMapper.toAdminView(delivery, names.get(delivery.getPartnerId()), offerViews);
    }

    private Map<UUID, String> partnerNames(Collection<UUID> ids) {
        List<UUID> known = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!known.isEmpty()) {
            partners.findAllById(known).forEach(partner -> names.put(partner.getId(), partner.getFullName()));
        }
        return names;
    }
}
