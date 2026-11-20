package com.grocery.common.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Switches for the grocery-common building blocks ({@code grocery.common.*}).
 */
@ConfigurationProperties(prefix = "grocery.common")
public class GroceryCommonProperties {

    private final Correlation correlation = new Correlation();
    private final Outbox outbox = new Outbox();
    private final ErrorHandling errorHandling = new ErrorHandling();

    public Correlation getCorrelation() {
        return correlation;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public ErrorHandling getErrorHandling() {
        return errorHandling;
    }

    public static class Correlation {
        /** Register the X-Correlation-Id servlet filter. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Outbox {
        /** Register the OutboxPublisher and ProcessedEventGuard (requires the two tables). */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ErrorHandling {
        /** Register the ProblemDetail-based GlobalExceptionHandler. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
