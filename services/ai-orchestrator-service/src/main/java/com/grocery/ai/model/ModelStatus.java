package com.grocery.ai.model;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * {@code GET /api/ai/status} (contract §2): is the model usable? Ollama: reachable ({@code GET /api/tags}) and the
 * configured model pulled. Anthropic: a key, and the API knows the model. The scripted model is always available.
 * Probed at most every
 * {@code grocery.ai.status-cache-ttl} (30 s); a failed chat ({@code AI_UNAVAILABLE}) refreshes the probe at once.
 */
@Component
public class ModelStatus {

    private static final Logger log = LoggerFactory.getLogger(ModelStatus.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    /** Anthropic's API version header (the one the Java SDK sends). */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** The answer of the status endpoint. */
    public record Status(String provider, String model, boolean available, Instant checkedAt) {
    }

    private final AiProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RestClient client;
    private volatile Status cached;

    /** {@code restClientBuilder}: Spring Boot's, so the probe is observed and traced like every other call. */
    public ModelStatus(AiProperties properties, JsonMapper jsonMapper, Clock clock,
                       RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(PROBE_TIMEOUT);
        this.client = restClientBuilder.clone().requestFactory(factory).build();
    }

    public Status current() {
        Status status = cached;
        Instant now = clock.instant();
        if (status != null && status.checkedAt().plus(properties.statusCacheTtl()).isAfter(now)) {
            return status;
        }
        synchronized (this) {
            status = cached;
            if (status != null && status.checkedAt().plus(properties.statusCacheTtl()).isAfter(clock.instant())) {
                return status;
            }
            cached = probe();
            return cached;
        }
    }

    /** The model just failed as unreachable: forget the cached "available". */
    public void invalidate() {
        cached = null;
    }

    private Status probe() {
        String provider = properties.provider().id();
        String model = properties.modelName();
        if (properties.provider() == AiProperties.Provider.SCRIPTED) {
            return new Status(provider, model, true, clock.instant());
        }
        if (properties.provider() == AiProperties.Provider.ANTHROPIC) {
            return new Status(provider, model, anthropicAvailable(model), clock.instant());
        }
        boolean available = false;
        try {
            String body = client.get().uri(trim(properties.ollama().baseUrl()) + "/api/tags").retrieve()
                    .body(String.class);
            JsonNode tags = body == null ? null : jsonMapper.readTree(body);
            for (JsonNode entry : Json.list(tags, "models")) {
                if (matches(model, Json.text(entry, "name")) || matches(model, Json.text(entry, "model"))) {
                    available = true;
                    break;
                }
            }
            if (!available) {
                log.warn("Ollama is up but model {} is not pulled (docker compose --profile ai runs ollama-init)",
                        model);
            }
        } catch (RuntimeException e) {
            log.info("Ollama not reachable at {}: {}", properties.ollama().baseUrl(), e.toString());
        }
        return new Status(provider, model, available, clock.instant());
    }

    /**
     * Anthropic: the key is set and the API knows the model ({@code GET /v1/models/{model}}, free, no tokens used).
     * 401 is a wrong key, 404 a model name the API does not know; both are logged, never the key.
     */
    private boolean anthropicAvailable(String model) {
        AiProperties.Anthropic anthropic = properties.anthropic();
        if (!anthropic.configured()) {
            return false;
        }
        try {
            client.get().uri(trim(anthropic.baseUrl()) + "/v1/models/{model}", model)
                    .header("x-api-key", anthropic.apiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            log.warn("Anthropic refused the model check for {}: HTTP {} ({})", model, e.getStatusCode().value(),
                    e.getStatusCode().value() == 401 ? "wrong API key" : e.getStatusCode().value() == 404
                            ? "unknown model" : "see Anthropic's status");
        } catch (RuntimeException e) {
            log.info("Anthropic not reachable at {}: {}", anthropic.baseUrl(), e.toString());
        }
        return false;
    }

    static boolean matches(String configured, String pulled) {
        if (pulled == null) {
            return false;
        }
        String wanted = configured.contains(":") ? configured : configured + ":latest";
        return pulled.equals(wanted) || pulled.equals(configured);
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
