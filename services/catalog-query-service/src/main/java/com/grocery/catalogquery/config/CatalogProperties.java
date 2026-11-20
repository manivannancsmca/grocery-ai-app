package com.grocery.catalogquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * {@code grocery.catalog.*}.
 *
 * @param timezone zone of the restaurants' weekly opening hours ("open now"); India time by default
 */
@ConfigurationProperties(prefix = "grocery.catalog")
public record CatalogProperties(@DefaultValue("Asia/Kolkata") String timezone) {

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }
}
