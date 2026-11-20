package com.grocery.apitests.ai;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The human-approval lifecycle (Phase 11 §4–5): approve executes exactly once (even under concurrent approvals),
 * reject / cancel / expiry run nothing, duplicates are reused, four-eyes, visibility, and a tampered stored body is
 * refused by the target service.
 */
@Tag("ai")
@Order(25)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI: approval workflow")
class ApprovalWorkflowTest {

    World.Store store;

    @BeforeAll
    void setUp() {
        Assistant.requireScriptedModel();
        store = World.storeA();
    }

    private String proposeOrder(World.Customer customer, int quantity) {
        Chat.send(customer.session(), "shopping", "add " + quantity + " " + store.rice().name()).streamed()
                .tool("addToCart", "SUCCEEDED");
        return Chat.send(customer.session(), "shopping", "checkout").streamed()
                .tool("placeOrder", "APPROVAL_REQUIRED").approvalId();
    }

    private static int orders(World.Customer customer) {
        return Api.as(customer.session()).get("/api/orders?size=100").ok().integer("totalElements");
    }

    @Test
    void concurrent_approvals_execute_exactly_once() throws Exception {
        World.Customer customer = World.newCustomer("Once");
        String id = proposeOrder(customer, 2);
        BigDecimal total = World.previewTotal(customer);
        Res pending = Assistant.approval(customer.session(), id).has("status", "PENDING_APPROVAL");
        assertThat(pending.dec("summary.amount")).isEqualByComparingTo(total);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Res> results = new ArrayList<>();
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Callable<Res> approve = () -> Api.as(customer.session()).body(obj()).post("/api/approvals/" + id + "/approve");
                futures.add(pool.submit(approve));
            }
            for (Future<Res> f : futures) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        List<Res> won = results.stream().filter(r -> r.status() == 200).toList();
        assertThat(won).as("exactly one approval wins: %s", results).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 409).toList()).hasSize(2)
                .allSatisfy(r -> r.problem(409, "APPROVAL_NOT_PENDING"));
        Res done = won.getFirst().has("status", "COMPLETED").has("decidedBy", customer.userId());
        String orderId = done.str("result.orderId");
        World.track(orderId);
        assertThat(done.str("result.orderNumber")).startsWith("GA-");
        Api.as(customer.session()).body(obj()).post("/api/approvals/" + id + "/approve")
                .conflict("APPROVAL_NOT_PENDING", "COMPLETED");
        Api.sleep(1500);
        assertThat(orders(customer)).as("exactly one order").isEqualTo(1);
        Api.as(customer.session()).get("/api/orders/" + orderId).ok().has("total", total).bodyContains(store.rice().name());
    }

    @Test
    void reject_and_cancel_run_nothing_and_close_the_request() {
        World.Customer customer = World.newCustomer("Reject");
        String first = proposeOrder(customer, 1);
        assertThat(Chat.send(customer.session(), "shopping", "checkout").streamed().approvalId())
                .as("an identical pending proposal is reused").isEqualTo(first);
        Api.as(customer.session()).get("/api/approvals?scope=to-decide").ok().page().bodyContains(first);
        Api.as(customer.session()).body(obj()).post("/api/approvals/" + first + "/reject").problem(400, "VALIDATION_FAILED");
        Api.as(customer.session()).body(obj("comment", "Not now")).post("/api/approvals/" + first + "/reject").ok()
                .has("status", "REJECTED").has("comment", "Not now");
        Api.as(customer.session()).body(obj()).post("/api/approvals/" + first + "/approve")
                .conflict("APPROVAL_NOT_PENDING", "REJECTED");
        Api.as(customer.session()).post("/api/approvals/" + first + "/cancel").conflict("APPROVAL_NOT_PENDING", "REJECTED");

        String second = Chat.send(customer.session(), "shopping", "checkout").streamed().approvalId();
        assertThat(second).as("a rejected request is never reused").isNotEqualTo(first);
        Api.as(customer.session()).post("/api/approvals/" + second + "/cancel").ok().has("status", "CANCELLED");
        Api.as(customer.session()).body(obj()).post("/api/approvals/" + second + "/approve")
                .conflict("APPROVAL_NOT_PENDING", "CANCELLED");
        assertThat(orders(customer)).isZero();
        Res mine = Api.as(customer.session()).get("/api/approvals?scope=mine").ok().page();
        assertThat(mine.<String>list("content.status")).contains("REJECTED", "CANCELLED");
        Await.until("requester told it was not approved", Await.seconds(30),
                () -> Api.as(customer.session()).get("/api/notifications"),
                r -> r.status() == 200 && r.body().contains("AI_APPROVAL_DECIDED"));
    }

    @Test
    void an_undecided_request_expires_and_cannot_be_approved_afterwards() {
        World.Customer customer = World.newCustomer("Expiry");
        String id = proposeOrder(customer, 1);
        Res view = Assistant.approval(customer.session(), id);
        Duration ttl = Duration.between(Instant.parse(view.str("createdAt")), Instant.parse(view.str("expiresAt")));
        assumeTrue(ttl.toSeconds() <= 120, "AI_APPROVAL_TTL_CUSTOMER is " + ttl.toMinutes()
                + " min; start the assistant with AI_APPROVAL_TTL_CUSTOMER=30s AI_APPROVAL_EXPIRY_INTERVAL=5s to test expiry");
        Await.until("approval EXPIRED", ttl.plusSeconds(60), () -> Api.as(customer.session()).get("/api/approvals/" + id),
                r -> "EXPIRED".equals(r.str("status")));
        Api.as(customer.session()).body(obj()).post("/api/approvals/" + id + "/approve")
                .conflict("APPROVAL_NOT_PENDING", "EXPIRED");
        assertThat(orders(customer)).isZero();
    }

    @Test
    void a_suspension_needs_a_second_admin() {
        Session vendor = Session.register("VENDOR", "Apit Shady Grocer");
        String vendorId = Await.status("vendor draft", Await.seconds(30), () -> Api.as(vendor).get("/api/vendors/me"), 200)
                .str("id");
        Api.as(vendor).body(obj("businessName", "Apit Shady Mart " + Config.RUN, "legalName", "Apit Shady Pvt Ltd",
                "gstin", "29ABCDE1234F1Z5", "fssaiLicense", "12345678901234", "contactEmail", "ops@shady.example",
                "contactPhone", "+919876543210", "address", obj("line1", "9 Far Road", "city", "Hubballi",
                        "state", "Karnataka", "postalCode", "580020", "country", "IN", "latitude", 15.40,
                        "longitude", 75.10))).put("/api/vendors/me").ok();
        Api.as(vendor).post("/api/vendors/me/submit").ok();
        Api.as(World.admin()).post("/api/admin/vendors/" + vendorId + "/approve").ok();

        Session ops = World.adminWithRole("OPS_ADMIN");
        Chat chat = Chat.send(ops, "admin-bi", "suspend vendor " + vendorId + " because Fraud reports").streamed()
                .tool("suspendVendor", "APPROVAL_REQUIRED");
        JsonPath approval = chat.approval();
        String id = approval.getString("id");
        assertThat(approval.getString("action")).isEqualTo("SUSPEND_VENDOR");
        assertThat(approval.getBoolean("fourEyes")).isTrue();
        Api.as(ops).body(obj()).post("/api/approvals/" + id + "/approve").problem(403, "FOUR_EYES_REQUIRED");
        Api.as(World.admin()).get("/api/admin/vendors/" + vendorId).ok().has("status", "APPROVED");
        Api.as(World.adminWithRole("SUPER_ADMIN")).body(obj("comment", "Confirmed")).post("/api/approvals/" + id + "/approve")
                .ok().has("status", "COMPLETED");
        Await.field("vendor SUSPENDED", Await.seconds(20), () -> Api.as(World.admin()).get("/api/admin/vendors/" + vendorId),
                "status", "SUSPENDED");
    }

    @Test
    void a_tampered_stored_body_is_refused_by_the_target_service() throws Exception {
        assumeTrue(!Config.MYSQL_URL.isBlank(), "needs database access to tamper with the stored request: run with "
                + "-DMYSQL_URL=jdbc:mysql://<host>:<port>/ai_db (and MYSQL_USER / MYSQL_PASSWORD)");
        World.Customer customer = World.newCustomer("Tampered");
        World.Order order = World.paidGroceryOrder(customer, store.rice(), 1);
        Session ops = World.adminWithRole("OPS_ADMIN");
        String id = Chat.send(ops, "admin-bi", "refund 10 for " + order.number() + " because Damaged").streamed()
                .tool("refundOrder", "APPROVAL_REQUIRED").approvalId();
        try (Connection db = DriverManager.getConnection(Config.MYSQL_URL, Config.MYSQL_USER, Config.MYSQL_PASSWORD);
             PreparedStatement update = db.prepareStatement("UPDATE ai_db.approval_request SET request_body = "
                     + "REGEXP_REPLACE(request_body, '\"amount\":[0-9.]+', '\"amount\":99.00') WHERE id = UUID_TO_BIN(?)")) {
            update.setString(1, id);
            assertThat(update.executeUpdate()).as("tampered rows").isEqualTo(1);
        }
        Res result = Api.as(World.adminWithRole("FINANCE_ADMIN")).body(obj("comment", "ok"))
                .post("/api/approvals/" + id + "/approve").ok().has("status", "FAILED")
                .has("failure.code", "APPROVAL_BODY_MISMATCH");
        assertThat(result.integer("failure.httpStatus")).isEqualTo(403);
        Api.as(World.adminWithRole("FINANCE_ADMIN")).get("/api/payments/orders/" + order.id()).ok()
                .has("refundedAmount", BigDecimal.ZERO);
        assertThat(Api.as(World.adminWithRole("FINANCE_ADMIN")).get("/api/payments/orders/" + order.id())
                .<Object>list("refunds")).isEmpty();
    }
}
