package com.grocery.ai.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One message of the transcript (the model's own memory is the separate window in {@code SPRING_AI_CHAT_MEMORY}).
 * A USER message carries {@code errorCode} when its turn failed; an ASSISTANT message carries the finish reason and
 * the turn's latency (model and tools).
 */
@Entity
@Table(name = "ai_message")
public class AiMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private Role role;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "finish_reason", length = 40)
    private String finishReason;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** An answer that states figures no tool returned in its turn (see ChatService); false otherwise. */
    @Column(name = "unverified_figures", nullable = false)
    private boolean unverifiedFigures;

    /** The help-center documents the answer was given, as JSON (ConversationService); null for none. */
    @Column(columnDefinition = "TEXT")
    private String sources;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiMessage() {
    }

    public AiMessage(UUID id, UUID conversationId, UUID userId, Role role, String content, String finishReason,
                     String errorCode, Integer latencyMs, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.finishReason = finishReason;
        this.errorCode = errorCode;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    public void failed(String code) {
        errorCode = code;
    }

    public void markUnverifiedFigures() {
        unverifiedFigures = true;
    }

    public boolean hasUnverifiedFigures() {
        return unverifiedFigures;
    }

    public void setSources(String sourcesJson) {
        sources = sourcesJson;
    }

    public String getSources() {
        return sources;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
