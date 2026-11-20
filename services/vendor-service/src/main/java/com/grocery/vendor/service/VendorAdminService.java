package com.grocery.vendor.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.vendor.dto.VendorDtos.StatusChangeView;
import com.grocery.vendor.dto.VendorDtos.VendorView;
import com.grocery.vendor.dto.VendorExports;
import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.entity.VendorStatus;
import com.grocery.vendor.event.VendorEvents;
import com.grocery.vendor.mapper.VendorMapper;
import com.grocery.vendor.repository.VendorRepository;
import com.grocery.vendor.repository.VendorStatusChangeRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Admin side of the lifecycle: review queue, approve/reject, suspend/reinstate, history. */
@Service
public class VendorAdminService {

    private final VendorRepository vendors;
    private final VendorStatusChangeRepository history;
    private final VendorLifecycleRecorder recorder;
    private final ExportQueries exports;
    private final Clock clock;

    public VendorAdminService(VendorRepository vendors, VendorStatusChangeRepository history,
                              VendorLifecycleRecorder recorder, ExportQueries exports, Clock clock) {
        this.vendors = vendors;
        this.history = history;
        this.recorder = recorder;
        this.exports = exports;
        this.clock = clock;
    }

    /**
     * {@code GET /api/admin/vendors/export}: the search's filters on every page, in the list's order (the review queue
     * oldest submission first, else most recently changed first), in keyset batches. No transaction here: each batch
     * has its own.
     */
    public RowSource<VendorView> searchExport(String status, String query) {
        VendorStatus statusFilter = BadRequestException.parseEnum(VendorStatus.class, "status", status);
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return exports.jpql(Vendor.class, "v", VendorRepository.ADMIN_SEARCH)
                .param("status", statusFilter)
                .param("query", like)
                .orderBy(statusFilter == VendorStatus.PENDING_APPROVAL
                        ? VendorExports.REVIEW_QUEUE : VendorExports.RECENTLY_CHANGED)
                .rows(VendorMapper::toView);
    }

    @Transactional(readOnly = true)
    public PageResponse<VendorView> search(String status, String query, Integer page, Integer size) {
        VendorStatus statusFilter = BadRequestException.parseEnum(VendorStatus.class, "status", status);
        String like = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        // Review queue: oldest submission first. Everything else: most recently changed first.
        Sort sort = statusFilter == VendorStatus.PENDING_APPROVAL
                ? Sort.by(Sort.Direction.ASC, "submittedAt")
                : Sort.by(Sort.Direction.DESC, "updatedAt");
        return Pages.toResponse(vendors.search(statusFilter, like, Pages.request(page, size, sort)), VendorMapper::toView);
    }

    @Transactional(readOnly = true)
    public VendorView get(UUID vendorId) {
        return VendorMapper.toView(load(vendorId));
    }

    @Transactional(readOnly = true)
    public List<StatusChangeView> history(UUID vendorId) {
        load(vendorId);
        return history.findByVendorIdOrderByChangedAtDesc(vendorId).stream().map(VendorMapper::toView).toList();
    }

    @Transactional
    public VendorView approve(UUID vendorId, CurrentUser admin) {
        Vendor vendor = load(vendorId);
        VendorStatus from = vendor.approve(clock.instant());
        recorder.record(vendor, from, VendorEvents.APPROVED, null, admin.userId());
        return VendorMapper.toView(vendor);
    }

    @Transactional
    public VendorView reject(UUID vendorId, String reason, CurrentUser admin) {
        Vendor vendor = load(vendorId);
        VendorStatus from = vendor.reject(reason, clock.instant());
        recorder.record(vendor, from, VendorEvents.REJECTED, reason, admin.userId());
        return VendorMapper.toView(vendor);
    }

    @Transactional
    public VendorView suspend(UUID vendorId, String reason, CurrentUser admin) {
        Vendor vendor = load(vendorId);
        VendorStatus from = vendor.suspend(reason, clock.instant());
        recorder.record(vendor, from, VendorEvents.SUSPENDED, reason, admin.userId());
        return VendorMapper.toView(vendor);
    }

    @Transactional
    public VendorView reinstate(UUID vendorId, CurrentUser admin) {
        Vendor vendor = load(vendorId);
        VendorStatus from = vendor.reinstate(clock.instant());
        recorder.record(vendor, from, VendorEvents.REINSTATED, null, admin.userId());
        return VendorMapper.toView(vendor);
    }

    private Vendor load(UUID vendorId) {
        return vendors.findById(vendorId).orElseThrow(VendorOnboardingService::notFound);
    }
}
