package com.grocery.delivery.partner.repository;

import com.grocery.delivery.partner.entity.PartnerStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PartnerStatusChangeRepository extends JpaRepository<PartnerStatusChange, UUID> {

    List<PartnerStatusChange> findByPartnerIdOrderByChangedAtDesc(UUID partnerId);
}
