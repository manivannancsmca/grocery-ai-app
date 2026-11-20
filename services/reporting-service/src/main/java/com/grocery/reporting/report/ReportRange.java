package com.grocery.reporting.report;

import com.grocery.common.web.BadRequestException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code from} / {@code to} of every report: India dates, both inclusive, at most {@value #MAX_DAYS} days
 * (contract §7). {@code to} defaults to today (India) and {@code from} to {@value #DEFAULT_DAYS} days ending on
 * {@code to}. A malformed date, {@code from} after {@code to} or a longer range → 400 {@code INVALID_PARAMETER}.
 *
 * <p>Facts are stored in UTC: a day D is {@code [D 00:00 IST, D+1 00:00 IST)} = {@code [D-1 18:30Z, D 18:30Z)}.
 */
public record ReportRange(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final int MAX_DAYS = 366;
    public static final int DEFAULT_DAYS = 30;

    public static ReportRange of(String fromParameter, String toParameter, Clock clock) {
        LocalDate to = blank(toParameter) ? LocalDate.now(clock.withZone(ZONE)) : date("to", toParameter);
        LocalDate from = blank(fromParameter) ? to.minusDays(DEFAULT_DAYS - 1) : date("from", fromParameter);
        if (from.isAfter(to)) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "'from' (" + from + ") must not be after 'to' (" + to + ")");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "A report covers at most " + MAX_DAYS + " days (asked: " + days + ")");
        }
        return new ReportRange(from, to);
    }

    /** First instant of {@code from} in India. */
    public Instant start() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    /** First instant AFTER {@code to} in India (exclusive end). */
    public Instant end() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** India's UTC offset in minutes (+330, no daylight saving), used to group UTC timestamps by India date. */
    public int offsetMinutes() {
        return ZONE.getRules().getOffset(start()).getTotalSeconds() / 60;
    }

    /** Every date of the range, in order. */
    public List<LocalDate> dates() {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            dates.add(day);
        }
        return dates;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static LocalDate date(String parameter, String value) {
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "Invalid " + parameter + " '" + value + "' (expected an India date like 2026-11-05)");
        }
    }
}
