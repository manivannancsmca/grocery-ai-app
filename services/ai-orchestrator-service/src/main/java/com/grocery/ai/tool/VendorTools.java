package com.grocery.ai.tool;

import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.link.Pages;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import com.grocery.common.security.Roles;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code vendor} agent's tools (Phase 12 §6), all called as the vendor through the gateway: stock
 * (inventory-service), prices (pricing-service), products (catalog-command), store orders and sales (vendor-service),
 * purchasing (vendor-service), returns (order-service) and reviews (review-service). Stock and price changes and
 * sending a purchase order are SENSITIVE: the vendor approves them.
 */
@Component
public class VendorTools {

    static final Set<String> VENDOR = Set.of(Roles.VENDOR);
    static final String PREFIX = "/api/vendor";
    static final Set<String> INVENTORY_FILTERS = Set.of("LOW_STOCK", "OUT_OF_STOCK");
    static final Set<String> ADJUSTMENT_TYPES = Set.of("INCREASE", "DECREASE", "SET");
    static final Set<String> ADJUSTMENT_REASONS = Set.of("RESTOCK", "RETURN", "DAMAGED", "EXPIRED", "STOCK_COUNT",
            "CORRECTION", "OTHER");
    static final Set<String> PURCHASE_ORDER_STATUSES = Set.of("DRAFT", "SENT", "PARTIALLY_RECEIVED", "RECEIVED",
            "CANCELLED", "CLOSED");

    private final BusinessApi api;

    public VendorTools(BusinessApi api) {
        this.api = api;
    }

