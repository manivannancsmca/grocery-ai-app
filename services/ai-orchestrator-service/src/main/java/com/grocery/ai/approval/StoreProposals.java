package com.grocery.ai.approval;

import com.grocery.ai.approval.ProposalBuilder.Outcome;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import com.grocery.ai.tool.ToolArgs;
import com.grocery.ai.tool.VendorTools;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The server-built proposals of the vendor's and restaurant owner's own changes (Phase 12 §6), all decided by the
 * requester. Like every proposal they read fresh data through the gateway with the requester's rights, fix the exact
 * call and render the summary from that data:
 * <ul>
 *   <li>ADJUST_STOCK: the product's current stock → product name, current and new stock (a decrease below what is
 *       available or a count below what is reserved is refused at once);</li>
 *   <li>CHANGE_PRICE: the live price → old and new price, the change in % and whether pricing-service will still
 *       wait for an admin (above its approval threshold);</li>
 *   <li>SEND_PURCHASE_ORDER: the DRAFT purchase order → supplier, lines and total;</li>
 *   <li>SET_MENU_ITEM_AVAILABILITY: the dish from the menu; ACCEPT / REJECT_STORE_ORDER: the NEW store order.</li>
 * </ul>
 */
@Component
public class StoreProposals {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final int MAX_ON_HAND = 1_000_000;
    static final BigDecimal DEFAULT_PRICE_THRESHOLD = new BigDecimal("20");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final BusinessApi api;

    public StoreProposals(BusinessApi api) {
        this.api = api;
    }

    // ---- ADJUST_STOCK -------------------------------------------------------------------------------------------

    Outcome adjustStock(CallContext context, ToolArgs args) {
        UUID productId = args.uuid("productId");
        Fetch fetched = api.get(context, "/api/vendor/inventory/" + productId);
        if (fetched instanceof Fetch.Problem problem) {
            return ProposalBuilder.failed(problem);
        }
        JsonNode stock = ((Fetch.Ok) fetched).body();
        String product = productLabel(stock, "productName", productId);
        if ("ARCHIVED".equals(Json.text(stock, "productStatus"))) {
            return new Outcome.Failed("PRODUCT_ARCHIVED", product + " is archived; its stock cannot change.", 409);
        }
        int onHand = Objects.requireNonNullElse(Json.integer(stock, "onHand"), 0);
        int reserved = Objects.requireNonNullElse(Json.integer(stock, "reserved"), 0);
        int available = Objects.requireNonNullElse(Json.integer(stock, "available"), onHand - reserved);
        String type = args.string("type");
        int quantity = args.integer("quantity");
        int newOnHand = switch (type) {
            case "INCREASE" -> onHand + quantity;
            case "DECREASE" -> onHand - quantity;
            default -> quantity;
        };
        if ("DECREASE".equals(type) && quantity > available) {
            return new Outcome.Invalid("Only " + available + " of " + product + " are available (" + reserved
                    + " reserved for orders), so the stock can go down by at most " + available + ".");
        }
        if ("SET".equals(type) && quantity < reserved) {
            return new Outcome.Invalid(product + " has " + reserved + " units reserved for orders; the stock on hand "
                    + "cannot be set below " + reserved + ".");
        }
        if (newOnHand > MAX_ON_HAND) {
            return new Outcome.Invalid("The stock on hand of " + product + " may not exceed " + MAX_ON_HAND + ".");
        }

        TreeMap<String, Object> body = new TreeMap<>();
        if (args.string("note") != null) {
            body.put("note", args.string("note"));
        }
        body.put("quantity", quantity);
        body.put("reason", args.string("reason"));
        body.put("type", type);

        int delta = newOnHand - onHand;
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(ProposalBuilder.line("Product", product));
        if (Json.text(stock, "sku") != null) {
            lines.add(ProposalBuilder.line("SKU", Json.text(stock, "sku")));
        }
        lines.add(ProposalBuilder.line("Current stock", onHand + " on hand · " + reserved + " reserved · "
                + available + " available"));
        lines.add(ProposalBuilder.line("Change", switch (type) {
            case "INCREASE" -> "+" + quantity;
            case "DECREASE" -> "−" + quantity;
            default -> "Set to " + quantity + " (" + (delta == 0 ? "no change" : signed(delta)) + ")";
        }));
        lines.add(ProposalBuilder.line("New stock", newOnHand + " on hand · " + (newOnHand - reserved)
                + " available"));
        lines.add(ProposalBuilder.line("Reason", words(args.string("reason"))));
        if (args.string("note") != null) {
            lines.add(ProposalBuilder.line("Note", args.string("note")));
        }
        String title = switch (type) {
            case "INCREASE" -> "Increase stock of " + product + " by " + quantity;
            case "DECREASE" -> "Decrease stock of " + product + " by " + quantity;
            default -> "Set stock of " + product + " to " + quantity;
        };
        return ProposalBuilder.ready(new ProposalDraft(ApprovalAction.ADJUST_STOCK, null, "POST",
                "/api/vendor/inventory/" + productId + "/adjustments", body, title, lines, null, null));
    }

