package com.grocery.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An in-process HTTP server (JDK HttpServer) playing cart-service's {@code GET /api/cart/preview?addressId=}
 * (docs/api/phase-5-cart-and-checkout-preview.md §3) for every customer. The caller is the forwarded token's
 * subject ("token-&lt;userId&gt;", see AbstractIntegrationTest#as), exactly like cart-service's "me". Tests set a
 * customer's cart and addresses, make the cart go down (503), lose one of its own dependencies (503
 * DEPENDENCY_UNAVAILABLE), become slow or answer an unexpected 404, and inspect the recorded calls.
 */
final class StubCart {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal GROCERY_FEE = new BigDecimal("25.00");
    static final BigDecimal RESTAURANT_FEE = new BigDecimal("30.00");
    static final BigDecimal DISTANCE_KM = new BigDecimal("4.6");
    /** Longer than the read timeout the tests configure (700 ms). */
    static final long SLOW_MILLIS = 1500;

    /** {@code mrp} null for menu items. */
    record Line(UUID itemId, String type, String name, String unitLabel, String foodType, int quantity,
                BigDecimal unitPrice, BigDecimal mrp) {
    }

    /** Where the stores of the test carts are (Phase 9 pickup point), unless a test says otherwise. */
    static final String STORE_CITY = "Bengaluru";
    static final double STORE_LATITUDE = 12.9352;
    static final double STORE_LONGITUDE = 77.6245;

    /**
     * {@code issues}: extra issues of the cart ({code, itemId, blocking, message}). {@code storeCity},
     * {@code storeLatitude}, {@code storeLongitude}: the preview's store location (Phase 9), null = unknown.
     */
    record Cart(UUID storeId, String storeType, String storeName, List<Line> lines, List<Map<String, Object>> issues,
                Instant updatedAt, String storeCity, Double storeLatitude, Double storeLongitude) {

        /** A cart whose store is at the default location. */
        Cart(UUID storeId, String storeType, String storeName, List<Line> lines, List<Map<String, Object>> issues,
             Instant updatedAt) {
            this(storeId, storeType, storeName, lines, issues, updatedAt, STORE_CITY, STORE_LATITUDE,
                    STORE_LONGITUDE);
        }

        /** The same cart, its store at another (or no) location. */
        Cart at(String city, Double latitude, Double longitude) {
            return new Cart(storeId, storeType, storeName, lines, issues, updatedAt, city, latitude, longitude);
        }
    }

    record Address(UUID id, String label, String recipientName, boolean isDefault) {
    }

    /**
     * Phase 12: the coupon applied to a customer's cart. Valid: {@code discount} off the items and, with
     * {@code freeDelivery}, the whole delivery fee off. Invalid: shown with {@code valid: false} and {@code reason},
     * and the blocking issue {@code COUPON_NOT_APPLICABLE}.
     */
    record Coupon(String code, boolean valid, BigDecimal discount, boolean freeDelivery, String reason) {
    }

    record Call(String path, String query, String authorization, String correlationId) {
    }

    final Map<UUID, Cart> carts = new ConcurrentHashMap<>();
    final Map<UUID, List<Address>> addresses = new ConcurrentHashMap<>();
    /** Phase 12: the coupon of a customer's cart (none = the preview has no coupon fields, like an older cart). */
    final Map<UUID, Coupon> coupons = new ConcurrentHashMap<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    /** cart-service itself answers 503. */
    volatile boolean down;
    /** cart-service answers 503 DEPENDENCY_UNAVAILABLE with {@code dependency: customer}. */
    volatile boolean customerDown;
    /** cart-service answers 404 without an errorCode (e.g. not deployed). */
    volatile boolean unknownPath;
    /** Answers only after this many milliseconds. */
    volatile long delayMillis;

    private final HttpServer server;

    private StubCart(HttpServer server) {
        this.server = server;
    }

