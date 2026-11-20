package com.grocery.ai.chat;

import com.grocery.ai.agent.AgentDefinition;
import com.grocery.ai.agent.Agents;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.support.IndiaTime;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import com.grocery.ai.tool.NextStop;
import com.grocery.ai.tool.PaymentTools;
import com.grocery.ai.tool.ShoppingTools;
import com.grocery.ai.tool.SupportTools;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The server-built context line the model sees with every message (contract §2): who is signed in (role in plain
 * words, first name), what that role may see, today's date in India and, per assistant, the user's own business:
 * <ul>
 *   <li>{@code shopping}: a one-line cart summary and the default delivery address;</li>
 *   <li>{@code payment}: the latest order's payment (status, deadline, what happened and the next step in plain
 *       words) and how many orders wait for payment;</li>
 *   <li>{@code support}: the latest order and the customer's open complaints;</li>
 *   <li>{@code vendor} / {@code restaurant}: the store's or restaurant's name and onboarding status;</li>
 *   <li>{@code delivery} (partners): the partner profile's status.</li>
 * </ul>
 * Everything comes from the validated token and from the APIs called with the user's own token, never from the
 * conversation, so the model cannot be talked into another identity, and the user never has to explain who they are.
 */
@Component
public class ContextBuilder {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** What each role may see, in the words the model repeats to the user when something is out of reach. */
    static final Map<String, String> ROLE_SCOPE = Map.of(
            Roles.CUSTOMER, "a customer: sees only their own cart, orders, payments, returns, complaints and "
                    + "addresses",
            Roles.VENDOR, "a grocery vendor: sees only their own store's products, stock, prices, orders, sales, "
                    + "returns and reviews",
            Roles.RESTAURANT_OWNER, "a restaurant owner: sees only their own restaurant's menu, orders, sales, "
                    + "returns and reviews",
            Roles.DELIVERY_PARTNER, "a delivery partner: sees only their own deliveries, offers and history",
            Roles.SUPER_ADMIN, "a super admin: sees the whole platform",
            Roles.OPS_ADMIN, "an operations admin: sees the whole platform's operations",
            Roles.FINANCE_ADMIN, "a finance admin: sees the whole platform's orders, payments and refunds",
            Roles.SUPPORT, "a support agent: sees the whole platform's orders and payments to help customers",
            Roles.DELIVERY_MANAGER, "a delivery manager: sees the whole platform's deliveries and business figures");

    private final BusinessApi api;
    private final Clock clock;
    private final PaymentTools paymentTools;

    public ContextBuilder(BusinessApi api, Clock clock, PaymentTools paymentTools) {
        this.api = api;
        this.clock = clock;
        this.paymentTools = paymentTools;
    }

