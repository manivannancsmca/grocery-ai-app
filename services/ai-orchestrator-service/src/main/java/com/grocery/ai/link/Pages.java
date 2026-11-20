package com.grocery.ai.link;

import com.grocery.ai.support.Money;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The web app's pages that the assistants link to, in one place. The paths are the React routes
 * ({@code frontend/grocery-web/src/roles/<role>/routes.tsx}); the chat panel only shows links to its assistant's own
 * pages ({@code frontend/grocery-web/src/features/ai/pageLinks.ts}), so keep the three in step.
 *
 * <p>Two kinds of links reach the user:
 * <ul>
 *   <li>tool links: a tool that ran adds the page that shows the same thing, with the same filters (a product
 *       search links to the search page for that query). They appear as buttons under the answer;</li>
 *   <li>inline links: each assistant's prompt lists its main pages ({@link #linkRule}), so the model can write
 *       {@code [My cart](/cart)} in its answer.</li>
 * </ul>
 */
public final class Pages {

    private Pages() {
    }

    // ---- customer (shopping assistant) ---------------------------------------------------------------------------

    /** The search page with the same query and price filters as a product search. */
    public static PageLink productSearch(String query, BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return productSearch(query, null, null, minPrice, maxPrice, sort);
    }

    /** The search page with the same query, category and price filters as a product search. */
    public static PageLink productSearch(String query, UUID categoryId, String categoryName, BigDecimal minPrice,
                                         BigDecimal maxPrice, String sort) {
        String in = categoryName == null ? "" : " in " + categoryName;
        String label = query != null ? "See all results for \"" + query + "\"" + in
                : categoryName != null ? "See all products in " + categoryName + Money.range(minPrice, maxPrice)
                : "See all products" + Money.range(minPrice, maxPrice);
        return link(label, "/search", "q", query, "categoryId", categoryId, "minPrice", plain(minPrice),
                "maxPrice", plain(maxPrice), "sort", "RELEVANCE".equals(sort) ? null : sort);
    }

    public static PageLink product(UUID productId, String name) {
        return link(name == null ? "View the product" : "View " + name, "/products/" + productId);
    }

    public static PageLink restaurantSearch(String query) {
        return link(query == null ? "See restaurants near you" : "See restaurants for \"" + query + "\"",
                "/restaurants", "q", query);
    }

    public static PageLink restaurant(UUID restaurantId, String name) {
        return link(name == null ? "View the menu" : "View " + name + "'s menu", "/restaurants/" + restaurantId);
    }

    public static PageLink cart() {
        return link("View my cart", "/cart");
    }

    public static PageLink checkout() {
        return link("Go to checkout", "/checkout");
    }

    public static PageLink myOrders(String status) {
        return link("View my orders", "/orders", "status", status);
    }

    public static PageLink myOrder(UUID orderId, String orderNumber) {
        return link(orderNumber == null ? "Track the order" : "Track order " + orderNumber, "/orders/" + orderId);
    }

    public static PageLink myReturns() {
        return link("View my returns", "/account/returns");
    }

    // ---- customer (payment and support assistants) ---------------------------------------------------------------

    /** The order page, whose "Pay now" button opens the payment window (the chat never takes payments). */
    public static PageLink payForOrder(UUID orderId, String orderNumber) {
        return link(orderNumber == null ? "Pay for the order" : "Pay for order " + orderNumber, "/orders/" + orderId);
    }

    /** Help & support: the customer's complaints, and the form to raise one. */
    public static PageLink myComplaints() {
        return link("View my complaints", "/account/complaints");
    }

    // ---- admin (business intelligence assistant) -----------------------------------------------------------------

    public static PageLink adminReports() {
        return link("Open reports", "/admin/reports");
    }

    public static PageLink adminOrders(String status) {
        return link("View orders", "/admin/orders", "status", status);
    }

    /** The admin order list with the order's details open. */
    public static PageLink adminOrder(UUID orderId, String orderNumber) {
        return link(orderNumber == null ? "Open the order" : "Open order " + orderNumber, "/admin/orders",
                "order", orderId);
    }

    public static PageLink stuckOrders() {
        return link("Open stuck orders", "/admin/operations", "tab", "stuck-orders");
    }

