package com.grocery.notification.dispatch;

import com.grocery.notification.entity.NotificationType;
import com.grocery.notification.entity.ReviewTarget;
import com.grocery.notification.entity.StoreType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12 §1 / §3: the review and return texts, links, categories, severities, fallbacks and data. */
class ReviewAndReturnTemplatesTest {

    private static final UUID REVIEW = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID RETURN = UUID.randomUUID();

    @Test
    void theNewTypesHaveTheirCategoryAndSeverity() {
        assertThat(NotificationType.NEW_REVIEW.category()).isEqualTo(NotificationType.Category.REVIEW);
        assertThat(NotificationType.NEW_REVIEW.severity()).isEqualTo(NotificationType.Severity.INFO);
        assertThat(NotificationType.REVIEW_FLAGGED.severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(NotificationType.REVIEW_NOT_PUBLISHED.severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(NotificationType.REVIEW_REPLY.severity()).isEqualTo(NotificationType.Severity.INFO);
        assertThat(NotificationType.REVIEW_REPLY.category()).isEqualTo(NotificationType.Category.REVIEW);
        assertThat(NotificationType.RETURN_REQUESTED.severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(NotificationType.RETURN_ESCALATED.severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(NotificationType.RETURN_APPROVED.severity()).isEqualTo(NotificationType.Severity.SUCCESS);
        assertThat(NotificationType.RETURN_REJECTED.severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(NotificationType.RETURN_APPROVED.category()).isEqualTo(NotificationType.Category.ORDER);
        // notification.type is VARCHAR(40)
        for (NotificationType type : NotificationType.values()) {
            assertThat(type.name().length()).isLessThanOrEqualTo(40);
        }
    }

    @Test
    void newReviewNamesTheTargetOrFallsBackForTheOwner() {
        NotificationDraft draft = NotificationTemplates.newReview(REVIEW, ReviewTarget.PRODUCT, "PRODUCT", TARGET, 4,
                "Basmati Rice", false, StoreType.VENDOR);
        assertThat(draft.type()).isEqualTo(NotificationType.NEW_REVIEW);
        assertThat(draft.title()).isEqualTo("New 4★ review");
        assertThat(draft.body()).isEqualTo("Basmati Rice");
        assertThat(draft.link()).isEqualTo("/vendor/reviews");
        assertThat(draft.data()).containsExactly(Map.entry("reviewId", REVIEW.toString()),
                Map.entry("targetType", "PRODUCT"), Map.entry("targetId", TARGET.toString()), Map.entry("rating", 4));

        assertThat(NotificationTemplates.newReview(REVIEW, ReviewTarget.PRODUCT, "PRODUCT", TARGET, 5, null, true,
                StoreType.VENDOR).body()).isEqualTo("your product (edited)");
        assertThat(NotificationTemplates.newReview(REVIEW, ReviewTarget.MENU_ITEM, "MENU_ITEM", TARGET, 2, " ", false,
                StoreType.RESTAURANT).link()).isEqualTo("/restaurant/reviews");
        assertThat(NotificationTemplates.newReview(REVIEW, ReviewTarget.MENU_ITEM, "MENU_ITEM", TARGET, 2, " ", false,
                StoreType.RESTAURANT).body()).isEqualTo("your dish");
        assertThat(NotificationTemplates.newReview(REVIEW, ReviewTarget.VENDOR, "VENDOR", TARGET, 3, null, false,
                StoreType.VENDOR).body()).isEqualTo("your store");
        assertThat(NotificationTemplates.newReview(REVIEW, ReviewTarget.RESTAURANT, "RESTAURANT", TARGET, null, null,
                false, StoreType.RESTAURANT).title()).isEqualTo("New review");
        assertThat(NotificationTemplates.newReview(REVIEW, null, "SOMETHING", TARGET, 3, null, false,
                StoreType.VENDOR).body()).isEqualTo("your product");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "LINK                | contains a link",
            "url                 | contains a link",
            "PHONE               | contains a phone number",
            "EMAIL               | contains an email address",
            "PROFANITY           | contains a blocked word",
            "UPPERCASE           | mostly capital letters",
            "SHOUTING            | mostly capital letters",
            "LINK,PHONE          | contains a link, contains a phone number",
            "LINK LINK           | contains a link",
            "NEW_RULE            | new rule",
            "null                | flagged by automatic moderation"
    })
    void flagReasonsAsAdminsReadThem(String reason, String label) {
        assertThat(NotificationTemplates.flagReasonLabel(reason)).isEqualTo(label);
    }

    @Test
    void reviewFlaggedNotPublishedAndReply() {
        NotificationDraft flagged = NotificationTemplates.reviewFlagged(REVIEW, ReviewTarget.PRODUCT, "PRODUCT",
                TARGET, 1, null, "PROFANITY");
        assertThat(flagged.title()).isEqualTo("A 1★ review needs moderation");
        assertThat(flagged.body()).isEqualTo("A product · contains a blocked word");
        assertThat(flagged.link()).isEqualTo("/admin/reviews");
        assertThat(flagged.data()).containsEntry("flagReason", "PROFANITY").containsEntry("rating", 1);
        assertThat(NotificationTemplates.reviewFlagged(REVIEW, ReviewTarget.DELIVERY_PARTNER, "DELIVERY_PARTNER",
                TARGET, 2, null, "LINK").body()).isEqualTo("A delivery partner · contains a link");

        NotificationDraft rejected = NotificationTemplates.reviewNotPublished(REVIEW, ReviewTarget.RESTAURANT,
                "RESTAURANT", TARGET, "REJECTED", "Spice Route", "  Personal data  ");
        assertThat(rejected.type()).isEqualTo(NotificationType.REVIEW_NOT_PUBLISHED);
        assertThat(rejected.title()).isEqualTo("Your review of Spice Route was not published");
        assertThat(rejected.body()).isEqualTo("Personal data");
        assertThat(rejected.link()).isEqualTo("/account/reviews");
        assertThat(rejected.data()).containsEntry("decision", "REJECTED");
        assertThat(NotificationTemplates.reviewNotPublished(REVIEW, ReviewTarget.VENDOR, "VENDOR", TARGET, "REMOVED",
                null, null).title()).isEqualTo("Your review of the store was removed");
        assertThat(NotificationTemplates.reviewNotPublished(REVIEW, ReviewTarget.DELIVERY_PARTNER, "DELIVERY_PARTNER",
                TARGET, "REMOVED", null, null).title()).isEqualTo("Your review of your delivery partner was removed");

        NotificationDraft reply = NotificationTemplates.reviewReply(REVIEW, ReviewTarget.MENU_ITEM, "MENU_ITEM",
                TARGET, "Spice Route", "Butter Chicken");
        assertThat(reply.title()).isEqualTo("Spice Route replied to your review");
        assertThat(reply.body()).isEqualTo("Your review of Butter Chicken.");
        assertThat(reply.link()).isEqualTo("/account/reviews");
        assertThat(NotificationTemplates.reviewReply(REVIEW, ReviewTarget.MENU_ITEM, "MENU_ITEM", TARGET, null, null)
                .title()).isEqualTo("The store replied to your review");
        assertThat(NotificationTemplates.reviewReply(REVIEW, ReviewTarget.MENU_ITEM, "MENU_ITEM", TARGET, null, null)
                .body()).isEqualTo("Your review of a dish.");
    }

    @Test
    void returnTexts() {
        // 14:35 India time
        Instant decideBy = Instant.parse("2026-11-13T09:05:00Z");
        NotificationDraft requested = NotificationTemplates.returnRequested(ORDER, "GA-7K3F9Q2M", RETURN,
                "RT-7K3M9QX2", 3, new BigDecimal("95.00"), decideBy, StoreType.VENDOR);
        assertThat(requested.type()).isEqualTo(NotificationType.RETURN_REQUESTED);
        assertThat(requested.title()).isEqualTo("Return requested for order GA-7K3F9Q2M");
        assertThat(requested.body()).isEqualTo("3 items · ₹95.00 · decide by 14:35");
        assertThat(requested.link()).isEqualTo("/vendor/returns");
        assertThat(requested.data()).containsExactly(Map.entry("orderId", ORDER.toString()),
                Map.entry("orderNumber", "GA-7K3F9Q2M"), Map.entry("returnId", RETURN.toString()),
                Map.entry("returnNumber", "RT-7K3M9QX2"), Map.entry("decideBy", "2026-11-13T09:05:00Z"));
        assertThat(NotificationTemplates.returnRequested(ORDER, "GA-1", RETURN, "RT-1", 1,
                new BigDecimal("123456.70"), null, StoreType.RESTAURANT).body()).isEqualTo("1 item · ₹1,23,456.70");
        assertThat(NotificationTemplates.returnRequested(ORDER, "GA-1", RETURN, "RT-1", null, null, null,
                StoreType.RESTAURANT).link()).isEqualTo("/restaurant/returns");

        NotificationDraft escalated = NotificationTemplates.returnEscalated(ORDER, "GA-7K3F9Q2M", RETURN,
                "RT-7K3M9QX2", new BigDecimal("95.00"));
        assertThat(escalated.title()).isEqualTo("Return RT-7K3M9QX2 needs a decision");
        assertThat(escalated.body()).isEqualTo("Order GA-7K3F9Q2M · ₹95.00 · the store did not decide in time.");
        assertThat(escalated.link()).isEqualTo("/admin/returns");

        NotificationDraft approved = NotificationTemplates.returnApproved(ORDER, "GA-7K3F9Q2M", RETURN,
                "RT-7K3M9QX2", new BigDecimal("95.00"));
        assertThat(approved.title()).isEqualTo("Return RT-7K3M9QX2 approved");
        assertThat(approved.body()).isEqualTo("₹95.00 will be refunded.");
        assertThat(approved.link()).isEqualTo("/orders/" + ORDER);
        assertThat(approved.data()).containsEntry("refundAmount", new BigDecimal("95.00"))
                .containsEntry("orderNumber", "GA-7K3F9Q2M");
        assertThat(NotificationTemplates.returnApproved(ORDER, "GA-1", RETURN, "RT-1", null).body())
                .isEqualTo("Your refund is on its way.");

        NotificationDraft rejected = NotificationTemplates.returnRejected(ORDER, "GA-7K3F9Q2M", RETURN,
                "RT-7K3M9QX2", " Seal opened ");
        assertThat(rejected.title()).isEqualTo("Return RT-7K3M9QX2 rejected");
        assertThat(rejected.body()).isEqualTo("Seal opened");
        assertThat(rejected.link()).isEqualTo("/orders/" + ORDER);
        assertThat(NotificationTemplates.returnRejected(ORDER, "GA-1", RETURN, "RT-1", null).body()).isEmpty();
    }

    @Test
    void aReturnRefundCarriesItsReturnId() {
        UUID refund = UUID.randomUUID();
        assertThat(NotificationTemplates.refundCompleted(ORDER, "GA-1", refund, new BigDecimal("95.00"), RETURN)
                .data()).containsEntry("returnId", RETURN.toString());
        assertThat(NotificationTemplates.refundCompleted(ORDER, "GA-1", refund, new BigDecimal("95.00"))
                .data()).doesNotContainKey("returnId");
    }
}