    public String build(AgentDefinition agent, CurrentUser user, CallContext context) {
        String role = role(agent, user);
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        StringBuilder line = new StringBuilder("Context (from the server, trustworthy): user role ")
                .append(role)
                .append(" (").append(ROLE_SCOPE.getOrDefault(role, "a signed-in user")).append(')')
                .append("; first name ").append(firstName(user))
                .append("; today ").append(today).append(" (India)");
        switch (agent.id()) {
            case Agents.SHOPPING -> {
                line.append("; ").append(switch (api.get(context, "/api/cart")) {
                    case BusinessApi.Fetch.Ok ok -> ShoppingTools.cartSummary(ok.body())
                            .replace("Cart: ", "cart: ").replace("Cart is empty", "cart is empty");
                    case BusinessApi.Fetch.Problem problem -> "cart unavailable right now";
                });
                line.append("; ").append(switch (api.get(context, "/api/customers/me/addresses")) {
                    case BusinessApi.Fetch.Ok ok -> defaultAddress(ok.body());
                    case BusinessApi.Fetch.Problem problem -> "addresses unavailable right now";
                });
                line.append("; ").append(latestOrder(api.get(context, "/api/orders", Map.of("size", 1))));
            }
            case Agents.PAYMENT -> {
                BusinessApi.Fetch latest = api.get(context, "/api/orders", Map.of("size", 1));
                line.append("; ").append(latestOrderPayment(latest)).append(latestPaymentNote(latest, context));
                line.append("; ").append(waitingForPayment(api.get(context, "/api/orders",
                        Map.of("status", "AWAITING_PAYMENT", "size", 1))));
            }
            case Agents.SUPPORT -> {
                BusinessApi.Fetch latest = api.get(context, "/api/orders", Map.of("size", 1));
                line.append("; ").append(latestOrder(latest)).append(latestCancellation(latest, context));
                line.append("; ").append(switch (api.get(context, "/api/returns", Map.of("size", 1))) {
                    case BusinessApi.Fetch.Ok ok -> SupportTools.returnsSnapshot(ok.body());
                    case BusinessApi.Fetch.Problem problem -> "returns unavailable right now";
                });
                line.append("; ").append(openComplaints(api.get(context, "/api/complaints", Map.of("size", 20))));
                line.append("; ").append(SUPPORT_NOT_IN_CONTEXT);
            }
            case Agents.VENDOR -> {
                BusinessApi.Fetch store = api.get(context, "/api/vendors/me");
                line.append("; ").append(business("store", "businessName", store));
                if (approved(store)) {
                    line.append("; ").append(todaysOrders(api.get(context, "/api/vendor/reports/sales",
                            Map.of("from", today, "to", today))));
                    line.append("; ").append(lowStock(api.get(context, "/api/vendor/inventory",
                            Map.of("filter", "LOW_STOCK", "size", 1))));
                }
            }
            case Agents.RESTAURANT -> {
                BusinessApi.Fetch restaurant = api.get(context, "/api/restaurants/me");
                line.append("; ").append(business("restaurant", "name", restaurant));
                if (approved(restaurant)) {
                    line.append("; ").append(todaysOrders(api.get(context, "/api/restaurant/reports/sales",
                            Map.of("from", today, "to", today))));
                }
            }
            case Agents.DELIVERY -> {
                if (Roles.DELIVERY_PARTNER.equals(role)) {
                    BusinessApi.Fetch profile = api.get(context, "/api/delivery/partners/me");
                    line.append("; ").append(partner(profile));
                    if (approved(profile)) {
                        line.append("; ").append(activeDelivery(api.get(context, "/api/delivery/deliveries/active"),
                                        api.get(context, "/api/delivery/partners/me/status")))
                                .append("; ").append(offers(api.get(context, "/api/delivery/offers")));
                    }
                }
            }
            default -> {
                // admin-bi: the role says it all; the tools read the platform's data.
            }
        }
        line.append(". Only discuss data the tools or this context return for this user; never reveal "
                + "other people's private details (phone numbers, e-mail or home addresses)");
        if (Roles.DELIVERY_PARTNER.equals(role)) {
            // The partner's own active delivery is their job: its drop address is theirs to see (the app shows it).
            line.append(", except the delivery address of this partner's own active delivery");
        }
        return line.append('.').toString();
    }

    // ---- live snapshot: the user's most asked-about figures, read now with the user's own rights -----------------
    // A small model sometimes answers "where is my latest order?" without calling a tool. With these facts in the
    // trustworthy context it answers from real data instead of inventing an order (and Grounding accepts it).

    private static boolean approved(BusinessApi.Fetch profile) {
        return profile instanceof BusinessApi.Fetch.Ok ok && "APPROVED".equals(Json.text(ok.body(), "status"));
    }

