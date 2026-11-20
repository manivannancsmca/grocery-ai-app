package com.grocery.review.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code grocery.review.*} (docs/api/phase-12-post-mvp-features.md §1).
 *
 * @param reviewWindow        how long after delivery an order's items, store and partner can be reviewed (30 days);
 *                            the default of the admin platform setting (area {@code reviews})
 * @param editWindow          how long after writing it the customer may edit a review (7 days)
 * @param eligibleOrdersLimit the most delivered orders {@code GET /api/reviews/eligible} lists without {@code orderId}
 * @param republishBatchSize  rating summaries per transaction when an admin republishes them
 * @param moderation          the automatic moderation rules
 */
@ConfigurationProperties(prefix = "grocery.review")
public record ReviewProperties(
        @DefaultValue("30d") Duration reviewWindow,
        @DefaultValue("7d") Duration editWindow,
        @DefaultValue("50") int eligibleOrdersLimit,
        @DefaultValue("200") int republishBatchSize,
        @DefaultValue Moderation moderation) {

    /**
     * @param words                the profanity list (Spring resource location), one word per line
     * @param upperCaseRatio       more than this share of upper-case letters is flagged …
     * @param upperCaseMinLetters  … once the title and comment have at least this many letters
     */
    public record Moderation(
            @DefaultValue("classpath:moderation/words.txt") String words,
            @DefaultValue("0.70") double upperCaseRatio,
            @DefaultValue("10") int upperCaseMinLetters) {
    }
}
