package com.grocery.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The application's clock (UTC, microsecond ticks = DATETIME(6)), unless a test pins it with {@link #set(Instant)}
 * (offers expiring, stale locations, the 2 s location throttle, the days of the summary).
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    public void set(Instant instant) {
        this.fixed = instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** Moves a pinned clock forward. */
    public void advance(Duration duration) {
        set(instant().plus(duration));
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
