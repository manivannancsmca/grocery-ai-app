package com.grocery.identity.repository;

import com.grocery.identity.entity.AuthAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuthAuditRepository extends JpaRepository<AuthAudit, UUID> {

    List<AuthAudit> findByUserIdOrderByOccurredAtDesc(UUID userId);
}
