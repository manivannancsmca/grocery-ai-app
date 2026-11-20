package com.grocery.ai.approval;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import com.grocery.ai.tool.ToolArgs;
import com.grocery.common.security.CurrentUser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the parameters of a sensitive action on the server (contract §4 "Proposal"): it reads fresh data through the
 * gateway with the requester's rights, fixes the exact method, path and body, and renders the summary from them.
 * <ul>
 *   <li>PLACE_ORDER: a fresh checkout preview gives {@code expectedTotal} and {@code expectedCartUpdatedAt}; the
 *       previewed address is pinned so the order goes where the summary says.</li>
 *   <li>REFUND_PAYMENT: the order and its payment are resolved first; more than what is still refundable is
 *       INVALID_ARGUMENTS. Without an amount, everything still refundable is proposed explicitly.</li>
 *   <li>Cancellations and suspensions: the order / business is resolved and must currently allow the action.</li>
 *   <li>REQUEST_RETURN (Phase 12): the delivered order; the items named by the model are matched to its lines and the
 *       refund is estimated for the summary.</li>
 *   <li>REASSIGN_DELIVERY (Phase 12): the delivery (searching or assigned), its current partner and the target
 *       partner (online and free).</li>
 *   <li>The vendor's and restaurant owner's own changes: {@link StoreProposals}.</li>
 * </ul>
 */
@Component
public class ProposalBuilder {

    private final BusinessApi api;
    private final AiProperties properties;
    private final StoreProposals store;

    public ProposalBuilder(BusinessApi api, AiProperties properties, StoreProposals store) {
        this.api = api;
        this.properties = properties;
        this.store = store;
    }

    /** The outcome of building a proposal. */
    public sealed interface Outcome {
        record Ready(ProposalDraft draft) implements Outcome {
        }

        /** The model asked for something impossible (ToolGuard: BLOCKED INVALID_ARGUMENTS). */
        record Invalid(String message) implements Outcome {
        }

        /** The data could not be read or does not allow the action (tool result ERROR with this code). */
        record Failed(String code, String message, Integer httpStatus) implements Outcome {
        }
    }

    public Outcome build(ApprovalAction action, CallContext context, CurrentUser user, ToolArgs args) {
        return switch (action) {
            case PLACE_ORDER -> placeOrder(context, user, args);
            case CANCEL_ORDER -> cancelOrder(context, user, args);
            case REFUND_PAYMENT -> refund(context, args);
            case ADMIN_CANCEL_ORDER -> adminCancel(context, args);
            case SUSPEND_VENDOR -> suspend(context, args, action, "vendorId", "/api/admin/vendors/", "vendor",
                    "businessName", "VENDOR_NOT_FOUND");
            case SUSPEND_RESTAURANT -> suspend(context, args, action, "restaurantId", "/api/admin/restaurants/",
                    "restaurant", "name", "RESTAURANT_NOT_FOUND");
            case REQUEST_RETURN -> requestReturn(context, user, args);
            case REASSIGN_DELIVERY -> reassignDelivery(context, args);
            case ADJUST_STOCK -> store.adjustStock(context, args);
            case CHANGE_PRICE -> store.changePrice(context, args);
            case SEND_PURCHASE_ORDER -> store.sendPurchaseOrder(context, args);
            case SET_MENU_ITEM_AVAILABILITY -> store.setMenuItemAvailability(context, args);
            case ACCEPT_STORE_ORDER -> store.acceptOrder(context, args);
            case REJECT_STORE_ORDER -> store.rejectOrder(context, args);
        };
    }

    // ---- PLACE_ORDER --------------------------------------------------------------------------------------------

