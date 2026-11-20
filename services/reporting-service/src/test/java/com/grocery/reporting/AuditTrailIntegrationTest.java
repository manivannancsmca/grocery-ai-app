package com.grocery.reporting;

import com.grocery.reporting.audit.AuditService;
import com.grocery.reporting.audit.AuditService.Outcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grocery.reporting.TestEvents.INDIA;
import static com.grocery.reporting.TestEvents.event;
import static com.grocery.reporting.TestEvents.grocery;
import static com.grocery.reporting.TestEvents.map;
import static com.grocery.reporting.TestEvents.money;
import static com.grocery.reporting.TestEvents.orderCreated;
import static com.grocery.reporting.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The central audit trail (README "Audit trail"): events from every service, each encoded with its real schema, become
 * one searchable list — sign-ins and account changes, settings, store decisions, order cancellations, price requests,
 * stock, the assistants' approvals — with who acted (an admin's e-mail learnt from their sign-in), newest first,
 * filtered, exported, for operations admins only; duplicates and other events add nothing; old entries are purged.
 */
class AuditTrailIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuditService auditService;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private final UUID admin = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID vendorUser = UUID.randomUUID();

    private Instant ago(int minutes) {
        return now.minus(minutes, ChronoUnit.MINUTES);
    }

    private ConsumerRecord<String, String> security(String type, UUID userId, String email, UUID actor, String detail,
                                                    String ip, Instant at) {
        UUID recordId = UUID.randomUUID();
        return event("identity.events", "SecurityEventRecorded", recordId, map("recordId", recordId, "type", type,
                "userId", userId, "email", email, "actorUserId", actor, "detail", detail, "ipAddress", ip,
                "userAgent", "Mozilla/5.0 (test)", "occurredAt", at), at);
    }

    private ConsumerRecord<String, String> settingChanged(Instant at) {
        return event("delivery.events", "PlatformSettingChanged", "delivery.offersPerWave", map(
                "settingKey", "delivery.offersPerWave", "area", "delivery", "field", "offersPerWave",
                "oldValue", "3", "newValue", "5", "changedBy", admin, "changedByName", "Meera Ops",
                "changedAt", at), at);
    }

    /** Nine audited events, one per family, and an order event that is not audited. */
    private List<ConsumerRecord<String, String>> platformDay() {
        UUID vendorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        List<ConsumerRecord<String, String>> events = new ArrayList<>();
        events.add(security("LOGIN_SUCCESS", admin, "meera.ops@grocery.local", null, null, "10.0.0.5", ago(60)));
        events.add(security("LOGIN_FAILURE", null, "intruder@example.com", null, "unknown email", "203.0.113.9",
                ago(50)));
        events.add(security("USER_SUSPENDED", customer, "kavya@example.com", admin, "chargeback fraud", "10.0.0.5",
                ago(45)));
        events.add(settingChanged(ago(40)));
        events.add(event("vendor.events", "VendorApproved", vendorId, map("vendorId", vendorId,
                "ownerUserId", vendorUser, "businessName", "Green Basket", "reason", null, "actorUserId", admin),
                ago(35)));
        events.add(event("order.events", "OrderCancelled", orderId, map("orderId", orderId,
                "orderNumber", "GA-AUDIT001", "orderType", "GROCERY", "customerId", customer, "storeId", vendorId,
                "previousStatus", "CONFIRMED", "reason", "ADMIN_CANCELLED", "cancelledBy", "ADMIN",
                "actorUserId", admin, "note", "customer called support", "cancelledAt", ago(30)), ago(30)));
        events.add(event("pricing.events", "PriceChangeRequested", productId, map("requestId", UUID.randomUUID(),
                "productId", productId, "vendorId", vendorId, "productName", "Ghee 500 ml",
                "oldMrp", money("400.00"), "oldSellingPrice", money("340.00"), "newMrp", money("520.00"),
                "newSellingPrice", money("460.00"), "changePercent", money("35.29"), "requestedBy", vendorUser),
                ago(25)));
        events.add(event("inventory.events", "StockAdjusted", productId, map("productId", productId,
                "vendorId", vendorId, "movementId", UUID.randomUUID(), "type", "DECREASE", "delta", -4, "onHand", 16,
                "reserved", 0, "available", 16, "reason", "damaged in storage", "actorUserId", vendorUser,
                "stockVersion", 7L), ago(20)));
        events.add(event("ai.events", "ApprovalDecided", approvalId, map("approvalId", approvalId,
                "action", "PLACE_ORDER", "agent", "shopping", "requestedBy", customer, "onBehalfOf", null,
                "title", "Place order of ₹245.00", "decision", "REJECTED", "decidedBy", customer,
                "comment", "changed my mind", "decidedAt", ago(15)), ago(15)));
        events.add(orderCreated(grocery(customer, vendorId, "Green Basket", "30.00",
                product(UUID.randomUUID(), "Apples", 1, "50.00")), ago(10)));
        return events;
    }

    private JsonNode search(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/audit").with(as("OPS_ADMIN"));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return EXACT.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private static List<String> actions(JsonNode page) {
        List<String> actions = new ArrayList<>();
        page.get("content").forEach(entry -> actions.add(entry.get("action").asString()));
        return actions;
    }

    @Test
    void everyServicesAuditableActionsAreOneSearchableList() throws Exception {
        List<ConsumerRecord<String, String>> day = platformDay();
        List<Outcome> outcomes = day.stream().map(this::audit).toList();
        assertThat(outcomes.subList(0, 9)).containsOnly(Outcome.WRITTEN);
        assertThat(outcomes.getLast()).isEqualTo(Outcome.IGNORED);                 // OrderCreated: not an action
        assertThat(audit(day.get(3))).isEqualTo(Outcome.DUPLICATE);               // redelivered: no second row

        JsonNode all = search();
        assertThat(all.get("totalElements").asLong()).isEqualTo(9);
        assertThat(actions(all)).containsExactly("APPROVAL_REJECTED", "STOCK_ADJUSTED", "PRICE_CHANGE_REQUESTED",
                "ORDER_CANCELLED", "VENDOR_APPROVED", "SETTING_CHANGED", "USER_SUSPENDED", "LOGIN_FAILURE",
                "LOGIN_SUCCESS");

        // the admin's e-mail, learnt from their own sign-in, is shown with everything they did
        JsonNode cancelled = all.get("content").get(3);
        assertThat(cancelled.get("category").asString()).isEqualTo("ORDERS");
        assertThat(cancelled.get("source").asString()).isEqualTo("order-service");
        assertThat(cancelled.get("summary").asString()).isEqualTo("Order cancelled by admin");
        assertThat(cancelled.get("detail").asString()).isEqualTo("admin cancelled · customer called support");
        assertThat(cancelled.get("actor").get("id").asString()).isEqualTo(admin.toString());
        assertThat(cancelled.get("actor").get("role").asString()).isEqualTo("ADMIN");
        assertThat(cancelled.get("actor").get("email").asString()).isEqualTo("meera.ops@grocery.local");
        assertThat(cancelled.get("actor").get("name").asString()).isEqualTo("Meera Ops");   // from the settings change
        assertThat(cancelled.get("target").get("label").asString()).isEqualTo("GA-AUDIT001");

        JsonNode setting = all.get("content").get(5);
        assertThat(setting.get("summary").asString()).isEqualTo("Setting offersPerWave (delivery) changed");
        assertThat(setting.get("detail").asString()).isEqualTo("3 → 5");
        assertThat(setting.get("target").get("id").asString()).isEqualTo("delivery.offersPerWave");

        JsonNode failure = all.get("content").get(7);
        assertThat(failure.get("category").asString()).isEqualTo("SECURITY");
        assertThat(failure.get("actor").get("role").asString()).isEqualTo("ANONYMOUS");
        assertThat(failure.get("target").get("label").asString()).isEqualTo("intruder@example.com");
        assertThat(failure.get("ipAddress").asString()).isEqualTo("203.0.113.9");

        JsonNode price = all.get("content").get(2);
        assertThat(price.get("detail").asString())
                .isEqualTo("MRP ₹400.00 → ₹520.00, selling price ₹340.00 → ₹460.00 (+35.3%)");
        assertThat(price.get("actor").get("role").asString()).isEqualTo("STORE");

        // filters: words (an e-mail, an order number), categories, the actor, the target
        // her e-mail (learnt from the account event) finds what she did and what was done to her account
        assertThat(actions(search("q", "kavya@example"))).containsExactly("APPROVAL_REJECTED", "USER_SUSPENDED");
        assertThat(actions(search("q", "ga-audit001"))).containsExactly("ORDER_CANCELLED");
        assertThat(actions(search("category", "SECURITY", "category", "accounts")))
                .containsExactly("USER_SUSPENDED", "LOGIN_FAILURE", "LOGIN_SUCCESS");
        assertThat(actions(search("actorId", admin.toString())))
                .containsExactly("ORDER_CANCELLED", "VENDOR_APPROVED", "SETTING_CHANGED", "USER_SUSPENDED",
                        "LOGIN_SUCCESS");                                      // her own sign-in is hers too
        assertThat(actions(search("q", "meera.ops"))).contains("ORDER_CANCELLED", "SETTING_CHANGED");
        assertThat(actions(search("targetType", "USER", "targetId", customer.toString())))
                .containsExactly("USER_SUSPENDED");
        JsonNode firstPage = search("size", "2");
        assertThat(firstPage.get("content")).hasSize(2);
        assertThat(firstPage.get("totalPages").asInt()).isEqualTo(5);
        LocalDate today = LocalDate.now(INDIA);
        assertThat(search("from", today.minusDays(400).toString(), "to", today.minusDays(100).toString())
                .get("totalElements").asLong()).isZero();
    }

    @Test
    void onlyOperationsAdminsMaySearchAndBadFiltersAreRefused() throws Exception {
        mvc.perform(get("/api/admin/audit").with(as("FINANCE_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").with(as("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").with(as("SUPER_ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").param("category", "GOSSIP").with(as("OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/audit").param("size", "500").with(as("OPS_ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSearchIsExportedAsAFile() throws Exception {
        platformDay().forEach(this::audit);

        String csv = mvc.perform(get("/api/admin/audit").param("format", "csv").param("category", "SETTINGS")
                        .with(as("OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-Rows", "1"))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("When", "What happened", "IP address")
                .contains("Setting offersPerWave (delivery) changed").contains("Meera Ops").contains("3 → 5");
    }

    @Test
    void entriesPastTheirRetentionArePurged() {
        Instant monthsAgo = now.minus(200, ChronoUnit.DAYS);
        Instant yearsAgo = now.minus(800, ChronoUnit.DAYS);
        audit(security("LOGIN_SUCCESS", customer, "kavya@example.com", null, null, "10.0.0.9", monthsAgo));
        audit(security("LOGIN_SUCCESS", customer, "kavya@example.com", null, null, "10.0.0.9", ago(5)));
        audit(settingChanged(monthsAgo));
        audit(settingChanged(yearsAgo));

        auditService.purge();

        assertThat(jdbc.sql("SELECT category, occurred_at FROM audit_entry ORDER BY occurred_at")
                .query((rs, n) -> rs.getString("category")).list())
                .as("sign-ins kept 180 days, settings 730 days").containsExactly("SETTINGS", "SECURITY");
    }
}
