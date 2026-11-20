package com.grocery.ai.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Page<Conversation> findByUserId(UUID userId, Pageable pageable);

    Page<Conversation> findByUserIdAndAgent(UUID userId, String agent, Pageable pageable);
}
