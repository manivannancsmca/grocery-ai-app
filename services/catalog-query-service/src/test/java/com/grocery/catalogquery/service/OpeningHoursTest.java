package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.RestaurantDocument.OpeningRange;
import com.grocery.catalogquery.service.OpeningHours.Day;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningHoursTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Test
    void sameDayRangesAreKeptAndOvernightRangesAreSplitAtMidnight() {
        List<OpeningRange> ranges = OpeningHours.toRanges(List.of(
                new Day("MONDAY", "09:00", "23:00"),
                new Day("FRIDAY", "18:00", "02:00"),
                new Day("SUNDAY", "20:00", "01:30"),
                new Day("TUESDAY", "19:00", "00:00")));

        assertThat(ranges).containsExactly(
                new OpeningRange("MONDAY", 540, 1380),
                new OpeningRange("FRIDAY", 1080, 1440),
                new OpeningRange("SATURDAY", 0, 120),
                new OpeningRange("SUNDAY", 1200, 1440),
                new OpeningRange("MONDAY", 0, 90),
                new OpeningRange("TUESDAY", 1140, 1440));
    }

    @Test
    void unreadableEntriesAreSkipped() {
        assertThat(OpeningHours.toRanges(List.of(
                new Day("FUNDAY", "09:00", "10:00"),
                new Day("MONDAY", "nine", "10:00"),
                new Day("monday", "09:00", "10:00")))).containsExactly(new OpeningRange("MONDAY", 540, 600));
        assertThat(OpeningHours.toRanges(null)).isEmpty();
    }

    @Test
    void openIsInclusiveAtOpeningAndExclusiveAtClosing() {
        List<OpeningRange> ranges = OpeningHours.toRanges(List.of(new Day("FRIDAY", "18:00", "02:00")));
        ZonedDateTime fridayEvening = ZonedDateTime.of(2026, 11, 13, 18, 0, 0, 0, INDIA);

        assertThat(OpeningHours.isOpen(ranges, fridayEvening)).isTrue();
        assertThat(OpeningHours.isOpen(ranges, fridayEvening.minusMinutes(1))).isFalse();
        assertThat(OpeningHours.isOpen(ranges, fridayEvening.plusHours(7).plusMinutes(59))).isTrue();   // Sat 01:59
        assertThat(OpeningHours.isOpen(ranges, fridayEvening.plusHours(8))).isFalse();                  // Sat 02:00
        assertThat(OpeningHours.isOpen(null, fridayEvening)).isFalse();
    }
}
