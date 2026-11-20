package com.grocery.ai.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One user's conversation with one agent. */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 20)
    private String agent;

    /** The first message (at most 80 characters) until the user renames the conversation. */
    @Column(nullable = false, length = 80)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(UUID id, UUID userId, String agent, String title, Instant now) {
        this.id = id;
        this.userId = userId;
        this.agent = agent;
        this.title = title;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        updatedAt = now;
    }

    /** A new title chosen by the user (validated by the service). The activity time is kept, so the list order is. */
    public void rename(String newTitle) {
        title = newTitle;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAgent() {
        return agent;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