    private Outcome placeOrder(CallContext context, CurrentUser user, ToolArgs args) {
        UUID addressId = args.uuid("addressId");
        Map<String, Object> query = new TreeMap<>();
        if (addressId != null) {
            query.put("addressId", addressId);
        }
        Fetch fetched = api.get(context, "/api/cart/preview", query);
        if (fetched instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        JsonNode preview = ((Fetch.Ok) fetched).body();
        if (!Json.bool(preview, "canCheckout")) {
            String issues = Json.list(preview, "issues").stream()
                    .filter(issue -> Json.bool(issue, "blocking"))
                    .map(issue -> Objects.requireNonNullElse(Json.text(issue, "message"), Json.text(issue, "code")))
                    .collect(Collectors.joining("; "));
            return new Outcome.Failed("CHECKOUT_BLOCKED", "The order cannot be placed right now"
                    + (issues.isBlank() ? "." : ": " + issues + "."), 409);
        }
        BigDecimal total = Money.scale(Json.decimal(preview, "total"));
        String cartUpdatedAt = Json.text(preview, "updatedAt");
        if (total == null || cartUpdatedAt == null) {
            return new Outcome.Failed("CHECKOUT_BLOCKED", "The checkout preview is incomplete. Please try again.",
                    null);
        }
        UUID pinnedAddress = addressId != null ? addressId : Json.uuid(preview, "address.id");

        TreeMap<String, Object> body = new TreeMap<>();
        if (pinnedAddress != null) {
            body.put("addressId", pinnedAddress.toString());
        }
        body.put("expectedCartUpdatedAt", cartUpdatedAt);
        body.put("expectedTotal", total);
        if (args.string("note") != null) {
            body.put("note", args.string("note"));
        }

        String store = Objects.requireNonNullElse(Json.text(preview, "store.name"), "the store");
        int itemCount = Objects.requireNonNullElse(Json.integer(preview, "itemCount"), 0);
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(line("Store", store));
        for (JsonNode item : Json.list(preview, "lines")) {
            BigDecimal unitPrice = Json.decimal(item, "unitPrice");
            lines.add(line(Json.text(item, "name") + " × " + Json.integer(item, "quantity")
                            + (unitPrice == null ? "" : " @ " + Money.inr(unitPrice)),
                    Money.inr(Json.decimal(item, "lineTotal"))));
        }
        BigDecimal fee = Json.decimal(preview, "delivery.fee");
        lines.add(line("Subtotal", Money.inr(Json.decimal(preview, "subtotal"))));
        lines.add(line("Delivery fee", fee == null || fee.signum() == 0 ? "Free" : Money.inr(fee)));
        lines.add(line("Total", Money.inr(total)));
        String address = address(Json.at(preview, "address"));
        if (address != null) {
            lines.add(line("Deliver to", address));
        }
        if (args.string("note") != null) {
            lines.add(line("Note", args.string("note")));
        }
        String title = "Place order: " + itemCount + (itemCount == 1 ? " item" : " items") + " from " + store;
        return ready(new ProposalDraft(ApprovalAction.PLACE_ORDER, user.userId(), "POST", "/api/orders", body, title,
                lines, total, Objects.requireNonNullElse(Json.text(preview, "currency"), "INR")));
    }

    private static String address(JsonNode address) {
        if (address == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String field : List.of("label", "line1", "city")) {
            String value = Json.text(address, field);
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // ---- CANCEL_ORDER / ADMIN_CANCEL_ORDER ----------------------------------------------------------------------

    private Outcome cancelOrder(CallContext context, CurrentUser user, ToolArgs args) {
        String number = args.string("orderNumber");
        Fetch fetched = api.customerOrder(context, number);
        if (fetched instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        JsonNode order = ((Fetch.Ok) fetched).body();
        Outcome notCancellable = requireCancellable(order, number, "cancellable");
        if (notCancellable != null) {
            return notCancellable;
        }
        TreeMap<String, Object> body = new TreeMap<>();
        String reason = args.string("reason");
        if (reason != null) {
            body.put("reason", reason);
        }
        List<ApprovalView.Line> lines = orderLines(order);
        lines.add(line("Reason", reason == null ? "—" : reason));
        return ready(new ProposalDraft(ApprovalAction.CANCEL_ORDER, user.userId(), "POST",
                "/api/orders/" + Json.text(order, "id") + "/cancel", body, "Cancel order " + number, lines,
                Money.scale(Json.decimal(order, "total")), currency(order)));
    }

    private Outcome adminCancel(CallContext context, ToolArgs args) {
        String number = args.string("orderNumber");
        Fetch fetched = api.adminOrder(context, number);
        if (fetched instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        JsonNode order = ((Fetch.Ok) fetched).body();
        Outcome notCancellable = requireCancellable(order, number,
                order.has("adminCancellable") ? "adminCancellable" : "cancellable");
        if (notCancellable != null) {
            return notCancellable;
        }
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("reason", args.string("reason"));
        List<ApprovalView.Line> lines = orderLines(order);
        if (Json.text(order, "customerId") != null) {
            lines.add(1, line("Customer", Json.text(order, "customerId")));
        }
        lines.add(line("Reason", args.string("reason")));
        return ready(new ProposalDraft(ApprovalAction.ADMIN_CANCEL_ORDER, null, "POST",
                "/api/admin/orders/" + Json.text(order, "id") + "/cancel", body, "Cancel order " + number + " (admin)",
                lines, Money.scale(Json.decimal(order, "total")), currency(order)));
    }

    private static Outcome requireCancellable(JsonNode order, String number, String field) {
        if (order.has(field) && !Json.bool(order, field)) {
            return new Outcome.Failed("ORDER_NOT_CANCELLABLE", "Order " + number + " is "
                    + Json.text(order, "status") + " and can no longer be cancelled.", 409);
        }
        return null;
    }

    private static List<ApprovalView.Line> orderLines(JsonNode order) {
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(line("Order", Json.text(order, "orderNumber")));
        lines.add(line("Store", Objects.requireNonNullElse(Json.text(order, "store.name"), "—")));
        lines.add(line("Status", Json.text(order, "status")));
        lines.add(line("Total", Money.inr(Json.decimal(order, "total"))));
        return lines;
    }

    // ---- REFUND_PAYMENT -----------------------------------------------------------------------------------------

    private Outcome refund(CallContext context, ToolArgs args) {
        String number = args.string("orderNumber");
        Fetch orderFetch = api.get(context, "/api/admin/orders", Map.of("q", number, "size", 1));
        if (orderFetch instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        List<JsonNode> orders = Json.list(((Fetch.Ok) orderFetch).body(), "content");
        if (orders.isEmpty()) {
            return new Outcome.Failed("ORDER_NOT_FOUND", "There is no order " + number + ".", 404);
        }
        JsonNode order = orders.getFirst();
        String orderId = Json.text(order, "id");
        Fetch paymentFetch = api.get(context, "/api/admin/payments", Map.of("orderId", orderId, "size", 1));
        if (paymentFetch instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        List<JsonNode> payments = Json.list(((Fetch.Ok) paymentFetch).body(), "content");
        if (payments.isEmpty()) {
            return new Outcome.Failed("PAYMENT_NOT_FOUND", "Order " + number + " has no payment.", 404);
        }
        JsonNode payment = payments.getFirst();
        BigDecimal paid = Money.scale(Objects.requireNonNullElse(Json.decimal(payment, "amount"), BigDecimal.ZERO));
        BigDecimal refunded = Money.scale(Objects.requireNonNullElse(Json.decimal(payment, "refundedAmount"),
                BigDecimal.ZERO));
        BigDecimal pending = Json.list(payment, "refunds").stream()
                .filter(refund -> "PENDING".equals(Json.text(refund, "status")))
                .map(refund -> Objects.requireNonNullElse(Json.decimal(refund, "amount"), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean captured = "CAPTURED".equals(Json.text(payment, "status"));
        BigDecimal refundable = captured ? Money.scale(paid.subtract(refunded).subtract(pending).max(BigDecimal.ZERO))
                : Money.scale(BigDecimal.ZERO);
        BigDecimal amount = args.decimal("amount") != null ? args.decimal("amount") : refundable;
        if (refundable.signum() == 0) {
            return new Outcome.Invalid("Nothing can be refunded for order " + number + " (payment "
                    + Json.text(payment, "status") + ", already refunded " + Money.inr(refunded) + ").");
        }
        if (amount.compareTo(refundable) > 0) {
            return new Outcome.Invalid("The amount " + Money.inr(amount) + " is more than what is still refundable for "
                    + number + " (" + Money.inr(refundable) + ").");
        }
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("amount", amount);
        body.put("note", args.string("note"));
        body.put("orderId", orderId);
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(line("Order", number));
        lines.add(line("Paid amount", Money.inr(paid)));
        lines.add(line("Already refunded", Money.inr(refunded)));
        if (pending.signum() > 0) {
            lines.add(line("Refunds in progress", Money.inr(pending)));
        }
        lines.add(line("Refund amount", Money.inr(amount)));
        lines.add(line("Note", args.string("note")));
        return ready(new ProposalDraft(ApprovalAction.REFUND_PAYMENT, null, "POST", "/api/admin/refunds", body,
                "Refund " + Money.inr(amount) + " for order " + number, lines, amount,
                Objects.requireNonNullElse(Json.text(payment, "currency"), "INR")));
    }

    /** Four-eyes rule of contract §4. */
    public boolean fourEyes(ProposalDraft draft) {
        return switch (draft.action().fourEyes()) {
            case NEVER -> false;
            case ALWAYS -> true;
            case ABOVE_THRESHOLD -> draft.amount() != null
                    && draft.amount().compareTo(properties.approvals().fourEyesRefundAbove()) > 0;
        };
    }

    // ---- SUSPEND_VENDOR / SUSPEND_RESTAURANT --------------------------------------------------------------------

    private Outcome suspend(CallContext context, ToolArgs args, ApprovalAction action, String idArg, String basePath,
                            String kind, String nameField, String notFoundCode) {
        UUID id = args.uuid(idArg);
        Fetch fetched = api.get(context, basePath + id);
        if (fetched instanceof Fetch.Problem problem) {
            if (problem.httpStatus() != null && problem.httpStatus() == 404) {
                return new Outcome.Failed(notFoundCode, "There is no " + kind + " " + id + ".", 404);
            }
            return failed(problem);
        }
        JsonNode business = ((Fetch.Ok) fetched).body();
        String name = Objects.requireNonNullElse(Json.text(business, nameField), id.toString());
        String status = Json.text(business, "status");
        if (!"APPROVED".equals(status)) {
            return new Outcome.Failed("INVALID_STATUS_TRANSITION", "The " + kind + " " + name + " is " + status
                    + "; only an approved " + kind + " can be suspended.", 409);
        }
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("reason", args.string("reason"));
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(line(capitalize(kind), name));
        lines.add(line(capitalize(kind) + " id", id.toString()));
        lines.add(line("Current status", status));
        lines.add(line("Reason", args.string("reason")));
        return ready(new ProposalDraft(action, null, "POST", basePath + id + "/suspend", body,
                "Suspend " + kind + " " + name, lines, null, null));
    }

    // ---- REQUEST_RETURN -----------------------------------------------------------------------------------------

    /**
     * The customer's DELIVERED (and still {@code returnable}) order by its number; each requested item is matched to
     * an order line by {@code itemId} or by name (exact, else every word contained; ambiguous or unknown names and
     * more units than ordered are INVALID_ARGUMENTS). The summary estimates the refund with the contract formula
     * (prorated line total minus the coupon share, plus the paid delivery fee when the whole order comes back); the
     * exact refund is still computed by order-service.
     */
    private Outcome requestReturn(CallContext context, CurrentUser user, ToolArgs args) {
        String number = args.string("orderNumber");
        Fetch fetched = api.customerOrder(context, number);
        if (fetched instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        JsonNode order = ((Fetch.Ok) fetched).body();
        String status = Json.text(order, "status");
        if (!"DELIVERED".equals(status)) {
            return new Outcome.Failed("RETURN_NOT_ALLOWED", "Order " + number + " is " + status
                    + "; only a delivered order can be returned.", 422);
        }
        if (order.has("returnable") && !Json.bool(order, "returnable")) {
            return new Outcome.Failed("RETURN_NOT_ALLOWED", "Order " + number + " can no longer be returned: the "
                    + "48-hour return window is over, everything was returned already, or a return request is still "
                    + "open.", 422);
        }
        List<JsonNode> orderItems = Json.list(order, "items");
        Map<String, Integer> chosen = new LinkedHashMap<>();
        Map<String, JsonNode> lineById = new LinkedHashMap<>();
        for (ToolArgs wanted : args.list("items")) {
            List<JsonNode> matches = matchItem(orderItems, wanted);
            String asked = wanted.uuid("itemId") != null ? wanted.uuid("itemId").toString() : wanted.string("name");
            if (matches.isEmpty()) {
                return new Outcome.Invalid("Order " + number + " has no item “" + asked + "” (it has: "
                        + itemNames(orderItems) + ").");
            }
            if (matches.size() > 1) {
                return new Outcome.Invalid("“" + asked + "” matches several items of order " + number + ": "
                        + itemNames(matches) + ". Name exactly one.");
            }
            JsonNode item = matches.getFirst();
            String itemId = Json.text(item, "itemId");
            String name = Json.text(item, "name");
            if (chosen.containsKey(itemId)) {
                return new Outcome.Invalid(name + " is listed twice; give it once with the total quantity.");
            }
            int ordered = Objects.requireNonNullElse(Json.integer(item, "quantity"), 0);
            int quantity = wanted.integer("quantity");
            if (quantity > ordered) {
                return new Outcome.Invalid("Only " + ordered + " × " + name + " were ordered in " + number + ".");
            }
            chosen.put(itemId, quantity);
            lineById.put(itemId, item);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        chosen.forEach((itemId, quantity) -> {
            TreeMap<String, Object> entry = new TreeMap<>();
            entry.put("itemId", itemId);
            entry.put("quantity", quantity);
            items.add(entry);
        });
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("items", items);
        if (args.string("note") != null) {
            body.put("note", args.string("note"));
        }
        body.put("reason", args.string("reason"));

        BigDecimal subtotal = Json.decimal(order, "subtotal");
        BigDecimal discount = Objects.requireNonNullElse(Json.decimal(order, "discount"), BigDecimal.ZERO);
        BigDecimal keep = subtotal == null || subtotal.signum() == 0 || discount.signum() == 0 ? BigDecimal.ONE
                : BigDecimal.ONE.subtract(discount.divide(subtotal, 10, RoundingMode.HALF_UP));
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(line("Order", Json.text(order, "orderNumber")));
        lines.add(line("Store", Objects.requireNonNullElse(Json.text(order, "store.name"), "—")));
        BigDecimal estimate = BigDecimal.ZERO;
        int units = 0;
        boolean everything = Json.list(order, "returns").isEmpty();
        for (JsonNode item : orderItems) {
            String itemId = Json.text(item, "itemId");
            int ordered = Objects.requireNonNullElse(Json.integer(item, "quantity"), 0);
            if (!chosen.containsKey(itemId)) {
                everything = false;
                continue;
            }
            int quantity = chosen.get(itemId);
            everything &= quantity == ordered;
            units += quantity;
            BigDecimal lineTotal = Objects.requireNonNullElse(Json.decimal(item, "lineTotal"), BigDecimal.ZERO);
            BigDecimal refund = ordered == 0 ? BigDecimal.ZERO : lineTotal.multiply(BigDecimal.valueOf(quantity))
                    .multiply(keep).divide(BigDecimal.valueOf(ordered), 2, RoundingMode.HALF_UP);
            estimate = estimate.add(refund);
            lines.add(line(Json.text(item, "name") + " × " + quantity + " of " + ordered, Money.inr(refund)));
        }
        if (everything) {
            BigDecimal fee = Objects.requireNonNullElse(Json.decimal(order, "deliveryFee"), BigDecimal.ZERO)
                    .subtract(Objects.requireNonNullElse(Json.decimal(order, "deliveryFeeDiscount"), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            if (fee.signum() > 0) {
                lines.add(line("Delivery fee (whole order returned)", Money.inr(fee)));
                estimate = estimate.add(fee);
            }
        }
        estimate = Money.scale(estimate);
        lines.add(line("Reason", reasonWords(args.string("reason"))));
        if (args.string("note") != null) {
            lines.add(line("Note", args.string("note")));
        }
        lines.add(line("Estimated refund", Money.inr(estimate) + " (the store decides; the exact refund is "
                + "computed by the order service)"));
        String what = units + (units == 1 ? " item" : " items");
        String title = "RESTAURANT".equals(Json.text(order, "type"))
                ? "Report a problem with order " + number + " (" + what + ")"
                : "Return " + what + " from order " + number;
        return ready(new ProposalDraft(ApprovalAction.REQUEST_RETURN, user.userId(), "POST",
                "/api/orders/" + Json.text(order, "id") + "/returns", body, title, lines, estimate, currency(order)));
    }

    /** By {@code itemId}; else by name: exact (ignoring case and spaces), else every word contained (plural-tolerant). */
    static List<JsonNode> matchItem(List<JsonNode> orderItems, ToolArgs wanted) {
        if (wanted.uuid("itemId") != null) {
            String id = wanted.uuid("itemId").toString();
            return orderItems.stream().filter(item -> id.equals(Json.text(item, "itemId"))).toList();
        }
        String name = normalize(wanted.string("name"));
        List<JsonNode> exact = orderItems.stream()
                .filter(item -> name.equals(normalize(Json.text(item, "name")))).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        String[] words = name.split(" ");
        return orderItems.stream().filter(item -> {
            String itemName = normalize(Json.text(item, "name"));
            for (String word : words) {
                if (!itemName.contains(word) && !itemName.contains(singular(word))) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String singular(String word) {
        if (word.length() > 4 && word.endsWith("es")) {
            return word.substring(0, word.length() - 2);
        }
        return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    private static String itemNames(List<JsonNode> items) {
        return items.stream().map(item -> Json.text(item, "name")).collect(Collectors.joining(", "));
    }

    private static String reasonWords(String reason) {
        return switch (reason) {
            case "WRONG_ITEM" -> "Wrong item";
            case "MISSING" -> "Missing item";
            case "QUALITY" -> "Quality problem";
            case "DAMAGED" -> "Damaged";
            default -> "Other";
        };
    }

    // ---- REASSIGN_DELIVERY --------------------------------------------------------------------------------------

    /**
     * The delivery of the order (admin view) must be SEARCHING or ASSIGNED. With {@code partnerId} the partner must be
     * online and free now (else PARTNER_UNAVAILABLE) and not the current one; without it, the delivery searches
     * again without the current partner.
     */
    private Outcome reassignDelivery(CallContext context, ToolArgs args) {
        String number = args.string("orderNumber");
        Fetch fetched = api.adminDelivery(context, number);
        if (fetched instanceof Fetch.Problem problem) {
            return failed(problem);
        }
        JsonNode delivery = ((Fetch.Ok) fetched).body();
        String status = Json.text(delivery, "status");
        if (!"SEARCHING".equals(status) && !"ASSIGNED".equals(status)) {
            return new Outcome.Failed("INVALID_DELIVERY_TRANSITION", "The delivery of order " + number + " is "
                    + status + "; only a searching or assigned delivery can be reassigned.", 409);
        }
        UUID current = Json.uuid(delivery, "partnerId");
        String currentName = current == null ? null
                : Objects.requireNonNullElse(Json.text(delivery, "partnerName"), current.toString());
        UUID target = args.uuid("partnerId");
        String targetName = null;
        if (target != null) {
            if (target.equals(current)) {
                return new Outcome.Invalid("Order " + number + " is already assigned to " + currentName + ".");
            }
            Fetch online = api.get(context, "/api/admin/delivery-partners/online");
            if (online instanceof Fetch.Problem problem) {
                return failed(problem);
            }
            JsonNode partner = null;
            JsonNode partners = ((Fetch.Ok) online).body();
            if (partners != null && partners.isArray()) {
                for (JsonNode candidate : partners) {
                    if (target.toString().equals(Json.text(candidate, "partnerId"))) {
                        partner = candidate;
                    }
                }
            }
            if (partner == null) {
                return new Outcome.Failed("PARTNER_UNAVAILABLE", "The partner " + target + " is not online right "
                        + "now.", 409);
            }
            targetName = Objects.requireNonNullElse(Json.text(partner, "name"), target.toString());
            if (Json.bool(partner, "busy")) {
                return new Outcome.Failed("PARTNER_UNAVAILABLE", targetName + " is busy with another delivery.",
                        409);
            }
        }
        TreeMap<String, Object> body = new TreeMap<>();
        if (target != null) {
            body.put("partnerId", target.toString());
        }
        if (args.string("reason") != null) {
            body.put("reason", args.string("reason"));
        }
        List<ApprovalView.Line> lines = new ArrayList<>();
        lines.add(line("Order", Json.text(delivery, "orderNumber")));
        lines.add(line("Store", Objects.requireNonNullElse(Json.text(delivery, "pickup.name"), "—")));
        lines.add(line("Delivery status", status + (Json.bool(delivery, "assignmentFailed")
                ? " (no partner found so far)" : "")));
        lines.add(line("Current partner", currentName == null ? "None" : currentName));
        lines.add(line("New partner", targetName != null ? targetName
                : "Search again for the nearest available partner" + (current == null ? "" : " (not " + currentName
                + ")")));
        lines.add(line("Reason", args.string("reason") == null ? "—" : args.string("reason")));
        String title = targetName != null ? "Reassign order " + number + " to " + targetName
                : "Reassign order " + number + " (search again)";
        return ready(new ProposalDraft(ApprovalAction.REASSIGN_DELIVERY, null, "POST",
                "/api/admin/deliveries/" + Json.text(delivery, "deliveryId") + "/reassign", body, title, lines,
                null, null));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static Outcome ready(ProposalDraft draft) {
        return new Outcome.Ready(draft);
    }

    static Outcome failed(Fetch.Problem problem) {
        return new Outcome.Failed(problem.code(), problem.message(), problem.httpStatus());
    }

    static ApprovalView.Line line(String label, String value) {
        return new ApprovalView.Line(label, value);
    }

    private static String currency(JsonNode order) {
        return Objects.requireNonNullElse(Json.text(order, "currency"), "INR");
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
