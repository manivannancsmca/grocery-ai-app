package com.grocery.review.service;

import com.grocery.review.entity.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AutoModeratorTest {

    private final AutoModerator moderator =
            new AutoModerator(AutoModerator.loadWords(new ClassPathResource("moderation/words.txt")), 0.70, 10);

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "write to asha.sharma@gmail.com          | EMAIL",
            "mail: a_b+c@shop.co.in                  | EMAIL",
            "http://cheap.example                    | LINK",
            "HTTPS://Deals.Example/x                 | LINK",
            "go to www.deals                         | LINK",
            "order on freshdeals.in                  | LINK",
            "bit.ly/abc                              | LINK",
            "best-veggies.com has it cheaper         | LINK",
            "call 9876543210                         | PHONE",
            "+91 98765 43210                         | PHONE",
            "(080) 2345-6789                         | PHONE",
            "098-765-432-10                          | PHONE",
            "what a fucking mess                     | PROFANITY",
            "Bullshit!                               | PROFANITY",
            "madarchod                               | PROFANITY",
            "THIS IS THE WORST                       | UPPER_CASE",
            "VERY BAD SERVICE, ok                    | UPPER_CASE",
            "VERY BAD SERVICE, really                | NULL",
            "Delivered 2026-11-13 10:30, fresh       | NULL",
            "Fresh and tasty                         | NULL",
            "Delivered 12/11/2026 at 7.30pm          | NULL",
            "Paid Rs. 1,200 for 2 kg                 | NULL",
            "Order GA-1A2B3C4D was fine, e.g. tasty  | NULL",
            "Classic Scunthorpe assortment           | NULL",
            "Price 123456789 (nine digits)           | NULL",
            "GOOD                                    | NULL",
            "NOT BAD at all, liked it                | NULL",
    })
    void flagReasons(String text, String expected) {
        assertThat(moderator.flagReason(text.strip())).as(text).isEqualTo(expected);
    }

    @Test
    void theFirstMatchingRuleIsTheReason() {
        assertThat(moderator.flagReason("MAIL ME AT ME@EXAMPLE.COM YOU SHIT")).isEqualTo("EMAIL");
        assertThat(moderator.flagReason("www.x.com shit")).isEqualTo("LINK");
        assertThat(moderator.flagReason("9876543210 shit")).isEqualTo("PHONE");
        assertThat(moderator.flagReason("SHIT SHIT SHIT SHIT")).isEqualTo("PROFANITY");
    }

    @Test
    void titleAndCommentAreBothChecked() {
        assertThat(moderator.moderate("Call 9876543210", null).status()).isEqualTo(ReviewStatus.FLAGGED);
        assertThat(moderator.moderate(null, "Call 9876543210").flagReason()).isEqualTo("PHONE");
        assertThat(moderator.moderate(null, null).status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(moderator.moderate("Nice", "Good").flagReason()).isNull();
        // upper case counts over title and comment together
        assertThat(moderator.moderate("TERRIBLE", "never again, it was rotten").status())
                .isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(moderator.moderate("TERRIBLE PACKING", "ROTTEN").flagReason()).isEqualTo("UPPER_CASE");
    }

    @Test
    void theRatioIsMoreThanSeventyPercent() {
        AutoModerator strict = new AutoModerator(Set.of(), 0.70, 10);
        assertThat(strict.flagReason("ABCDEFGhij")).isNull();          // exactly 70 %
        assertThat(strict.flagReason("ABCDEFGHij")).isEqualTo("UPPER_CASE");
        assertThat(strict.flagReason("ABCDEFGHI")).isNull();           // 9 letters: too short to judge
    }

    @Test
    void theWordListIgnoresCommentsAndCase() {
        assertThat(AutoModerator.loadWords(new ClassPathResource("moderation/words.txt")))
                .contains("shit", "chutiya")
                .noneMatch(word -> word.startsWith("#") || word.isBlank() || !word.equals(word.toLowerCase()));
    }
}
