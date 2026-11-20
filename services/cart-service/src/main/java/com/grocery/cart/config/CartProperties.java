package com.grocery.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code grocery.cart.*} — cart limits, the downstream services, their timeouts and circuit breakers, and the
 * delivery-fee rules of the checkout preview. Every value has a default (the contract's numbers), so the service
 * also starts without any of these properties.
 */
@ConfigurationProperties(prefix = "grocery.cart")
public class CartProperties {

    /** How long a cart lives after its last change (Redis TTL of {@code cart:v1:<customerId>}). */
    private Duration ttl = Duration.ofDays(30);
    /** Highest quantity of one line (a line above → 422 QUANTITY_LIMIT). */
    private int maxQuantityPerLine = 20;
    /** Most lines in one cart (one more → 422 CART_LIMIT_REACHED). */
    private int maxLines = 30;
    /** Compare-and-set retries of one write before 409 CONCURRENT_MODIFICATION. */
    private int writeRetries = 3;

    private final Http http = new Http();
    private final Services services = new Services();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Delivery delivery = new Delivery();

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxQuantityPerLine() {
        return maxQuantityPerLine;
    }

    public void setMaxQuantityPerLine(int maxQuantityPerLine) {
        this.maxQuantityPerLine = maxQuantityPerLine;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public int getWriteRetries() {
        return writeRetries;
    }

    public void setWriteRetries(int writeRetries) {
        this.writeRetries = writeRetries;
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

    public Delivery getDelivery() {
        return delivery;
    }

    /** Timeouts of every downstream call: a slow dependency must not hold the customer's request for long. */
    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofMillis(2500);

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

    /** Base URLs of the services the cart reads from. */
    public static class Services {
        private String pricing = "http://localhost:8108";
        private String inventory = "http://localhost:8107";
        private String catalogQuery = "http://localhost:8106";
        private String vendor = "http://localhost:8103";
        private String restaurant = "http://localhost:8104";
        private String customer = "http://localhost:8102";

        public String getPricing() {
            return pricing;
        }

        public void setPricing(String pricing) {
            this.pricing = pricing;
        }

        public String getInventory() {
            return inventory;
        }

        public void setInventory(String inventory) {
            this.inventory = inventory;
        }

        public String getCatalogQuery() {
            return catalogQuery;
        }

        public void setCatalogQuery(String catalogQuery) {
            this.catalogQuery = catalogQuery;
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String vendor) {
            this.vendor = vendor;
        }

        public String getRestaurant() {
            return restaurant;
        }

        public void setRestaurant(String restaurant) {
            this.restaurant = restaurant;
        }

        public String getCustomer() {
            return customer;
        }

        public void setCustomer(String customer) {
            this.customer = customer;
        }
    }

    /**
     * One Resilience4j circuit breaker per dependency (count-based window). Only server errors, timeouts and
     * connection failures count; 4xx answers never open a breaker.
     */
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

    /**
     * Delivery charges per store type (contract §3). The fees (base fee, included km, per extra km, free above,
     * minimum order) are admin platform settings owned by pricing-service ({@code DeliveryFeeRules}); the values here
     * are used only until pricing-service has answered once. Road factor and maximum distance are always these.
     */
    public static class Delivery {
        /** Straight-line (haversine) distance × this factor ≈ road distance. */
        private BigDecimal roadFactor = new BigDecimal("1.3");
        /** How long the fees read from pricing-service are used before asking again (also the wait after a failure). */
        private Duration settingsCacheTtl = Duration.ofSeconds(15);
        private final DeliveryRule grocery = new DeliveryRule(
                "3", "25.00", "8.00", "499.00", "15", "99.00");
        private final DeliveryRule restaurant = new DeliveryRule(
                "3", "30.00", "10.00", null, "12", null);

        public BigDecimal getRoadFactor() {
            return roadFactor;
        }

        public void setRoadFactor(BigDecimal roadFactor) {
            this.roadFactor = roadFactor;
        }

        public Duration getSettingsCacheTtl() {
            return settingsCacheTtl;
        }

        public void setSettingsCacheTtl(Duration settingsCacheTtl) {
            if (settingsCacheTtl == null || settingsCacheTtl.isNegative()) {
                throw new IllegalArgumentException("grocery.cart.delivery.settings-cache-ttl must not be negative");
            }
            this.settingsCacheTtl = settingsCacheTtl;
        }

        public DeliveryRule getGrocery() {
            return grocery;
        }

        public DeliveryRule getRestaurant() {
            return restaurant;
        }
    }

    /**
     * @see com.grocery.cart.service.DeliveryCalculator
     */
    public static class DeliveryRule {
        /** Kilometres covered by the base fee. */
        private BigDecimal includedKm;
        private BigDecimal baseFee;
        /** Charged for every started kilometre beyond {@code includedKm}. */
        private BigDecimal perExtraKm;
        /** Delivery is free from this subtotal on; null = never free. */
        private BigDecimal freeAbove;
        /** Further than this → OUT_OF_DELIVERY_AREA. */
        private BigDecimal maxDistanceKm;
        /** Subtotal below this → BELOW_MINIMUM_ORDER; null = no minimum. */
        private BigDecimal minimumOrder;

        public DeliveryRule() {
        }

        DeliveryRule(String includedKm, String baseFee, String perExtraKm, String freeAbove, String maxDistanceKm,
                     String minimumOrder) {
            this.includedKm = new BigDecimal(includedKm);
            this.baseFee = new BigDecimal(baseFee);
            this.perExtraKm = new BigDecimal(perExtraKm);
            this.freeAbove = freeAbove == null ? null : new BigDecimal(freeAbove);
            this.maxDistanceKm = new BigDecimal(maxDistanceKm);
            this.minimumOrder = minimumOrder == null ? null : new BigDecimal(minimumOrder);
        }

        public BigDecimal getIncludedKm() {
            return includedKm;
        }

        public void setIncludedKm(BigDecimal includedKm) {
            this.includedKm = includedKm;
        }

        public BigDecimal getBaseFee() {
            return baseFee;
        }

        public void setBaseFee(BigDecimal baseFee) {
            this.baseFee = baseFee;
        }

        public BigDecimal getPerExtraKm() {
            return perExtraKm;
        }

        public void setPerExtraKm(BigDecimal perExtraKm) {
            this.perExtraKm = perExtraKm;
        }

        public BigDecimal getFreeAbove() {
            return freeAbove;
        }

        public void setFreeAbove(BigDecimal freeAbove) {
            this.freeAbove = freeAbove;
        }

        public BigDecimal getMaxDistanceKm() {
            return maxDistanceKm;
        }

        public void setMaxDistanceKm(BigDecimal maxDistanceKm) {
            this.maxDistanceKm = maxDistanceKm;
        }

        public BigDecimal getMinimumOrder() {
            return minimumOrder;
        }

        public void setMinimumOrder(BigDecimal minimumOrder) {
            this.minimumOrder = minimumOrder;
        }
    }
}
