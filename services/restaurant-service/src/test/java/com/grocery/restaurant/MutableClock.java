package com.grocery.restaurant;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The application's clock (UTC, microsecond ticks = DATETIME(6)), unless a test pins it with {@link #set(Instant)}
 * (e.g. to check the menu quote's {@code openNow}).
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    public void set(Instant instant) {
        this.fixed = instant;
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        Instant pinned = fixed;
        return pinned != null ? pinned : Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
