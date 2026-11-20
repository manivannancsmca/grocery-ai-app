package com.grocery.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Reporting service (Phase 12 §7, docs/api/phase-12-post-mvp-features.md): consumes order, payment, delivery, review
 * and vendor events in one consumer group ({@code reporting-service.facts}), keeps a read model of facts in its own
 * database (one row per order, per line, per delivery, refund, return, review, rating and goods receipt) and answers
 * the admin reports under {@code /api/admin/reports} as JSON or CSV. It calls no other service and publishes nothing.
 */
@SpringBootApplication
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }
}
