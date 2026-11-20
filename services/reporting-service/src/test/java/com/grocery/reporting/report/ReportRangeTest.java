package com.grocery.reporting.report;

import com.grocery.common.web.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRangeTest {

    /** 2026-11-12 19:00 UTC = 2026-11-13 00:30 in India: "today" is already the 13th there. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T19:00:00Z"), ZoneOffset.UTC);

    @Test
    void indiaDaysAreUtcInstantsShiftedByFiveAndAHalfHours() {
        ReportRange range = ReportRange.of("2026-11-02", "2026-11-02", clock);

        assertThat(range.start()).isEqualTo(Instant.parse("2026-11-01T18:30:00Z"));
        assertThat(range.end()).isEqualTo(Instant.parse("2026-11-02T18:30:00Z"));
        assertThat(range.offsetMinutes()).isEqualTo(330);
        assertThat(range.dates()).containsExactly(LocalDate.parse("2026-11-02"));
    }

    @Test
    void defaultsAreTheLast30DaysEndingTodayInIndia() {
        ReportRange range = ReportRange.of(null, " ", clock);

        assertThat(range.to()).isEqualTo(LocalDate.parse("2026-11-13"));
        assertThat(range.from()).isEqualTo(LocalDate.parse("2026-10-15"));
        assertThat(range.dates()).hasSize(30);

        assertThat(ReportRange.of(null, "2026-03-31", clock).from()).isEqualTo(LocalDate.parse("2026-03-02"));
        assertThat(ReportRange.of("2026-11-01", null, clock).to()).isEqualTo(LocalDate.parse("2026-11-13"));
    }

    @Test
    void atMost366DaysBothInclusive() {
        assertThat(ReportRange.of("2024-01-01", "2024-12-31", clock).dates()).hasSize(366);
        assertThat(ReportRange.of("2026-01-01", "2026-01-01", clock).dates()).hasSize(1);

        assertThatThrownBy(() -> ReportRange.of("2025-01-01", "2026-01-02", clock))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A report covers at most 366 days (asked: 367)")
                .extracting(e -> ((BadRequestException) e).errorCode()).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void malformedOrReversedDatesAreRejected() {
        assertThatThrownBy(() -> ReportRange.of("2026-10-06", "2026-10-05", clock))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("must not be after");
        assertThatThrownBy(() -> ReportRange.of("05/10/2026", null, clock))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Invalid from '05/10/2026'");
        assertThatThrownBy(() -> ReportRange.of(null, "2026-02-30", clock))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Invalid to");
        // only 'from', after today
        assertThatThrownBy(() -> ReportRange.of("2026-12-01", null, clock))
                .isInstanceOf(BadRequestException.class);
    }
}
