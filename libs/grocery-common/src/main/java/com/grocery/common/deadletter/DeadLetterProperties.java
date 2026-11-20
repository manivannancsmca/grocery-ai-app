package com.grocery.common.deadletter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dead-letter inspection and replay ({@code grocery.dead-letters.*}, grocery-common 0.7.0).
 * <pre>
 * grocery.dead-letters.enabled=true                               # the API + background reader
 * grocery.dead-letters.auto-startup=                              # default: spring.kafka.listener.auto-startup (true)
 * grocery.dead-letters.actions-topic=_grocery.dead-letter-actions # compacted, 1 partition
 * grocery.dead-letters.excluded-topics=delivery.location          # topics never dead-lettered
 * grocery.dead-letters.max-records=2000                           # per instance, in memory
 * grocery.dead-letters.catch-up-timeout=3s                        # bounded wait before answering an action / GET by id
 * </pre>
 */
@ConfigurationProperties(prefix = "grocery.dead-letters")
public class DeadLetterProperties {

    /** Expose {@code /api/admin/dead-letters} and read this service's DLTs. */
    private boolean enabled = true;

    /**
     * Start the background reader with the application. Unset = follow {@code spring.kafka.listener.auto-startup}
     * (true by default), so service tests that do not start their listeners do not start the reader either.
     */
    private Boolean autoStartup;

    /** Compacted topic holding the state of every dead letter (shared by all services). */
    private String actionsTopic = "_grocery.dead-letter-actions";

    /** Topics whose failures are never dead-lettered (best effort): no {@code .DLT} is read for them. */
    private Set<String> excludedTopics = new LinkedHashSet<>(List.of("delivery.location"));

    /** Most dead letters one instance keeps in memory (closed ones are dropped first, then the oldest). */
    private int maxRecords = 2000;

    /** How long a request waits for this instance to read the latest actions (other replicas' too). */
    private Duration catchUpTimeout = Duration.ofSeconds(3);

    /** How often the reader looks for topics / partitions that did not exist before and drops expired records. */
    private Duration metadataRefreshInterval = Duration.ofSeconds(30);

    /** Poll timeout of the background reader. */
    private Duration pollTimeout = Duration.ofMillis(500);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getAutoStartup() {
        return autoStartup;
    }

    public void setAutoStartup(Boolean autoStartup) {
        this.autoStartup = autoStartup;
    }

    public String getActionsTopic() {
        return actionsTopic;
    }

    public void setActionsTopic(String actionsTopic) {
        this.actionsTopic = actionsTopic;
    }

    public Set<String> getExcludedTopics() {
        return excludedTopics;
    }

    public void setExcludedTopics(Set<String> excludedTopics) {
        this.excludedTopics = excludedTopics;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    public Duration getCatchUpTimeout() {
        return catchUpTimeout;
    }

    public void setCatchUpTimeout(Duration catchUpTimeout) {
        this.catchUpTimeout = catchUpTimeout;
    }

    public Duration getMetadataRefreshInterval() {
        return metadataRefreshInterval;
    }

    public void setMetadataRefreshInterval(Duration metadataRefreshInterval) {
        this.metadataRefreshInterval = metadataRefreshInterval;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }
}
