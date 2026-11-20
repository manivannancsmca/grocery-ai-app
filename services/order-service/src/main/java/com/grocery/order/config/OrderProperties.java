package com.grocery.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * {@code grocery.order.*} — the payment window, the business time zone, cart-service (and, Phase 12, pricing-service)
 * with their timeouts and circuit breakers, (Phase 10) the stuck-order watchdog, (Phase 12) returns and complaints.
 * Every value has a default (the contract's numbers), so the service also starts without these properties.
 */
@ConfigurationProperties(prefix = "grocery.order")
public class OrderProperties {

    /** How long the customer has to pay, from the order's creation ({@code payment.dueAt}). */
    private Duration paymentWindow = Duration.ofMinutes(15);
    /** Day boundaries of the admin stats and the admin list's {@code from}/{@code to} dates. */
    private ZoneId timezone = ZoneId.of("Asia/Kolkata");

    private final Http http = new Http();
    private final Services services = new Services();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Watchdog watchdog = new Watchdog();
    private final Returns returns = new Returns();
    private final Complaints complaints = new Complaints();

    public Duration getPaymentWindow() {
        return paymentWindow;
    }

    public void setPaymentWindow(Duration paymentWindow) {
        this.paymentWindow = paymentWindow;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public void setTimezone(ZoneId timezone) {
        this.timezone = timezone;
    }

    public Http getHttp() {
        return http;
    }

    public Services getServices() {
        return services;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Watchdog getWatchdog() {
        return watchdog;
    }

    public Returns getReturns() {
        return returns;
    }

    public Complaints getComplaints() {
        return complaints;
    }

    /**
     * The stuck-order watchdog (docs/api/phase-10-event-driven-hardening.md §4): how often it runs and how long an
     * order may stay in each status (env names in brackets).
     */
    public static class Watchdog {
        /** {@code false} = no scheduled runs in this instance (tests run it themselves). */
        private boolean enabled = true;
        /** Pause between two runs ({@code ORDER_WATCHDOG_INTERVAL}, fixed delay). */
        private Duration interval = Duration.ofMinutes(1);
        /** Orders flagged per batch (one non-locking candidate query per status and batch). */
        private int batchSize = 100;
        /** CREATED longer than this after creation → STOCK_CHECK_OVERDUE ({@code ORDER_STUCK_CREATED_AFTER}). */
        private Duration createdAfter = Duration.ofMinutes(2);
        /** AWAITING_PAYMENT this long after {@code payment.dueAt} → PAYMENT_EXPIRY_OVERDUE ({@code ORDER_STUCK_PAYMENT_GRACE}). */
        private Duration paymentGrace = Duration.ofMinutes(2);
        /** CONFIRMED longer than this after confirmation → STORE_RESPONSE_OVERDUE ({@code ORDER_STUCK_CONFIRMED_AFTER}). */
        private Duration confirmedAfter = Duration.ofMinutes(15);
        /** ACCEPTED / PREPARING this long after {@code readyBy} → PREPARATION_OVERDUE ({@code ORDER_STUCK_PREPARATION_GRACE}). */
        private Duration preparationGrace = Duration.ofMinutes(30);
        /** READY_FOR_PICKUP longer than this after {@code readyAt} → PICKUP_OVERDUE ({@code ORDER_STUCK_PICKUP_AFTER}). */
        private Duration pickupAfter = Duration.ofMinutes(30);
        /** OUT_FOR_DELIVERY longer than this after pickup → DELIVERY_OVERDUE ({@code ORDER_STUCK_DELIVERY_AFTER}). */
        private Duration deliveryAfter = Duration.ofMinutes(90);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("grocery.order.watchdog.batch-size must be 1-1000");
            }
            this.batchSize = batchSize;
        }

        public Duration getCreatedAfter() {
            return createdAfter;
        }

        public void setCreatedAfter(Duration createdAfter) {
            this.createdAfter = notNegative(createdAfter, "created-after");
        }

        public Duration getPaymentGrace() {
            return paymentGrace;
        }

        public void setPaymentGrace(Duration paymentGrace) {
            this.paymentGrace = notNegative(paymentGrace, "payment-grace");
        }

        public Duration getConfirmedAfter() {
            return confirmedAfter;
        }

        public void setConfirmedAfter(Duration confirmedAfter) {
            this.confirmedAfter = notNegative(confirmedAfter, "confirmed-after");
        }

        public Duration getPreparationGrace() {
            return preparationGrace;
        }

        public void setPreparationGrace(Duration preparationGrace) {
            this.preparationGrace = notNegative(preparationGrace, "preparation-grace");
        }

        public Duration getPickupAfter() {
            return pickupAfter;
        }

        public void setPickupAfter(Duration pickupAfter) {
            this.pickupAfter = notNegative(pickupAfter, "pickup-after");
        }

        public Duration getDeliveryAfter() {
            return deliveryAfter;
        }

        public void setDeliveryAfter(Duration deliveryAfter) {
            this.deliveryAfter = notNegative(deliveryAfter, "delivery-after");
        }

        private static Duration notNegative(Duration value, String name) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException("grocery.order.watchdog." + name + " must not be negative");
            }
            return value;
        }
    }

    /** Timeouts of the cart-service call (the preview itself calls up to six services). */
    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(5);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    /** Base URLs of the services order-service calls. */
    public static class Services {
        private String cart = "http://localhost:8109";
        /** Phase 12: coupon redemption at placement ({@code PRICING_SERVICE_URL}). */
        private String pricing = "http://localhost:8108";

        public String getCart() {
            return cart;
        }

        public void setCart(String cart) {
            this.cart = cart;
        }

        public String getPricing() {
            return pricing;
        }

        public void setPricing(String pricing) {
            this.pricing = pricing;
        }
    }

    /**
     * Returns (docs/api/phase-12-post-mvp-features.md §3; env names in brackets): how long after delivery a customer
     * may ask, how long the store has to decide, and the escalation poller. {@code window} and {@code decisionTimeout}
     * are the defaults of the admin platform settings (area {@code returns}, {@code settings.ReturnSettingsService}).
     */
    public static class Returns {
        /** A delivered order can be returned until {@code deliveredAt + window} ({@code ORDER_RETURN_WINDOW}). */
        private Duration window = Duration.ofHours(48);
        /** The store decides by {@code requestedAt + decisionTimeout}, then admins ({@code RETURN_DECISION_TIMEOUT}). */
        private Duration decisionTimeout = Duration.ofHours(24);
        /** Pause between two escalation runs ({@code RETURN_ESCALATION_INTERVAL}, fixed delay). */
        private Duration escalationInterval = Duration.ofMinutes(1);
        /** {@code false} = no scheduled escalation runs in this instance (tests run it themselves). */
        private boolean escalationEnabled = true;
        /** Returns escalated per batch ({@code FOR UPDATE SKIP LOCKED}). */
        private int batchSize = 100;

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = positive(window, "window");
        }

        public Duration getDecisionTimeout() {
            return decisionTimeout;
        }

        public void setDecisionTimeout(Duration decisionTimeout) {
            this.decisionTimeout = positive(decisionTimeout, "decision-timeout");
        }

        public Duration getEscalationInterval() {
            return escalationInterval;
        }

        public void setEscalationInterval(Duration escalationInterval) {
            this.escalationInterval = positive(escalationInterval, "escalation-interval");
        }

        public boolean isEscalationEnabled() {
            return escalationEnabled;
        }

        public void setEscalationEnabled(boolean escalationEnabled) {
            this.escalationEnabled = escalationEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("grocery.order.returns.batch-size must be 1-1000");
            }
            this.batchSize = batchSize;
        }

        private static Duration positive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("grocery.order.returns." + name + " must be positive");
            }
            return value;
        }
    }

    /** Complaints (docs/api/payment-and-support-agents.md §2; env names in brackets). */
    public static class Complaints {
        /**
         * How many open (OPEN / IN_PROGRESS) complaints one customer may have at once; one more is 409
         * TOO_MANY_OPEN_COMPLAINTS ({@code ORDER_COMPLAINTS_MAX_OPEN}).
         */
        private int maxOpen = 5;

        public int getMaxOpen() {
            return maxOpen;
        }

        public void setMaxOpen(int maxOpen) {
            if (maxOpen < 1 || maxOpen > 100) {
                throw new IllegalArgumentException("grocery.order.complaints.max-open must be 1-100");
            }
            this.maxOpen = maxOpen;
        }
    }

    /** Resilience4j circuit breaker settings (count-based window); only 5xx, timeouts and I/O errors count. */
    public static class CircuitBreaker {
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 10;
        private float failureRateThreshold = 50;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int permittedCallsInHalfOpenState = 3;

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }
    }
}
