package com.grocery.ai.tool;

import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.gateway.McpGateway;
import com.grocery.ai.link.Pages;
import com.grocery.ai.support.IndiaTime;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import com.grocery.common.security.Roles;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The {@code shopping} agent's tools (contract §3), all called as the customer through the gateway. */
@Component
public class ShoppingTools {

    static final Set<String> CUSTOMER = Set.of(Roles.CUSTOMER);
    static final Set<String> ORDER_STATUSES = Set.of("OPEN", "CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED",
            "PREPARING", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED", "REJECTED",
            "CANCELLED");
    /** Phase 12 §3 return reasons. */
    public static final Set<String> RETURN_REASONS = Set.of("DAMAGED", "WRONG_ITEM", "MISSING", "QUALITY", "OTHER");
    /** The product search's sort orders the model may ask for (catalog-query-service ProductSearchService.Sort). */
    static final Set<String> PRODUCT_SORTS = Set.of("RELEVANCE", "PRICE_ASC", "PRICE_DESC", "RATING");

    private final BusinessApi api;
    private final McpGateway mcp;

    public ShoppingTools(BusinessApi api, McpGateway mcp) {
        this.api = api;
        this.mcp = mcp;
    }

    public List<ToolSpec> tools() {
        return List.of(
                ToolSpec.run("searchProducts", "Searched products",
                        "Search the grocery catalog: up to 5 products with price and stock, and totalFound, the "
                                + "number of ALL matching products. Use it before adding a product, to get its id. "
                                + "Leave query out to browse or count by price alone: for \"how many products under "
                                + "₹50\" call it with maxPrice 50 and no query, and answer with totalFound (not with "
                                + "the number of products listed).", RiskClass.READ, CUSTOMER,
                        ArgSchema.builder()
                                .string("query", false, 1, 100, "What to look for, e.g. 'basmati rice'. Leave it out "
                                        + "to match every product (for price-only questions)")
                                .string("category", false, 1, 80, "A category name the customer mentioned, e.g. "
                                        + "'Fruits' or 'dairy' (spelling need not be exact)")
                                .bool("inStockOnly", "Only products in stock")
                                .decimal("minPrice", false, "0", "1000000", "Lowest selling price in rupees "
                                        + "(inclusive); only when the customer names a price")
                                .decimal("maxPrice", false, "0", "1000000", "Highest selling price in rupees "
                                        + "(inclusive); only when the customer names a price")
                                .enumeration("sort", PRODUCT_SORTS, "Leave it out to get the best matches first. "
                                        + "PRICE_ASC (cheapest first) or PRICE_DESC only when the customer asks for "
                                        + "cheap or expensive products, RATING for the best rated")
                                .build(),
                        this::searchProducts),
                ToolSpec.run("getProduct", "Looked up a product", "Details of one grocery product.",
                        RiskClass.READ, CUSTOMER,
                        ArgSchema.builder().uuid("productId", true, "The product id from a search result").build(),
                        this::getProduct),
                ToolSpec.run("searchRestaurants", "Searched restaurants",
                        "Search restaurants near the customer's default address (up to 5), optionally by dish, "
                                + "cuisine or name.", RiskClass.READ, CUSTOMER,
                        ArgSchema.builder()
                                .string("query", false, 1, 100, "Dish, cuisine or restaurant name")
                                .bool("openNow", "Only restaurants open now")
                                .build(),
                        this::searchRestaurants),
                ToolSpec.run("getRestaurantMenu", "Read a menu", "A restaurant's menu with prices and availability.",
                        RiskClass.READ, CUSTOMER,
                        ArgSchema.builder().uuid("restaurantId", true, "The restaurant id").build(),
                        this::getRestaurantMenu),
                ToolSpec.run("getCart", "Checked the cart", "The customer's cart with live prices and issues.",
                        RiskClass.READ, CUSTOMER, ArgSchema.none(), this::getCart),
                ToolSpec.run("addToCart", "Added to cart",
                        "Add a grocery product (productId) or a restaurant dish (restaurantId + menuItemId) to the "
                                + "cart. A cart holds one store; replaceCart=true empties a cart from another store "
                                + "first (only when the customer agreed).", RiskClass.LOW_RISK_WRITE, CUSTOMER,
                        ArgSchema.builder()
                                .uuid("productId", false, "Grocery product id")
                                .uuid("restaurantId", false, "Restaurant id (with menuItemId)")
                                .uuid("menuItemId", false, "Menu item id (with restaurantId)")
                                .integer("quantity", false, 1, 20, "How many (1–20, default 1)")
                                .bool("replaceCart", "Empty a cart from another store first")
                                .check(values -> {
                                    boolean product = values.containsKey("productId");
                                    boolean dish = values.containsKey("restaurantId") || values.containsKey("menuItemId");
                                    if (product == dish || (dish && !(values.containsKey("restaurantId")
                                            && values.containsKey("menuItemId")))) {
                                        throw new InvalidArgumentsException(
                                                "give either productId, or restaurantId and menuItemId");
                                    }
                                })
                                .build(),
                        this::addToCart),
                ToolSpec.run("updateCartItem", "Changed the cart", "Change a cart line's quantity (0 removes it).",
                        RiskClass.LOW_RISK_WRITE, CUSTOMER,
                        ArgSchema.builder()
                                .uuid("itemId", true, "The cart line's itemId")
                                .integer("quantity", true, 0, 20, "New quantity (0–20)")
                                .build(),
                        this::updateCartItem),
                ToolSpec.run("previewCheckout", "Previewed checkout",
                        "The checkout preview: items, delivery fee, total and anything blocking the order.",
                        RiskClass.READ, CUSTOMER,
                        ArgSchema.builder().uuid("addressId", false, "Delivery address id (default address if "
                                + "absent)").build(),
                        this::previewCheckout),
                ToolSpec.run("listMyOrders", "Listed orders", "The customer's recent orders (newest first).",
                        RiskClass.READ, CUSTOMER,
                        ArgSchema.builder().enumeration("status", ORDER_STATUSES, "Only orders in this status")
                                .build(),
                        this::listMyOrders),
                ToolSpec.run("getOrderStatus", "Checked an order", "Status and details of one of the customer's "
                                + "orders.", RiskClass.READ, CUSTOMER,
                        ArgSchema.builder().orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M").build(),
                        this::getOrderStatus),
                ToolSpec.propose("placeOrder", "Prepared the order",
                        "Prepare placing the order for the current cart. It does NOT place it: it creates an approval "
                                + "the customer confirms in the app.", CUSTOMER,
                        ArgSchema.builder()
                                .uuid("addressId", false, "Delivery address id (default address if absent)")
                                .string("note", false, 1, 300, "Delivery instructions")
                                .rationale()
                                .build(),
                        ApprovalAction.PLACE_ORDER),
                ToolSpec.propose("cancelOrder", "Prepared a cancellation",
                        "Prepare cancelling one of the customer's orders. It does NOT cancel it: the customer "
                                + "confirms it in the app.", CUSTOMER,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .string("reason", false, 1, 300, "Why the customer cancels")
                                .rationale()
                                .build(),
                        ApprovalAction.CANCEL_ORDER),
                ToolSpec.propose("requestReturn", "Prepared a return request",
                        "Prepare a return request (or a problem report for a restaurant order) for items of a "
                                + "DELIVERED order, within the return window after delivery (the server checks it). "
                                + "Name each item by its name (or "
                                + "itemId) with the quantity. It does NOT send it: the customer confirms it in the "
                                + "app, then the store decides and the refund is computed by the server.", CUSTOMER,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .list("items", true, 20, ArgSchema.builder()
                                        .uuid("itemId", false, "The item's id in the order (or give its name)")
                                        .string("name", false, 1, 120, "The item's name as in the order")
                                        .integer("quantity", true, 1, 100, "How many units to return")
                                        .check(values -> {
                                            if (!values.containsKey("itemId") && !values.containsKey("name")) {
                                                throw new InvalidArgumentsException("give the item's name or itemId");
                                            }
                                        })
                                        .build(), "The items to return")
                                .requiredEnumeration("reason", RETURN_REASONS,
                                        "DAMAGED, WRONG_ITEM, MISSING, QUALITY or OTHER")
                                .string("note", false, 1, 500, "What happened, in the customer's words")
                                .rationale()
                                .build(),
                        ApprovalAction.REQUEST_RETURN));
    }

