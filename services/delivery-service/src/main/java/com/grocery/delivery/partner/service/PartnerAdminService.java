package com.grocery.delivery.partner.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerView;
import com.grocery.delivery.partner.dto.PartnerExports;
import com.grocery.delivery.partner.dto.PartnerDtos.StatusChangeView;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.entity.PartnerStatus;
import com.grocery.delivery.partner.event.PartnerEvents;
import com.grocery.delivery.partner.mapper.PartnerMapper;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import com.grocery.delivery.partner.repository.PartnerStatusChangeRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Admin / delivery-manager side of the lifecycle: review queue, approve/reject, suspend/reinstate, history. */
@Service
public class PartnerAdminService {

    private final DeliveryPartnerRepository partners;
    private final PartnerStatusChangeRepository history;
    private final PartnerLifecycleRecorder recorder;
    private final ExportQueries exports;
    private final Clock clock;

    public PartnerAdminService(DeliveryPartnerRepository partners, PartnerStatusChangeRepository history,
                               PartnerLifecycleRecorder recorder, ExportQueries exports, Clock clock) {
        this.partners = partners;
        this.history = history;
        this.recorder = recorder;
        this.exports = exports;
        this.clock = clock;
    }

    /**
     * {@code GET /api/admin/delivery-partners/export}: the search's filters on every page, in the list's order (the
     * review queue oldest submission first, else most recently changed first), in keyset batches.
     */
    public RowSource<PartnerView> searchExport(String status, String query) {
        PartnerStatus statusFilter = BadRequestException.parseEnum(PartnerStatus.class, "status", status);
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return exports.jpql(DeliveryPartner.class, "p", DeliveryPartnerRepository.ADMIN_SEARCH)
                .param("status", statusFilter)
                .param("query", like)
                .orderBy(statusFilter == PartnerStatus.PENDING_APPROVAL
                        ? PartnerExports.REVIEW_QUEUE : PartnerExports.RECENTLY_CHANGED)
                .rows(PartnerMapper::toView);
    }

    @Transactional(readOnly = true)
    public PageResponse<PartnerView> search(String status, String query, Integer page, Integer size) {
        PartnerStatus statusFilter = BadRequestException.parseEnum(PartnerStatus.class, "status", status);
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        // Review queue: oldest submission first. Everything else: most recently changed first.
        Sort sort = statusFilter == PartnerStatus.PENDING_APPROVAL
                ? Sort.by(Sort.Direction.ASC, "submittedAt")
                : Sort.by(Sort.Direction.DESC, "updatedAt");
        return Pages.toResponse(partners.search(statusFilter, like, Pages.request(page, size, sort)), PartnerMapper::toView);
    }

    @Transactional(readOnly = true)
    public PartnerView get(UUID partnerId) {
        return PartnerMapper.toView(load(partnerId));
    }

    @Transactional(readOnly = true)
    public List<StatusChangeView> history(UUID partnerId) {
        load(partnerId);
        return history.findByPartnerIdOrderByChangedAtDesc(partnerId).stream().map(PartnerMapper::toView).toList();
    }

    @Transactional
    public PartnerView approve(UUID partnerId, CurrentUser admin) {
        DeliveryPartner partner = lock(partnerId);
        PartnerStatus from = partner.approve(clock.instant());
        recorder.record(partner, from, PartnerEvents.APPROVED, null, admin.userId());
        return PartnerMapper.toView(partner);
    }

    @Transactional
    public PartnerView reject(UUID partnerId, String reason, CurrentUser admin) {
        DeliveryPartner partner = lock(partnerId);
        PartnerStatus from = partner.reject(reason, clock.instant());
        recorder.record(partner, from, PartnerEvents.REJECTED, reason, admin.userId());
        return PartnerMapper.toView(partner);
    }

    @Transactional
    public PartnerView suspend(UUID partnerId, String reason, CurrentUser admin) {
        DeliveryPartner partner = lock(partnerId);
        PartnerStatus from = partner.suspend(reason, clock.instant());
        recorder.record(partner, from, PartnerEvents.SUSPENDED, reason, admin.userId());
        return PartnerMapper.toView(partner);
    }

    @Transactional
    public PartnerView reinstate(UUID partnerId, CurrentUser admin) {
        DeliveryPartner partner = lock(partnerId);
        PartnerStatus from = partner.reinstate(clock.instant());
        recorder.record(partner, from, PartnerEvents.REINSTATED, null, admin.userId());
        return PartnerMapper.toView(partner);
    }

    private DeliveryPartner load(UUID partnerId) {
        return partners.findById(partnerId).orElseThrow(PartnerOnboardingService::notFound);
    }

    /**
     * Status changes lock the partner row (Phase 9): an online partner's location reports update the same row every
     * few seconds, which would otherwise make an approve / suspend fail with an optimistic-locking conflict.
     */
    private DeliveryPartner lock(UUID partnerId) {
        return partners.lockById(partnerId).orElseThrow(PartnerOnboardingService::notFound);
    }
}