    /** "latest order GA-7K3F9Q2M: OUT_FOR_DELIVERY, ₹260.00, from Fresh Mart, placed 14 Nov, 3:00 pm IST". */
    static String latestOrder(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "latest order unavailable right now";
        }
        List<JsonNode> orders = Json.list(ok.body(), "content");
        if (orders.isEmpty()) {
            return "no orders yet";
        }
        JsonNode order = orders.getFirst();
        String store = Json.text(order, "store.name");
        String placed = IndiaTime.format(Json.text(order, "createdAt"));      // India time, not the API's UTC
        return "latest order " + Json.text(order, "orderNumber") + ": " + Json.text(order, "status")
                + (Json.decimal(order, "total") == null ? "" : ", " + Money.inr(Json.decimal(order, "total")))
                + (store == null ? "" : ", from " + store)
                + (placed == null ? "" : ", placed " + placed);
    }

    /**
     * "latest order GA-7K3F9Q2M (₹260.00, from Fresh Mart): order status AWAITING_PAYMENT, payment status PENDING,
     * pay by 19 Nov, 2:32 pm IST" — the payment assistant's most asked-about fact.
     */
    static String latestOrderPayment(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "latest order unavailable right now";
        }
        List<JsonNode> orders = Json.list(ok.body(), "content");
        if (orders.isEmpty()) {
            return "no orders yet";
        }
        JsonNode order = orders.getFirst();
        String store = Json.text(order, "store.name");
        String payBy = IndiaTime.format(Json.text(order, "payment.dueAt"));
        return "latest order " + Json.text(order, "orderNumber")
                + (Json.decimal(order, "total") == null ? "" : " (" + Money.inr(Json.decimal(order, "total"))
                + (store == null ? "" : ", from " + store) + ")")
                + ": order status " + Json.text(order, "status")
                + ", payment status " + Objects.requireNonNullElse(Json.text(order, "payment.status"), "unknown")
                + (payBy == null ? "" : ", pay by " + payBy);
    }

    /**
     * What happened to the latest order's payment and the next step, in getPaymentStatus's words (payment only): the
     * line's codes alone ("CANCELLED", "REFUNDED") let the small model invent the story. "" when there is no order or
     * it cannot be read.
     */
    private String latestPaymentNote(BusinessApi.Fetch latest, CallContext context) {
        if (!(latest instanceof BusinessApi.Fetch.Ok ok)) {
            return "";
        }
        List<JsonNode> orders = Json.list(ok.body(), "content");
        UUID id = orders.isEmpty() ? null : Json.uuid(orders.getFirst(), "id");
        return id == null ? "" : paymentTools.paymentNote(context, id);
    }

    /** "orders waiting for payment: 1". */
    static String waitingForPayment(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "orders waiting for payment unavailable right now";
        }
        Integer total = Json.integer(ok.body(), "totalElements");
        return "orders waiting for payment: " + (total != null ? total : Json.list(ok.body(), "content").size());
    }

    /**
     * What the support assistant's context line does not hold. Without this a small model reads "latest order …:
     * CANCELLED" or "no open complaints" as the whole story and invents the rest (a cancellation reason, "no refund",
     * "no complaints") instead of calling the tool.
     */
    static final String SUPPORT_NOT_IN_CONTEXT = "anything else (where an order is now, why another order was "
            + "cancelled, an order's refund, return details, older complaints) is not in this line: call the matching "
            + "tool, and never answer that you cannot see it";

    /** Longest part of a complaint's answer quoted in the context line (the whole answer comes from the tool). */
    private static final int ANSWER_IN_CONTEXT = 120;

    /**
     * The latest order's cancellation reason and time (support only), e.g. " (cancelled because the store could not
     * accept it, on 18 Nov, 8:37 pm IST)": the order list has no reason, so this costs one more read, made only when
     * the latest order was cancelled. "" otherwise, or when the order cannot be read.
     */
    private String latestCancellation(BusinessApi.Fetch latest, CallContext context) {
        if (!(latest instanceof BusinessApi.Fetch.Ok ok)) {
            return "";
        }
        List<JsonNode> orders = Json.list(ok.body(), "content");
        String id = orders.isEmpty() ? null : Json.text(orders.getFirst(), "id");
        if (id == null || !"CANCELLED".equals(Json.text(orders.getFirst(), "status"))) {
            return "";
        }
        return switch (api.get(context, "/api/orders/" + id)) {
            case BusinessApi.Fetch.Ok order -> SupportTools.cancellationNote(order.body());
            case BusinessApi.Fetch.Problem problem -> "";
        };
    }

    /**
     * "open complaints: 1 (CMP-7K3F9Q2M about GA-28AT309Q, being looked into)" / "no open complaints", followed by
     * "; resolved complaints: 2 (newest: CMP-… about GA-…, answered "…"; …)" with up to three answers (shortened).
     * Without the answers a small model asked "what did support say?" made up complaints and replies.
     */
    static String openComplaints(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "complaints unavailable right now";
        }
        List<JsonNode> complaints = Json.list(ok.body(), "content");
        List<JsonNode> resolved = complaints.stream()
                .filter(complaint -> "RESOLVED".equals(Json.text(complaint, "status"))).toList();
        String answered = resolved.isEmpty() ? ""
                : "; resolved complaints: " + resolved.size() + " (newest: " + resolved.stream().limit(3)
                .map(complaint -> Json.text(complaint, "complaintNumber")
                        + (Json.text(complaint, "orderNumber") == null ? ""
                        : " about " + Json.text(complaint, "orderNumber"))
                        + ", answered \"" + shorten(Json.text(complaint, "resolution")) + "\"")
                .collect(Collectors.joining("; ")) + ")";
        List<String> open = complaints.stream()
                .filter(complaint -> !"RESOLVED".equals(Json.text(complaint, "status")))
                .map(complaint -> Json.text(complaint, "complaintNumber")
                        + (Json.text(complaint, "orderNumber") == null ? ""
                        : " about " + Json.text(complaint, "orderNumber"))
                        + ", " + ("IN_PROGRESS".equals(Json.text(complaint, "status"))
                        ? "being looked into" : "waiting for the support team"))
                .toList();
        return (open.isEmpty() ? "no open complaints"
                : "open complaints: " + open.size() + " (" + String.join("; ", open) + ")") + answered;
    }

    /** A complaint's answer on one line, cut to {@link #ANSWER_IN_CONTEXT} characters ("…" when cut). */
    private static String shorten(String answer) {
        if (answer == null) {
            return "";
        }
        String oneLine = answer.strip().replaceAll("\\s+", " ").replace('"', '\'');
        return oneLine.length() <= ANSWER_IN_CONTEXT ? oneLine
                : oneLine.substring(0, ANSWER_IN_CONTEXT).stripTrailing() + "…";
    }

    /** "today so far: 7 orders received, 6 accepted". */
    static String todaysOrders(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "today's orders unavailable right now";
        }
        return "today so far: " + Objects.requireNonNullElse(Json.integer(ok.body(), "ordersReceived"), 0)
                + " orders received, " + Objects.requireNonNullElse(Json.integer(ok.body(), "ordersAccepted"), 0)
                + " accepted";
    }

    /** "3 products low on stock". */
    static String lowStock(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "stock levels unavailable right now";
        }
        Integer total = Json.integer(ok.body(), "totalElements");
        int count = total != null ? total : Json.list(ok.body(), "content").size();
        return count + (count == 1 ? " product" : " products") + " low on stock";
    }

    /** "active delivery: order GA-1, status PICKED_UP" / "no active delivery". */
    static String activeDelivery(BusinessApi.Fetch fetch, BusinessApi.Fetch partnerStatus) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "active delivery unavailable right now";
        }
        if (ok.body() == null || Json.text(ok.body(), "deliveryId") == null) {
            return "no active delivery";
        }
        // The next stop and its real distance, so a model that answers "how far is it?" without a tool call still
        // has the true figure (the same as getActiveDelivery gives).
        JsonNode location = partnerStatus instanceof BusinessApi.Fetch.Ok status ? Json.at(status.body(), "location") : null;
        String text = "active delivery: order " + Json.text(ok.body(), "orderNumber") + ", status "
                + Json.text(ok.body(), "status") + ", pickup at " + Objects.requireNonNullElse(
                        Json.text(ok.body(), "pickup.name"), "the store");
        String dropAddress = dropAddress(Json.at(ok.body(), "drop"));
        if (dropAddress != null) {
            text += ", customer's delivery address " + dropAddress;
        }
        return text + ", next stop " + NextStop.of(ok.body(), location).describe();
    }

    /** "Divya Krishnan, 22 Kamaraj Nagar 3rd Street, Thiruvanmiyur, Near the temple, Chennai 600041" (no phone). */
    static String dropAddress(JsonNode drop) {
        if (drop == null || !drop.isObject()) {
            return null;
        }
        String city = Stream.of(Json.text(drop, "city"), Json.text(drop, "postalCode"))
                .filter(part -> part != null && !part.isBlank()).collect(Collectors.joining(" "));
        String address = Stream.of(Json.text(drop, "recipientName"), Json.text(drop, "line1"), Json.text(drop, "line2"),
                        Json.text(drop, "landmark"), city)
                .filter(part -> part != null && !part.isBlank()).collect(Collectors.joining(", "));
        return address.isBlank() ? null : address;
    }

    /** "2 delivery offers waiting for an answer". */
    static String offers(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok)) {
            return "delivery offers unavailable right now";
        }
        int count = ok.body() != null && ok.body().isArray() ? ok.body().size() : Json.list(ok.body(), "content").size();
        return count + (count == 1 ? " delivery offer" : " delivery offers") + " waiting for an answer";
    }

    /**
     * "store: FreshCart Store (APPROVED)". A store that is not approved yet cannot use its store tools (the services
     * answer VENDOR_NOT_ACTIVE / RESTAURANT_NOT_ACTIVE); the model says why instead of guessing.
     */
    static String business(String noun, String nameField, BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok) || ok.body() == null) {
            return "the " + noun + " profile is unavailable right now";
        }
        String name = Json.text(ok.body(), nameField);
        String status = Json.text(ok.body(), "status");
        String text = noun + ": " + (name == null || name.isBlank() ? "(no name yet)" : name.strip())
                + " (status " + (status == null ? "unknown" : status) + ")";
        if ("restaurant".equals(noun) && Json.at(ok.body(), "acceptingOrders") != null) {
            text += Json.bool(ok.body(), "acceptingOrders") ? ", accepting orders" : ", not accepting orders now";
        }
        if (status != null && !"APPROVED".equals(status)) {
            text += "; the " + noun + " is not approved, so its orders, sales and stock tools only work once an "
                    + "admin approves it (the user finishes onboarding in the app)";
        }
        return text;
    }

    /** "delivery partner profile: APPROVED". */
    static String partner(BusinessApi.Fetch fetch) {
        if (!(fetch instanceof BusinessApi.Fetch.Ok ok) || ok.body() == null) {
            return "the delivery partner profile is unavailable right now";
        }
        String status = Json.text(ok.body(), "status");
        String text = "delivery partner profile status " + (status == null ? "unknown" : status);
        if (status != null && !"APPROVED".equals(status)) {
            text += "; deliveries are offered only once an admin approves the profile";
        }
        return text;
    }

    /**
     * "default delivery address: Home, Bengaluru (id …)": checkout tools take an optional addressId, and a model that
     * knows the id (or that it may leave it out) does not invent one.
     */
    static String defaultAddress(JsonNode addresses) {
        if (addresses == null || !addresses.isArray() || addresses.isEmpty()) {
            return "no delivery address saved yet";
        }
        JsonNode chosen = addresses.get(0);                  // the API lists the default address first
        for (JsonNode address : addresses) {
            if (address.path("isDefault").asBoolean(false)) {
                chosen = address;
                break;
            }
        }
        String label = chosen.path("label").asString("");
        String city = chosen.path("city").asString("");
        String name = (label.isBlank() ? "" : label + ", ") + city;
        return "default delivery address: " + (name.isBlank() ? "saved" : name.strip())
                + " (id " + chosen.path("id").asString("?") + "; leave addressId out to use it)";
    }

    /** The user's role for this agent: the first (alphabetically) of their roles the agent serves. */
    static String role(AgentDefinition agent, CurrentUser user) {
        return user.roles().stream().filter(agent.roles()::contains).sorted().findFirst()
                .orElse(String.join(",", user.roles()));
    }

    static String firstName(CurrentUser user) {
        String name = user.name();
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        String first = name.strip().split("\\s+")[0];
        return first.length() > 40 ? first.substring(0, 40) : first;
    }
}
