package com.grocery.cart;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One in-process HTTP server (JDK HttpServer) playing all six services the cart reads from, following the Phase 5
 * contract §4 (and the Phase 2 address endpoints, and Phase 12's coupon evaluate and the delivery fees of the admin
 * platform settings as part of "pricing"). Tests change
 * its data, take a dependency down (503) or make it slow, and inspect the recorded calls (path, Authorization,
 * correlation ID, body).
 */
final class StubServices {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Pattern VENDOR_STORE = Pattern.compile("^/internal/vendors/([^/]+)/store$");
    private static final Pattern MENU_QUOTE = Pattern.compile("^/internal/restaurants/([^/]+)/menu-quote$");
    private static final Pattern ADDRESS = Pattern.compile("^/api/customers/me/addresses/([^/]+)$");

    final Map<UUID, Product> products = new ConcurrentHashMap<>();
    final Map<UUID, Vendor> vendors = new ConcurrentHashMap<>();
    final Map<UUID, Restaurant> restaurants = new ConcurrentHashMap<>();
    final Map<UUID, List<Address>> addresses = new ConcurrentHashMap<>();
    /** Phase 12: pricing-service's coupons, by upper-case code. */
    final Map<String, Coupon> coupons = new ConcurrentHashMap<>();
    /** pricing-service's delivery fees in force (admin platform settings): the configured ones unless changed. */
    final Map<String, Object> deliveryFees = new ConcurrentHashMap<>(DEFAULT_DELIVERY_FEES);
    /** Dependencies answering 503: pricing, pricing-fees, inventory, catalog, vendor, restaurant, customer. */
    final Set<String> down = ConcurrentHashMap.newKeySet();
    /** Dependencies answering only after {@link #SLOW_MILLIS} (beyond the cart's read timeout in tests). */
    final Set<String> slow = ConcurrentHashMap.newKeySet();
    final List<Call> calls = new CopyOnWriteArrayList<>();

    static final long SLOW_MILLIS = 1500;

    static final Map<String, Object> DEFAULT_DELIVERY_FEES = Map.of(
            "groceryBaseFee", new BigDecimal("25.00"), "groceryIncludedKm", new BigDecimal("3"),
            "groceryPerExtraKm", new BigDecimal("8.00"), "groceryFreeAbove", new BigDecimal("499.00"),
            "groceryMinimumOrder", new BigDecimal("99.00"), "restaurantBaseFee", new BigDecimal("30.00"),
            "restaurantIncludedKm", new BigDecimal("3"), "restaurantPerExtraKm", new BigDecimal("10.00"));

    private final HttpServer server;

    private StubServices(HttpServer server) {
        this.server = server;
    }

