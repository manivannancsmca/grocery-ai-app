package com.grocery.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer's review of one target of one delivered order (contract §1). The order, target and store are fixed at
 * submission; rating, title and comment change on edits (each edit is moderated again); moderation and the store's
 * reply are recorded here. Changes lock the row first ({@code SELECT … FOR UPDATE}) and are also guarded by
 * {@code @Version}.
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, updatable = false, length = 40)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "target_name", nullable = false, updatable = false, length = 200)
    private String targetName;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "store_name", updatable = false, length = 200)
    private String storeName;

    @Column(nullable = false, columnDefinition = "TINYINT")
    private int rating;

    @Column(length = 80)
    private String title;

    @Column(length = 1000)
    private String comment;

    @Column(name = "author_name", nullable = false, length = 100)
    private String authorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    @Column(name = "flag_reason", length = 40)
    private String flagReason;

    @Column(name = "moderation_reason", length = 300)
    private String moderationReason;

    @Column(name = "moderated_by")
    private UUID moderatedBy;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    @Column(name = "reply_text", length = 500)
    private String replyText;

    @Column(name = "reply_by")
    private UUID replyBy;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "reply_updated_at")
    private Instant replyUpdatedAt;

    @Column(nullable = false)
    private boolean edited;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Review() {
        // JPA
    }

    /** A new review, already moderated automatically ({@code status} PUBLISHED or FLAGGED). */
    public static Review submit(UUID id, UUID customerId, UUID orderId, String orderNumber, TargetType targetType,
                                UUID targetId, String targetName, UUID storeId, String storeName, int rating,
                                String title, String comment, String authorName, Moderation moderation,
                                Instant now) {
        Review review = new Review();
        review.id = id;
        review.customerId = customerId;
        review.orderId = orderId;
        review.orderNumber = orderNumber;
        review.targetType = targetType;
        review.targetId = targetId;
        review.targetName = targetName;
        review.storeId = storeId;
        review.storeName = storeName;
        review.rating = rating;
        review.title = title;
        review.comment = comment;
        review.authorName = authorName;
        review.apply(moderation);
        review.edited = false;
        review.createdAt = now;
        review.submittedAt = now;
        review.updatedAt = now;
        return review;
    }

    /** The customer's edit: new content, moderated again (whatever the status was), earlier admin decision cleared. */
    public void edit(int rating, String title, String comment, String authorName, Moderation moderation, Instant now) {
        this.rating = rating;
        this.title = title;
        this.comment = comment;
        this.authorName = authorName;
        apply(moderation);
        this.moderationReason = null;
        this.moderatedBy = null;
        this.moderatedAt = null;
        this.edited = true;
        this.submittedAt = now;
        this.updatedAt = now;
    }

    /** An admin's decision; the caller has checked that the transition is allowed. */
    public void moderate(ReviewStatus decision, String reason, UUID moderatorId, Instant now) {
        this.status = decision;
        this.moderationReason = reason;
        this.moderatedBy = moderatorId;
        this.moderatedAt = now;
        this.updatedAt = now;
    }

    /** The store's reply, first or edited. */
    public void reply(String text, UUID repliedBy, Instant now) {
        if (this.repliedAt == null) {
            this.repliedAt = now;
        }
        this.replyText = text;
        this.replyBy = repliedBy;
        this.replyUpdatedAt = now;
        this.updatedAt = now;
    }

    private void apply(Moderation moderation) {
        this.status = moderation.status();
        this.flagReason = moderation.flagReason();
    }

    /** The outcome of automatic moderation: PUBLISHED (no reason) or FLAGGED with the first rule that matched. */
    public record Moderation(ReviewStatus status, String flagReason) {

        public static final Moderation PUBLISHED = new Moderation(ReviewStatus.PUBLISHED, null);

        public static Moderation flagged(String reason) {
            return new Moderation(ReviewStatus.FLAGGED, reason);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getStoreName() {
        return storeName;
    }

    public int getRating() {
        return rating;
    }

    public String getTitle() {
        return title;
    }

    public String getComment() {
        return comment;
    }

    public String getAuthorName() {
        return authorName;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public String getModerationReason() {
        return moderationReason;
    }

    public UUID getModeratedBy() {
        return moderatedBy;
    }

    public Instant getModeratedAt() {
        return moderatedAt;
    }

    public String getReplyText() {
        return replyText;
    }

    public UUID getReplyBy() {
        return replyBy;
    }

    public Instant getRepliedAt() {
        return repliedAt;
    }

    public Instant getReplyUpdatedAt() {
        return replyUpdatedAt;
    }

    public boolean isEdited() {
        return edited;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
