package com.grocery.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in for Razorpay's REST API ({@code /v1/...}) on a local port, for the Razorpay-mode tests (the real API is
 * never called). It keeps orders, payments and refunds in memory with Razorpay's JSON shapes, records every request,
 * and can be told to fail ({@link #failNext}) or to be slow ({@link #orderDelay}).
 */
public final class FakeRazorpay {

    /** One request as received. */
    public record Request(String method, String path, String authorization, String refundIdempotency,
                          String body) {
    }

    /** Route names for {@link #failNext}. */
    public static final String CREATE_ORDER = "createOrder";
    public static final String LIST_ORDER_PAYMENTS = "listOrderPayments";
    public static final String FETCH_PAYMENT = "fetchPayment";
    public static final String CAPTURE = "capture";
    public static final String CREATE_REFUND = "createRefund";
    public static final String FETCH_REFUND = "fetchRefund";

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> refunds = new ConcurrentHashMap<>();
    private final Map<String, String> refundsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Deque<Integer>> failures = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** How long {@code POST /orders} takes (to make races visible). */
    public volatile Duration orderDelay = Duration.ZERO;
    /** Status of new refunds: {@code processed} (test mode), {@code pending} or {@code failed}. */
    public volatile String refundStatus = "processed";

    public FakeRazorpay() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void stop() {
        server.stop(0);
    }

    /** Forget failure plans and delays (data stays: ids are unique across tests). */
    public void reset() {
        failures.clear();
        orderDelay = Duration.ZERO;
        refundStatus = "processed";
    }

    /** The next {@code times} calls of {@code route} answer {@code status} (4xx with a Razorpay error body). */
    public void failNext(String route, int status, int times) {
        Deque<Integer> plan = failures.computeIfAbsent(route, r -> new ArrayDeque<>());
        synchronized (plan) {
            for (int i = 0; i < times; i++) {
                plan.add(status);
            }
        }
    }

    // ---- state for tests ----------------------------------------------------------------------------------

    /** A payment the customer made on {@code orderId} (as if through Checkout); returns its id. */
    public String addPayment(String orderId, String status, String method, long amountPaise) {
        String id = "pay_" + next();
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", id);
        payment.put("entity", "payment");
        payment.put("amount", amountPaise);
        payment.put("currency", "INR");
        payment.put("status", status);
        payment.put("order_id", orderId);
        payment.put("method", method);
        payment.put("captured", "captured".equals(status));
        payment.put("error_code", "failed".equals(status) ? "BAD_REQUEST_ERROR" : null);
        payment.put("error_description", "failed".equals(status) ? "Payment declined by the bank" : null);
        payments.put(id, payment);
        return id;
    }

    public void setPaymentStatus(String paymentId, String status) {
        payments.get(paymentId).put("status", status);
    }

    public Map<String, Object> payment(String paymentId) {
        return payments.get(paymentId);
    }

    public Map<String, Object> order(String orderId) {
        return orders.get(orderId);
    }

    public Map<String, Object> refund(String refundId) {
        return refunds.get(refundId);
    }

    public void setRefundStatus(String refundId, String status) {
        refunds.get(refundId).put("status", status);
    }

    /** {@code POST /orders} calls (successful or not) whose receipt is {@code receipt}. */
    public long createOrderCalls(String receipt) {
        return requests.stream()
                .filter(r -> r.method().equals("POST") && r.path().equals("/v1/orders"))
                .filter(r -> receipt.equals(read(r.body()).path("receipt").asString(null)))
                .count();
    }

    /** Orders created for {@code receipt}. */
    public long ordersWithReceipt(String receipt) {
        return orders.values().stream().filter(o -> receipt.equals(o.get("receipt"))).count();
    }

    public long calls(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).count();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(method, path, exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Refund-Idempotency"), body));
        String[] parts = path.startsWith("/v1/") ? path.substring(4).split("/") : new String[0];
        String route = route(method, parts);
        Integer failure = nextFailure(route);
        if (failure != null) {
            if (failure >= 500) {
                respond(exchange, failure, Map.of("error", Map.of("code", "SERVER_ERROR",
                        "description", "Fake outage")));
            } else {
                respond(exchange, failure, error("The fake was told to refuse " + route));
            }
            return;
        }
        try {
            switch (route) {
                case CREATE_ORDER -> createOrder(exchange, read(body));
                case LIST_ORDER_PAYMENTS -> {
                    List<Map<String, Object>> items = payments.values().stream()
                            .filter(p -> parts[1].equals(p.get("order_id"))).toList();
                    respond(exchange, 200, Map.of("entity", "collection", "count", items.size(), "items", items));
                }
                case FETCH_PAYMENT -> {
                    Map<String, Object> payment = payments.get(parts[1]);
                    respond(exchange, payment == null ? 400 : 200,
                            payment == null ? error("The id provided does not exist") : payment);
                }
                case CAPTURE -> capture(exchange, parts[1]);
                case CREATE_REFUND -> createRefund(exchange, parts[1], read(body),
                        exchange.getRequestHeaders().getFirst("X-Refund-Idempotency"));
                case FETCH_REFUND -> {
                    Map<String, Object> refund = refunds.get(parts[1]);
                    respond(exchange, refund == null ? 400 : 200,
                            refund == null ? error("The id provided does not exist") : refund);
                }
                default -> respond(exchange, 404, error("No such endpoint " + method + " " + path));
            }
        } catch (RuntimeException e) {
            respond(exchange, 500, Map.of("error", Map.of("code", "SERVER_ERROR", "description", e.toString())));
        }
    }

    private void createOrder(HttpExchange exchange, JsonNode request) throws IOException {
        sleep(orderDelay);
        String id = "order_" + next();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", id);
        order.put("entity", "order");
        order.put("amount", request.path("amount").asLong());
        order.put("currency", request.path("currency").asString(null));
        order.put("receipt", request.path("receipt").asString(null));
        order.put("status", "created");
        order.put("notes", json.convertValue(request.path("notes"), Map.class));
        orders.put(id, order);
        respond(exchange, 200, order);
    }

    private void capture(HttpExchange exchange, String paymentId) throws IOException {
        Map<String, Object> payment = payments.get(paymentId);
        if (payment == null) {
            respond(exchange, 400, error("The id provided does not exist"));
            return;
        }
        synchronized (payment) {
            if (!"authorized".equals(payment.get("status"))) {
                respond(exchange, 400, error("This payment has already been captured"));
                return;
            }
            payment.put("status", "captured");
            payment.put("captured", true);
        }
        respond(exchange, 200, payment);
    }

    private synchronized void createRefund(HttpExchange exchange, String paymentId, JsonNode request,
                                           String idempotencyKey) throws IOException {
        if (idempotencyKey != null && refundsByIdempotencyKey.containsKey(idempotencyKey)) {
            respond(exchange, 200, refunds.get(refundsByIdempotencyKey.get(idempotencyKey)));
            return;
        }
        Map<String, Object> payment = payments.get(paymentId);
        if (payment == null || !List.of("captured", "refunded").contains(payment.get("status"))) {
            respond(exchange, 400, error("The payment has not been captured"));
            return;
        }
        String id = "rfnd_" + next();
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("id", id);
        refund.put("entity", "refund");
        refund.put("amount", request.path("amount").asLong());
        refund.put("currency", "INR");
        refund.put("payment_id", paymentId);
        refund.put("receipt", request.path("receipt").asString(null));
        refund.put("notes", json.convertValue(request.path("notes"), Map.class));
        refund.put("status", refundStatus);
        refunds.put(id, refund);
        if (idempotencyKey != null) {
            refundsByIdempotencyKey.put(idempotencyKey, id);
        }
        respond(exchange, 200, refund);
    }

    private static String route(String method, String[] parts) {
        if (parts.length == 1 && parts[0].equals("orders") && method.equals("POST")) {
            return CREATE_ORDER;
        }
        if (parts.length == 3 && parts[0].equals("orders") && parts[2].equals("payments") && method.equals("GET")) {
            return LIST_ORDER_PAYMENTS;
        }
        if (parts.length == 2 && parts[0].equals("payments") && method.equals("GET")) {
            return FETCH_PAYMENT;
        }
        if (parts.length == 3 && parts[0].equals("payments") && parts[2].equals("capture") && method.equals("POST")) {
            return CAPTURE;
        }
        if (parts.length == 3 && parts[0].equals("payments") && parts[2].equals("refund") && method.equals("POST")) {
            return CREATE_REFUND;
        }
        if (parts.length == 2 && parts[0].equals("refunds") && method.equals("GET")) {
            return FETCH_REFUND;
        }
        return "unknown";
    }

    private Integer nextFailure(String route) {
        Deque<Integer> plan = failures.get(route);
        if (plan == null) {
            return null;
        }
        synchronized (plan) {
            return plan.poll();
        }
    }

    private static Map<String, Object> error(String description) {
        return Map.of("error", Map.of("code", "BAD_REQUEST_ERROR", "description", description));
    }

    private JsonNode read(String body) {
        return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String next() {
        return "T" + Long.toString(System.nanoTime(), 36).toUpperCase() + sequence.incrementAndGet();
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
