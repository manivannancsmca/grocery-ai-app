package com.grocery.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The application clock in tests: real UTC time (µs precision, like the production clock) plus an adjustable
 * offset, so a test can run the pollers "in the future". Tests that move it reset it afterwards.
 */
public class TestClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration by) {
        offset = offset.plus(by);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant now = Instant.now().plus(offset);
        return now.minusNanos(now.getNano() % 1_000);
    }
}
