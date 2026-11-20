package com.grocery.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Requests and responses of docs/api/phase-12-post-mvp-features.md §1. */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    // ------------------------------------------------------------------ requests

    /** {@code POST /api/reviews}; {@code targetType} one of PRODUCT, MENU_ITEM, VENDOR, RESTAURANT, DELIVERY_PARTNER. */
    public record SubmitReviewRequest(@NotNull UUID orderId,
                                      @NotBlank String targetType,
                                      @NotNull UUID targetId,
                                      @NotNull @Min(1) @Max(5) Integer rating,
                                      @Size(max = 80) String title,
                                      @Size(max = 1000) String comment) {
    }

    /** {@code PUT /api/reviews/{id}}. */
    public record EditReviewRequest(@NotNull @Min(1) @Max(5) Integer rating,
                                    @Size(max = 80) String title,
                                    @Size(max = 1000) String comment) {
    }

    /** {@code PUT /api/store/reviews/{id}/reply}. */
    public record ReplyRequest(@NotBlank @Size(max = 500) String text) {
    }

    /** {@code POST /api/admin/reviews/{id}/reject} and {@code /remove}. */
    public record ModerationRequest(@NotBlank @Size(max = 300) String reason) {
    }

    // ------------------------------------------------------------------ responses

    /** The store's public reply; {@code updatedAt} = last edit (= {@code repliedAt} until edited). */
    public record ReplyView(String text, Instant repliedAt, Instant updatedAt) {
    }

    /**
     * A review as its author, the store and admins see it.
     *
     * @param storeName        the order's store name (from OrderConfirmed), null when unknown
     * @param authorName       the public name ("Asha S.")
     * @param flagReason       the automatic moderation rule that flagged the last submission (EMAIL, LINK, PHONE,
     *                         PROFANITY, UPPER_CASE); null when it was published at once. Kept after an admin
     *                         decision, so the admin list still shows why it was flagged.
     * @param moderationReason the admin's reason (REJECTED, REMOVED)
     * @param editableUntil    createdAt + 7 days: the author may edit it until then
     * @param submittedAt      the last submission (creation or edit)
     */
    public record ReviewView(UUID id, UUID orderId, String orderNumber, String targetType, UUID targetId,
                             String targetName, UUID storeId, String storeName, UUID customerId,
                             String authorName, int rating,
                             String title, String comment, String status, String flagReason,
                             String moderationReason, Instant moderatedAt, boolean edited, ReplyView reply,
                             Instant createdAt, Instant submittedAt, Instant updatedAt, Instant editableUntil) {
    }

    /** A published review as everybody sees it: no customer, order or moderation data. */
    public record PublicReview(UUID id, String targetType, UUID targetId, String authorName, int rating,
                               String title, String comment, boolean edited, ReplyView reply, Instant createdAt,
                               Instant updatedAt) {
    }

    /** {@code distribution}: published reviews per star, index 0 = 1 star … index 4 = 5 stars. */
    public record RatingSummaryView(String targetType, UUID targetId, BigDecimal averageRating, int ratingCount,
                                    List<Integer> distribution) {
    }

    /** One thing of a delivered order the customer can review, with their review of it if there is one. */
    public record EligibleTarget(UUID orderId, String orderNumber, Instant deliveredAt, String targetType,
                                 UUID targetId, String targetName, ReviewView review) {
    }

    /** {@code POST /api/admin/reviews/summaries/republish}. */
    public record RepublishResult(int republished) {
    }
}