    public static PageLink payments(String status) {
        return link("View payments", "/admin/payments", "status", status);
    }

    /** The payments page starts with the reconciliation summary. */
    public static PageLink reconciliation() {
        return link("Open payment reconciliation", "/admin/payments");
    }

    /**
     * The admin directory of vendors, restaurants or delivery partners ({@code VENDOR}, {@code RESTAURANT},
     * {@code DELIVERY_PARTNER}), filtered like the page's own controls ({@code ?status=APPROVED&q=fresh}).
     */
    public static PageLink stakeholders(String type, String status, String query) {
        String path = switch (type) {
            case "RESTAURANT" -> "/admin/restaurants";
            case "DELIVERY_PARTNER" -> "/admin/delivery-partners";
            default -> "/admin/vendors";
        };
        String noun = switch (type) {
            case "RESTAURANT" -> "restaurants";
            case "DELIVERY_PARTNER" -> "delivery partners";
            default -> "vendors";
        };
        String label = status == null ? "View " + noun
                : "View " + status.toLowerCase(Locale.ROOT).replace('_', ' ') + " " + noun;
        return link(label, path, "status", status, "q", query);
    }

    /** The onboarding approval queue, on the tab of one stakeholder type. */
    public static PageLink onboardingQueue(String type) {
        return switch (type) {
            case "RESTAURANT" -> link("Review pending restaurants", "/admin/approvals", "type", "restaurants");
            case "DELIVERY_PARTNER" -> link("Review pending delivery partners", "/admin/approvals", "type",
                    "delivery-partners");
            default -> link("Review pending vendors", "/admin/approvals", "type", "vendors");
        };
    }

    // ---- vendor --------------------------------------------------------------------------------------------------

    public static PageLink vendorReports() {
        return link("View sales reports", "/vendor/reports");
    }

    /** The inventory page, optionally filtered (LOW_STOCK, OUT_OF_STOCK, IN_STOCK) or searched by product name. */
    public static PageLink vendorInventory(String filter, String productName) {
        return link(productName == null ? "View inventory" : "View stock of " + productName, "/vendor/inventory",
                "q", productName, "filter", filter);
    }

    public static PageLink vendorProducts() {
        return link("View my products", "/vendor/products");
    }

    public static PageLink vendorPricing(String productName) {
        return link(productName == null ? "View prices" : "View the price of " + productName, "/vendor/pricing",
                "q", productName);
    }

    public static PageLink vendorOrders() {
        return link("View store orders", "/vendor/orders");
    }

    public static PageLink purchaseOrders() {
        return link("View purchase orders", "/vendor/purchase-orders");
    }

    public static PageLink purchaseOrder(UUID purchaseOrderId) {
        return link("Open the purchase order", "/vendor/purchase-orders/" + purchaseOrderId);
    }

    public static PageLink suppliers() {
        return link("View suppliers", "/vendor/suppliers");
    }

    public static PageLink vendorReturns() {
        return link("View return requests", "/vendor/returns");
    }

    public static PageLink vendorReviews() {
        return link("View reviews", "/vendor/reviews");
    }

    // ---- restaurant ----------------------------------------------------------------------------------------------

    public static PageLink restaurantOrders() {
        return link("Open the order queue", "/restaurant/orders");
    }

    public static PageLink restaurantReports() {
        return link("View sales reports", "/restaurant/reports");
    }

    public static PageLink restaurantMenu() {
        return link("Open the menu", "/restaurant/menu");
    }

    public static PageLink restaurantReturns() {
        return link("View reported problems", "/restaurant/returns");
    }

    public static PageLink restaurantReviews() {
        return link("View reviews", "/restaurant/reviews");
    }

    // ---- delivery ------------------------------------------------------------------------------------------------

    public static PageLink myDeliveries() {
        return link("Open my deliveries", "/delivery");
    }

    public static PageLink deliveryHistory() {
        return link("View delivery history", "/delivery/history");
    }

    /** The delivery managers' monitor, optionally filtered by status or to deliveries without a partner. */
    public static PageLink deliveryMonitor(String status, boolean withoutPartner) {
        return link("Open the delivery monitor", "/admin/deliveries", "status", status,
                "noPartner", withoutPartner ? "1" : null);
    }

