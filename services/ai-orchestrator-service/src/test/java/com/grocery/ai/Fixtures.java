package com.grocery.ai;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business API answers in the shapes of the phase 3–10 contracts, for the fake gateway. */
public final class Fixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();
    public static final UUID STORE = UUID.fromString("0192f000-0000-7000-8000-000000000001");
    public static final UUID RICE = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
    public static final UUID DAL = UUID.fromString("0192f000-0000-7000-8000-0000000000a2");
    public static final UUID ADDRESS = UUID.fromString("0192f000-0000-7000-8000-0000000000d1");
    public static final String CART_UPDATED_AT = "2026-11-13T09:15:30.123456Z";

    private Fixtures() {
    }

    public static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public static Map<String, Object> productCard(UUID id, String name, String price, boolean inStock) {
        return map("id", id.toString(), "name", name, "unitLabel", "1 kg", "foodType", "VEG",
                "primaryImageUrl", null, "brand", map("id", UUID.randomUUID().toString(), "name", "Farm Fresh"),
                "category", map("id", UUID.randomUUID().toString(), "name", "Rice"),
                "vendor", map("id", STORE.toString(), "name", "Fresh Mart"),
                "price", map("mrp", new BigDecimal("120.00"), "sellingPrice", new BigDecimal(price), "currency", "INR"),
                "inStock", inStock);
    }

    public static String searchResults(List<Map<String, Object>> cards) {
        return searchResults(cards, cards.size());
    }

    /** One page of a search that matched {@code totalElements} products in all. */
    public static String searchResults(List<Map<String, Object>> cards, int totalElements) {
        return json(map("content", cards, "page", 0, "size", 5, "totalElements", totalElements,
                "totalPages", (totalElements + 4) / 5,
                "facets", map("categories", List.of(), "brands", List.of(), "foodTypes", List.of())));
    }

    public static Map<String, Object> cartLine(UUID itemId, String name, int quantity, String unitPrice) {
        BigDecimal unit = new BigDecimal(unitPrice);
        return map("itemId", itemId.toString(), "type", "PRODUCT", "name", name, "unitLabel", "1 kg",
                "imageUrl", null, "foodType", "VEG", "quantity", quantity, "unitPrice", unit, "mrp",
                new BigDecimal("120.00"), "lineTotal", unit.multiply(BigDecimal.valueOf(quantity)),
                "priceWhenAdded", unit, "availableQuantity", 50, "issue", null);
    }

    public static Map<String, Object> cart(List<Map<String, Object>> lines) {
        int count = lines.stream().mapToInt(l -> (Integer) l.get("quantity")).sum();
        BigDecimal subtotal = lines.stream().map(l -> (BigDecimal) l.get("lineTotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        return map("store", lines.isEmpty() ? null : map("id", STORE.toString(), "type", "GROCERY", "name",
                        "Fresh Mart"),
                "lines", lines, "itemCount", count, "subtotal", subtotal, "mrpTotal", subtotal, "savings",
                new BigDecimal("0.00"), "currency", "INR", "issues", List.of(),
                "updatedAt", lines.isEmpty() ? null : CART_UPDATED_AT);
    }

    /** The default cart: Smart Rice × 2 at 100.00 and Toor Dal × 1 at 60.00 from Fresh Mart. */
    public static Map<String, Object> defaultCart() {
        return cart(List.of(cartLine(RICE, "Smart Rice", 2, "100.00"), cartLine(DAL, "Toor Dal", 1, "60.00")));
    }

    /** The checkout preview of {@link #defaultCart()}: subtotal 260.00 + delivery 25.00 = 285.00. */
    public static String preview(boolean canCheckout) {
        Map<String, Object> preview = defaultCart();
        preview.put("address", map("id", ADDRESS.toString(), "label", "Home", "recipientName", "Asha Rao",
                "phone", "+919876543210", "line1", "12 MG Road", "line2", null, "landmark", null,
                "city", "Bengaluru", "state", "Karnataka", "postalCode", "560001", "latitude", 12.9716,
                "longitude", 77.5946));
        preview.put("delivery", map("distanceKm", 4.6, "fee", new BigDecimal("25.00"), "freeAbove",
                new BigDecimal("499.00"), "maxDistanceKm", 15, "minimumOrder", new BigDecimal("99.00")));
        preview.put("total", new BigDecimal("285.00"));
        preview.put("canCheckout", canCheckout);
        if (!canCheckout) {
            preview.put("issues", List.of(map("code", "STORE_CLOSED", "itemId", null, "blocking", true,
                    "message", "Fresh Mart is closed now")));
        }
        preview.put("pricedAt", "2026-11-13T09:16:00Z");
        return json(preview);
    }

    /** An OrderView (customer). */
    public static Map<String, Object> order(UUID id, String number, String status, boolean cancellable,
                                            String total) {
        return map("id", id.toString(), "orderNumber", number, "type", "GROCERY", "status", status,
                "store", map("id", STORE.toString(), "type", "GROCERY", "name", "Fresh Mart"),
                "items", List.of(map("itemId", RICE.toString(), "type", "PRODUCT", "name", "Smart Rice",
                        "quantity", 2, "unitPrice", new BigDecimal("100.00"), "lineTotal", new BigDecimal("200.00"))),
                "itemCount", 2, "subtotal", new BigDecimal("200.00"), "deliveryFee", new BigDecimal("25.00"),
                "total", new BigDecimal(total), "currency", "INR",
                "payment", map("status", "PAID", "dueAt", null, "paidAt", null, "refundedAmount",
                        new BigDecimal("0.00"), "lastFailure", null),
                "cancellable", cancellable, "cancellation", null, "createdAt", "2026-11-13T08:00:00Z");
    }

    public static String page(List<?> content) {
        return json(map("content", content, "page", 0, "size", 20, "totalElements", content.size(),
                "totalPages", 1));
    }

    /** A PaymentView of a captured payment. */
    public static Map<String, Object> payment(UUID orderId, String number, String amount, String refunded,
                                              String status) {
        return map("id", UUID.randomUUID().toString(), "orderId", orderId.toString(), "orderNumber", number,
                "customerId", UUID.randomUUID().toString(), "status", status, "amount", new BigDecimal(amount),
                "currency", "INR", "method", "upi", "refundedAmount", new BigDecimal(refunded),
                "refundStatus", "NONE", "refunds", List.of(), "createdAt", "2026-11-13T08:00:00Z");
    }

    public static String stats(int orders, String revenue) {
        return json(map("date", "2026-11-13", "timezone", "Asia/Kolkata", "orders", orders,
                "byStatus", map("CREATED", 0, "AWAITING_PAYMENT", 3, "CONFIRMED", orders - 10, "REJECTED", 2,
                        "CANCELLED", 5),
                "byType", map("GROCERY", orders - 7, "RESTAURANT", 7),
                "confirmedRevenue", new BigDecimal(revenue), "averageOrderValue", new BigDecimal("341.67"),
                "currency", "INR"));
    }

    // ---- Phase 12 §6: vendor, restaurant and delivery APIs ---------------------------------------------------------

    public static final UUID TOMATO = UUID.fromString("0192f000-0000-7000-8000-0000000000b1");
    public static final UUID PANEER_TIKKA = UUID.fromString("0192f000-0000-7000-8000-0000000000c1");
    public static final UUID SPRING_ROLL = UUID.fromString("0192f000-0000-7000-8000-0000000000c2");
    public static final UUID DAL_MAKHANI = UUID.fromString("0192f000-0000-7000-8000-0000000000c3");

    /** A StockView (Phase 4 §2) of a "500 g" product. */
    public static Map<String, Object> stock(UUID productId, String name, int onHand, int reserved, int threshold) {
        int available = onHand - reserved;
        return map("productId", productId.toString(), "productName", name, "unitLabel", "500 g", "sku", "TOM-500",
                "productStatus", "PUBLISHED", "onHand", onHand, "reserved", reserved, "available", available,
                "lowStockThreshold", threshold, "lowStock", threshold > 0 && available <= threshold,
                "inStock", available > 0, "updatedAt", "2026-11-13T08:00:00Z");
    }

    /** A PriceView (Phase 4 §3), threshold 20 %. */
    public static Map<String, Object> priceView(UUID productId, String name, String mrp, String selling,
                                                Map<String, Object> pendingRequest) {
        return map("productId", productId.toString(), "productName", name, "unitLabel", "500 g", "sku", "TOM-500",
                "productStatus", "PUBLISHED",
                "price", map("mrp", new BigDecimal(mrp), "sellingPrice", new BigDecimal(selling), "currency", "INR",
                        "discountPercent", 18, "updatedAt", "2026-11-12T08:00:00Z"),
                "pendingRequest", pendingRequest, "approvalThresholdPercent", 20);
    }

    /** A PurchaseOrderView (Phase 12 §4): Tomato × 50 @ 18.50 + Smart Rice × 20 @ 80.00 = 2525.00. */
    public static Map<String, Object> purchaseOrder(UUID id, String number, String status, UUID supplierId) {
        return map("id", id.toString(), "number", number, "status", status, "supplierId", supplierId.toString(),
                "supplier", map("id", supplierId.toString(), "name", "Nashik Farms"),
                "expectedAt", "2026-11-15", "notes", null,
                "lines", List.of(
                        map("productId", TOMATO.toString(), "productName", "Tomato", "orderedQuantity", 50,
                                "unitCost", new BigDecimal("18.50"), "receivedQuantity", 0),
                        map("productId", RICE.toString(), "productName", "Smart Rice", "orderedQuantity", 20,
                                "unitCost", new BigDecimal("80.00"), "receivedQuantity", 0)),
                "createdAt", "2026-11-13T07:00:00Z");
    }

    /** A MenuView (Phase 3 §5): Paneer Tikka (available), Veg Spring Roll (sold out), Dal Makhani (available). */
    public static String menu() {
        return json(map("restaurantId", STORE.toString(), "restaurantStatus", "APPROVED", "acceptingOrders", true,
                "itemCount", 3, "categories", List.of(
                        map("id", UUID.randomUUID().toString(), "name", "Starters", "sortOrder", 0, "items", List.of(
                                menuItem(PANEER_TIKKA, "Paneer Tikka", "249.00", true),
                                menuItem(SPRING_ROLL, "Veg Spring Roll", "149.00", false))),
                        map("id", UUID.randomUUID().toString(), "name", "Mains", "sortOrder", 1, "items", List.of(
                                menuItem(DAL_MAKHANI, "Dal Makhani", "199.00", true))))));
    }

    public static Map<String, Object> menuItem(UUID id, String name, String price, boolean available) {
        return map("id", id.toString(), "categoryId", UUID.randomUUID().toString(), "name", name,
                "description", null, "price", new BigDecimal(price), "foodType", "VEG", "imageUrl", null,
                "available", available, "sortOrder", 0, "updatedAt", "2026-11-13T07:00:00Z");
    }

    /** A StoreOrderView (Phase 8 §2): Paneer Tikka × 2, total 520.00, accept by 09:05Z (14:35 India). */
    public static Map<String, Object> storeOrder(UUID orderId, String number, String status) {
        return map("orderId", orderId.toString(), "orderNumber", number, "storeId", STORE.toString(),
                "status", status,
                "items", List.of(map("itemId", PANEER_TIKKA.toString(), "name", "Paneer Tikka", "quantity", 2,
                        "unitPrice", new BigDecimal("249.00"), "lineTotal", new BigDecimal("498.00"))),
                "itemCount", 2, "subtotal", new BigDecimal("498.00"), "total", new BigDecimal("520.00"),
                "currency", "INR", "customer", map("name", "Ravi Kumar", "area", "Indiranagar, Bengaluru"),
                "note", "Less spicy", "receivedAt", "2026-11-13T09:00:00Z", "acceptBy", "2026-11-13T09:05:00Z",
                "acceptedAt", null, "readyInMinutes", null, "readyBy", null, "rejection", null,
                "cancellation", null, "timeline", List.of(map("status", "NEW", "at", "2026-11-13T09:00:00Z")));
    }

    /** A SalesReport (Phase 8 §3). */
    public static String salesReport() {
        return json(map("from", "2026-11-07", "to", "2026-11-13", "timezone", "Asia/Kolkata", "currency", "INR",
                "ordersReceived", 12, "ordersAccepted", 9, "ordersRejected", 2, "autoRejected", 1,
                "ordersCancelled", 1, "acceptanceRate", new BigDecimal("0.75"),
                "grossSales", new BigDecimal("2340.00"), "itemsSold", 31,
                "averageOrderValue", new BigDecimal("292.50"),
                "daily", List.of(map("date", "2026-11-13", "ordersReceived", 2, "ordersAccepted", 2,
                        "grossSales", new BigDecimal("480.00"))),
                "topItems", List.of(map("itemId", TOMATO.toString(), "name", "Tomato", "quantity", 8,
                        "sales", new BigDecimal("392.00")))));
    }

    /** An AdminDeliveryView (Phase 9 §5) picked up at Green Basket. */
    public static Map<String, Object> adminDelivery(UUID deliveryId, String number, String status, UUID partnerId,
                                                    String partnerName, boolean assignmentFailed) {
        return map("deliveryId", deliveryId.toString(), "orderId", UUID.randomUUID().toString(),
                "orderNumber", number, "orderType", "GROCERY", "status", status, "readyForPickup", false,
                "readyBy", null, "pickup", map("name", "Green Basket", "city", "Bengaluru", "latitude", 12.97,
                        "longitude", 77.59),
                "drop", map("recipientName", "Asha Rao", "phone", "+919876543210", "line1", "12 MG Road",
                        "line2", null, "landmark", "Near the metro", "city", "Bengaluru", "postalCode", "560001",
                        "latitude", 12.98, "longitude", 77.6),
                "note", null, "itemCount", 3, "total", new BigDecimal("245.00"), "currency", "INR",
                "assignedAt", partnerId == null ? null : "2026-11-13T09:00:00Z", "pickedUpAt", null,
                "deliveredAt", null, "failedAt", null, "failure", null, "timeline", List.of(),
                "partnerId", partnerId == null ? null : partnerId.toString(), "partnerName", partnerName,
                "assignmentFailed", assignmentFailed, "offersSent", 2);
    }

    /** An OrderView of a DELIVERED order: Smart Rice × 2 (200.00) + Toor Dal × 1 (60.00), coupon 26.00, fee 25.00. */
    public static Map<String, Object> deliveredOrder(UUID id, String number, boolean returnable) {
        Map<String, Object> order = order(id, number, "DELIVERED", false, "259.00");
        order.put("items", List.of(
                map("itemId", RICE.toString(), "type", "PRODUCT", "name", "Smart Rice", "quantity", 2,
                        "unitPrice", new BigDecimal("100.00"), "lineTotal", new BigDecimal("200.00")),
                map("itemId", DAL.toString(), "type", "PRODUCT", "name", "Toor Dal", "quantity", 1,
                        "unitPrice", new BigDecimal("60.00"), "lineTotal", new BigDecimal("60.00"))));
        order.put("itemCount", 3);
        order.put("subtotal", new BigDecimal("260.00"));
        order.put("discount", new BigDecimal("26.00"));
        order.put("deliveryFeeDiscount", new BigDecimal("0.00"));
        order.put("returnable", returnable);
        order.put("returns", List.of());
        return order;
    }
}