    // ---- CHANGE_PRICE -------------------------------------------------------------------------------------------

    Outcome changePrice(CallContext context, ToolArgs args) {
        UUID productId = args.uuid("productId");
        Fetch fetched = api.get(context, "/api/vendor/prices/" + productId);
        if (fetched instanceof Fetch.Problem problem) {
            return ProposalBuilder.failed(problem);
        }
        JsonNode view = ((Fetch.Ok) fetched).body();
        String product = productLabel(view, "productName", productId);
        if (Json.at(view, "pendingRequest") != null) {
            BigDecimal requested = Json.decimal(view, "pendingRequest.requestedSellingPrice");
            return new Outcome.Failed("PRICE_CHANGE_PENDING", "A price change of " + product
                    + (requested == null ? "" : " to " + Money.inr(requested))
                    + " already waits for an admin; cancel it first.", 409);
        }
        BigDecimal oldSelling = Money.scale(Json.decimal(view, "price.sellingPrice"));
        BigDecimal oldMrp = Money.scale(Json.decimal(view, "price.mrp"));
        BigDecimal selling = args.decimal("sellingPrice");
        BigDecimal mrp = args.decimal("mrp") != null ? args.decimal("mrp") : oldMrp;
        if (mrp == null) {
            return new Outcome.Invalid(product + " has no price yet: give the MRP too.");
        }
        if (selling.compareTo(mrp) > 0) {
            return new Outcome.Invalid("The selling price " + Money.inr(selling) + " may not be above the MRP "
                    + Money.inr(mrp) + ".");
        }
        if (oldSelling != null && selling.compareTo(oldSelling) == 0 && oldMrp != null
                && mrp.compareTo(oldMrp) == 0) {
            return new Outcome.Failed("PRICE_UNCHANGED", product + " already costs " + Money.inr(selling)
                    + " (MRP " + Money.inr(mrp) + ").", 409);
        }
        BigDecimal threshold = Objects.requireNonNullElse(Json.decimal(view, "approvalThresholdPercent"),
                DEFAULT_PRICE_THRESHOLD);
        BigDecimal changePercent = oldSelling == null || oldSelling.signum() == 0 ? null
                : selling.subtract(oldSelling).multiply(BigDecimal.valueOf(100))
                .divide(oldSelling, 2, RoundingMode.HALF_UP);
        boolean needsAdmin = changePercent != null && changePercent.abs().compareTo(threshold) > 0;

        TreeMap<String, Object> body = new TreeMap<>();
        body.put("mrp", mrp);
        if (args.string("note") != null) {
            body.put("note", args.string("note"));
        }
        body.put("sellingPrice", selling);

        String thresholdText = threshold.stripTrailingZeros().toPlainString() + " %";
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(ProposalBuilder.line("Product", product));
        lines.add(ProposalBuilder.line("Current price", oldSelling == null ? "No price yet"
                : Money.inr(oldSelling) + " (MRP " + Money.inr(oldMrp) + ")"));
        lines.add(ProposalBuilder.line("New price", Money.inr(selling) + " (MRP " + Money.inr(mrp) + ")"));
        lines.add(ProposalBuilder.line("Change", changePercent == null ? "First price"
                : (changePercent.signum() > 0 ? "+" : "") + changePercent.toPlainString() + " %"));
        lines.add(ProposalBuilder.line("Admin approval", needsAdmin
                ? "Needed: the change is above the " + thresholdText + " threshold, so after you approve, pricing "
                + "waits for an admin and the live price stays until then"
                : changePercent == null ? "Not needed (first price)"
                : "Not needed (within the " + thresholdText + " threshold)"));
        if (args.string("note") != null) {
            lines.add(ProposalBuilder.line("Note", args.string("note")));
        }
        return ProposalBuilder.ready(new ProposalDraft(ApprovalAction.CHANGE_PRICE, null, "PUT",
                "/api/vendor/prices/" + productId, body, "Change price of " + product + " to " + Money.inr(selling),
                lines, selling, "INR"));
    }

    // ---- SEND_PURCHASE_ORDER ------------------------------------------------------------------------------------