    public List<ToolSpec> tools() {
        return List.of(
                ToolSpec.run("getSalesReport", "Read the sales report",
                        "The store's sales report: orders received, accepted, rejected, gross sales, average order "
                                + "value and top items (default the last 7 days).", RiskClass.READ, VENDOR,
                        StoreTools.salesReportArgs(), call -> StoreTools.salesReport(api, call, PREFIX,
                                Pages.vendorReports())),
                ToolSpec.run("listInventory", "Checked stock",
                        "Stock of the vendor's products (up to 20): on hand, reserved, available, low-stock flag. "
                                + "Use it to find a product's id by name.", RiskClass.READ, VENDOR,
                        ArgSchema.builder()
                                .enumeration("filter", INVENTORY_FILTERS, "Only low-stock or out-of-stock products")
                                .string("query", false, 1, 100, "Product name contains / SKU equals")
                                .build(),
                        this::listInventory),
                ToolSpec.run("getStock", "Checked a product's stock", "Stock of one product.", RiskClass.READ,
                        VENDOR, ArgSchema.builder().uuid("productId", true, "The product id").build(),
                        this::getStock),
                ToolSpec.run("listProducts", "Listed products",
                        "The vendor's products (up to 20) with their status. Use it to find a product's id by name.",
                        RiskClass.READ, VENDOR,
                        ArgSchema.builder().string("query", false, 1, 100, "Product name contains / SKU equals")
                                .build(),
                        this::listProducts),
                ToolSpec.run("getPrice", "Checked a price",
                        "A product's live price (MRP and selling price), a pending price change and the admin "
                                + "approval threshold.", RiskClass.READ, VENDOR,
                        ArgSchema.builder().uuid("productId", true, "The product id").build(), this::getPrice),
                ToolSpec.run("listStoreOrders", "Listed store orders",
                        "The store's orders (up to 20, new ones first).", RiskClass.READ, VENDOR,
                        ArgSchema.builder().enumeration("status", StoreTools.STORE_ORDER_STATUSES,
                                "A store status, or ACTIVE (new, accepted, packing, ready)").build(),
                        call -> StoreTools.storeOrders(api, call, PREFIX, false, Pages.vendorOrders())),
                ToolSpec.run("listPurchaseOrders", "Listed purchase orders",
                        "The vendor's purchase orders to suppliers (up to 20, newest first).", RiskClass.READ, VENDOR,
                        ArgSchema.builder().enumeration("status", PURCHASE_ORDER_STATUSES, "Purchase order status")
                                .build(),
                        this::listPurchaseOrders),
                ToolSpec.run("listSuppliers", "Listed suppliers", "The vendor's suppliers with their ids.",
                        RiskClass.READ, VENDOR, ArgSchema.none(), this::listSuppliers),
                ToolSpec.run("listReturns", "Listed return requests",
                        "Customers' return requests for the store (up to 20).", RiskClass.READ, VENDOR,
                        ArgSchema.builder().enumeration("status", StoreTools.RETURN_STATUSES, "Return status").build(),
                        call -> StoreTools.returns(api, call, Pages.vendorReturns())),
                ToolSpec.run("listReviews", "Read reviews",
                        "Published customer reviews of the store and its products (up to 20, newest first).",
                        RiskClass.READ, VENDOR, ArgSchema.none(), call -> StoreTools.reviews(api, call,
                                Pages.vendorReviews())),
                ToolSpec.run("draftPurchaseOrder", "Drafted a purchase order",
                        "Create a DRAFT purchase order to a supplier (nothing is sent; sendPurchaseOrder sends it "
                                + "after the vendor approves).", RiskClass.LOW_RISK_WRITE, VENDOR,
                        ArgSchema.builder()
                                .uuid("supplierId", true, "The supplier id (from listSuppliers)")
                                .list("lines", true, 50, ArgSchema.builder()
                                        .uuid("productId", true, "The product id")
                                        .integer("quantity", true, 1, 100000, "Units to order")
                                        .decimal("unitCost", true, "0", "1000000.00", "Cost per unit in rupees")
                                        .build(), "The products to order")
                                .string("notes", false, 1, 500, "Notes for the supplier")
                                .build(),
                        this::draftPurchaseOrder),
                ToolSpec.propose("adjustStock", "Prepared a stock change",
                        "Prepare changing a product's stock: INCREASE or DECREASE by a quantity (1–100000), or SET "
                                + "the counted stock on hand (0–1000000). It does NOT change it: the vendor approves "
                                + "it in the app.", VENDOR,
                        ArgSchema.builder()
                                .uuid("productId", true, "The product id (from listInventory)")
                                .requiredEnumeration("type", ADJUSTMENT_TYPES, "INCREASE, DECREASE or SET")
                                .integer("quantity", true, 0, 1000000, "Units to add or remove, or the new stock "
                                        + "on hand for SET")
                                .requiredEnumeration("reason", ADJUSTMENT_REASONS, "Why the stock changes")
                                .string("note", false, 1, 300, "A note for the stock ledger")
                                .rationale()
                                .check(values -> {
                                    if (!"SET".equals(values.get("type"))) {
                                        int quantity = (Integer) values.get("quantity");
                                        if (quantity < 1 || quantity > 100000) {
                                            throw new InvalidArgumentsException(
                                                    "quantity must be between 1 and 100000 for INCREASE / DECREASE");
                                        }
                                    }
                                })
                                .build(),
                        ApprovalAction.ADJUST_STOCK),
                ToolSpec.propose("changePrice", "Prepared a price change",
                        "Prepare changing a product's selling price (and MRP; the current MRP is kept when left "
                                + "out). It does NOT change it: the vendor approves it in the app, and a change above "
                                + "the price threshold then still waits for an admin in pricing.", VENDOR,
                        ArgSchema.builder()
                                .uuid("productId", true, "The product id (from listProducts or listInventory)")
                                .decimal("sellingPrice", true, "1.00", "1000000.00", "New selling price in rupees")
                                .decimal("mrp", false, "1.00", "1000000.00", "New MRP in rupees (default: unchanged)")
                                .string("note", false, 1, 300, "A note for the admin when approval is needed")
                                .rationale()
                                .build(),
                        ApprovalAction.CHANGE_PRICE),
                ToolSpec.propose("sendPurchaseOrder", "Prepared sending a purchase order",
                        "Prepare sending a DRAFT purchase order to its supplier. It does NOT send it: the vendor "
                                + "approves it in the app.", VENDOR,
                        ArgSchema.builder()
                                .reference("purchaseOrderNumber", "a purchase order number such as PO-7K3M9QX2",
                                        "The purchase order number, e.g. PO-7K3M9QX2")
                                .rationale()
                                .build(),
                        ApprovalAction.SEND_PURCHASE_ORDER));
    }

