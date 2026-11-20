package com.grocery.common.autoconfigure;

import com.grocery.common.housekeeping.HousekeepingProperties;
import com.grocery.common.housekeeping.HousekeepingScheduler;
import com.grocery.common.housekeeping.TableCleaner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Housekeeping of the platform tables (grocery-common 0.7.0, contract Phase 10 §6): {@code outbox_event} (7 days),
 * {@code processed_event} (35 days), {@code idempotency_key} (7 days) and, since 0.8.0, {@code consumed_approval}
 * (30 days), each only where the table exists; services
 * without a database (catalog-query, cart) get nothing. {@code grocery.housekeeping.enabled=false} switches it off.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "grocery.housekeeping", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(HousekeepingProperties.class)
public class GroceryHousekeepingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TableCleaner groceryTableCleaner(DataSource dataSource, HousekeepingProperties properties) {
        return new TableCleaner(dataSource, Clock.systemUTC(), properties.getHousekeeping().getBatchSize(),
                properties.getHousekeeping().getMaxBatchesPerRun());
    }

    @Bean
    @ConditionalOnMissingBean
    HousekeepingScheduler groceryHousekeepingScheduler(TableCleaner cleaner, HousekeepingProperties properties) {
        return new HousekeepingScheduler(cleaner, properties);
    }
}
