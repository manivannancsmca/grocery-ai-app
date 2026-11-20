package com.grocery.apitests.security;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.grocery.apitests.security.Role.ADMINS;
import static com.grocery.apitests.security.Role.ANONYMOUS;
import static com.grocery.apitests.security.Role.CUSTOMER;
import static com.grocery.apitests.security.Role.DELIVERY_MANAGER;
import static com.grocery.apitests.security.Role.DELIVERY_PARTNER;
import static com.grocery.apitests.security.Role.EVERYONE;
import static com.grocery.apitests.security.Role.FINANCE_ADMIN;
import static com.grocery.apitests.security.Role.OPS_ADMIN;
import static com.grocery.apitests.security.Role.RESTAURANT_OWNER;
import static com.grocery.apitests.security.Role.SIGNED_IN;
import static com.grocery.apitests.security.Role.SUPER_ADMIN;
import static com.grocery.apitests.security.Role.SUPPORT;
import static com.grocery.apitests.security.Role.VENDOR;
import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization matrix (Phase 13 §4): every caller identity × representative public, customer, store, partner,
 * admin and assistant endpoints. A role that may call an endpoint gets its documented success (or, for actions on a
 * random id, the documented 404 — proof that it passed the authorization check); every other signed-in role gets
 * 403 and an anonymous caller 401, both as ProblemDetail.
 */
@Tag("security")
@Order(10)
@DisplayName("Authorization matrix")
class AuthorizationMatrixTest {

    /** A random id that exists nowhere: the allowed roles get the documented 404, the others 403. */
    static final String NONE = "01a1d000-0000-7000-8000-00000000abcd";

    /** One endpoint: who may call it, and what they get. */
    record Row(String method, String path, Supplier<Object> body, Set<Role> allowed, int allowedStatus,
               String allowedErrorCode, boolean idempotencyKey, boolean stream) {

        String label() {
            return method + " " + path;
        }
    }

    static Row get(String path, Set<Role> allowed) {
        return new Row("GET", path, null, allowed, 200, null, false, false);
    }

    static Row action(String method, String path, Supplier<Object> body, Set<Role> allowed, int status, String errorCode) {
        return new Row(method, path, body, allowed, status, errorCode, false, false);
    }

