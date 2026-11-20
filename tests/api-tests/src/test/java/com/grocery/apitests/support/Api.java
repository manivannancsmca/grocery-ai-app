package com.grocery.apitests.support;

import io.restassured.RestAssured;
import io.restassured.config.DecoderConfig;
import io.restassured.config.EncoderConfig;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.JsonConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tiny fluent HTTP client over REST Assured, always through the gateway ({@link Config#GATEWAY_URL}).
 *
 * <pre>
 *   Api.as(customer).body(Json.obj("type", "PRODUCT", "productId", id, "quantity", 2)).post("/api/cart/items").ok();
 *   Api.anon().get("/api/orders").problem(401, "UNAUTHENTICATED");
 * </pre>
 *
 * Every request carries an {@code X-Correlation-Id} of this run. A 429 (the gateway's token buckets: 20/s per user,
 * 2/s per IP for login and registration) is waited out and retried, unless {@link Req#noRetry()} — the rate-limit test
 * wants to see it.
 */
public final class Api {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long RETRY_429_FOR_MS = 90_000;

    public static final RestAssuredConfig REST_CONFIG = RestAssuredConfig.config()
            .encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset("UTF-8")
                    .appendDefaultContentCharsetToContentTypeIfUndefined(false))
            .decoderConfig(DecoderConfig.decoderConfig().defaultContentCharset("UTF-8"))
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .redirect(RedirectConfig.redirectConfig().followRedirects(false))
            .httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", 10_000)
                    .setParam("http.socket.timeout", 150_000));

    static {
        RestAssured.config = REST_CONFIG;
    }

    private Api() {
    }

    public static Req anon() {
        return new Req(null);
    }

    public static Req as(Session session) {
        return new Req(session == null ? null : session.token());
    }

    public static Req asToken(String bearerToken) {
        return new Req(bearerToken);
    }

    public static String correlationId(String purpose) {
        return "apit-" + Config.RUN + "-" + purpose + "-" + SEQUENCE.incrementAndGet();
    }

    /** One request being built. */
    public static final class Req {

        private final String token;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Object body;
        private boolean retry429 = true;
        private String accept;

        private Req(String token) {
            this.token = token;
        }

        public Req header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Req idempotencyKey(String key) {
            return header("Idempotency-Key", key);
        }

        public Req correlationId(String id) {
            return header("X-Correlation-Id", id);
        }

        /** A Map/List (serialised as JSON) or a raw JSON string. */
        public Req body(Object body) {
            this.body = body;
            return this;
        }

        public Req accept(String accept) {
            this.accept = accept;
            return this;
        }

        public Req noRetry() {
            this.retry429 = false;
            return this;
        }

        public Res get(String path) {
            return send("GET", path);
        }

        public Res post(String path) {
            return send("POST", path);
        }

        public Res put(String path) {
            return send("PUT", path);
        }

        public Res patch(String path) {
            return send("PATCH", path);
        }

        public Res delete(String path) {
            return send("DELETE", path);
        }

        public Res send(String method, String path) {
            long giveUpAt = System.currentTimeMillis() + RETRY_429_FOR_MS;
            while (true) {
                Response response = spec().request(method, path);
                if (response.statusCode() != 429 || !retry429 || System.currentTimeMillis() > giveUpAt) {
                    return new Res(method, path, response);
                }
                sleep(retryAfterMillis(response));
            }
        }

        private RequestSpecification spec() {
            RequestSpecification spec = RestAssured.given().config(REST_CONFIG).baseUri(Config.GATEWAY_URL)
                    .urlEncodingEnabled(true);
            if (!headers.containsKey("X-Correlation-Id")) {
                spec.header("X-Correlation-Id", Api.correlationId("r"));
            }
            if (token != null) {
                spec.header("Authorization", "Bearer " + token);
            }
            headers.forEach((name, value) -> spec.header(name, value));
            if (accept != null) {
                spec.accept(accept);
            }
            if (body != null) {
                spec.contentType("application/json");
                spec.body(body instanceof String s ? s : Json.write(body));
            }
            return spec;
        }
    }

    private static long retryAfterMillis(Response response) {
        String retryAfter = response.header("Retry-After");
        try {
            return retryAfter == null ? 600 : Math.min(10_000, Long.parseLong(retryAfter.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
