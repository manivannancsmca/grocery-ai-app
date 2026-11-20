package com.grocery.identity.repository;

import com.grocery.identity.entity.BusinessMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessMembershipRepository extends JpaRepository<BusinessMembership, UUID> {

    List<BusinessMembership> findByUserId(UUID userId);

    Optional<BusinessMembership> findByBusinessTypeAndBusinessId(BusinessMembership.BusinessType type, UUID businessId);
}
