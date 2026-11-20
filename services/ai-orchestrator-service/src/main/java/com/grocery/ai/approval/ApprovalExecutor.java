package com.grocery.ai.approval;

import com.grocery.ai.gateway.ApiResponse;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.gateway.GatewayClient;
import com.grocery.ai.gateway.GatewayUnavailableException;
import com.grocery.ai.support.Json;
import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.idempotency.IdempotencyKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Runs one approved action (contract §4 "Execution"): the stored method, path and body bytes through the gateway with
 * the APPROVER's {@code Authorization}, a freshly signed {@code X-Approval-Token}, {@code Idempotency-Key = approvalId}
 * where the endpoint takes one, and the correlation ID. The target's answer decides COMPLETED or FAILED; nothing is
 * retried.
 */
@Component
public class ApprovalExecutor {

    public static final String APPROVAL_TOKEN_HEADER = "X-Approval-Token";
    public static final String TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(ApprovalExecutor.class);

    private final GatewayClient gateway;
    private final ApprovalTokenSigner signer;
    private final JsonMapper jsonMapper;

    public ApprovalExecutor(GatewayClient gateway, ApprovalTokenSigner signer, JsonMapper jsonMapper) {
        this.gateway = gateway;
        this.signer = signer;
        this.jsonMapper = jsonMapper;
    }

    /** What the target answered. */
    public sealed interface Outcome {
        record Completed(String resultJson, String reference) implements Outcome {
        }

        record Failed(String code, String message, Integer httpStatus) implements Outcome {
        }
    }

    public Outcome execute(ApprovalRequest approval, UUID approverId, CallContext approver) {
        HttpHeaders headers = new HttpHeaders();
        if (approver.authorization() != null) {
            headers.set(HttpHeaders.AUTHORIZATION, approver.authorization());
        }
        headers.set(APPROVAL_TOKEN_HEADER, signer.sign(approval, approverId));
        if (approval.getAction().takesIdempotencyKey()) {
            headers.set(IdempotencyKeys.HEADER, approval.getId().toString());
        }
        if (approver.correlationId() != null) {
            headers.set(CorrelationId.HEADER, approver.correlationId());
        }
        ApiResponse response;
        try {
            response = gateway.execute(HttpMethod.valueOf(approval.getHttpMethod()), approval.getHttpPath(),
                    approval.getRequestBody().getBytes(StandardCharsets.UTF_8), headers);
        } catch (GatewayUnavailableException e) {
            log.warn("Approval {} ({}): target unreachable: {}", approval.getId(), approval.getAction(),
                    e.getMessage());
            return new Outcome.Failed(TARGET_UNAVAILABLE, "The service could not be reached. Nothing was changed "
                    + "unless the service says otherwise; ask the assistant for a new proposal.", null);
        }
        if (response.ok()) {
            return completed(approval.getAction(), response.json());
        }
        String code = response.errorCode();
        int status = response.status();
        if (code == null || ((status == 503 || status == 504)
                && ("SERVICE_UNAVAILABLE".equals(code) || "GATEWAY_TIMEOUT".equals(code)))) {
            code = status >= 500 ? TARGET_UNAVAILABLE : "HTTP_" + status;
        }
        String message = response.detail() != null ? response.detail()
                : "The service refused the request (HTTP " + status + ").";
        log.info("Approval {} ({}) failed at the target: {} {}", approval.getId(), approval.getAction(), status, code);
        return new Outcome.Failed(code, message, status);
    }