    static StubServices start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StubServices stub = new StubServices(server);
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
        down.clear();
        slow.clear();
        calls.clear();
        deliveryFees.clear();
        deliveryFees.putAll(DEFAULT_DELIVERY_FEES);
    }

    // ---------------------------------------------------------------- data

    /** {@code sellingPrice} null = not priced; {@code available} null = unknown to inventory. */
    record Product(UUID id, UUID vendorId, String vendorName, String name, String unitLabel, boolean visible,
                   BigDecimal mrp, BigDecimal sellingPrice, Integer available) {

        Product withPrice(String newMrp, String newSellingPrice) {
            return new Product(id, vendorId, vendorName, name, unitLabel, visible,
                    newMrp == null ? null : new BigDecimal(newMrp),
                    newSellingPrice == null ? null : new BigDecimal(newSellingPrice), available);
        }

        Product withStock(Integer newAvailable) {
            return new Product(id, vendorId, vendorName, name, unitLabel, visible, mrp, sellingPrice, newAvailable);
        }

        Product withVisible(boolean newVisible) {
            return new Product(id, vendorId, vendorName, name, unitLabel, newVisible, mrp, sellingPrice, available);
        }
    }

    record Vendor(UUID id, String name, boolean active, Double latitude, Double longitude) {
    }

    record Restaurant(UUID id, String name, String status, boolean acceptingOrders, boolean openNow, Double latitude,
                      Double longitude, Map<UUID, MenuItem> menu) {

        Restaurant with(String newStatus, boolean newAcceptingOrders, boolean newOpenNow) {
            return new Restaurant(id, name, newStatus, newAcceptingOrders, newOpenNow, latitude, longitude, menu);
        }
    }

    record MenuItem(UUID id, String name, BigDecimal price, String foodType, boolean available) {
    }

    record Address(UUID id, String label, Double latitude, Double longitude, boolean isDefault) {
    }

    /**
     * A coupon as pricing-service would evaluate it (simplified rules of Phase 12 §2). {@code storeType} GROCERY /
     * RESTAURANT / ANY; {@code forcedReason} non-null = always invalid with that reason (paused, expired, limits).
     */
    record Coupon(String code, String scope, UUID storeId, String storeType, String discountType, BigDecimal value,
                  BigDecimal maxDiscount, BigDecimal minOrderSubtotal, String forcedReason) {

        Coupon withForcedReason(String reason) {
            return new Coupon(code, scope, storeId, storeType, discountType, value, maxDiscount, minOrderSubtotal,
                    reason);
        }
    }

    record Call(String method, String path, String authorization, String correlationId, JsonNode body) {
    }

    List<Call> calls(String pathPrefix) {
        return calls.stream().filter(call -> call.path().startsWith(pathPrefix)).toList();
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            JsonNode body = requestBody.length == 0 ? null : JSON.readTree(requestBody);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            calls.add(new Call(exchange.getRequestMethod(), path, authorization,
                    exchange.getRequestHeaders().getFirst("X-Correlation-Id"), body));
            String dependency = dependencyOf(path);
            if (slow.contains(dependency)) {
                Thread.sleep(SLOW_MILLIS);
            }
            if (down.contains(dependency)) {
                respond(exchange, 503, problem(503, "SERVICE_UNAVAILABLE"));
                return;
            }
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                respond(exchange, 401, problem(401, "UNAUTHENTICATED"));
                return;
            }
            route(exchange, path, body, authorization);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            respond(exchange, 500, problem(500, "INTERNAL_ERROR"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path, JsonNode body, String authorization) throws IOException {
        Matcher matcher;
        if (path.equals("/internal/catalog/products/lookup")) {
            respond(exchange, 200, lookup(ids(body, "productIds")));
        } else if (path.equals("/internal/prices/quote")) {
            respond(exchange, 200, quote(ids(body, "productIds")));
        } else if (path.equals("/internal/coupons/evaluate")) {
            respond(exchange, 200, evaluate(body));
        } else if (path.equals("/internal/delivery-fees")) {
            respond(exchange, 200, Map.copyOf(deliveryFees));
        } else if (path.equals("/internal/inventory/availability")) {
            respond(exchange, 200, availability(ids(body, "productIds")));
        } else if ((matcher = VENDOR_STORE.matcher(path)).matches()) {
            Vendor vendor = vendors.get(UUID.fromString(matcher.group(1)));
            if (vendor == null) {
                respond(exchange, 404, problem(404, "VENDOR_NOT_FOUND"));
            } else {
                respond(exchange, 200, vendorStore(vendor));
            }
        } else if ((matcher = MENU_QUOTE.matcher(path)).matches()) {
            Restaurant restaurant = restaurants.get(UUID.fromString(matcher.group(1)));
            if (restaurant == null) {
                respond(exchange, 404, problem(404, "RESTAURANT_NOT_FOUND"));
            } else {
                respond(exchange, 200, menuQuote(restaurant, ids(body, "itemIds")));
            }
        } else if (path.equals("/api/customers/me/addresses")) {
            List<Address> list = new ArrayList<>(addresses.getOrDefault(caller(authorization), List.of()));
            list.sort(Comparator.comparing(address -> !address.isDefault()));
            respond(exchange, 200, list.stream().map(StubServices::address).toList());
        } else if ((matcher = ADDRESS.matcher(path)).matches()) {
            UUID id = UUID.fromString(matcher.group(1));
            Address address = addresses.getOrDefault(caller(authorization), List.of()).stream()
                    .filter(a -> a.id().equals(id)).findFirst().orElse(null);
            if (address == null) {
                respond(exchange, 404, problem(404, "ADDRESS_NOT_FOUND"));
            } else {
                respond(exchange, 200, address(address));
            }
        } else {
            respond(exchange, 404, problem(404, "REQUEST_ERROR"));
        }
    }

    private static String dependencyOf(String path) {
        if (path.startsWith("/internal/catalog")) {
            return "catalog";
        }
        if (path.startsWith("/internal/delivery-fees")) {
            return "pricing-fees";                  // its own name: a test can take only the fee lookup down
        }
        if (path.startsWith("/internal/prices") || path.startsWith("/internal/coupons")) {
            return "pricing";
        }
        if (path.startsWith("/internal/inventory")) {
            return "inventory";
        }
        if (path.startsWith("/internal/vendors")) {
            return "vendor";
        }
        if (path.startsWith("/internal/restaurants")) {
            return "restaurant";
        }
        return "customer";
    }

    /** The stub's tokens are "token-<userId>" (see AbstractIntegrationTest#as). */
    private static UUID caller(String authorization) {
        return UUID.fromString(authorization.substring("Bearer token-".length()));
    }

    private Map<String, Object> lookup(List<UUID> ids) {
        List<Object> items = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            Product product = products.get(id);
            if (product == null || !product.visible()) {
                missing.add(id);
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", product.id());
            card.put("name", product.name());
            card.put("unitLabel", product.unitLabel());
            card.put("foodType", "VEG");
            card.put("primaryImageUrl", null);
            card.put("brand", null);
            card.put("category", Map.of("id", UUID.nameUUIDFromBytes(new byte[]{1}), "name", "Staples"));
            card.put("vendor", Map.of("id", product.vendorId(), "name", product.vendorName()));
            items.add(card);
        }
        return Map.of("items", items, "missing", missing);
    }

    private Map<String, Object> quote(List<UUID> ids) {
        List<Object> items = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            Product product = products.get(id);
            if (product == null || product.sellingPrice() == null) {
                missing.add(id);
                continue;
            }
            items.add(Map.of("productId", id, "vendorId", product.vendorId(), "mrp", product.mrp(),
                    "sellingPrice", product.sellingPrice(), "currency", "INR", "priceVersion", 1));
        }
        return Map.of("items", items, "missing", missing);
    }

    /** {@code POST /internal/coupons/evaluate}: {@code { valid, discount, deliveryFeeDiscount, reason?, … }}. */
    private Map<String, Object> evaluate(JsonNode body) {
        String code = body.get("code").asString().strip().toUpperCase();
        BigDecimal subtotal = body.get("subtotal").decimalValue();
        JsonNode fee = body.get("deliveryFee");
        BigDecimal deliveryFee = fee == null || fee.isNull() ? BigDecimal.ZERO : fee.decimalValue();
        UUID storeId = UUID.fromString(body.get("storeId").asString());
        String orderType = body.get("orderType").asString();
        Map<String, Object> result = new LinkedHashMap<>();
        Coupon coupon = coupons.get(code);
        String reason = null;
        if (coupon == null) {
            reason = "COUPON_NOT_FOUND";
        } else if (coupon.forcedReason() != null) {
            reason = coupon.forcedReason();
        } else if (("STORE".equals(coupon.scope()) && !storeId.equals(coupon.storeId()))
                || (!"ANY".equals(coupon.storeType()) && !coupon.storeType().equals(orderType))) {
            reason = "COUPON_WRONG_STORE";
        } else if (subtotal.compareTo(coupon.minOrderSubtotal()) < 0) {
            reason = "COUPON_MIN_ORDER";
            result.put("minOrderSubtotal", coupon.minOrderSubtotal());
        }
        BigDecimal discount = new BigDecimal("0.00");
        BigDecimal deliveryFeeDiscount = new BigDecimal("0.00");
        if (reason == null) {
            switch (coupon.discountType()) {
                case "PERCENT" -> {
                    discount = subtotal.multiply(coupon.value()).divide(new BigDecimal("100"), 2,
                            java.math.RoundingMode.HALF_UP);
                    if (coupon.maxDiscount() != null) {
                        discount = discount.min(coupon.maxDiscount());
                    }
                }
                case "FLAT" -> discount = coupon.value().min(subtotal);
                default -> deliveryFeeDiscount = deliveryFee;
            }
        }
        result.put("valid", reason == null);
        result.put("discount", discount);
        result.put("deliveryFeeDiscount", deliveryFeeDiscount);
        if (reason != null) {
            result.put("reason", reason);
        }
        if (coupon != null) {
            result.put("code", coupon.code());
            result.put("scope", coupon.scope());
            if (coupon.storeId() != null) {
                result.put("storeId", coupon.storeId());
            }
            result.put("discountType", coupon.discountType());
        }
        return result;
    }

    private Map<String, Object> availability(List<UUID> ids) {
        List<Object> items = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            Product product = products.get(id);
            if (product == null || product.available() == null) {
                missing.add(id);
                continue;
            }
            items.add(Map.of("productId", id, "vendorId", product.vendorId(), "available", product.available(),
                    "inStock", product.available() > 0));
        }
        return Map.of("items", items, "missing", missing);
    }

    private static Map<String, Object> vendorStore(Vendor vendor) {
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("vendorId", vendor.id());
        store.put("name", vendor.name());
        store.put("status", vendor.active() ? "APPROVED" : "SUSPENDED");
        store.put("active", vendor.active());
        store.put("city", "Bengaluru");
        store.put("latitude", vendor.latitude());
        store.put("longitude", vendor.longitude());
        return store;
    }

    private static Map<String, Object> menuQuote(Restaurant restaurant, List<UUID> ids) {
        List<Object> items = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            MenuItem item = restaurant.menu().get(id);
            if (item == null) {
                missing.add(id);
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("itemId", item.id());
            view.put("name", item.name());
            view.put("price", item.price());
            view.put("foodType", item.foodType());
            view.put("available", item.available());
            view.put("imageUrl", null);
            items.add(view);
        }
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("restaurantId", restaurant.id());
        quote.put("name", restaurant.name());
        quote.put("status", restaurant.status());
        quote.put("acceptingOrders", restaurant.acceptingOrders());
        quote.put("openNow", restaurant.openNow());
        quote.put("city", "Bengaluru");
        quote.put("latitude", restaurant.latitude());
        quote.put("longitude", restaurant.longitude());
        quote.put("items", items);
        quote.put("missing", missing);
        return quote;
    }

    private static Map<String, Object> address(Address address) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", address.id());
        view.put("label", address.label());
        view.put("recipientName", "Asha Rao");
        view.put("phone", "+919876543210");
        view.put("line1", "12 MG Road");
        view.put("line2", null);
        view.put("landmark", null);
        view.put("city", "Bengaluru");
        view.put("state", "Karnataka");
        view.put("postalCode", "560001");
        view.put("country", "IN");
        view.put("latitude", address.latitude());
        view.put("longitude", address.longitude());
        view.put("isDefault", address.isDefault());
        view.put("createdAt", "2026-11-11T10:15:30Z");
        view.put("updatedAt", "2026-11-11T10:15:30Z");
        return view;
    }

    private static List<UUID> ids(JsonNode body, String field) {
        List<UUID> ids = new ArrayList<>();
        if (body != null && body.get(field) != null) {
            body.get(field).forEach(node -> ids.add(UUID.fromString(node.asString())));
        }
        return ids;
    }

    private static Map<String, Object> problem(int status, String errorCode) {
        return Map.of("type", "about:blank", "status", status, "detail", errorCode, "errorCode", errorCode);
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
