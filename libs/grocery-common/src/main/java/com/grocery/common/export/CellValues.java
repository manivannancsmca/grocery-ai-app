package com.grocery.common.export;

import com.grocery.common.export.ExportColumn.Kind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/** Turns row values into what the writers need: text, numbers, local dates and times in the export's zone. */
final class CellValues {

    static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final ZoneId zone;

    CellValues(ZoneId zone) {
        this.zone = zone;
    }

    ZoneId zone() {
        return zone;
    }

    /** The value as text the way CSV and PDF show it; {@code ""} for null. */
    String text(Kind kind, Object value) {
        if (value == null) {
            return "";
        }
        return switch (kind) {
            case TEXT -> words(value);
            case INTEGER -> value instanceof Number number ? Long.toString(number.longValue()) : value.toString();
            case DECIMAL -> value instanceof Number number ? decimal(number).toPlainString() : value.toString();
            case MONEY -> value instanceof Number number
                    ? decimal(number).setScale(2, RoundingMode.HALF_UP).toPlainString() : value.toString();
            case DATE -> {
                LocalDate date = localDate(value);
                yield date == null ? value.toString() : DATE.format(date);
            }
            case DATE_TIME -> {
                LocalDateTime dateTime = localDateTime(value);
                yield dateTime == null ? value.toString() : DATE_TIME.format(dateTime);
            }
            case YES_NO -> value instanceof Boolean yes ? (yes ? "Yes" : "No") : value.toString();
        };
    }

    /** The moment in the export's time zone (null when the value is not a date-time). */
    LocalDateTime localDateTime(Object value) {
        return switch (value) {
            case Instant instant -> LocalDateTime.ofInstant(instant, zone);
            case OffsetDateTime offset -> offset.atZoneSameInstant(zone).toLocalDateTime();
            case ZonedDateTime zoned -> zoned.withZoneSameInstant(zone).toLocalDateTime();
            case LocalDateTime local -> local;
            case LocalDate date -> date.atStartOfDay();
            default -> null;
        };
    }

    /** The calendar date in the export's time zone (null when the value is not a date). */
    LocalDate localDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        LocalDateTime dateTime = value instanceof TemporalAccessor ? localDateTime(value) : null;
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    static BigDecimal decimal(Number number) {
        return switch (number) {
            case BigDecimal big -> big;
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f.doubleValue());
            default -> BigDecimal.valueOf(number.longValue());
        };
    }

    /**
     * A filter value as the PDF heading shows it: like {@link #words}, and a status code passed as a string
     * ({@code "DELIVERED"}: capitals and underscores only) as words too.
     */
    static String filterValue(Object value) {
        String text = words(value);
        return text.matches("[A-Z]+(_[A-Z]+)*") ? humanize(text) : text;
    }

    /** Enum constants as words ({@code OUT_FOR_DELIVERY} → "Out for delivery"); collections joined by commas. */
    static String words(Object value) {
        return switch (value) {
            case Enum<?> constant -> humanize(constant.name());
            case Collection<?> values -> values.stream().map(CellValues::words).collect(Collectors.joining(", "));
            default -> value.toString();
        };
    }

    static String humanize(String constant) {
        String spaced = constant.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
