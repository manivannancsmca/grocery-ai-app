package com.grocery.review.dto;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.ExportTable;
import com.grocery.review.dto.ReviewDtos.ReplyView;
import com.grocery.review.dto.ReviewDtos.ReviewView;
import com.grocery.review.entity.Review;

/**
 * The CSV / Excel / PDF exports of the review lists (admin moderation, a store's reviews, the customer's own). The
 * review pages show cards, not sortable tables, so each export comes in its list's own order only. A review carries
 * the author's public name ("Asha S."), never an e-mail or phone.
 */
public final class ReviewExports {

    private ReviewExports() {
    }

    /** Moderation: the most recently submitted first, like the list (JPQL paths, alias {@code r}). */
    public static final Order<Review> RECENTLY_SUBMITTED = Order.of(
            Key.desc("r.submittedAt", Review::getSubmittedAt), Key.desc("r.id", Review::getId));

    /** A store's reviews and the customer's own: newest first, like the lists. */
    public static final Order<Review> NEWEST_FIRST = Order.of(
            Key.desc("r.createdAt", Review::getCreatedAt), Key.desc("r.id", Review::getId));

    /** {@code GET /api/admin/reviews/export}: what the moderation cards show, with the moderation details. */
    public static final ExportTable<ReviewView> ADMIN_REVIEWS = ExportTable.<ReviewView>named("reviews", "Reviews")
            .dateTime("Submitted", ReviewView::submittedAt)
            .label("Status", ReviewView::status)
            .integer("Rating", ReviewView::rating)
            .text("Title", ReviewView::title)
            .text("Comment", ReviewView::comment)
            .text("Author", ReviewView::authorName)
            .text("Customer ID", ReviewView::customerId)
            .label("Type", ReviewView::targetType)
            .text("About", ReviewView::targetName)
            .text("Order number", ReviewView::orderNumber)
            .text("Store", ReviewView::storeName)
            .label("Flag reason", ReviewView::flagReason)
            .text("Moderation note", ReviewView::moderationReason)
            .dateTime("Moderated", ReviewView::moderatedAt)
            .yesNo("Edited", ReviewView::edited)
            .text("Store reply", view -> replyText(view.reply()))
            .dateTime("Written", ReviewView::createdAt)
            .build();

    /** {@code GET /api/store/reviews/export}: the store's published reviews and its replies (no customer id). */
    public static final ExportTable<ReviewView> STORE_REVIEWS = ExportTable.<ReviewView>named("store-reviews",
                    "Reviews")
            .dateTime("Written", ReviewView::createdAt)
            .integer("Rating", ReviewView::rating)
            .text("Title", ReviewView::title)
            .text("Comment", ReviewView::comment)
            .text("Author", ReviewView::authorName)
            .label("Type", ReviewView::targetType)
            .text("About", ReviewView::targetName)
            .text("Order number", ReviewView::orderNumber)
            .yesNo("Edited", ReviewView::edited)
            .text("Your reply", view -> replyText(view.reply()))
            .dateTime("Replied", view -> repliedAt(view.reply()))
            .build();

    /** {@code GET /api/reviews/mine/export}: the customer's own reviews, every status. */
    public static final ExportTable<ReviewView> MY_REVIEWS = ExportTable.<ReviewView>named("my-reviews",
                    "My reviews")
            .dateTime("Written", ReviewView::createdAt)
            .text("Order number", ReviewView::orderNumber)
            .label("Type", ReviewView::targetType)
            .text("About", ReviewView::targetName)
            .text("Store", ReviewView::storeName)
            .integer("Rating", ReviewView::rating)
            .text("Title", ReviewView::title)
            .text("Comment", ReviewView::comment)
            .label("Status", ReviewView::status)
            .text("Moderation reason", ReviewView::moderationReason)
            .yesNo("Edited", ReviewView::edited)
            .text("Store reply", view -> replyText(view.reply()))
            .build();

    private static String replyText(ReplyView reply) {
        return reply == null ? null : reply.text();
    }

    /** The reply's last edit (its first writing until edited). */
    private static Object repliedAt(ReplyView reply) {
        return reply == null ? null : reply.updatedAt() != null ? reply.updatedAt() : reply.repliedAt();
    }
}
