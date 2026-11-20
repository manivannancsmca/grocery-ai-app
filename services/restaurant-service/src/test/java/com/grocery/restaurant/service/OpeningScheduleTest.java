package com.grocery.restaurant.service;

import com.grocery.restaurant.entity.OpeningHours;
import com.grocery.restaurant.service.OpeningSchedule.Range;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

/** Same rule and the same cases as catalog-query's OpeningHoursTest, so cart and search agree. */
class OpeningScheduleTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private static OpeningHours day(DayOfWeek day, String opensAt, String closesAt) {
        return new OpeningHours(day, LocalTime.parse(opensAt), LocalTime.parse(closesAt));
    }

    /** 2026-11-13 is a Friday. */
    private static ZonedDateTime india(int dayOfMonth, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, dayOfMonth, hour, minute, 0, 0, INDIA);
    }

    @Test
    void sameDayRangesAreKeptAndOvernightRangesAreSplitAtMidnight() {
        assertThat(OpeningSchedule.toRanges(List.of(
                day(MONDAY, "09:00", "23:00"),
                day(FRIDAY, "18:00", "02:00"),
                day(SUNDAY, "20:00", "01:30"),
                day(TUESDAY, "19:00", "00:00")))).containsExactly(
                new Range(MONDAY, 540, 1380),
                new Range(FRIDAY, 1080, 1440),
                new Range(SATURDAY, 0, 120),
                new Range(SUNDAY, 1200, 1440),
                new Range(MONDAY, 0, 90),
                new Range(TUESDAY, 1140, 1440));
        assertThat(OpeningSchedule.toRanges(null)).isEmpty();
    }

    @Test
    void openIsInclusiveAtOpeningAndExclusiveAtClosing() {
        List<OpeningHours> week = List.of(day(MONDAY, "09:00", "23:00"));
        assertThat(OpeningSchedule.isOpen(week, india(16, 8, 59))).isFalse();
        assertThat(OpeningSchedule.isOpen(week, india(16, 9, 0))).isTrue();
        assertThat(OpeningSchedule.isOpen(week, india(16, 22, 59).plusSeconds(59))).isTrue();
        assertThat(OpeningSchedule.isOpen(week, india(16, 23, 0))).isFalse();
    }

    @Test
    void overnightHoursCountAfterMidnightForThePreviousDay() {
        // FRIDAY 18:00–02:00, nothing on Saturday
        List<OpeningHours> week = List.of(day(FRIDAY, "18:00", "02:00"));
        assertThat(OpeningSchedule.isOpen(week, india(13, 17, 59))).isFalse();
        assertThat(OpeningSchedule.isOpen(week, india(13, 18, 0))).isTrue();
        assertThat(OpeningSchedule.isOpen(week, india(13, 23, 59))).isTrue();
        assertThat(OpeningSchedule.isOpen(week, india(14, 0, 0))).isTrue();       // Saturday 00:00
        assertThat(OpeningSchedule.isOpen(week, india(14, 1, 59))).isTrue();      // Saturday 01:59
        assertThat(OpeningSchedule.isOpen(week, india(14, 2, 0))).isFalse();      // Saturday 02:00
        assertThat(OpeningSchedule.isOpen(week, india(14, 18, 30))).isFalse();    // Saturday evening: no hours
        assertThat(OpeningSchedule.isOpen(week, india(20, 1, 0))).isFalse();      // Friday 01:00: Thursday is closed

        // closing at midnight exactly spills nothing into the next day
        List<OpeningHours> toMidnight = List.of(day(FRIDAY, "19:00", "00:00"));
        assertThat(OpeningSchedule.isOpen(toMidnight, india(13, 23, 59))).isTrue();
        assertThat(OpeningSchedule.isOpen(toMidnight, india(14, 0, 0))).isFalse();
    }

    @Test
    void aDayWithoutHoursIsClosed() {
        List<OpeningHours> week = List.of(day(MONDAY, "09:00", "23:00"), day(SUNDAY, "18:00", "02:00"));
        assertThat(OpeningSchedule.isOpen(week, india(17, 12, 0))).isFalse();     // Tuesday
        assertThat(OpeningSchedule.isOpen(List.of(), india(16, 12, 0))).isFalse();
        assertThat(OpeningSchedule.isOpen(null, india(16, 12, 0))).isFalse();
    }

    @Test
    void theHoursAreReadInTheConfiguredTimeZone() {
        List<OpeningHours> week = List.of(day(MONDAY, "09:00", "23:00"));
        // Monday 04:00 UTC = Monday 09:30 in India
        var instant = ZonedDateTime.of(2026, 11, 16, 4, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(OpeningSchedule.isOpen(week, instant, INDIA)).isTrue();
        assertThat(OpeningSchedule.isOpen(week, instant, ZoneOffset.UTC)).isFalse();
        // Monday 18:00 UTC = Monday 23:30 in India
        var evening = ZonedDateTime.of(2026, 11, 16, 18, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(OpeningSchedule.isOpen(week, evening, INDIA)).isFalse();
        assertThat(OpeningSchedule.isOpen(week, evening, ZoneOffset.UTC)).isTrue();
    }
}
