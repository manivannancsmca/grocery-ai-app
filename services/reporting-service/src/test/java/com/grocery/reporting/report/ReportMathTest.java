package com.grocery.reporting.report;

import com.grocery.reporting.event.OccurredAt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The rounding rules of the reports, and the occurredAt header formats. */
class ReportMathTest {

    @Test
    void ratesHaveFourDecimalsAndAveragesTwo() {
        assertThat(ReportService.rate(2, 6)).isEqualTo(new BigDecimal("0.3333"));
        assertThat(ReportService.rate(1, 8)).isEqualTo(new BigDecimal("0.1250"));
        assertThat(ReportService.rate(0, 0)).isEqualTo(new BigDecimal("0.0000"));
        assertThat(ReportService.average(new BigDecimal("741.00"), 4)).isEqualTo(new BigDecimal("185.25"));
        assertThat(ReportService.average(new BigDecimal("100.00"), 3)).isEqualTo(new BigDecimal("33.33"));
        assertThat(ReportService.average(new BigDecimal("0.00"), 0)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void averageMinutesHaveOneDecimal() {
        assertThat(ReportService.minutes(new BigDecimal("750000000.0000"))).isEqualTo(new BigDecimal("12.5"));
        assertThat(ReportService.minutes(new BigDecimal("61000000"))).isEqualTo(new BigDecimal("1.0"));
        assertThat(ReportService.minutes(new BigDecimal("63000000"))).isEqualTo(new BigDecimal("1.1"));
        assertThat(ReportService.minutes(null)).isNull();
    }

    @Test
    void occurredAtHeaderFormats() {
        Instant at = Instant.parse("2026-10-05T04:30:00.123456Z");
        assertThat(OccurredAt.parse("1791174600123456")).isEqualTo(at);                  // Debezium micros
        assertThat(OccurredAt.parse("1791174600123")).isEqualTo(Instant.parse("2026-10-05T04:30:00.123Z"));
        assertThat(OccurredAt.parse("2026-10-05T04:30:00.123456Z")).isEqualTo(at);
        assertThat(OccurredAt.parse("2026-10-05 04:30:00.123456")).isEqualTo(at);
        assertThat(OccurredAt.parse("yesterday")).isNull();
        assertThat(OccurredAt.parse(null)).isNull();
    }
}
