package com.grocery.restaurant.service;

import com.grocery.restaurant.entity.OpeningHours;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * "Open now" from the weekly opening hours — the same rule as catalog-query's {@code service.OpeningHours}
 * (search's "open now"), so the cart and search always agree.
 *
 * <p>Each day becomes single-day minute ranges; a day that closes after midnight is split: SUNDAY 18:00–02:00
 * becomes SUNDAY 1080–1440 and MONDAY 0–120, so Monday 01:30 is still open (Sunday's evening). Opening is
 * inclusive, closing exclusive. A day without hours is closed, except for the previous day's overnight range.
 * Times are minute-precise and read in the restaurants' time zone.
 */
public final class OpeningSchedule {

    static final int MINUTES_PER_DAY = 24 * 60;

    private OpeningSchedule() {
    }

    /** A single-day range [opensMinute, closesMinute) of one weekday. */
    record Range(DayOfWeek day, int opensMinute, int closesMinute) {

        boolean contains(DayOfWeek today, int minute) {
            return day == today && opensMinute <= minute && minute < closesMinute;
        }
    }

    /** Whether the restaurant is open at {@code instant}, reading its hours in {@code zone}. */
    public static boolean isOpen(Collection<OpeningHours> week, Instant instant, ZoneId zone) {
        return isOpen(week, instant.atZone(zone));
    }

    /** Whether one of the ranges contains {@code now} (already in the restaurants' time zone). */
    public static boolean isOpen(Collection<OpeningHours> week, ZonedDateTime now) {
        DayOfWeek today = now.getDayOfWeek();
        int minute = now.getHour() * 60 + now.getMinute();
        return toRanges(week).stream().anyMatch(range -> range.contains(today, minute));
    }

    static List<Range> toRanges(Collection<OpeningHours> week) {
        List<Range> ranges = new ArrayList<>();
        if (week == null) {
            return ranges;
        }
        for (OpeningHours day : week) {
            if (day == null || day.getDayOfWeek() == null || day.getOpensAt() == null || day.getClosesAt() == null) {
                continue;
            }
            int opens = minutes(day.getOpensAt());
            int closes = minutes(day.getClosesAt());
            if (closes > opens) {
                ranges.add(new Range(day.getDayOfWeek(), opens, closes));
            } else {
                // Closes after midnight (equal times are rejected by validation; they would mean 24 h)
                ranges.add(new Range(day.getDayOfWeek(), opens, MINUTES_PER_DAY));
                if (closes > 0) {
                    ranges.add(new Range(day.getDayOfWeek().plus(1), 0, closes));
                }
            }
        }
        return ranges;
    }

    private static int minutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
