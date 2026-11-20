package com.grocery.ai.config;

import com.grocery.ai.model.ScriptedChatModel;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * The model provider switch (contract §1, {@code grocery.ai.provider} / {@code AI_PROVIDER}).
 * <ul>
 *   <li><b>ollama</b> (default): Spring AI's {@code OllamaChatModel} (auto-configured; {@code spring.ai.model.chat}
 *       follows the provider, so it exists only in this mode) over an {@link OllamaApi} with explicit timeouts: connect
 *       5 s, model answer {@code grocery.ai.model-timeout}.</li>
 *   <li><b>anthropic</b> (production, 2026-11-20): Spring AI's {@code AnthropicChatModel}, built here from
 *       {@code grocery.ai.anthropic.*}: Claude through Anthropic's Messages API, with the rules and tools cached on
 *       Anthropic's side. Without a key the status says unavailable and the chat answers AI_UNAVAILABLE without
 *       calling Anthropic, like an Ollama that is not there.</li>
 *   <li><b>scripted</b>: the deterministic {@link ScriptedChatModel}, refused outside the {@code local} and
 *       {@code docker} profiles (like the payment simulator).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ModelProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderConfig.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "grocery.ai", name = "provider", havingValue = "ollama", matchIfMissing = true)
    static class Ollama {

        /**
         * Replaces the auto-configured OllamaApi (which has no timeouts) with one that has them, built from Spring
         * Boot's {@link RestClient.Builder} / {@link WebClient.Builder} so the model calls are observed and traced.
         */
        @Bean
        OllamaApi ollamaApi(AiProperties properties, ObjectProvider<RestClient.Builder> restClientBuilder,
                            ObjectProvider<WebClient.Builder> webClientBuilder) {
            Duration modelTimeout = properties.modelTimeout();
            java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
            requestFactory.setReadTimeout(modelTimeout);
            HttpClient nettyClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                    .responseTimeout(modelTimeout);
            log.info("AI provider: OLLAMA ({}, model {}, context {} tokens, timeout {})", properties.ollama().baseUrl(),
                    properties.ollama().model(), properties.ollama().contextTokens(), modelTimeout);
            return OllamaApi.builder()
                    .baseUrl(properties.ollama().baseUrl())
                    .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder).clone()
                            .requestFactory(requestFactory))
                    .webClientBuilder(webClientBuilder.getIfAvailable(WebClient::builder).clone()
                            .clientConnector(new ReactorClientHttpConnector(nettyClient)))
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "grocery.ai", name = "provider", havingValue = "anthropic")
    static class Anthropic {

        /** Sent instead of a missing key so the client can be built; never used ({@code modelConfigured()}). */
        private static final String NO_KEY = "not-configured";
        private static final double TEMPERATURE = 0.2;

        /**
         * Claude with explicit limits: the answer timeout ({@code grocery.ai.model-timeout}), retries, answer length.
         * The rules and the tool definitions are the same for every user, so they are cached on Anthropic's side (a
         * cache read costs a tenth of a normal read): SYSTEM_AND_TOOLS with one system block per system message and
         * the cache mark on the second-to-last, so the rules are cached and this user's context line after them is
         * not ({@code ChatService.prompt}). Replaces the auto-configured model ({@code @ConditionalOnMissingBean}).
         */
        @Bean
        AnthropicChatModel anthropicChatModel(AiProperties properties,
                                              ObjectProvider<ObservationRegistry> observationRegistry) {
            AiProperties.Anthropic anthropic = properties.anthropic();
            AnthropicChatOptions options = AnthropicChatOptions.builder()
                    .apiKey(anthropic.configured() ? anthropic.apiKey() : NO_KEY)
                    .baseUrl(anthropic.baseUrl())
                    .timeout(properties.modelTimeout())
                    .maxRetries(anthropic.maxRetries())
                    .model(anthropic.model())
                    .maxTokens(anthropic.maxTokens())
                    .temperature(TEMPERATURE)
                    .cacheOptions(AnthropicCacheOptions.builder()
                            .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                            .multiBlockSystemCaching(true)
                            .build())
                    .build();
            return AnthropicChatModel.builder()
                    .options(options)                     // tools run in ToolGuard's advisor, not in the model
                    .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                    .build();
        }

        /** Says at start-up which Claude model answers, and that a key is missing (never the key itself). */
        Anthropic(AiProperties properties) {
            AiProperties.Anthropic anthropic = properties.anthropic();
            if (anthropic.configured()) {
                log.info("AI provider: ANTHROPIC ({}, model {}, max {} tokens per answer, timeout {}, {} retries)",
                        anthropic.baseUrl(), anthropic.model(), anthropic.maxTokens(), properties.modelTimeout(),
                        anthropic.maxRetries());
            } else {
                log.error("AI provider: ANTHROPIC without an API key (ANTHROPIC_API_KEY): the assistants answer "
                        + "AI_UNAVAILABLE until it is set");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "grocery.ai", name = "provider", havingValue = "scripted")
    static class Scripted {

        @Bean
        ScriptedChatModel scriptedChatModel(AiProperties properties, Environment environment, JsonMapper jsonMapper) {
            if (!properties.scripted().allowAnyProfile()
                    && !environment.acceptsProfiles(Profiles.of("docker | local"))) {
                throw new IllegalStateException("The scripted model (AI_PROVIDER=scripted) is a local stand-in for an "
                        + "LLM and is only allowed with the 'docker' or 'local' profile. Use AI_PROVIDER=ollama.");
            }
            log.warn("AI provider: SCRIPTED (deterministic stand-in for an LLM; local development and tests only)");
            return new ScriptedChatModel(jsonMapper);
        }
    }
}