    /** The delivery monitor searched for one order. */
    public static PageLink delivery(String orderNumber) {
        return link("Open delivery " + orderNumber, "/admin/deliveries", "q", orderNumber);
    }

    // ---- what each assistant may link to in its own words -----------------------------------------------------------

    public static final List<PageLink> SHOPPING_PAGES = List.of(
            new PageLink("My cart", "/cart"),
            new PageLink("Checkout", "/checkout"),
            new PageLink("My orders", "/orders"),
            new PageLink("My returns", "/account/returns"),
            new PageLink("My addresses", "/account/addresses"),
            new PageLink("Search products", "/search"),
            new PageLink("Restaurants", "/restaurants"));

    public static final List<PageLink> PAYMENT_PAGES = List.of(
            new PageLink("My orders", "/orders"),
            new PageLink("Orders waiting for payment", "/orders?status=AWAITING_PAYMENT"),
            new PageLink("Help & support", "/account/complaints"));

    public static final List<PageLink> SUPPORT_PAGES = List.of(
            new PageLink("My orders", "/orders"),
            new PageLink("My returns", "/account/returns"),
            new PageLink("Help & support", "/account/complaints"));

    public static final List<PageLink> ADMIN_PAGES = List.of(
            new PageLink("Orders", "/admin/orders"),
            new PageLink("Payments", "/admin/payments"),
            new PageLink("Stuck orders", "/admin/operations?tab=stuck-orders"),
            new PageLink("Reports", "/admin/reports"),
            new PageLink("Vendors", "/admin/vendors"),
            new PageLink("Restaurants", "/admin/restaurants"),
            new PageLink("Delivery partners", "/admin/delivery-partners"),
            new PageLink("Customers", "/admin/customers"),
            new PageLink("Onboarding approvals", "/admin/approvals"),
            new PageLink("Catalog", "/admin/catalog"),
            new PageLink("AI approvals", "/admin/ai/approvals"));

    public static final List<PageLink> VENDOR_PAGES = List.of(
            new PageLink("Inventory", "/vendor/inventory"),
            new PageLink("Prices", "/vendor/pricing"),
            new PageLink("Products", "/vendor/products"),
            new PageLink("Store orders", "/vendor/orders"),
            new PageLink("Purchase orders", "/vendor/purchase-orders"),
            new PageLink("Sales reports", "/vendor/reports"));

    public static final List<PageLink> RESTAURANT_PAGES = List.of(
            new PageLink("Order queue", "/restaurant/orders"),
            new PageLink("Menu", "/restaurant/menu"),
            new PageLink("Sales reports", "/restaurant/reports"),
            new PageLink("Reviews", "/restaurant/reviews"));

    public static final List<PageLink> DELIVERY_PAGES = List.of(
            new PageLink("My deliveries", "/delivery"),
            new PageLink("Delivery history", "/delivery/history"),
            new PageLink("Delivery monitor (managers)", "/admin/deliveries"));

    /** The system prompt rule that lets the model link to these pages, and only to these. */
    public static String linkRule(List<PageLink> pages) {
        String list = pages.stream().map(page -> "[" + page.label() + "](" + page.path() + ")")
                .collect(Collectors.joining(", "));
        return "- When it helps the user, point them to a page of the app with a markdown link. Use only these "
                + "links, exactly as written: " + list + ". Never invent other links or web addresses.\n";
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A link to {@code path} with the given query parameters (name, value pairs); null or blank values are left out.
     * Values are URL-encoded (a space becomes "+"), so whatever the user searched for cannot change the path.
     */
    private static PageLink link(String label, String path, Object... query) {
        StringJoiner params = new StringJoiner("&");
        for (int i = 0; i + 1 < query.length; i += 2) {
            Object value = query[i + 1];
            if (value != null && !value.toString().isBlank()) {
                params.add(query[i] + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
            }
        }
        return new PageLink(label, params.length() == 0 ? path : path + "?" + params);
    }

    /** 50.00 → "50" and 49.50 → "49.5", as a person would type it into the price filter. */
    private static String plain(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
