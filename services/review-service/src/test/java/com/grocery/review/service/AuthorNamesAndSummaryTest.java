package com.grocery.review.service;

import com.grocery.review.entity.TargetType;
import com.grocery.review.repository.RatingSummaries.Summary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorNamesAndSummaryTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Asha Sharma           | Asha S.",
            "  Asha   Rani Sharma  | Asha S.",
            "asha sharma           | asha S.",
            "Asha                  | Asha",
            "Ravi Kumar            | Ravi K.",
            "Émile Ölander         | Émile Ö.",
            "NULL                  | Customer",
            "'   '                 | Customer",
    })
    void publicNames(String name, String expected) {
        assertThat(AuthorNames.publicName(name)).isEqualTo(expected);
    }

    @Test
    void longFirstNamesAreCut() {
        assertThat(AuthorNames.publicName("A".repeat(80) + " Bose")).isEqualTo("A".repeat(60) + " B.");
    }

    @Test
    void theSummaryHasTwoDecimalsRoundedHalfUp() {
        UUID target = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-13T10:00:00Z");
        Summary s = RatingSummaryService.summarise(TargetType.PRODUCT, target, new int[]{0, 0, 0, 1, 2}, 7, now);
        assertThat(s.averageRating()).isEqualByComparingTo("4.67");        // 14 / 3
        assertThat(s.averageRating().scale()).isEqualTo(2);
        assertThat(s.ratingCount()).isEqualTo(3);
        assertThat(s.distribution()).containsExactly(0, 0, 0, 1, 2);
        assertThat(s.version()).isEqualTo(7);
        assertThat(s.updatedAt()).isEqualTo(now);

        assertThat(RatingSummaryService.summarise(TargetType.VENDOR, target, new int[]{1, 0, 0, 0, 1}, 1, now)
                .averageRating()).isEqualByComparingTo("3.00");
        assertThat(RatingSummaryService.summarise(TargetType.VENDOR, target, new int[]{0, 0, 1, 0, 0}, 1, now)
                .averageRating().toPlainString()).isEqualTo("3.00");
        // 1,1,2 → 1.333… → 1.33; 2,2,1 → 1.666… → 1.67; 1,2 → 1.5 → 1.50; 8 × 5 + 1 × 4 → 4.888… → 4.89
        assertThat(RatingSummaryService.summarise(TargetType.VENDOR, target, new int[]{2, 1, 0, 0, 0}, 1, now)
                .averageRating()).isEqualByComparingTo("1.33");
        assertThat(RatingSummaryService.summarise(TargetType.VENDOR, target, new int[]{1, 2, 0, 0, 0}, 1, now)
                .averageRating()).isEqualByComparingTo("1.67");
        assertThat(RatingSummaryService.summarise(TargetType.VENDOR, target, new int[]{0, 0, 0, 1, 8}, 1, now)
                .averageRating()).isEqualByComparingTo("4.89");
        Summary none = RatingSummaryService.summarise(TargetType.RESTAURANT, target, new int[5], 2, now);
        assertThat(none.ratingCount()).isZero();
        assertThat(none.averageRating().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void searchTermsAreEscapedForLike() {
        assertThat(ModerationService.like("  50%_off\\  ")).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(ModerationService.like("GA-12AB")).isEqualTo("%ga-12ab%");
        assertThat(ModerationService.like(" ")).isNull();
    }
}
