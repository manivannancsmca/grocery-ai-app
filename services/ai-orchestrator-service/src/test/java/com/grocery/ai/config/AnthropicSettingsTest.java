package com.grocery.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** Without a real key the production model counts as not configured, so nothing is sent to Anthropic. */
class AnthropicSettingsTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class Settings {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Settings.class);

    @Test
    void aKeyIsNeededOnlyForAnthropic() {
        runner.withPropertyValues("grocery.ai.provider=anthropic").run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.anthropic().configured()).isFalse();
            assertThat(properties.modelConfigured()).isFalse();
            assertThat(properties.modelName()).isEqualTo("claude-opus-5-5");
            assertThat(properties.contextTokens()).isEqualTo(100000);
            assertThat(properties.anthropic().baseUrl()).isEqualTo("https://api.anthropic.com");
        });
        runner.withPropertyValues("grocery.ai.provider=anthropic", "grocery.ai.anthropic.api-key=REPLACE_ME")
                .run(context -> assertThat(context.getBean(AiProperties.class).modelConfigured())
                        .as("the Secrets Manager placeholder is not a key").isFalse());
        runner.withPropertyValues("grocery.ai.provider=anthropic", "grocery.ai.anthropic.api-key=sk-ant-x",
                "grocery.ai.anthropic.model=claude-sonnet-5-5").run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.modelConfigured()).isTrue();
            assertThat(properties.modelName()).isEqualTo("claude-sonnet-5-5");
        });
        runner.withPropertyValues("grocery.ai.provider=ollama").run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);
            assertThat(properties.modelConfigured()).isTrue();
            assertThat(properties.contextTokens()).isEqualTo(8192);
        });
    }
}
