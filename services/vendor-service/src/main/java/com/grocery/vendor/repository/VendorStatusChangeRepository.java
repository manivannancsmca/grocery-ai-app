package com.grocery.vendor.repository;

import com.grocery.vendor.entity.VendorStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VendorStatusChangeRepository extends JpaRepository<VendorStatusChange, UUID> {

    List<VendorStatusChange> findByVendorIdOrderByChangedAtDesc(UUID vendorId);
}
