package com.grocery.vendor.service;

import com.grocery.vendor.dto.VendorDtos.StoreView;
import com.grocery.vendor.mapper.VendorMapper;
import com.grocery.vendor.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The store view other services read (internal, Phase 5 contract §4): name, status, location. Read-only. */
@Service
public class VendorStoreService {

    private final VendorRepository vendors;

    public VendorStoreService(VendorRepository vendors) {
        this.vendors = vendors;
    }

    /** Any status is answered ({@code active} tells whether it may sell); unknown id → 404 VENDOR_NOT_FOUND. */
    @Transactional(readOnly = true)
    public StoreView store(UUID vendorId) {
        return vendors.findById(vendorId)
                .map(VendorMapper::toStoreView)
                .orElseThrow(VendorOnboardingService::notFound);
    }
}
