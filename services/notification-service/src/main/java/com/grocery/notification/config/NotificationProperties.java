package com.grocery.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * {@code grocery.notification.*}. Every value has a default (the contract's numbers), so the service also starts
 * without these properties.
 */
@ConfigurationProperties(prefix = "grocery.notification")
public class NotificationProperties {

    /** Events older than this (by their {@code occurredAt} header) only update projections: no notification, no signal. */
    private Duration maxEventAge = Duration.ofMinutes(15);
    /** A {@code : hb} comment on every open stream at this interval. */
    private Duration heartbeatInterval = Duration.ofSeconds(20);
    /** Open streams per user on one replica; one more closes the oldest with {@code session REPLACED}. */
    private int maxStreamsPerUser = 5;
    /** Notifications replayed after a {@code Last-Event-ID}; more missed ones → {@code signal RESYNC}. */
    private int replayLimit = 200;
    /** At most one public signal per (type, entity) in this window; the latest one is sent at the end of it. */
    private Duration publicSignalThrottle = Duration.ofSeconds(2);
    /** Notifications older than this are deleted by the nightly cleanup. */
    private Duration retention = Duration.ofDays(90);
    /** When the cleanup runs (Spring cron, India time). */
    private String retentionCron = "0 30 3 * * *";
    /**
     * Parked payment events whose order is still unknown after this long are dropped (with a WARN) by the nightly
     * cleanup: they can only belong to orders this service never heard of.
     */
    private Duration pendingEventMaxAge = Duration.ofHours(24);
    /**
     * Phase 9: a {@code DeliveryLocationUpdated} recorded longer ago than this is dropped (the customer's map only
     * wants where the partner is now).
     */
    private Duration locationMaxAge = Duration.ofSeconds(30);
    /** Phase 9: at most one {@code DELIVERY_LOCATION} per order in this window; the others are dropped. */
    private Duration locationThrottle = Duration.ofSeconds(2);
    /** Redis pub/sub channel every replica publishes to and subscribes to. */
    private String redisChannel = "grocery:realtime";
    /** Name of this replica in the logs; blank = the host name. */
    private String instanceId;

    public Duration getMaxEventAge() {
        return maxEventAge;
    }

    public void setMaxEventAge(Duration maxEventAge) {
        this.maxEventAge = maxEventAge;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getMaxStreamsPerUser() {
        return maxStreamsPerUser;
    }

    public void setMaxStreamsPerUser(int maxStreamsPerUser) {
        this.maxStreamsPerUser = maxStreamsPerUser;
    }

    public int getReplayLimit() {
        return replayLimit;
    }

    public void setReplayLimit(int replayLimit) {
        this.replayLimit = replayLimit;
    }

    public Duration getPublicSignalThrottle() {
        return publicSignalThrottle;
    }

    public void setPublicSignalThrottle(Duration publicSignalThrottle) {
        this.publicSignalThrottle = publicSignalThrottle;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }

    public Duration getPendingEventMaxAge() {
        return pendingEventMaxAge;
    }

    public void setPendingEventMaxAge(Duration pendingEventMaxAge) {
        this.pendingEventMaxAge = pendingEventMaxAge;
    }

    public Duration getLocationMaxAge() {
        return locationMaxAge;
    }

    public void setLocationMaxAge(Duration locationMaxAge) {
        this.locationMaxAge = locationMaxAge;
    }

    public Duration getLocationThrottle() {
        return locationThrottle;
    }

    public void setLocationThrottle(Duration locationThrottle) {
        this.locationThrottle = locationThrottle;
    }

    public String getRedisChannel() {
        return redisChannel;
    }

    public void setRedisChannel(String redisChannel) {
        this.redisChannel = redisChannel;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    /** {@link #getInstanceId()}, or the host name when it is blank. */
    public String resolvedInstanceId() {
        if (instanceId != null && !instanceId.isBlank()) {
            return instanceId.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "notification-service";
        }
    }
}
