package com.grocery.notification.service;

import com.grocery.notification.realtime.ConnectionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * notification-service's business metrics (docs/api/phase-13-observability-and-testing.md §1):
 * <ul>
 *   <li>{@code grocery_emails_total{status}} — SMTP hand-overs of the email outbox: {@code SENT} (accepted by SMTP)
 *       and {@code FAILED} (an attempt SMTP refused or could not take — every failed attempt, whether it is retried
 *       later or was the last one; alert {@code EmailFailures});</li>
 *   <li>{@code grocery_sse_connections} — SSE streams open on this replica (in memory, no query).</li>
 * </ul>
 */
@Component
public class NotificationMetrics {

    public static final String EMAILS = "grocery.emails";
    public static final String SSE_CONNECTIONS = "grocery.sse.connections";

    private final Counter sent;
    private final Counter failed;

    public NotificationMetrics(MeterRegistry registry, ConnectionRegistry connections) {
        this.sent = Counter.builder(EMAILS)
                .description("Emails handed to SMTP (SENT) and failed SMTP attempts (FAILED)")
                .tag("status", "SENT")
                .register(registry);
        this.failed = Counter.builder(EMAILS)
                .description("Emails handed to SMTP (SENT) and failed SMTP attempts (FAILED)")
                .tag("status", "FAILED")
                .register(registry);
        Gauge.builder(SSE_CONNECTIONS, connections, open -> open.size())
                .description("SSE streams open on this replica")
                .register(registry);
    }

    /** SMTP accepted an email. */
    public void emailSent() {
        sent.increment();
    }

    /** An attempt to hand an email to SMTP failed (retried later, or failed for good). */
    public void emailFailed() {
        failed.increment();
    }
}