    static Row chat(String agent, Set<Role> allowed) {
        return new Row("POST", "/api/ai/agents/" + agent + "/chat", () -> obj("message", "hello"), allowed, 200, null,
                false, true);
    }

    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        // ---- public reads and health
        rows.add(get("/api/search/products?q=rice&size=1", EVERYONE));
        rows.add(get("/api/catalog/categories", EVERYONE));
        rows.add(get("/api/health/order", EVERYONE));
        rows.add(get("/api/reviews/summary?targetType=PRODUCT&targetId=" + NONE, EVERYONE));
        rows.add(get("/api/stores/nearby?lat=12.9202&lng=80.1445", EVERYONE));
        rows.add(action("GET", "/api/stores/" + NONE, null, EVERYONE, 404, "STORE_NOT_FOUND"));
        // ---- password management: forgot and reset are public (forgot answers alike for every address; an unknown
        // token is refused); change needs a session (a too-short new password is refused before anything else)
        rows.add(action("POST", "/api/auth/password/forgot", () -> obj("email", Session.uniqueEmail("NOBODY")),
                EVERYONE, 202, null));
        rows.add(action("POST", "/api/auth/password/reset", () -> obj("token", "apit-no-such-token",
                "newPassword", "Matrix2026x"), EVERYONE, 400, "RESET_TOKEN_INVALID"));
        // ---- any signed-in user
        rows.add(get("/api/auth/me", SIGNED_IN));
        rows.add(action("POST", "/api/auth/password/change", () -> obj("currentPassword", "x", "newPassword", "short"),
                SIGNED_IN, 400, "VALIDATION_FAILED"));
        rows.add(get("/api/notifications/unread-count", SIGNED_IN));
        rows.add(get("/api/notifications/preferences", SIGNED_IN));
        rows.add(get("/api/approvals", SIGNED_IN));
        rows.add(get("/api/ai/agents", SIGNED_IN));
        rows.add(get("/api/ai/conversations", SIGNED_IN));
        // ---- customer
        rows.add(get("/api/customers/me", Role.of(CUSTOMER)));
        rows.add(get("/api/customers/me/addresses", Role.of(CUSTOMER)));
        rows.add(get("/api/cart", Role.of(CUSTOMER)));
        rows.add(get("/api/cart/preview", Role.of(CUSTOMER)));
        rows.add(get("/api/orders", Role.of(CUSTOMER)));
        rows.add(get("/api/reviews/mine", Role.of(CUSTOMER)));
        rows.add(get("/api/reviews/eligible", Role.of(CUSTOMER)));
        rows.add(get("/api/returns", Role.of(CUSTOMER)));
        rows.add(get("/api/coupons?storeId=" + NONE, Role.of(CUSTOMER)));
        rows.add(action("POST", "/api/orders/" + NONE + "/cancel", () -> obj(), Role.of(CUSTOMER), 404, "ORDER_NOT_FOUND"));
        rows.add(action("GET", "/api/payments/orders/" + NONE, null, Role.adminsAnd(CUSTOMER), 404, "PAYMENT_NOT_FOUND"));
        rows.add(action("GET", "/api/delivery/orders/" + NONE + "/tracking", null, Role.adminsAnd(CUSTOMER), 404,
                "TRACKING_NOT_FOUND"));
        // ---- vendor
        rows.add(get("/api/vendors/me", Role.of(VENDOR)));
        rows.add(get("/api/vendor/products", Role.of(VENDOR)));
        rows.add(get("/api/vendor/inventory", Role.of(VENDOR)));
        rows.add(get("/api/vendor/prices", Role.of(VENDOR)));
        rows.add(get("/api/vendor/orders", Role.of(VENDOR)));
        rows.add(get("/api/vendor/reports/sales", Role.of(VENDOR)));
        rows.add(get("/api/vendor/suppliers", Role.of(VENDOR)));
        rows.add(get("/api/vendor/purchase-orders", Role.of(VENDOR)));
        rows.add(get("/api/vendor/coupons", Role.of(VENDOR)));
        // ---- restaurant owner
        rows.add(get("/api/restaurants/me", Role.of(RESTAURANT_OWNER)));
        rows.add(get("/api/restaurant/menu", Role.of(RESTAURANT_OWNER)));
        rows.add(get("/api/restaurant/orders", Role.of(RESTAURANT_OWNER)));
        rows.add(get("/api/restaurant/coupons", Role.of(RESTAURANT_OWNER)));
        // ---- either store
        rows.add(get("/api/store/reviews", Role.of(VENDOR, RESTAURANT_OWNER)));
        rows.add(get("/api/store/returns", Role.of(VENDOR, RESTAURANT_OWNER)));
        rows.add(get("/api/delivery/store/deliveries", Role.of(VENDOR, RESTAURANT_OWNER)));
        // ---- delivery partner
        rows.add(get("/api/delivery/partners/me/status", Role.of(DELIVERY_PARTNER)));
        rows.add(get("/api/delivery/offers", Role.of(DELIVERY_PARTNER)));
        rows.add(get("/api/delivery/history", Role.of(DELIVERY_PARTNER)));
        // ---- admin reads (any admin role)
        for (String path : List.of("/api/admin/customers", "/api/admin/vendors", "/api/admin/restaurants",
                "/api/admin/delivery-partners", "/api/admin/categories", "/api/admin/products",
                "/api/admin/search/indexes", "/api/admin/price-change-requests", "/api/admin/orders",
                "/api/admin/orders/stuck", "/api/admin/stats", "/api/admin/payments", "/api/admin/payments/reconciliation",
                "/api/admin/deliveries", "/api/admin/reviews", "/api/admin/returns", "/api/admin/coupons",
                "/api/admin/purchase-orders", "/api/admin/notifications/emails", "/api/admin/reports/overview",
                "/api/admin/ai/summary", "/api/admin/ai/tool-invocations", "/api/admin/ops/order/dead-letters",
                "/api/admin/system/connectors", "/api/admin/settings/delivery", "/api/admin/settings/grocery-stores",
                "/api/admin/settings/restaurants", "/api/admin/settings/returns", "/api/admin/settings/reviews",
                "/api/admin/settings/delivery-fees", "/api/admin/settings/delivery/history",
                "/api/admin/settings/grocery-stores/history", "/api/admin/settings/restaurants/history",
                "/api/admin/settings/returns/history", "/api/admin/settings/reviews/history",
                "/api/admin/settings/delivery-fees/history")) {
            rows.add(get(path, ADMINS));
        }
        // ---- admin actions restricted to some admin roles (on a random id: allowed → 404, others → 403)
        rows.add(get("/api/admin/users", Role.of(SUPER_ADMIN, OPS_ADMIN, SUPPORT)));
        rows.add(action("POST", "/api/admin/users", () -> obj("email", Session.uniqueEmail("SUPPORT"),
                "fullName", "Apit Matrix", "password", World.ADMIN_PASSWORD, "roles", List.of("SUPPORT")),
                Role.of(SUPER_ADMIN), 201, null));
        rows.add(action("POST", "/api/admin/users/" + NONE + "/suspend", () -> obj("reason", "matrix"),
                Role.of(SUPER_ADMIN, OPS_ADMIN), 404, "USER_NOT_FOUND"));
        rows.add(action("POST", "/api/admin/vendors/" + NONE + "/approve", null, Role.of(SUPER_ADMIN, OPS_ADMIN), 404,
                "VENDOR_NOT_FOUND"));
        rows.add(action("POST", "/api/admin/restaurants/" + NONE + "/approve", null, Role.of(SUPER_ADMIN, OPS_ADMIN), 404,
                "RESTAURANT_NOT_FOUND"));
        rows.add(action("POST", "/api/admin/delivery-partners/" + NONE + "/approve", null,
                Role.of(SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER), 404, "PARTNER_NOT_FOUND"));
        rows.add(action("POST", "/api/admin/categories/" + NONE + "/deactivate", null, Role.of(SUPER_ADMIN, OPS_ADMIN),
                404, null));
        rows.add(action("POST", "/api/admin/price-change-requests/" + NONE + "/approve", () -> obj("note", "matrix"),
                Role.of(SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN), 404, "PRICE_CHANGE_REQUEST_NOT_FOUND"));
        rows.add(action("POST", "/api/admin/orders/" + NONE + "/cancel", () -> obj("reason", "matrix"),
                Role.of(SUPER_ADMIN, OPS_ADMIN, SUPPORT), 404, "ORDER_NOT_FOUND"));
        rows.add(new Row("POST", "/api/admin/refunds", () -> obj("orderId", NONE, "note", "matrix"),
                Role.of(SUPER_ADMIN, FINANCE_ADMIN), 404, "PAYMENT_NOT_FOUND", true, false));
        rows.add(action("POST", "/api/admin/deliveries/" + NONE + "/reassign", () -> obj("reason", "matrix"),
                Role.of(SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER), 404, null));
        rows.add(action("POST", "/api/admin/reviews/" + NONE + "/publish", null, Role.of(SUPER_ADMIN, OPS_ADMIN, SUPPORT),
                404, null));
        rows.add(action("POST", "/api/admin/returns/" + NONE + "/approve", () -> obj("restock", false),
                Role.of(SUPER_ADMIN, OPS_ADMIN, SUPPORT), 404, null));
        rows.add(action("POST", "/api/admin/coupons/" + NONE + "/pause", null, Role.of(SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN),
                404, null));
        rows.add(action("POST", "/api/admin/ops/order/dead-letters/order.events.DLT:0:999999999/replay", null,
                Role.of(SUPER_ADMIN, OPS_ADMIN), 404, "DEAD_LETTER_NOT_FOUND"));
        rows.add(action("POST", "/api/admin/system/connectors/apit-no-such-connector/restart", () -> obj(),
                Role.of(SUPER_ADMIN, OPS_ADMIN), 404, null));
        // platform settings: a save from a version that never existed changes nothing (allowed → 409, others → 403)
        for (String area : List.of("delivery", "grocery-stores", "restaurants", "returns", "reviews", "delivery-fees")) {
            rows.add(action("PUT", "/api/admin/settings/" + area,
                    () -> obj("values", obj(), "expectedVersion", 999_999_999),
                    Role.of(SUPER_ADMIN, OPS_ADMIN), 409, "SETTINGS_CHANGED"));
        }
        // ---- assistants: the agent comes from the URL and is checked against the caller's role
        rows.add(chat("shopping", Role.of(CUSTOMER)));
        rows.add(chat("admin-bi", ADMINS));
        rows.add(chat("vendor", Role.of(VENDOR)));
        rows.add(chat("restaurant", Role.of(RESTAURANT_OWNER)));
        rows.add(chat("delivery", Role.of(DELIVERY_PARTNER, DELIVERY_MANAGER)));
        rows.add(action("POST", "/api/ai/agents/admin-bi/insights", () -> obj(), ADMINS, 200, null));
        return rows;
    }

    @TestFactory
    Stream<DynamicNode> every_role_against_every_endpoint() {
        return rows().stream().map(row -> DynamicContainer.dynamicContainer(row.label(),
                EVERYONE.stream().map(role -> DynamicTest.dynamicTest(role + " → " + expected(row, role),
                        () -> check(row, role)))));
    }

    static int expected(Row row, Role role) {
        if (row.allowed().contains(role)) {
            return row.allowedStatus();
        }
        return role == ANONYMOUS ? 401 : 403;
    }

    static void check(Row row, Role role) {
        Session session = role.session();
        Api.Req req = Api.as(session);
        if (row.body() != null) {
            req.body(row.body().get());
        }
        if (row.idempotencyKey()) {
            req.idempotencyKey(World.key("matrix"));
        }
        if (row.stream()) {
            req.accept("text/event-stream");
        }
        Res r = req.send(row.method(), row.path());
        int expected = expected(row, role);
        assertThat(r.status()).as("%s as %s: %s", row.label(), role, r.describe()).isEqualTo(expected);
        if (expected == 401) {
            r.problem(401, "UNAUTHENTICATED");
        } else if (expected == 403) {
            r.problem(403, null);
        } else if (row.allowedErrorCode() != null) {
            r.problem(expected, row.allowedErrorCode());
        }
        if (row.stream() && expected == 200) {
            assertThat(r.contentType()).startsWith("text/event-stream");
            assertThat(r.body()).contains("event:").contains("conversation");
        }
    }

    @Test
    @Tag("smoke")
    void smoke_a_customer_is_kept_out_of_admin_store_and_partner_apis() {
        Session customer = Session.register("CUSTOMER", "Apit Smoke Matrix");
        Api.as(customer).get("/api/admin/orders").problem(403, "FORBIDDEN");
        Api.as(customer).get("/api/admin/users").problem(403, "FORBIDDEN");
        Api.as(customer).get("/api/vendor/products").problem(403, null);
        Api.as(customer).get("/api/delivery/offers").problem(403, null);
        Api.as(customer).get("/api/restaurant/menu").problem(403, null);
        Api.as(customer).get("/api/auth/me").ok();
        Api.anon().get("/api/cart").problem(401, "UNAUTHENTICATED");
        Api.anon().get("/api/admin/stats").problem(401, "UNAUTHENTICATED");
    }

    @Test
    void the_agents_offered_follow_the_role() {
        assertThat(agents(CUSTOMER)).containsExactly("shopping");
        assertThat(agents(VENDOR)).containsExactly("vendor");
        assertThat(agents(RESTAURANT_OWNER)).containsExactly("restaurant");
        assertThat(agents(DELIVERY_PARTNER)).containsExactly("delivery");
        assertThat(agents(DELIVERY_MANAGER)).containsExactlyInAnyOrder("admin-bi", "delivery");
        for (Role admin : List.of(SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT)) {
            assertThat(agents(admin)).as("agents of %s", admin).containsExactly("admin-bi");
        }
        Api.as(CUSTOMER.session()).accept("text/event-stream").body(obj("message", "stats"))
                .post("/api/ai/agents/admin-bi/chat").problem(403, "AGENT_NOT_ALLOWED");
        Api.as(CUSTOMER.session()).accept("text/event-stream").body(obj("message", "hello"))
                .post("/api/ai/agents/no-such-agent/chat").problem(404, "AGENT_NOT_FOUND");
    }

    private static List<String> agents(Role role) {
        return Api.as(role.session()).get("/api/ai/agents").ok().list("id");
    }
}
