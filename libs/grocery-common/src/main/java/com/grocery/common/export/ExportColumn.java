package com.grocery.common.export;

import java.util.Objects;
import java.util.function.Function;

/**
 * One column of an export: its header, what kind of value it holds (decides how each format writes it: a real number
 * or date cell in Excel, right-aligned in the PDF…) and how to read the value from a row.
 *
 * <p>The reader must be null-safe: return {@code null} for "nothing to show", never throw.
 *
 * <p>{@code personal}: a contact detail of a person (e-mail, phone, street address), left out of an export unless the
 * caller may see personal data in bulk ({@link ExportTable#visibleTo}).
 *
 * @param <R> the row type (usually the same view record the list endpoint returns)
 */
public record ExportColumn<R>(String header, Kind kind, Function<? super R, ?> value, boolean personal) {

    /** How a value is written. */
    public enum Kind {
        /** Any text; an enum constant becomes words ({@code OUT_FOR_DELIVERY} → "Out for delivery"). */
        TEXT,
        /** A whole number. */
        INTEGER,
        /** A decimal number as it is (quantities, distances, ratings). */
        DECIMAL,
        /** An amount of money: two decimals (the currency goes in the header, e.g. "Total (INR)"). */
        MONEY,
        /** A calendar date ({@code LocalDate}, or an instant shown as its date in the export's time zone). */
        DATE,
        /** A moment ({@code Instant}, {@code OffsetDateTime}…) shown in the export's time zone, to the minute. */
        DATE_TIME,
        /** {@code true}/{@code false} as "Yes"/"No". */
        YES_NO
    }

    public ExportColumn {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    public ExportColumn(String header, Kind kind, Function<? super R, ?> value) {
        this(header, kind, value, false);
    }

    ExportColumn<R> asPersonal() {
        return new ExportColumn<>(header, kind, value, true);
    }

    /** Reads this column's value from {@code row}. */
    public Object read(R row) {
        return value.apply(row);
    }
}