    Outcome sendPurchaseOrder(CallContext context, ToolArgs args) {
        String number = args.string("purchaseOrderNumber");
        Fetch fetched = api.purchaseOrder(context, number);
        if (fetched instanceof Fetch.Problem problem) {
            return ProposalBuilder.failed(problem);
        }
        JsonNode order = ((Fetch.Ok) fetched).body();
        String status = Json.text(order, "status");
        if (!"DRAFT".equals(status)) {
            return new Outcome.Failed("INVALID_STATUS_TRANSITION", "Purchase order " + number + " is " + status
                    + "; only a draft can be sent.", 409);
        }
        String supplier = VendorTools.supplierName(order);
        if (supplier == null && Json.text(order, "supplierId") != null
                && api.get(context, "/api/vendor/suppliers/" + Json.text(order, "supplierId")) instanceof Fetch.Ok ok) {
            supplier = Json.text(ok.body(), "name");
        }
        supplier = Objects.requireNonNullElse(supplier, Objects.requireNonNullElse(Json.text(order, "supplierId"),
                "the supplier"));
        BigDecimal total = VendorTools.purchaseOrderTotal(order);

        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(ProposalBuilder.line("Purchase order", number));
        lines.add(ProposalBuilder.line("Supplier", supplier));
        for (JsonNode line : Json.list(order, "lines")) {
            BigDecimal cost = Money.scale(Objects.requireNonNullElse(Json.decimal(line, "unitCost"), BigDecimal.ZERO));
            int quantity = Objects.requireNonNullElse(Json.integer(line, "orderedQuantity"), 0);
            lines.add(ProposalBuilder.line(Objects.requireNonNullElse(Json.text(line, "productName"),
                            Json.text(line, "productId")) + " × " + quantity + " @ " + Money.inr(cost),
                    Money.inr(cost.multiply(BigDecimal.valueOf(quantity)))));
        }
        if (Json.text(order, "expectedAt") != null) {
            lines.add(ProposalBuilder.line("Expected", Json.text(order, "expectedAt")));
        }
        lines.add(ProposalBuilder.line("Total", Money.inr(total)));
        return ProposalBuilder.ready(new ProposalDraft(ApprovalAction.SEND_PURCHASE_ORDER, null, "POST",
                "/api/vendor/purchase-orders/" + Json.text(order, "id") + "/send", new TreeMap<>(),
                "Send purchase order " + number + " to " + supplier, lines, total, total == null ? null : "INR"));
    }

    // ---- SET_MENU_ITEM_AVAILABILITY -----------------------------------------------------------------------------

    Outcome setMenuItemAvailability(CallContext context, ToolArgs args) {
        UUID menuItemId = args.uuid("menuItemId");
        boolean available = Boolean.TRUE.equals(args.bool("available"));
        Fetch fetched = api.get(context, "/api/restaurant/menu");
        if (fetched instanceof Fetch.Problem problem) {
            return ProposalBuilder.failed(problem);
        }
        JsonNode item = null;
        String category = null;
        for (JsonNode section : Json.list(((Fetch.Ok) fetched).body(), "categories")) {
            for (JsonNode candidate : Json.list(section, "items")) {
                if (menuItemId.toString().equals(Json.text(candidate, "id"))) {
                    item = candidate;
                    category = Json.text(section, "name");
                }
            }
        }
        if (item == null) {
            return new Outcome.Failed("MENU_ITEM_NOT_FOUND", "There is no dish " + menuItemId + " on your menu.",
                    404);
        }
        String dish = Objects.requireNonNullElse(Json.text(item, "name"), menuItemId.toString());
        boolean now = Json.bool(item, "available");
        if (now == available) {
            return new Outcome.Failed("MENU_ITEM_UNCHANGED", dish + " is already "
                    + (available ? "available." : "sold out."), 409);
        }
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("available", available);
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(ProposalBuilder.line("Dish", dish));
        if (category != null) {
            lines.add(ProposalBuilder.line("Category", category));
        }
        lines.add(ProposalBuilder.line("Price", Money.inr(Json.decimal(item, "price"))));
        lines.add(ProposalBuilder.line("Now", now ? "Available" : "Sold out"));
        lines.add(ProposalBuilder.line("Change to", available ? "Available" : "Sold out"));
        return ProposalBuilder.ready(new ProposalDraft(ApprovalAction.SET_MENU_ITEM_AVAILABILITY, null, "PATCH",
                "/api/restaurant/menu/items/" + menuItemId + "/availability", body,
                available ? "Mark " + dish + " available again" : "Mark " + dish + " sold out", lines, null, null));
    }

    // ---- ACCEPT_STORE_ORDER / REJECT_STORE_ORDER ----------------------------------------------------------------

