package com.grocery.catalogquery.service;

import com.grocery.catalogquery.document.RestaurantDocument.OpeningRange;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weekly opening hours as single-day minute ranges. A range that closes after midnight is split:
 * FRIDAY 18:00–02:00 becomes FRIDAY 1080–1440 and SATURDAY 0–120. The same ranges drive the Elasticsearch
 * "open now" filter and the {@code openNow} flag computed in Java, so both always agree.
 */
public final class OpeningHours {

    public static final int MINUTES_PER_DAY = 24 * 60;

    private OpeningHours() {
    }

    /** One published day: "MONDAY", "09:00", "23:00". */
    public record Day(String dayOfWeek, String opensAt, String closesAt) {
    }

    /** Splits the published schedule into searchable ranges; unreadable entries are skipped. */
    public static List<OpeningRange> toRanges(List<Day> days) {
        List<OpeningRange> ranges = new ArrayList<>();
        if (days == null) {
            return ranges;
        }
        for (Day day : days) {
            DayOfWeek dayOfWeek = parseDay(day.dayOfWeek());
            Integer opens = minutes(day.opensAt());
            Integer closes = minutes(day.closesAt());
            if (dayOfWeek == null || opens == null || closes == null) {
                continue;
            }
            if (closes > opens) {
                ranges.add(new OpeningRange(dayOfWeek.name(), opens, closes));
            } else {
                // Closes after midnight (equal times are rejected by restaurant-service; treat them as 24 h)
                ranges.add(new OpeningRange(dayOfWeek.name(), opens, MINUTES_PER_DAY));
                if (closes > 0) {
                    ranges.add(new OpeningRange(dayOfWeek.plus(1).name(), 0, closes));
                }
            }
        }
        return ranges;
    }

    /** Whether one of the ranges contains {@code now} (already in the restaurants' time zone). */
    public static boolean isOpen(List<OpeningRange> ranges, ZonedDateTime now) {
        if (ranges == null) {
            return false;
        }
        String today = now.getDayOfWeek().name();
        int minute = minuteOfDay(now);
        return ranges.stream().anyMatch(range -> today.equals(range.dayOfWeek())
                && range.opensMinute() != null && range.closesMinute() != null
                && range.opensMinute() <= minute && minute < range.closesMinute());
    }

    public static int minuteOfDay(ZonedDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    static DayOfWeek parseDay(String value) {
        if (value == null) {
            return null;
        }
        try {
            return DayOfWeek.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Integer minutes(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            LocalTime parsed = LocalTime.parse(time.trim());
            return parsed.getHour() * 60 + parsed.getMinute();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
