package com.grocery.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An in-process HTTP server (JDK HttpServer) playing pricing-service's {@code POST /internal/coupons/redeem}
 * (docs/api/phase-12-post-mvp-features.md §2), which pricing-service builds concurrently: {@code { code, orderId,
 * customerId, storeId, orderType, subtotal, deliveryFee }} → 200 {@code { redemptionId, discount,
 * deliveryFeeDiscount, couponName, fundedBy }}, idempotent per {@code orderId}; 409 ProblemDetail with
 * {@code reason} (and {@code minOrderSubtotal}) when the coupon does not apply. Tests register offers (code → discount,
 * free delivery, name, funding) and refusals (code → status, reason), can make it go down (503), and inspect the
 * recorded calls.
 */
final class StubPricing {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String REDEEM = "/internal/coupons/redeem";

    /**
     * A coupon that applies: {@code discount} off the items; {@code freeDelivery} = the whole fee off. {@code name} and
     * {@code fundedBy} are answered as {@code couponName} / {@code fundedBy}; null leaves them out (a pricing-service
     * from before schemas 1.7.0).
     */
    record Offer(BigDecimal discount, boolean freeDelivery, String name, String fundedBy) {
    }

    /** A coupon that does not apply: answered with {@code status} (409) and {@code reason}. */
    record Refusal(int status, String reason, BigDecimal minOrderSubtotal) {
    }

    record Call(String path, String authorization, JsonNode body) {
    }

    final Map<String, Offer> offers = new ConcurrentHashMap<>();
    final Map<String, Refusal> refusals = new ConcurrentHashMap<>();
    /** orderId → the redemption answered for it (idempotent per order). */
    final Map<String, Map<String, Object>> redemptions = new ConcurrentHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    /** pricing-service answers 503. */
    volatile boolean down;

    private final HttpServer server;

    private StubPricing(HttpServer server) {
        this.server = server;
    }

    static StubPricing start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubPricing stub = new StubPricing(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        down = false;
        calls.clear();
    }

    /** The recorded redeem calls made with {@code customerId}'s token. */
    List<Call> callsOf(UUID customerId) {
        return calls.stream().filter(call -> ("Bearer token-" + customerId).equals(call.authorization())).toList();
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? null : JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            calls.add(new Call(path, authorization, body));
            if (down) {
                respond(exchange, 503, problem(503, "SERVICE_UNAVAILABLE", null, null));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod()) || !REDEEM.equals(path) || body == null) {
                respond(exchange, 404, Map.of("status", 404, "error", "Not Found"));
                return;
            }
            if (authorization == null || !authorization.startsWith("Bearer token-")) {
                respond(exchange, 401, problem(401, "UNAUTHENTICATED", null, null));
                return;
            }
            String code = body.get("code").asString();
            Refusal refusal = refusals.get(code);
            if (refusal != null) {
                respond(exchange, refusal.status(), problem(refusal.status(), "COUPON_NOT_APPLICABLE",
                        refusal.reason(), refusal.minOrderSubtotal()));
                return;
            }
            Offer offer = offers.get(code);
            if (offer == null) {
                respond(exchange, 409, problem(409, "COUPON_NOT_APPLICABLE", "COUPON_NOT_FOUND", null));
                return;
            }
            String orderId = body.get("orderId").asString();
            Map<String, Object> redemption = redemptions.computeIfAbsent(orderId, id -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("redemptionId", UUID.randomUUID().toString());
                created.put("discount", money(offer.discount()));
                created.put("deliveryFeeDiscount", offer.freeDelivery()
                        ? money(body.get("deliveryFee").decimalValue())
                        : money(BigDecimal.ZERO));
                if (offer.name() != null) {
                    created.put("couponName", offer.name());
                }
                if (offer.fundedBy() != null) {
                    created.put("fundedBy", offer.fundedBy());
                }
                return created;
            });
            respond(exchange, 200, redemption);
        } catch (RuntimeException e) {
            respond(exchange, 500, problem(500, "INTERNAL_ERROR", null, null));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> problem(int status, String errorCode, String reason, BigDecimal minimum) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("status", status);
        problem.put("detail", errorCode);
        problem.put("errorCode", errorCode);
        if (reason != null) {
            problem.put("reason", reason);
        }
        if (minimum != null) {
            problem.put("minOrderSubtotal", minimum);
        }
        return problem;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
