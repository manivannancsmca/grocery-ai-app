package com.grocery.notification.email;

import com.grocery.notification.entity.NotificationType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code grocery.notification.email.*} (Phase 12 §5). Every value has a default; the channel is off unless
 * {@code enabled} (env {@code GROCERY_MAIL_ENABLED}). SMTP itself is Spring Boot's {@code spring.mail.*}
 * ({@code SPRING_MAIL_HOST}, {@code SPRING_MAIL_PORT}, …).
 */
@ConfigurationProperties(prefix = "grocery.notification.email")
public class EmailProperties {

    /** The types emailed by default (contract §5). */
    public static final Set<NotificationType> DEFAULT_TYPES = EnumSet.of(
            NotificationType.ORDER_CONFIRMED, NotificationType.ORDER_CANCELLED, NotificationType.ORDER_DELIVERED,
            NotificationType.REFUND_COMPLETED, NotificationType.REFUND_FAILED, NotificationType.RETURN_APPROVED,
            NotificationType.RETURN_REJECTED, NotificationType.ACCOUNT_APPROVED, NotificationType.ACCOUNT_REJECTED,
            NotificationType.ACCOUNT_SUSPENDED, NotificationType.AI_APPROVAL_REQUESTED);

    /**
     * Security emails: the password reset link and "your password was changed". Emailed whenever the channel is
     * {@link #enabled}, to the address the event names, whatever the {@link #types} list and the person's preferences
     * say; they never appear in the in-app inbox (the reset link carries a secret).
     */
    public static final Set<NotificationType> SECURITY_TYPES = EnumSet.of(
            NotificationType.PASSWORD_RESET_REQUESTED, NotificationType.PASSWORD_CHANGED);

    /** Off: no {@code email_outbox} rows are written and the sender job does not run. */
    private boolean enabled = false;
    /** The sender job runs (when {@link #enabled}); tests switch it off and call the sender themselves. */
    private boolean senderEnabled = true;
    /** The From header. */
    private String from = "Grocery <no-reply@grocery.local>";
    /** The React app, to make the notification's route an absolute link. */
    private String appBaseUrl = "http://localhost:6420";
    /** The notification types that are emailed; every other type stays in-app only. */
    private Set<NotificationType> types = EnumSet.copyOf(DEFAULT_TYPES);
    /** Pause between two runs of the sender job. */
    private Duration pollInterval = Duration.ofSeconds(5);
    /** Emails claimed per run. */
    private int batchSize = 20;
    /**
     * A claimed email is due again after this (its replica died while sending); a run stops sending after half of it
     * and gives the rest back.
     */
    private Duration lease = Duration.ofMinutes(10);
    /** Waits after the 1st, 2nd, 3rd … failed attempt; one more failure → FAILED. */
    private List<Duration> retryDelays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSenderEnabled() {
        return senderEnabled;
    }

    public void setSenderEnabled(boolean senderEnabled) {
        this.senderEnabled = senderEnabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    public Set<NotificationType> getTypes() {
        return types;
    }

    public void setTypes(Set<NotificationType> types) {
        this.types = types == null || types.isEmpty() ? EnumSet.noneOf(NotificationType.class) : EnumSet.copyOf(types);
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public List<Duration> getRetryDelays() {
        return retryDelays;
    }

    public void setRetryDelays(List<Duration> retryDelays) {
        this.retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
    }

    /** Whether a notification of this type is emailed. */
    public boolean emails(NotificationType type) {
        return enabled && types.contains(type);
    }
}