    // ---- READ ---------------------------------------------------------------------------------------------------

    private ToolResult searchProducts(ToolCallContext call) {
        String query = call.args().string("query");
        BigDecimal minPrice = call.args().decimal("minPrice");
        BigDecimal maxPrice = call.args().decimal("maxPrice");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", query);                              // null (left out) = every product
        params.put("size", 5);
        // A category the customer names ("in the fruits category") becomes the search's categoryId filter.
        CategoryMatcher.Category category = null;
        String categoryName = call.args().string("category");
        if (categoryName != null) {
            Fetch tree = api.get(call.http(), "/api/catalog/categories");
            if (!(tree instanceof Fetch.Ok ok)) {
                Fetch.Problem problem = (Fetch.Problem) tree;
                return new ToolResult.Failed(problem.code(), problem.message(), problem.httpStatus());
            }
            List<CategoryMatcher.Category> categories = CategoryMatcher.flatten(ok.body());
            category = CategoryMatcher.match(categories, categoryName).orElse(null);
            if (category == null) {
                return new ToolResult.Failed("CATEGORY_NOT_FOUND", "There is no category like \"" + categoryName
                        + "\". Categories: " + String.join(", ", CategoryMatcher.suggestions(categories, 15))
                        + ".", 404);
            }
            params.put("categoryId", category.id());
        }
        CategoryMatcher.Category matched = category;
        if (call.args().isTrue("inStockOnly")) {
            params.put("inStock", true);
        }
        if (minPrice != null) {
            params.put("minPrice", minPrice.toPlainString());
        }
        if (maxPrice != null) {
            params.put("maxPrice", maxPrice.toPlainString());
        }
        params.put("sort", call.args().string("sort"));
        StoreScope scope = storeScope(call.http());
        if (scope.noneDeliver()) {
            return new ToolResult.Failed("NO_STORE_DELIVERS", "No grocery store delivers to " + scope.place()
                    + " yet, so no products can be ordered there. Another saved address may have stores nearby.",
                    404);
        }
        params.put("vendorId", scope.vendorIds());                 // null = every store
        Fetch found = search(call, params);
        // The small model adds categories nobody named ("tomatoes" in Fruits): nothing there → every category, said so.
        CategoryMatcher.Category emptyCategory = null;
        if (matched != null && query != null && found instanceof Fetch.Ok inCategory
                && Objects.requireNonNullElse(Json.integer(inCategory.body(), "totalElements"), 0) == 0) {
            Map<String, Object> everyCategory = new LinkedHashMap<>(params);
            everyCategory.remove("categoryId");
            if (search(call, everyCategory) instanceof Fetch.Ok wider
                    && Objects.requireNonNullElse(Json.integer(wider.body(), "totalElements"), 0) > 0) {
                found = wider;
                emptyCategory = matched;
            }
        }
        CategoryMatcher.Category searched = emptyCategory == null ? matched : null;
        CategoryMatcher.Category nothingIn = emptyCategory;
        return Results.from(found, ok -> {
            ArrayNode products = Results.array();
            for (JsonNode card : Json.list(ok.body(), "content")) {
                products.add(productCard(card));
            }
            int totalFound = Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"), products.size());
            ObjectNode result = Results.object();
            Results.put(result, "query", query);
            Results.put(result, "category", searched == null ? null : searched.name());
            if (nothingIn != null) {
                result.put("note", "Nothing matches in " + nothingIn.name() + "; these are from every category.");
            }
            Results.put(result, "priceFilter", priceFilter(minPrice, maxPrice));
            Results.put(result, "minPrice", Money.scale(minPrice));
            Results.put(result, "maxPrice", Money.scale(maxPrice));
            Results.put(result, "stores", scope.describe());
            result.put("totalFound", totalFound);
            result.put("listed", products.size());
            result.set("products", products);
            String summary = searchSummary(query, minPrice, maxPrice, totalFound, products.size());
            return Results.ok(result, searched != null ? summary + " in " + searched.name()
                            : nothingIn != null ? summary + " (none in " + nothingIn.name() + ")" : summary, ok,
                    Pages.productSearch(query, searched == null ? null : searched.id(),
                            searched == null ? null : searched.name(), minPrice, maxPrice,
                            call.args().string("sort")));
        });
    }

