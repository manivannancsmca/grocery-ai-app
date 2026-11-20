package com.grocery.restaurant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * {@code grocery.restaurant.*}.
 *
 * @param timezone zone of the restaurants' weekly opening hours ("open now" in the internal menu quote); India
 *                 time by default. An unknown zone fails the startup.
 */
@ConfigurationProperties(prefix = "grocery.restaurant")
public record RestaurantProperties(@DefaultValue("Asia/Kolkata") String timezone) {

    public RestaurantProperties {
        ZoneId.of(timezone);
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}
