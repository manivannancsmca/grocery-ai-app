package com.grocery.common.autoconfigure;

import com.grocery.common.export.ExportProperties;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.Exporter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * Data exports (0.10.0): the {@link Exporter} in every servlet service and, where JPA is on the classpath, the
 * {@link ExportQueries} that read a list query in keyset batches. Settings: {@code grocery.export.*}
 * ({@link ExportProperties}). Both back off if the service defines its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(ExportProperties.class)
public class GroceryExportAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ExporterConfiguration {

        @Bean
        @ConditionalOnMissingBean
        Exporter exporter(ExportProperties properties, ObjectProvider<MeterRegistry> meters) {
            return new Exporter(properties, meters.getIfUnique(), Clock.systemUTC());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "jakarta.persistence.EntityManagerFactory",
            "org.springframework.orm.jpa.SharedEntityManagerCreator",
            "org.springframework.data.jpa.domain.Specification"
    })
    static class QueriesConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ExportQueries exportQueries(ObjectProvider<EntityManagerFactory> entityManagerFactory,
                                    ObjectProvider<PlatformTransactionManager> transactionManager) {
            return new ExportQueries(entityManagerFactory, transactionManager);
        }
    }
}
