package com.grocery.common.autoconfigure;

import com.grocery.common.approval.ApprovalProperties;
import com.grocery.common.approval.ApprovalTokenFilter;
import com.grocery.common.approval.ApprovalTokenVerifier;
import com.grocery.common.approval.ConsumedApprovals;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** When the approval-token filter is there, and how it is set up. */
class GroceryApprovalAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GroceryApprovalAutoConfiguration.class))
            .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:mysql://localhost:1/none"))
            .withPropertyValues("spring.application.name=order-service");

    @Test
    void offByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ApprovalTokenFilter.class)
                .doesNotHaveBean(ApprovalTokenVerifier.class)
                .doesNotHaveBean(FilterRegistrationBean.class));
        runner.withPropertyValues("grocery.approvals.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApprovalTokenFilter.class));
    }

    @Test
    void enabledItRegistersTheFilterRightAfterSpringSecurity() {
        runner.withPropertyValues("grocery.approvals.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ApprovalTokenFilter.class).hasSingleBean(ConsumedApprovals.class);
            assertThat(context.getBean(ApprovalTokenVerifier.class).audience()).isEqualTo("order-service");
            FilterRegistrationBean<?> registration = context.getBean("groceryApprovalTokenFilterRegistration",
                    FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isSameAs(context.getBean(ApprovalTokenFilter.class));
            assertThat(registration.getOrder()).isEqualTo(-90);           // Spring Security's chain is -100
            assertThat(registration.determineDispatcherTypes()).containsExactly(DispatcherType.REQUEST);
        });
        runner.withPropertyValues("grocery.approvals.enabled=true", "spring.security.filter.order=5",
                        "grocery.approvals.audience=payment-service")
                .run(context -> {
                    assertThat(context.getBean("groceryApprovalTokenFilterRegistration", FilterRegistrationBean.class)
                            .getOrder()).isEqualTo(15);
                    assertThat(context.getBean(ApprovalTokenVerifier.class).audience()).isEqualTo("payment-service");
                });
    }

    @Test
    void misconfigurationFailsTheStartInsteadOfIgnoringApprovalTokens() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GroceryApprovalAutoConfiguration.class))
                .withPropertyValues("grocery.approvals.enabled=true", "spring.application.name=order-service")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("grocery.approvals.enabled=true needs a single DataSource with the "
                                + "consumed_approval table (see the grocery-common README)"));
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GroceryApprovalAutoConfiguration.class))
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:mysql://localhost:1/none"))
                .withPropertyValues("grocery.approvals.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("spring.application.name"));
        runner.withPropertyValues("grocery.approvals.enabled=true", "grocery.approvals.jwks-uri=not a url")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("grocery.approvals.enabled=true", "grocery.approvals.jwks-cache-ttl=10s",
                        "grocery.approvals.jwks-min-refetch-interval=30s")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("jwks-min-refetch-interval"));
    }

    @Test
    void onlyInServletApplicationsWithSpringSecurity() {
        runner.withPropertyValues("grocery.approvals.enabled=true")
                .withClassLoader(new FilteredClassLoader(SecurityContextHolder.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ApprovalTokenFilter.class));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GroceryApprovalAutoConfiguration.class))
                .withPropertyValues("grocery.approvals.enabled=true", "spring.application.name=order-service")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ApprovalTokenFilter.class));
    }

    @Test
    void theJwksUriComesFromThePropertyOrApprovalJwksUri() {
        runner.withPropertyValues("grocery.approvals.enabled=true")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.<String, Object>of("APPROVAL_JWKS_URI", "http://ai-orchestrator-service:8115"
                                        + "/.well-known/approval-jwks.json",
                                        "GROCERY_APPROVALS_CLOCKSKEW", "2s"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ApprovalProperties properties = context.getBean(ApprovalProperties.class);
                    assertThat(properties.getJwksUri()).isNull();
                    assertThat(GroceryApprovalAutoConfiguration.jwksUri(properties, context.getEnvironment()))
                            .hasToString("http://ai-orchestrator-service:8115/.well-known/approval-jwks.json");
                    properties.setJwksUri("http://localhost:9999/keys.json");
                    assertThat(GroceryApprovalAutoConfiguration.jwksUri(properties, context.getEnvironment()))
                            .hasToString("http://localhost:9999/keys.json");
                    assertThat(properties.getClockSkew()).hasSeconds(2);
                    assertThat(properties.getIssuer()).isEqualTo("ai-orchestrator-service");
                    assertThat(properties.getJwksCacheTtl()).hasMinutes(5);
                    assertThat(properties.getJwksMinRefetchInterval()).hasSeconds(30);
                    assertThat(properties.getJwksTimeout()).hasSeconds(2);
                });
        runner.withPropertyValues("grocery.approvals.enabled=true").run(context -> assertThat(
                GroceryApprovalAutoConfiguration.jwksUri(context.getBean(ApprovalProperties.class),
                        context.getEnvironment())).hasToString(ApprovalProperties.DEFAULT_JWKS_URI));
    }
}