    static StubCart start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubCart stub = new StubCart(server);
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
        customerDown = false;
        unknownPath = false;
        delayMillis = 0;
        calls.clear();
    }

    /** The recorded preview calls made with {@code customerId}'s token. */
    List<Call> callsOf(UUID customerId) {
        return calls.stream().filter(call -> ("Bearer token-" + customerId).equals(call.authorization())).toList();
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            calls.add(new Call(path, query, authorization, exchange.getRequestHeaders().getFirst("X-Correlation-Id")));
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            if (down) {
                respond(exchange, 503, problem(503, "SERVICE_UNAVAILABLE", null));
                return;
            }
            if (customerDown) {
                respond(exchange, 503, problem(503, "DEPENDENCY_UNAVAILABLE", "customer"));
                return;
            }
            if (unknownPath || !"GET".equals(exchange.getRequestMethod()) || !"/api/cart/preview".equals(path)) {
                respond(exchange, 404, Map.of("status", 404, "error", "Not Found"));
                return;
            }
            if (authorization == null || !authorization.startsWith("Bearer token-")) {
                respond(exchange, 401, problem(401, "UNAUTHENTICATED", null));
                return;
            }
            UUID customerId = UUID.fromString(authorization.substring("Bearer token-".length()));
            String addressParam = param(query, "addressId");
            List<Address> own = addresses.getOrDefault(customerId, List.of());
            Address address;
            if (addressParam != null) {
                address = own.stream().filter(a -> a.id().toString().equals(addressParam)).findFirst().orElse(null);
                if (address == null) {
                    respond(exchange, 404, problem(404, "ADDRESS_NOT_FOUND", null));
                    return;
                }
            } else {
                address = own.stream().filter(Address::isDefault).findFirst().orElse(null);
            }
            respond(exchange, 200, preview(carts.get(customerId), address, coupons.get(customerId)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(exchange, 500, problem(500, "INTERNAL_ERROR", null));
        } finally {
            exchange.close();
        }
    }

    /** The CheckoutPreview shape of cart-service (contract §3), with a fixed distance and per-type fee. */
    private static Map<String, Object> preview(Cart cart, Address address, Coupon coupon) {
        List<Line> lines = cart == null ? List.of() : cart.lines();
        boolean restaurant = cart != null && "RESTAURANT".equals(cart.storeType());
        List<Map<String, Object>> issues = new ArrayList<>(cart == null || cart.issues() == null ? List.of()
                : cart.issues());
        BigDecimal subtotal = money(BigDecimal.ZERO);
        BigDecimal mrpTotal = money(BigDecimal.ZERO);
        int itemCount = 0;
        List<Map<String, Object>> lineViews = new ArrayList<>();
        for (Line line : lines) {
            BigDecimal lineTotal = money(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            subtotal = subtotal.add(lineTotal);
            mrpTotal = mrpTotal.add(line.mrp() == null ? lineTotal
                    : money(line.mrp().multiply(BigDecimal.valueOf(line.quantity()))));
            itemCount += line.quantity();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("itemId", line.itemId());
            view.put("type", line.type());
            view.put("name", line.name());
            view.put("unitLabel", line.unitLabel());
            view.put("imageUrl", null);
            view.put("foodType", line.foodType());
            view.put("quantity", line.quantity());
            view.put("unitPrice", money(line.unitPrice()));
            view.put("mrp", line.mrp() == null ? null : money(line.mrp()));
            view.put("lineTotal", lineTotal);
            view.put("priceWhenAdded", money(line.unitPrice()));
            view.put("availableQuantity", restaurant ? null : 50);
            view.put("issue", null);
            lineViews.add(view);
        }
        if (lines.isEmpty()) {
            issues.add(issue("EMPTY_CART", "Your cart is empty"));
        }
        if (address == null) {
            issues.add(issue("ADDRESS_REQUIRED", "Add a delivery address to check out"));
        }
        Map<String, Object> delivery = null;
        BigDecimal fee = money(BigDecimal.ZERO);
        if (!lines.isEmpty() && address != null) {
            fee = restaurant ? RESTAURANT_FEE : GROCERY_FEE;
            delivery = new LinkedHashMap<>();
            delivery.put("distanceKm", DISTANCE_KM);
            delivery.put("fee", fee);
            delivery.put("freeAbove", restaurant ? null : new BigDecimal("499.00"));
            delivery.put("maxDistanceKm", restaurant ? 12 : 15);
            delivery.put("minimumOrder", restaurant ? null : new BigDecimal("99.00"));
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        Map<String, Object> store = null;
        if (!lines.isEmpty()) {
            store = new LinkedHashMap<>();
            store.put("id", cart.storeId());
            store.put("type", cart.storeType());
            store.put("name", cart.storeName());
            store.put("city", cart.storeCity());                // Phase 9 (null when unknown)
            store.put("latitude", cart.storeLatitude());
            store.put("longitude", cart.storeLongitude());
        }
        preview.put("store", store);
        preview.put("lines", lineViews);
        preview.put("itemCount", itemCount);
        preview.put("subtotal", subtotal);
        preview.put("mrpTotal", mrpTotal);
        preview.put("savings", mrpTotal.subtract(subtotal));
        preview.put("currency", "INR");
        preview.put("issues", issues);
        preview.put("updatedAt", cart == null || cart.updatedAt() == null ? null : cart.updatedAt().toString());
        preview.put("address", address == null ? null : address(address));
        preview.put("delivery", delivery);
        BigDecimal total = subtotal.add(fee);
        if (coupon != null && !lines.isEmpty()) {             // Phase 12: the discounted total
            BigDecimal discount = coupon.valid() ? money(coupon.discount()) : money(BigDecimal.ZERO);
            BigDecimal feeDiscount = coupon.valid() && coupon.freeDelivery() ? fee : money(BigDecimal.ZERO);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", coupon.code());
            view.put("valid", coupon.valid());
            view.put("discount", discount);
            view.put("deliveryFeeDiscount", feeDiscount);
            view.put("reason", coupon.valid() ? null : coupon.reason());
            view.put("message", coupon.valid() ? null : "This coupon cannot be applied");
            preview.put("coupon", view);
            preview.put("discount", discount);
            preview.put("deliveryFeeDiscount", feeDiscount);
            total = total.subtract(discount).subtract(feeDiscount);
            if (!coupon.valid()) {
                issues.add(issue("COUPON_NOT_APPLICABLE", "This coupon cannot be applied"));
            }
        }
        preview.put("total", total);
        preview.put("canCheckout", issues.stream().noneMatch(i -> Boolean.TRUE.equals(i.get("blocking"))));
        preview.put("pricedAt", Instant.now().toString());
        return preview;
    }

    static Map<String, Object> issue(String code, String message) {
        return issue(code, null, true, message);
    }

    static Map<String, Object> issue(String code, UUID itemId, boolean blocking, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("itemId", itemId);
        issue.put("blocking", blocking);
        issue.put("message", message);
        return issue;
    }

    private static Map<String, Object> address(Address address) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", address.id());
        view.put("label", address.label());
        view.put("recipientName", address.recipientName());
        view.put("phone", "+919876543210");
        view.put("line1", "12 MG Road");
        view.put("line2", null);
        view.put("landmark", "Near the metro");
        view.put("city", "Bengaluru");
        view.put("state", "Karnataka");
        view.put("postalCode", "560001");
        view.put("latitude", 12.9716);
        view.put("longitude", 77.5946);
        return view;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String param(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Map<String, Object> problem(int status, String errorCode, String dependency) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("status", status);
        problem.put("detail", errorCode);
        problem.put("errorCode", errorCode);
        if (dependency != null) {
            problem.put("dependency", dependency);
        }
        return problem;
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
