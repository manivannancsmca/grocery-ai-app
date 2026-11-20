package com.grocery.ai.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, UUID>,
        JpaSpecificationExecutor<ToolInvocation> {

    List<ToolInvocation> findByMessageIdInOrderByCreatedAt(List<UUID> messageIds);

    List<ToolInvocation> findByConversationIdOrderByCreatedAt(UUID conversationId);
}
