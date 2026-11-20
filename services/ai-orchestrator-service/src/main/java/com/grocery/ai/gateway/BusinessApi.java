package com.grocery.ai.gateway;

import com.grocery.ai.support.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The business API calls of the tools, the proposals and the insights, as the caller, through the gateway. Every
 * call ends in {@link Fetch.Ok} or a {@link Fetch.Problem} carrying the API's {@code errorCode} (or
 * {@code SERVICE_UNAVAILABLE} when nothing answered), never in an exception.
 */
@Component
public class BusinessApi {

    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    /** Customer order lists are scanned page by page for an order number (newest first). */
    static final int ORDER_SCAN_PAGES = 5;
    private static final Logger log = LoggerFactory.getLogger(BusinessApi.class);

    private final GatewayClient gateway;

    public BusinessApi(GatewayClient gateway) {
        this.gateway = gateway;
    }

    /** The outcome of one business API call. */
    public sealed interface Fetch {
        record Ok(JsonNode body, int httpStatus) implements Fetch {
        }

        record Problem(String code, String message, Integer httpStatus) implements Fetch {
        }
    }

    public Fetch get(CallContext context, String path, Map<String, ?> query) {
        try {
            return outcome(gateway.get(context, path, query));
        } catch (GatewayUnavailableException e) {
            return unavailable(e);
        }
    }

    public Fetch get(CallContext context, String path) {
        return get(context, path, Map.of());
    }

    public Fetch send(CallContext context, HttpMethod method, String path, Object body) {
        try {
            return outcome(gateway.send(context, method, path, body));
        } catch (GatewayUnavailableException e) {
            return unavailable(e);
        }
    }

    /**
     * A customer's own order by its number: {@code GET /api/orders} (newest first, up to 500 orders) for the id, then
     * {@code GET /api/orders/{id}} (OrderView). Unknown → Problem ORDER_NOT_FOUND (404).
     */
    public Fetch customerOrder(CallContext context, String orderNumber) {
        for (int page = 0; page < ORDER_SCAN_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("page", page);
            query.put("size", 100);
            Fetch list = get(context, "/api/orders", query);
            if (!(list instanceof Fetch.Ok ok)) {
                return list;
            }
            for (JsonNode summary : Json.list(ok.body(), "content")) {
                if (orderNumber.equalsIgnoreCase(Json.text(summary, "orderNumber"))) {
                    return get(context, "/api/orders/" + Json.text(summary, "id"));
                }
            }
            Integer totalPages = Json.integer(ok.body(), "totalPages");
            if (totalPages == null || page + 1 >= totalPages) {
                break;
            }
        }
        return orderNotFound(orderNumber);
    }

    /** Any order by its number, as an admin: {@code GET /api/admin/orders?q=} then {@code /api/admin/orders/{id}}. */
    public Fetch adminOrder(CallContext context, String orderNumber) {
        Fetch list = get(context, "/api/admin/orders", Map.of("q", orderNumber, "size", 1));
        if (!(list instanceof Fetch.Ok ok)) {
            return list;
        }
        for (JsonNode summary : Json.list(ok.body(), "content")) {
            return get(context, "/api/admin/orders/" + Json.text(summary, "id"));
        }
        return orderNotFound(orderNumber);
    }

    /**
     * One of the caller's store orders by its number (Phase 8 §2: {@code GET /api/vendor/orders} or
     * {@code /api/restaurant/orders}, NEW ones first, up to 500): the {@code StoreOrderView} from the list. Unknown →
     * Problem STORE_ORDER_NOT_FOUND (404).
     */
    public Fetch storeOrder(CallContext context, String prefix, String orderNumber) {
        Fetch found = scan(context, prefix, Map.of(), "orderNumber", orderNumber);
        return found != null ? found : new Fetch.Problem("STORE_ORDER_NOT_FOUND", "There is no order "
                + orderNumber + " for your store.", 404);
    }

    /**
     * One of the vendor's purchase orders by its number PO-… (Phase 12 §4: {@code GET
     * /api/vendor/purchase-orders} for the id, then {@code GET /api/vendor/purchase-orders/{id}} with its lines).
     * Unknown → Problem PURCHASE_ORDER_NOT_FOUND (404).
     */
    public Fetch purchaseOrder(CallContext context, String number) {
        Fetch found = scan(context, "/api/vendor/purchase-orders", Map.of(), "number", number);
        if (found instanceof Fetch.Ok ok) {
            return get(context, "/api/vendor/purchase-orders/" + Json.text(ok.body(), "id"));
        }
        return found != null ? found : new Fetch.Problem("PURCHASE_ORDER_NOT_FOUND", "There is no purchase order "
                + number + ".", 404);
    }

    /**
     * A delivery by its order number, as an admin (Phase 9 §5: {@code GET /api/admin/deliveries?q=} for the
     * deliveryId, then {@code GET /api/admin/deliveries/{id}}). Unknown → Problem DELIVERY_NOT_FOUND (404).
     */
    public Fetch adminDelivery(CallContext context, String orderNumber) {
        Fetch list = get(context, "/api/admin/deliveries", Map.of("q", orderNumber, "size", 20));
        if (!(list instanceof Fetch.Ok ok)) {
            return list;
        }
        for (JsonNode delivery : Json.list(ok.body(), "content")) {
            if (orderNumber.equalsIgnoreCase(Json.text(delivery, "orderNumber"))) {
                return get(context, "/api/admin/deliveries/" + Json.text(delivery, "deliveryId"));
            }
        }
        return new Fetch.Problem("DELIVERY_NOT_FOUND", "There is no delivery for order " + orderNumber + ".", 404);
    }

    /**
     * Pages through {@code path} (size 100, at most {@link #ORDER_SCAN_PAGES} pages) for the entry whose {@code field}
     * equals {@code value} (ignoring case): Ok with that entry, the Problem of a failed page, or null when not found.
     */
    private Fetch scan(CallContext context, String path, Map<String, ?> filters, String field, String value) {
        for (int page = 0; page < ORDER_SCAN_PAGES; page++) {
            Map<String, Object> query = new LinkedHashMap<>(filters);
            query.put("page", page);
            query.put("size", 100);
            Fetch list = get(context, path, query);
            if (!(list instanceof Fetch.Ok ok)) {
                return list;
            }
            for (JsonNode entry : Json.list(ok.body(), "content")) {
                if (value.equalsIgnoreCase(Json.text(entry, field))) {
                    return new Fetch.Ok(entry, ok.httpStatus());
                }
            }
            Integer totalPages = Json.integer(ok.body(), "totalPages");
            if (totalPages == null || page + 1 >= totalPages) {
                break;
            }
        }
        return null;
    }

    /** The problem the assistants get for an order number the customer has no order with. */
    public static Fetch orderNotFound(String orderNumber) {
        return new Fetch.Problem("ORDER_NOT_FOUND", "There is no order " + orderNumber + ".", 404);
    }

    private static Fetch outcome(ApiResponse response) {
        if (response.ok()) {
            return new Fetch.Ok(response.json(), response.status());
        }
        String code = response.errorCode() != null ? response.errorCode() : "HTTP_" + response.status();
        String message = response.detail() != null ? response.detail()
                : "The request failed with HTTP " + response.status() + ".";
        return new Fetch.Problem(code, message, response.status());
    }

    private static Fetch unavailable(GatewayUnavailableException e) {
        log.warn("Business API unreachable: {}", e.getMessage());
        return new Fetch.Problem(SERVICE_UNAVAILABLE, "The service is not reachable right now. Please try again.",
                null);
    }
}
