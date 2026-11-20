package com.grocery.reporting.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Reads the {@code occurredAt} header. Debezium's outbox router places the {@code occurred_at DATETIME(6)} column
 * as epoch <b>microseconds</b> (rendered as text by Kafka Connect's header converter); other writers may use epoch
 * milliseconds or ISO-8601. Anything unreadable is {@code null} (the caller then uses the processing time).
 */
public final class OccurredAt {

    private static final Pattern EPOCH_NUMBER = Pattern.compile("-?\\d{1,19}");

    private OccurredAt() {
    }

    public static Instant parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        if (EPOCH_NUMBER.matcher(value).matches()) {
            try {
                return fromEpochNumber(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** Microseconds (Debezium), else milliseconds or seconds, told apart by magnitude (any date after 1973). */
    static Instant fromEpochNumber(long value) {
        long abs = Math.abs(value);
        if (abs >= 100_000_000_000_000_000L) {
            return Instant.ofEpochSecond(0, value);                                     // nanoseconds
        }
        if (abs >= 100_000_000_000_000L) {
            return Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000L),
                    Math.floorMod(value, 1_000_000L) * 1_000L);                         // microseconds
        }
        if (abs >= 100_000_000_000L) {
            return Instant.ofEpochMilli(value);                                         // milliseconds
        }
        return Instant.ofEpochSecond(value);                                            // seconds
    }
}