    // ---- stock and products -------------------------------------------------------------------------------------

    private ToolResult listInventory(ToolCallContext call) {
        String filter = call.args().string("filter");
        String query = call.args().string("query");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("filter", filter);
        params.put("size", 20);
        return Results.from(api.get(call.http(), PREFIX + "/inventory", params), ok -> {
            ArrayNode products = Results.array();
            for (JsonNode view : Json.list(ok.body(), "content")) {
                products.add(stock(view));
            }
            ObjectNode result = Results.object();
            Results.put(result, "filter", filter);
            Results.put(result, "query", query);
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    products.size()));
            result.set("products", products);
            String what = filter == null ? "" : "LOW_STOCK".equals(filter) ? " with low stock" : " out of stock";
            String matching = query == null ? "" : " matching “" + query + "”";
            return Results.ok(result, (products.isEmpty() ? "No products" : products.size()
                    + (products.size() == 1 ? " product" : " products")) + what + matching, ok,
                    Pages.vendorInventory(filter, query));
        });
    }

    private ToolResult getStock(ToolCallContext call) {
        return Results.from(api.get(call.http(), PREFIX + "/inventory/" + call.args().uuid("productId")), ok -> {
            ObjectNode result = Results.object();
            result.set("stock", stock(ok.body()));
            return Results.ok(result, Json.text(ok.body(), "productName") + ": "
                    + Json.integer(ok.body(), "available") + " available", ok,
                    Pages.vendorInventory(null, Json.text(ok.body(), "productName")));
        });
    }

    /** A StockView trimmed for the model. */
    static ObjectNode stock(JsonNode view) {
        return Results.pick(view, "productId", "productName", "unitLabel", "sku", "productStatus", "onHand",
                "reserved", "available", "lowStockThreshold", "lowStock", "inStock");
    }

    private ToolResult listProducts(ToolCallContext call) {
        String query = call.args().string("query");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("size", 20);
        return Results.from(api.get(call.http(), PREFIX + "/products", params), ok -> {
            ArrayNode products = Results.array();
            for (JsonNode view : Json.list(ok.body(), "content")) {
                ObjectNode product = Results.pick(view, "id", "name", "unitLabel", "sku", "status");
                Results.put(product, "category", Json.text(view, "category.name"));
                products.add(product);
            }
            ObjectNode result = Results.object();
            Results.put(result, "query", query);
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    products.size()));
            result.set("products", products);
            return Results.ok(result, (products.isEmpty() ? "No products" : products.size()
                    + (products.size() == 1 ? " product" : " products"))
                    + (query == null ? "" : " matching “" + query + "”"), ok, Pages.vendorProducts());
        });
    }

    private ToolResult getPrice(ToolCallContext call) {
        return Results.from(api.get(call.http(), PREFIX + "/prices/" + call.args().uuid("productId")), ok -> {
            JsonNode body = ok.body();
            ObjectNode price = Results.pick(body, "productId", "productName", "unitLabel", "approvalThresholdPercent");
            JsonNode live = Json.at(body, "price");
            if (live != null) {
                price.set("price", Results.pick(live, "mrp", "sellingPrice", "discountPercent", "currency"));
            }
            JsonNode pending = Json.at(body, "pendingRequest");
            if (pending != null) {
                price.set("pendingRequest", Results.pick(pending, "requestedMrp", "requestedSellingPrice",
                        "changePercent", "status", "requestedAt"));
            }
            ObjectNode result = Results.object();
            result.set("price", price);
            return Results.ok(result, Json.text(body, "productName") + ": " + (live == null ? "no price yet"
                    : Money.inr(Json.decimal(live, "sellingPrice")) + " (MRP " + Money.inr(Json.decimal(live, "mrp"))
                    + ")") + (pending == null ? "" : ", a change waits for an admin"), ok,
                    Pages.vendorPricing(Json.text(body, "productName")));
        });
    }

    // ---- purchasing ---------------------------------------------------------------------------------------------

    private ToolResult listPurchaseOrders(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("size", 20);
        return Results.from(api.get(call.http(), PREFIX + "/purchase-orders", params), ok -> {
            ArrayNode orders = Results.array();
            for (JsonNode view : Json.list(ok.body(), "content")) {
                orders.add(purchaseOrder(view));
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    orders.size()));
            result.set("purchaseOrders", orders);
            return Results.ok(result, orders.isEmpty() ? "No purchase orders"
                    : orders.size() + (orders.size() == 1 ? " purchase order" : " purchase orders"), ok,
                    Pages.purchaseOrders());
        });
    }

    private ToolResult listSuppliers(ToolCallContext call) {
        return Results.from(api.get(call.http(), PREFIX + "/suppliers", Map.of("size", 50)), ok -> {
            ArrayNode suppliers = Results.array();
            for (JsonNode view : StoreTools.entries(ok.body())) {
                suppliers.add(Results.pick(view, "id", "name", "contactName", "leadTimeDays", "active"));
            }
            ObjectNode result = Results.object();
            result.set("suppliers", suppliers);
            return Results.ok(result, suppliers.isEmpty() ? "No suppliers"
                    : suppliers.size() + (suppliers.size() == 1 ? " supplier" : " suppliers"), ok, Pages.suppliers());
        });
    }

    private ToolResult draftPurchaseOrder(ToolCallContext call) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ToolArgs line : call.args().list("lines")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("productId", line.uuid("productId"));
            entry.put("orderedQuantity", line.integer("quantity"));
            entry.put("unitCost", line.decimal("unitCost"));
            lines.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplierId", call.args().uuid("supplierId"));
        body.put("lines", lines);
        if (call.args().string("notes") != null) {
            body.put("notes", call.args().string("notes"));
        }
        return Results.from(api.send(call.http(), HttpMethod.POST, PREFIX + "/purchase-orders", body), ok -> {
            ObjectNode order = purchaseOrder(ok.body());
            ObjectNode result = Results.object();
            result.set("purchaseOrder", order);
            UUID draftId = Json.uuid(ok.body(), "id");
            return Results.ok(result, "Drafted " + Json.text(ok.body(), "number") + " for "
                    + supplierName(ok.body()) + ": " + Json.list(ok.body(), "lines").size() + " lines, "
                    + Money.inr(purchaseOrderTotal(ok.body())), ok, draftId == null ? Pages.purchaseOrders()
                    : Pages.purchaseOrder(draftId));
        });
    }

    /** A PurchaseOrderView trimmed for the model. */
    static ObjectNode purchaseOrder(JsonNode view) {
        ObjectNode order = Results.pick(view, "id", "number", "status", "supplierId", "expectedAt", "createdAt");
        Results.put(order, "supplier", supplierName(view));
        List<JsonNode> lines = Json.list(view, "lines");
        if (!lines.isEmpty()) {
            ArrayNode trimmed = Results.array();
            for (JsonNode line : lines) {
                trimmed.add(Results.pick(line, "productId", "productName", "orderedQuantity", "unitCost",
                        "receivedQuantity"));
            }
            order.set("lines", trimmed);
        }
        Results.put(order, "total", purchaseOrderTotal(view));
        return order;
    }

    /** The supplier's name as the view gives it ({@code supplier.name} or {@code supplierName}), else null. */
    public static String supplierName(JsonNode view) {
        String name = Json.text(view, "supplier.name");
        return name != null ? name : Json.text(view, "supplierName");
    }

    /** Σ orderedQuantity × unitCost of the lines; without lines the view's {@code total} (or null). */
    public static BigDecimal purchaseOrderTotal(JsonNode view) {
        List<JsonNode> lines = Json.list(view, "lines");
        if (lines.isEmpty()) {
            BigDecimal total = Json.decimal(view, "total");
            return total != null ? Money.scale(total) : Money.scale(Json.decimal(view, "totals.total"));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : lines) {
            BigDecimal cost = Objects.requireNonNullElse(Json.decimal(line, "unitCost"), BigDecimal.ZERO);
            int quantity = Objects.requireNonNullElse(Json.integer(line, "orderedQuantity"), 0);
            total = total.add(cost.multiply(BigDecimal.valueOf(quantity)));
        }
        return Money.scale(total);
    }
}
