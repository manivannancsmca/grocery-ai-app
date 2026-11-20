package com.grocery.common.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.Assert;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * Consumer retry policy ({@code grocery.kafka.retry.*}, grocery-common 0.7.0). The defaults are exactly the policy
 * of earlier versions:
 * <ul>
 *   <li>processing error: blocking retries after 1 s, 2 s, 4 s, 8 s (per-key order kept), then {@code <topic>.DLT};</li>
 *   <li>outage ({@link TransientFailures}): the same record is retried 1 s, 2 s, 4 s … capped at 30 s apart, for up to
 *       30 minutes, then {@code <topic>.DLT};</li>
 *   <li>{@link InvalidEventException} or undecodable bytes: {@code <topic>.DLT} at once (not configurable).</li>
 * </ul>
 * <pre>
 * grocery.kafka.retry.max-attempts=4            # retries after the first delivery (processing errors)
 * grocery.kafka.retry.initial-interval=1s       # first wait (processing errors and outages)
 * grocery.kafka.retry.multiplier=2.0            # growth of the wait (processing errors and outages)
 * grocery.kafka.retry.max-interval=10s          # cap of one wait (processing errors)
 * grocery.kafka.retry.outage-max-interval=30s   # cap of one wait (outages)
 * grocery.kafka.retry.outage-max-elapsed=30m    # give up on an outage after this long (then the DLT, replayable)
 * </pre>
 */
@ConfigurationProperties(prefix = "grocery.kafka.retry")
public class KafkaRetryProperties {

    /** Retries after the first delivery for a processing error (0 = dead-letter at the first failure). */
    private int maxAttempts = 4;

    /** Wait before the first retry; also the first wait of the outage policy. */
    private Duration initialInterval = Duration.ofSeconds(1);

    /** Each wait is the previous one times this factor (processing errors and outages). */
    private double multiplier = 2.0;

    /** Longest single wait between retries of a processing error. */
    private Duration maxInterval = Duration.ofSeconds(10);

    /** Longest single wait between retries of an outage. */
    private Duration outageMaxInterval = Duration.ofSeconds(30);

    /** How long an outage is retried (the same record) before it is dead-lettered. */
    private Duration outageMaxElapsed = Duration.ofMinutes(30);

    /** Backoff for processing errors: {@code max-attempts} retries growing from {@code initial-interval}. */
    public BackOff processingBackOff() {
        validate();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxAttempts);
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        return backOff;
    }

    /** Backoff for outages: growing from {@code initial-interval}, capped at {@code outage-max-interval}, for {@code outage-max-elapsed}. */
    public ExponentialBackOff outageBackOff() {
        validate();
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(outageMaxInterval.toMillis());
        backOff.setMaxElapsedTime(outageMaxElapsed.toMillis());
        return backOff;
    }

    private void validate() {
        Assert.state(maxAttempts >= 0, "grocery.kafka.retry.max-attempts must be >= 0");
        Assert.state(multiplier >= 1.0, "grocery.kafka.retry.multiplier must be >= 1.0");
        Assert.state(positive(initialInterval), "grocery.kafka.retry.initial-interval must be > 0");
        Assert.state(positive(maxInterval), "grocery.kafka.retry.max-interval must be > 0");
        Assert.state(positive(outageMaxInterval), "grocery.kafka.retry.outage-max-interval must be > 0");
        Assert.state(positive(outageMaxElapsed), "grocery.kafka.retry.outage-max-elapsed must be > 0");
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialInterval() {
        return initialInterval;
    }

    public void setInitialInterval(Duration initialInterval) {
        this.initialInterval = initialInterval;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public Duration getMaxInterval() {
        return maxInterval;
    }

    public void setMaxInterval(Duration maxInterval) {
        this.maxInterval = maxInterval;
    }

    public Duration getOutageMaxInterval() {
        return outageMaxInterval;
    }

    public void setOutageMaxInterval(Duration outageMaxInterval) {
        this.outageMaxInterval = outageMaxInterval;
    }

    public Duration getOutageMaxElapsed() {
        return outageMaxElapsed;
    }

    public void setOutageMaxElapsed(Duration outageMaxElapsed) {
        this.outageMaxElapsed = outageMaxElapsed;
    }
}