    /**
     * The product search: catalog-query-service's MCP tool {@code searchProducts} (the MCP pilot), else its REST API
     * ({@code GET /api/search/products}); both answer the same page of product cards.
     */
    private Fetch search(ToolCallContext call, Map<String, Object> params) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        params.forEach((name, value) -> {
            if (value != null) {
                arguments.put("vendorId".equals(name) ? "vendorIds" : name, value instanceof String text
                        && ("minPrice".equals(name) || "maxPrice".equals(name)) ? new BigDecimal(text) : value);
            }
        });
        Optional<Fetch> viaMcp = mcp.call(call.http(), McpGateway.Server.CATALOG, "searchProducts", arguments);
        if (viaMcp.isPresent()) {
            call.transport().usedMcp();
            return viaMcp.get();
        }
        return api.get(call.http(), "/api/search/products", params);
    }

    /**
     * One of the customer's orders by its number: order-service's MCP tool {@code getOrderStatus} (the MCP pilot: one
     * lookup by number, the customer checked by order-service), else the REST API (the customer's order list, then the
     * order).
     */
    private Fetch customerOrder(ToolCallContext call, String number) {
        Optional<Fetch> viaMcp = mcp.call(call.http(), McpGateway.Server.ORDERS, "getOrderStatus",
                Map.of("orderNumber", number));
        if (viaMcp.isPresent()) {
            call.transport().usedMcp();
            return viaMcp.get() instanceof Fetch.Problem problem && "ORDER_NOT_FOUND".equals(problem.code())
                    ? BusinessApi.orderNotFound(number) : viaMcp.get();
        }
        return api.customerOrder(call.http(), number);
    }

    /** catalog-query accepts at most this many {@code vendorId} values in one search (the storefront sends as many). */
    static final int MAX_SCOPE_STORES = 50;

    /**
     * Which stores a product search covers. Like the storefront, only the stores that deliver to the customer's default
     * address: otherwise a Velachery customer was offered a Tambaram store's products, which cannot be delivered to them.
     *
     * @param vendorIds the stores; null = every store (no address with a location yet, or the store list failed);
     *                  empty = no store delivers there
     * @param place     the address's label ("Home"), null when every store is searched
     */
    record StoreScope(List<String> vendorIds, String place) {

        boolean noneDeliver() {
            return vendorIds != null && vendorIds.isEmpty();
        }

        /** "stores that deliver to Home (10)", for the model; null when every store was searched. */
        String describe() {
            return vendorIds == null ? null : "stores that deliver to " + place + " (" + vendorIds.size() + ")";
        }
    }

    /** The stores that deliver to the default address ({@code GET /api/stores/nearby}, nearest first). */
    StoreScope storeScope(CallContext context) {
        JsonNode address = defaultAddress(context);
        BigDecimal latitude = address == null ? null : Json.decimal(address, "latitude");
        BigDecimal longitude = address == null ? null : Json.decimal(address, "longitude");
        if (latitude == null || longitude == null) {
            return new StoreScope(null, null);
        }
        if (!(api.get(context, "/api/stores/nearby", Map.of("lat", latitude.toPlainString(),
                "lng", longitude.toPlainString())) instanceof Fetch.Ok nearby)) {
            return new StoreScope(null, null);                    // as the storefront: then every store
        }
        List<String> ids = Json.list(nearby.body(), "content").stream()
                .filter(store -> Json.at(store, "deliversHere") == null || Json.bool(store, "deliversHere"))
                .map(store -> Json.text(store, "id")).filter(Objects::nonNull).limit(MAX_SCOPE_STORES).toList();
        return new StoreScope(ids, Objects.requireNonNullElse(Json.text(address, "label"), "your address"));
    }

    /** The customer's default address (or their first one); null without addresses or when they cannot be read. */
    private JsonNode defaultAddress(CallContext context) {
        if (!(api.get(context, "/api/customers/me/addresses") instanceof Fetch.Ok addresses)
                || addresses.body() == null || !addresses.body().isArray()) {
            return null;
        }
        JsonNode first = null;
        for (JsonNode address : addresses.body()) {
            if (Json.bool(address, "isDefault")) {
                return address;
            }
            first = first == null ? address : first;
        }
        return first;
    }

    /**
     * The price bounds in words, for the model to repeat: both are inclusive, and without this a small model answers
     * "21 products below ₹50" when 10 of them cost exactly ₹50.
     */
    static String priceFilter(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && maxPrice != null) {
            return Money.inr(minPrice) + " to " + Money.inr(maxPrice) + " (both included)";
        }
        if (maxPrice != null) {
            return Money.inr(maxPrice) + " or less (" + Money.inr(maxPrice) + " included)";
        }
        return minPrice != null ? Money.inr(minPrice) + " or more (" + Money.inr(minPrice) + " included)" : null;
    }

    /** "Found 2 products for “rice”", "Found 21 products up to ₹50.00 (showing 5)", "No products found …". */
    static String searchSummary(String query, BigDecimal minPrice, BigDecimal maxPrice, int totalFound, int listed) {
        String what = (query == null ? "" : " for “" + query + "”") + Money.range(minPrice, maxPrice);
        if (totalFound == 0) {
            return "No products found" + what;
        }
        return "Found " + totalFound + (totalFound == 1 ? " product" : " products") + what
                + (totalFound > listed ? " (showing " + listed + ")" : "");
    }

    private static ObjectNode productCard(JsonNode card) {
        ObjectNode product = Results.pick(card, "id", "name", "unitLabel", "foodType");
        Results.put(product, "brand", Json.text(card, "brand.name"));
        Results.put(product, "vendor", Json.text(card, "vendor.name"));
        Results.put(product, "price", Json.decimal(card, "price.sellingPrice"));
        Results.put(product, "mrp", Json.decimal(card, "price.mrp"));
        JsonNode inStock = Json.at(card, "inStock");
        if (inStock != null) {
            product.put("inStock", inStock.asBoolean());
        }
        return product;
    }

    private ToolResult getProduct(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/catalog/products/" + call.args().uuid("productId")), ok -> {
            ObjectNode product = productCard(ok.body());
            Results.put(product, "category", Json.text(ok.body(), "category.name"));
            Results.put(product, "description", Results.truncate(Json.text(ok.body(), "description"), 300));
            ObjectNode result = Results.object();
            result.set("product", product);
            return Results.ok(result, "Looked up " + Json.text(ok.body(), "name"), ok,
                    Pages.product(call.args().uuid("productId"), Json.text(ok.body(), "name")));
        });
    }

    private ToolResult searchRestaurants(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", call.args().string("query"));
        if (call.args().isTrue("openNow")) {
            params.put("openNow", true);
        }
        params.put("size", 5);
        String near = null;
        JsonNode chosen = defaultAddress(call.http());
        if (chosen != null && Json.decimal(chosen, "latitude") != null && Json.decimal(chosen, "longitude") != null) {
            params.put("lat", Json.decimal(chosen, "latitude").toPlainString());
            params.put("lng", Json.decimal(chosen, "longitude").toPlainString());
            near = Objects.requireNonNullElse(Json.text(chosen, "label"), "your address");
        }
        String nearLabel = near;
        return Results.from(api.get(call.http(), "/api/search/restaurants", params), ok -> {
            ArrayNode restaurants = Results.array();
            for (JsonNode card : Json.list(ok.body(), "content")) {
                ObjectNode restaurant = Results.pick(card, "id", "name", "cuisines", "distanceKm", "openNow",
                        "acceptingOrders", "pureVeg");
                ArrayNode dishes = Results.array();
                for (JsonNode item : Json.list(card, "matchingItems")) {
                    dishes.add(Results.pick(item, "id", "name", "price", "foodType"));
                }
                if (!dishes.isEmpty()) {
                    restaurant.set("matchingItems", dishes);
                }
                restaurants.add(restaurant);
            }
            ObjectNode result = Results.object();
            Results.put(result, "near", nearLabel);
            result.set("restaurants", restaurants);
            return Results.ok(result, restaurants.isEmpty() ? "No restaurants found"
                    : "Found " + restaurants.size() + (restaurants.size() == 1 ? " restaurant" : " restaurants"), ok,
                    Pages.restaurantSearch(call.args().string("query")));
        });
    }

    private ToolResult getRestaurantMenu(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/catalog/restaurants/" + call.args().uuid("restaurantId")),
                ok -> {
                    ObjectNode restaurant = Results.pick(ok.body(), "id", "name", "openNow", "acceptingOrders",
                            "pureVeg");
                    ArrayNode menu = Results.array();
                    int items = 0;
                    for (JsonNode category : Json.list(ok.body(), "menu")) {
                        ArrayNode dishes = Results.array();
                        for (JsonNode item : Json.list(category, "items")) {
                            if (items++ < 60) {
                                dishes.add(Results.pick(item, "id", "name", "price", "foodType", "available"));
                            }
                        }
                        ObjectNode section = Results.object();
                        section.put("category", Json.text(category, "name"));
                        section.set("items", dishes);
                        menu.add(section);
                    }
                    restaurant.set("menu", menu);
                    ObjectNode result = Results.object();
                    result.set("restaurant", restaurant);
                    return Results.ok(result, "Menu of " + Json.text(ok.body(), "name") + ": " + items + " dishes",
                            ok, Pages.restaurant(call.args().uuid("restaurantId"), Json.text(ok.body(), "name")));
                });
    }

    private ToolResult getCart(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/cart"), ok -> {
            ObjectNode result = Results.object();
            result.set("cart", cart(ok.body()));
            return Results.ok(result, cartSummary(ok.body()), ok, Pages.cart());
        });
    }

    /** The cart trimmed for the model (also used by previewCheckout). */
    static ObjectNode cart(JsonNode body) {
        ObjectNode cart = Results.object();
        cart.set("store", Results.store(body));
        ArrayNode items = Results.array();
        for (JsonNode line : Json.list(body, "lines")) {
            items.add(Results.pick(line, "itemId", "type", "name", "unitLabel", "quantity", "unitPrice", "lineTotal",
                    "issue"));
        }
        cart.set("items", items);
        Results.put(cart, "itemCount", Json.decimal(body, "itemCount"));
        Results.put(cart, "subtotal", Json.decimal(body, "subtotal"));
        Results.put(cart, "currency", Json.text(body, "currency"));
        ArrayNode issues = Results.array();
        for (JsonNode issue : Json.list(body, "issues")) {
            issues.add(Results.pick(issue, "code", "message", "blocking"));
        }
        cart.set("issues", issues);
        Results.put(cart, "updatedAt", Json.text(body, "updatedAt"));
        return cart;
    }

    /** "3 items from Green Basket, subtotal ₹248.00" / "empty". */
    public static String cartSummary(JsonNode body) {
        Integer itemCount = Json.integer(body, "itemCount");
        if (itemCount == null || itemCount == 0 || Json.list(body, "lines").isEmpty()) {
            return "Cart is empty";
        }
        return "Cart: " + itemCount + (itemCount == 1 ? " item" : " items") + " from "
                + Objects.requireNonNullElse(Json.text(body, "store.name"), "a store") + ", subtotal "
                + Money.inr(Json.decimal(body, "subtotal"));
    }

    // ---- LOW_RISK_WRITE -----------------------------------------------------------------------------------------

    private ToolResult addToCart(ToolCallContext call) {
        ToolArgs args = call.args();
        Map<String, Object> body = new LinkedHashMap<>();
        UUID itemId;
        if (args.uuid("productId") != null) {
            body.put("type", "PRODUCT");
            body.put("productId", args.uuid("productId"));
            itemId = args.uuid("productId");
        } else {
            body.put("type", "MENU_ITEM");
            body.put("restaurantId", args.uuid("restaurantId"));
            body.put("menuItemId", args.uuid("menuItemId"));
            itemId = args.uuid("menuItemId");
        }
        int quantity = Objects.requireNonNullElse(args.integer("quantity"), 1);
        body.put("quantity", quantity);
        if (args.isTrue("replaceCart")) {
            body.put("replaceCart", true);
        }
        return Results.from(api.send(call.http(), HttpMethod.POST, "/api/cart/items", body), ok -> {
            String name = lineName(ok.body(), itemId);
            ObjectNode added = Results.object();
            added.put("itemId", itemId.toString());
            Results.put(added, "name", name);
            added.put("quantity", quantity);
            ObjectNode result = Results.object();
            result.set("added", added);
            result.set("cart", cart(ok.body()));
            return Results.ok(result, "Added " + quantity + " × " + Objects.requireNonNullElse(name, "item")
                    + " · " + cartSummary(ok.body()), ok, Pages.cart());
        });
    }

    private ToolResult updateCartItem(ToolCallContext call) {
        UUID itemId = call.args().uuid("itemId");
        int quantity = call.args().integer("quantity");
        return Results.from(api.send(call.http(), HttpMethod.PUT, "/api/cart/items/" + itemId,
                Map.of("quantity", quantity)), ok -> {
            ObjectNode result = Results.object();
            result.set("cart", cart(ok.body()));
            String name = lineName(ok.body(), itemId);
            return Results.ok(result, (quantity == 0 ? "Removed an item" : "Set " + Objects.requireNonNullElse(name,
                    "item") + " to " + quantity) + " · " + cartSummary(ok.body()), ok, Pages.cart());
        });
    }

    private static String lineName(JsonNode cart, UUID itemId) {
        for (JsonNode line : Json.list(cart, "lines")) {
            if (itemId.toString().equals(Json.text(line, "itemId"))) {
                return Json.text(line, "name");
            }
        }
        return null;
    }

    private ToolResult previewCheckout(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("addressId", call.args().uuid("addressId"));
        return Results.from(api.get(call.http(), "/api/cart/preview", params), ok -> {
            JsonNode body = ok.body();
            ObjectNode preview = cart(body);
            Results.put(preview, "deliveryFee", Json.decimal(body, "delivery.fee"));
            Results.put(preview, "total", Json.decimal(body, "total"));
            preview.put("canCheckout", Json.bool(body, "canCheckout"));
            JsonNode address = Json.at(body, "address");
            if (address != null) {
                preview.set("address", Results.pick(address, "id", "label", "line1", "city"));
            }
            ObjectNode result = Results.object();
            result.set("preview", preview);
            String summary = Json.bool(body, "canCheckout")
                    ? "Total " + Money.inr(Json.decimal(body, "total")) + " (ready to order)"
                    : "Checkout blocked: " + Json.list(body, "issues").stream()
                    .filter(issue -> Json.bool(issue, "blocking")).map(issue -> Json.text(issue, "code"))
                    .toList();
            // Ready: go and order. Blocked: the cart is where the issues get fixed.
            return Results.ok(result, summary, ok, Json.bool(body, "canCheckout") ? Pages.checkout() : Pages.cart());
        });
    }

    private ToolResult listMyOrders(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("size", 10);
        return Results.from(api.get(call.http(), "/api/orders", params), ok -> {
            ArrayNode orders = Results.array();
            for (JsonNode summary : Json.list(ok.body(), "content")) {
                ObjectNode order = Results.pick(summary, "orderNumber", "type", "status", "total", "itemCount",
                        "firstItemName");
                Results.put(order, "placedAt", IndiaTime.format(Json.text(summary, "createdAt")));
                Results.put(order, "store", Json.text(summary, "store.name"));
                Results.put(order, "paymentStatus", Json.text(summary, "payment.status"));
                orders.add(order);
            }
            ObjectNode result = Results.object();
            result.set("orders", orders);
            return Results.ok(result, orders.isEmpty() ? "No orders" : orders.size() + " recent orders", ok,
                    Pages.myOrders(call.args().string("status")));
        });
    }

    private ToolResult getOrderStatus(ToolCallContext call) {
        String number = call.args().string("orderNumber");
        return Results.from(customerOrder(call, number), ok -> {
            ObjectNode result = Results.object();
            result.set("order", order(ok.body()));
            UUID orderId = Json.uuid(ok.body(), "id");
            return Results.ok(result, "Order " + number + " is " + Json.text(ok.body(), "status"), ok,
                    orderId == null ? Pages.myOrders(null) : Pages.myOrder(orderId, number));
        });
    }

    /**
     * An OrderView trimmed for the model (also used by the admin tools). Times are India time, written out
     * ("19 Nov, 2:32 pm IST"): given the API's UTC instants, the small model read them out as local times.
     */
    static ObjectNode order(JsonNode view) {
        ObjectNode order = Results.pick(view, "orderNumber", "type", "status", "subtotal", "deliveryFee", "total",
                "currency", "cancellable", "returnable", "customerId");
        Results.put(order, "placedAt", IndiaTime.format(Json.text(view, "createdAt")));
        Results.put(order, "store", Json.text(view, "store.name"));
        Results.put(order, "paymentStatus", Json.text(view, "payment.status"));
        Results.put(order, "refundedAmount", Json.decimal(view, "payment.refundedAmount"));
        ArrayNode items = Results.array();
        for (JsonNode item : Json.list(view, "items")) {
            items.add(Results.pick(item, "itemId", "name", "quantity", "lineTotal"));
        }
        order.set("items", items);
        JsonNode cancellation = Json.at(view, "cancellation");
        if (cancellation != null) {
            // The reason as a code and in words ("STORE_REJECTED", "the store could not accept it"): given only the
            // code, the model read it out to people as it is.
            ObjectNode cancelled = Results.pick(cancellation, "reason", "by");
            Results.put(cancelled, "reasonText", StatusWords.cancellation(Json.text(cancellation, "reason")));
            Results.put(cancelled, "at", IndiaTime.format(Json.text(cancellation, "at")));
            order.set("cancellation", cancelled);
        }
        return order;
    }
}