    private Outcome completed(ApprovalAction action, JsonNode body) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        String reference;
        switch (action) {
            case PLACE_ORDER, CANCEL_ORDER, ADMIN_CANCEL_ORDER -> {
                result.put("orderId", Json.text(body, "id"));
                result.put("orderNumber", Json.text(body, "orderNumber"));
                result.put("status", Json.text(body, "status"));
                reference = Json.text(body, "orderNumber");
            }
            case REFUND_PAYMENT -> {
                result.put("refundId", Json.text(body, "id"));
                result.put("amount", Json.decimal(body, "amount"));
                result.put("status", Json.text(body, "status"));
                reference = Json.text(body, "id");
            }
            case SUSPEND_VENDOR -> {
                result.put("vendorId", Json.text(body, "id"));
                result.put("status", Json.text(body, "status"));
                reference = Json.text(body, "businessName");
            }
            case SUSPEND_RESTAURANT -> {
                result.put("restaurantId", Json.text(body, "id"));
                result.put("status", Json.text(body, "status"));
                reference = Json.text(body, "name");
            }
            case REQUEST_RETURN -> {                                         // ReturnView (the frontend reads these)
                result.put("returnId", Json.text(body, "id"));
                result.put("returnNumber", Json.text(body, "returnNumber"));
                result.put("refundAmount", Json.decimal(body, "refundAmount"));
                result.put("orderId", Json.text(body, "orderId"));
                result.put("orderNumber", Json.text(body, "orderNumber"));
                result.put("status", Json.text(body, "status"));
                reference = Json.text(body, "returnNumber");
            }
            case ADJUST_STOCK -> {                                           // StockView
                result.put("productId", Json.text(body, "productId"));
                result.put("productName", Json.text(body, "productName"));
                result.put("onHand", Json.integer(body, "onHand"));
                result.put("available", Json.integer(body, "available"));
                reference = Json.text(body, "productName") + ": " + Json.integer(body, "onHand") + " on hand";
            }
            case CHANGE_PRICE -> {                                           // PriceView, 200 APPLIED / 202 PENDING
                String outcome = Json.text(body, "outcome");
                boolean pending = "PENDING_APPROVAL".equals(outcome);
                result.put("productId", Json.text(body, "productId"));
                result.put("productName", Json.text(body, "productName"));
                result.put("outcome", outcome);
                result.put("sellingPrice", Json.decimal(body, "price.sellingPrice"));
                result.put("mrp", Json.decimal(body, "price.mrp"));
                if (pending) {
                    result.put("requestedSellingPrice", Json.decimal(body, "pendingRequest.requestedSellingPrice"));
                }
                result.put("message", pending
                        ? "The change is above the price threshold: it now waits for an admin's approval in pricing, "
                        + "and the live price stays until then."
                        : "The new price is live.");
                reference = Json.text(body, "productName") + (pending ? ": waiting for admin approval"
                        : ": price applied");
            }
            case SEND_PURCHASE_ORDER -> {                                    // PurchaseOrderView
                result.put("purchaseOrderId", Json.text(body, "id"));
                result.put("purchaseOrderNumber", Json.text(body, "number"));
                result.put("number", Json.text(body, "number"));
                result.put("status", Json.text(body, "status"));
                reference = Json.text(body, "number");
            }
            case SET_MENU_ITEM_AVAILABILITY -> {                             // MenuItemView
                boolean available = Json.bool(body, "available");
                result.put("menuItemId", Json.text(body, "id"));
                result.put("name", Json.text(body, "name"));
                result.put("available", available);
                reference = Json.text(body, "name") + (available ? ": available" : ": sold out");
            }
            case ACCEPT_STORE_ORDER, REJECT_STORE_ORDER -> {                 // StoreOrderView
                result.put("orderId", Json.text(body, "orderId"));
                result.put("orderNumber", Json.text(body, "orderNumber"));
                result.put("status", Json.text(body, "status"));
                result.put("readyBy", Json.text(body, "readyBy"));
                reference = Json.text(body, "orderNumber");
            }
            case REASSIGN_DELIVERY -> {                                      // AdminDeliveryView
                result.put("deliveryId", Json.text(body, "deliveryId"));
                result.put("orderNumber", Json.text(body, "orderNumber"));
                result.put("status", Json.text(body, "status"));
                result.put("partnerId", Json.text(body, "partnerId"));
                result.put("partnerName", Json.text(body, "partnerName"));
                reference = Json.text(body, "orderNumber");
            }
            default -> throw new IllegalStateException("Unknown action " + action);
        }
        return new Outcome.Completed(jsonMapper.writeValueAsString(result), truncate(reference, 100));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
