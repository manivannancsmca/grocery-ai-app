package com.grocery.ai.support;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Times as customers read them: "19 Nov, 2:32 pm IST". The APIs answer in UTC; a small model often converts UTC to
 * India time wrongly, so the tools hand it the India time already written out.
 */
public final class IndiaTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
            .withZone(ZONE);

    private IndiaTime() {
    }

    /** "19 Nov, 2:32 pm IST", or null for null. */
    public static String format(Instant instant) {
        if (instant == null) {
            return null;
        }
        String text = FORMAT.format(instant);
        // "PM" → "pm": reads like a sentence, not a timestamp.
        return text.substring(0, text.length() - 2) + text.substring(text.length() - 2).toLowerCase(Locale.ENGLISH)
                + " IST";
    }

    /** {@link #format} of an ISO-8601 instant from an API, or null when absent or not a valid instant. */
    public static String format(String isoInstant) {
        return format(parse(isoInstant));
    }

    /** An ISO-8601 instant from an API, or null when absent or not a valid instant. */
    public static Instant parse(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(isoInstant);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
