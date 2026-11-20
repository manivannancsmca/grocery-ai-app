package com.grocery.review.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Events PUBLISHED on {@code review.events} (v1, docs/api/phase-12-post-mvp-features.md §1, Avro schemas 1.5.0 in
 * {@code libs/grocery-event-schemas/src/main/avro/review.events}). The record components are exactly the schema
 * fields: the outbox converts them strictly. Aggregates: {@code Review} (key reviewId) and {@code RatingSummary}
 * (key targetId). Consumers: notification (the first three), catalog-query and reporting (RatingSummaryUpdated).
 */
public final class ReviewEvents {

    public static final String TOPIC = "review.events";
    public static final String REVIEW = "Review";
    public static final String RATING_SUMMARY = "RatingSummary";

    public static final String REVIEW_SUBMITTED = "ReviewSubmitted";
    public static final String REVIEW_MODERATED = "ReviewModerated";
    public static final String REVIEW_REPLIED = "ReviewReplied";
    public static final String RATING_SUMMARY_UPDATED = "RatingSummaryUpdated";

    private ReviewEvents() {
    }

    /**
     * A review was submitted ({@code edited=false}) or edited ({@code edited=true}); {@code status} PUBLISHED or
     * FLAGGED, {@code flagReason} the automatic moderation rule that flagged it.
     */
    public record ReviewSubmitted(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId,
                                  UUID orderId, int rating, String status, String flagReason, boolean edited,
                                  Instant submittedAt) {
    }

    /** An admin decided: PUBLISHED or REJECTED (a flagged review), REMOVED (a published one). */
    public record ReviewModerated(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId,
                                  String decision, String reason, UUID moderatorId, Instant moderatedAt) {
    }

    /** The store replied to, or edited its reply to, a published review. */
    public record ReviewReplied(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId,
                                UUID repliedBy, Instant repliedAt) {
    }

    /**
     * The target's published ratings changed. {@code ratingDistribution}: index 0 = 1 star … index 4 = 5 stars;
     * {@code averageRating} 2 decimals (0.00 without ratings); {@code summaryVersion} increases with every change.
     */
    public record RatingSummaryUpdated(String targetType, UUID targetId, BigDecimal averageRating, int ratingCount,
                                       List<Integer> ratingDistribution, long summaryVersion, Instant updatedAt) {
    }
}
