package com.grocery.notification.realtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 9: at most one DELIVERY_LOCATION per order per 2 s, on a controlled clock. */
class LocationThrottleTest {

    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    @Test
    void theFirstPositionOfEveryTwoSecondWindowPassesPerOrder() {
        AtomicLong now = new AtomicLong(1_000 * SECOND);
        LocationThrottle throttle = new LocationThrottle(2 * SECOND, now::get);
        UUID order = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThat(throttle.tryAcquire(order)).isTrue();
        now.addAndGet(SECOND);
        assertThat(throttle.tryAcquire(order)).isFalse();
        assertThat(throttle.tryAcquire(other)).isTrue();                 // every order has its own window
        now.addAndGet(SECOND - 1);
        assertThat(throttle.tryAcquire(order)).isFalse();                // 1.999999999 s after the first
        now.addAndGet(1);
        assertThat(throttle.tryAcquire(order)).isTrue();                 // exactly 2 s: a new window
        assertThat(throttle.tryAcquire(order)).isFalse();
        now.addAndGet(5 * SECOND);
        assertThat(throttle.tryAcquire(order)).isTrue();                 // after a pause: at once
    }

    @Test
    void idleOrdersAreForgotten() {
        AtomicLong now = new AtomicLong(0);
        LocationThrottle throttle = new LocationThrottle(2 * SECOND, now::get);
        throttle.tryAcquire(UUID.randomUUID());
        throttle.tryAcquire(UUID.randomUUID());
        now.addAndGet(SECOND);
        UUID recent = UUID.randomUUID();
        throttle.tryAcquire(recent);
        now.addAndGet(SECOND);

        throttle.forgetIdle();

        assertThat(throttle.size()).isEqualTo(1);                        // the recent one is still in its window
        assertThat(throttle.tryAcquire(recent)).isFalse();
    }
}