    Outcome acceptOrder(CallContext context, ToolArgs args) {
        String number = args.string("orderNumber");
        int readyInMinutes = args.integer("readyInMinutes");
        Fetch fetched = api.storeOrder(context, "/api/restaurant/orders", number);
        if (fetched instanceof Fetch.Problem problem) {
            return ProposalBuilder.failed(problem);
        }
        JsonNode order = ((Fetch.Ok) fetched).body();
        Outcome notNew = requireNew(order, number, "accepted");
        if (notNew != null) {
            return notNew;
        }
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("readyInMinutes", readyInMinutes);
        List<ApprovalView.Line> lines = storeOrderLines(order);
        lines.add(ProposalBuilder.line("Ready in", readyInMinutes + " min"));
        return ProposalBuilder.ready(new ProposalDraft(ApprovalAction.ACCEPT_STORE_ORDER, null, "POST",
                "/api/restaurant/orders/" + Json.text(order, "orderId") + "/accept", body,
                "Accept order " + number + " (ready in " + readyInMinutes + " min)", lines,
                Money.scale(Json.decimal(order, "total")), currency(order)));
    }

    Outcome rejectOrder(CallContext context, ToolArgs args) {
        String number = args.string("orderNumber");
        Fetch fetched = api.storeOrder(context, "/api/restaurant/orders", number);
        if (fetched instanceof Fetch.Problem problem) {
            return ProposalBuilder.failed(problem);
        }
        JsonNode order = ((Fetch.Ok) fetched).body();
        Outcome notNew = requireNew(order, number, "rejected");
        if (notNew != null) {
            return notNew;
        }
        TreeMap<String, Object> body = new TreeMap<>();
        if (args.string("note") != null) {
            body.put("note", args.string("note"));
        }
        body.put("reason", args.string("reason"));
        List<ApprovalView.Line> lines = storeOrderLines(order);
        lines.add(ProposalBuilder.line("Reason", words(args.string("reason"))));
        if (args.string("note") != null) {
            lines.add(ProposalBuilder.line("Note", args.string("note")));
        }
        lines.add(ProposalBuilder.line("Customer", "Refunded in full when it is rejected"));
        return ProposalBuilder.ready(new ProposalDraft(ApprovalAction.REJECT_STORE_ORDER, null, "POST",
                "/api/restaurant/orders/" + Json.text(order, "orderId") + "/reject", body, "Reject order " + number,
                lines, Money.scale(Json.decimal(order, "total")), currency(order)));
    }

    private static Outcome requireNew(JsonNode order, String number, String verb) {
        String status = Json.text(order, "status");
        if (!"NEW".equals(status)) {
            return new Outcome.Failed("STORE_ORDER_NOT_PENDING", "Order " + number + " is " + status
                    + "; only a new order can be " + verb + ".", 409);
        }
        return null;
    }

    /** Order, the customer's area, the items, total and the acceptance deadline (India time). */
    private static List<ApprovalView.Line> storeOrderLines(JsonNode order) {
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(ProposalBuilder.line("Order", Json.text(order, "orderNumber")));
        if (Json.text(order, "customer.area") != null) {
            lines.add(ProposalBuilder.line("Customer area", Json.text(order, "customer.area")));
        }
        for (JsonNode item : Json.list(order, "items")) {
            lines.add(ProposalBuilder.line(Json.text(item, "name") + " × " + Json.integer(item, "quantity"),
                    Money.inr(Json.decimal(item, "lineTotal"))));
        }
        lines.add(ProposalBuilder.line("Total", Money.inr(Json.decimal(order, "total"))));
        if (Json.text(order, "note") != null) {
            lines.add(ProposalBuilder.line("Customer's note", Json.text(order, "note")));
        }
        String acceptBy = time(Json.text(order, "acceptBy"));
        if (acceptBy != null) {
            lines.add(ProposalBuilder.line("Accept by", acceptBy));
        }
        return lines;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** "Tomato (500 g)". */
    static String productLabel(JsonNode view, String nameField, UUID fallback) {
        String name = Objects.requireNonNullElse(Json.text(view, nameField), fallback.toString());
        String unit = Json.text(view, "unitLabel");
        return unit == null || unit.isBlank() ? name : name + " (" + unit + ")";
    }

    /** STOCK_COUNT → "Stock count". */
    static String words(String enumValue) {
        if (enumValue == null || enumValue.isEmpty()) {
            return "—";
        }
        String text = enumValue.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String signed(int delta) {
        return delta > 0 ? "+" + delta : "−" + (-delta);
    }

    /** HH:mm in India time, or null. */
    static String time(String isoInstant) {
        if (isoInstant == null) {
            return null;
        }
        try {
            return HH_MM.format(Instant.parse(isoInstant).atZone(INDIA)) + " (India)";
        } catch (RuntimeException e) {
            return isoInstant;
        }
    }

    private static String currency(JsonNode order) {
        return Objects.requireNonNullElse(Json.text(order, "currency"), "INR");
    }
}
