package com.grocery.ai.gateway;

import com.grocery.ai.config.AiProperties;
import com.grocery.common.correlation.CorrelationId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The only way out of this service to the business APIs: through the API gateway ({@code grocery.ai.gateway-url}),
 * never to another service's database. Every call carries the caller's {@code Authorization} header and the
 * correlation ID, so the business services apply the user's own rights. Answers of any status are returned as
 * {@link ApiResponse}; no answer at all (connection refused, timeout) is a {@link GatewayUnavailableException}.
 *
 * <p>Built from Spring Boot's auto-configured {@link RestClient.Builder} (Phase 13): every call is observed
 * ({@code http.client.requests}) and sends the W3C {@code traceparent}, so a tool call or an approved action is part of
 * the user's trace through the gateway and the business services.
 */
@Component
public class GatewayClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final String baseUrl;
    private final RestClient toolClient;
    private final RestClient executionClient;
    private final JsonMapper jsonMapper;

    public GatewayClient(AiProperties properties, JsonMapper jsonMapper, RestClient.Builder restClientBuilder) {
        this.baseUrl = stripTrailingSlash(properties.gatewayUrl());
        this.jsonMapper = jsonMapper;
        this.toolClient = client(restClientBuilder.clone(), properties.toolTimeout());
        this.executionClient = client(restClientBuilder.clone(), properties.approvals().executionTimeout());
    }

    private static RestClient client(RestClient.Builder builder, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory).build();
    }

    /**
     * {@code GET path?query} as the caller (null query values are left out; a collection becomes the parameter
     * repeated, {@code vendorId=a&vendorId=b}, which Spring binds to a list).
     */
    public ApiResponse get(CallContext context, String path, Map<String, ?> query) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (query != null) {
            query.forEach((name, value) -> {
                if (value instanceof java.util.Collection<?> values) {
                    values.stream().filter(java.util.Objects::nonNull)
                            .forEach(each -> params.add(name, String.valueOf(each)));
                } else if (value != null) {
                    params.add(name, String.valueOf(value));
                }
            });
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl).path(path).queryParams(params).build().encode().toUri();
        return exchange(toolClient, HttpMethod.GET, uri, null, headers -> apply(context, headers));
    }

    /** A JSON write (cart changes) as the caller. */
    public ApiResponse send(CallContext context, HttpMethod method, String path, Object body) {
        byte[] bytes = body == null ? null : jsonMapper.writeValueAsBytes(body);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl).path(path).build().encode().toUri();
        return exchange(toolClient, method, uri, bytes, headers -> apply(context, headers));
    }

    /**
     * An approved action: exactly the stored method, path and body bytes, with the headers the caller built
     * (approver's token, X-Approval-Token, Idempotency-Key, correlation ID).
     */
    public ApiResponse execute(HttpMethod method, String path, byte[] body, HttpHeaders headers) {
        return exchange(executionClient, method, URI.create(baseUrl + path), body, h -> h.addAll(headers));
    }

    private ApiResponse exchange(RestClient client, HttpMethod method, URI uri, byte[] body,
                                 Consumer<HttpHeaders> headers) {
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers);
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, response) -> {
                byte[] raw = response.getBody().readAllBytes();
                return ApiResponse.of(response.getStatusCode().value(), new String(raw, StandardCharsets.UTF_8),
                        jsonMapper);
            });
        } catch (ResourceAccessException e) {
            throw new GatewayUnavailableException(method + " " + uri.getPath() + " failed: " + e.getMessage(), e);
        }
    }

    private static void apply(CallContext context, HttpHeaders headers) {
        if (context.authorization() != null) {
            headers.set(HttpHeaders.AUTHORIZATION, context.authorization());
        }
        if (context.correlationId() != null) {
            headers.set(CorrelationId.HEADER, context.correlationId());
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
