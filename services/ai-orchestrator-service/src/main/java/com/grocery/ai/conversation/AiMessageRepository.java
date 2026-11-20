package com.grocery.ai.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AiMessageRepository extends JpaRepository<AiMessage, UUID> {

    List<AiMessage> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

    /** The per-user rate limit: USER messages since {@code since}. */
    long countByUserIdAndRoleAndCreatedAtAfter(UUID userId, AiMessage.Role role, Instant since);

    /** The oldest USER message still inside the window (when the limit frees up again). */
    java.util.Optional<AiMessage> findFirstByUserIdAndRoleAndCreatedAtAfterOrderByCreatedAtAsc(UUID userId,
                                                                                              AiMessage.Role role,
                                                                                              Instant since);

    @Modifying
    @Query("DELETE FROM AiMessage m WHERE m.conversationId = :conversationId")
    int deleteByConversation(@Param("conversationId") UUID conversationId);
}
