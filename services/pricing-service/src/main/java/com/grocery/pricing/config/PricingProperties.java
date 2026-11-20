package com.grocery.pricing.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code grocery.pricing.*}
 *
 * @param approvalThresholdPercent a selling-price change of more than this many percent (either direction)
 *                                 needs an admin's approval; exactly this much is applied at once
 * @param quoteCacheTtl            how long a quote entry ({@code price:v1:<productId>}) lives in Redis
 */
@Validated
@ConfigurationProperties(prefix = "grocery.pricing")
public record PricingProperties(
        @DefaultValue("20") @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal approvalThresholdPercent,
        @DefaultValue("10m") @NotNull Duration quoteCacheTtl) {
}
