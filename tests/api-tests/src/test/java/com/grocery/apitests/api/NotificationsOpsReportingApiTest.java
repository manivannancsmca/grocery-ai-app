package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phases 7, 10, 11 and 12: inbox and stream, operations (dead letters, connectors, stuck orders), AI audit, reports. */
@Tag("api")
@Order(8)
@DisplayName("Notifications, operations, AI audit and reporting API")
class NotificationsOpsReportingApiTest {

    @Test
    void the_inbox_is_paged_and_only_the_callers_own() {
        World.Customer customer = World.newCustomer("Inbox");
        World.Order order = World.paidGroceryOrder(customer, World.storeA().rice(), 1);
        Res inbox = Await.until("ORDER_CONFIRMED notification", Await.seconds(30),
                () -> Api.as(customer.session()).get("/api/notifications"),
                r -> r.status() == 200 && r.body().contains("ORDER_CONFIRMED"));
        inbox.page();
        Map<String, Object> confirmed = inbox.<Map<String, Object>>list("content").stream()
                .filter(n -> "ORDER_CONFIRMED".equals(n.get("type"))).findFirst().orElseThrow();
        assertThat(confirmed).containsEntry("category", "ORDER").containsEntry("severity", "SUCCESS")
                .containsEntry("read", false).containsKeys("id", "title", "body", "link", "data", "createdAt");
        assertThat((Map<String, Object>) confirmed.get("data")).containsEntry("orderId", order.id())
                .containsEntry("orderNumber", order.number());
        int unread = Api.as(customer.session()).get("/api/notifications/unread-count").ok().integer("count");
        assertThat(unread).isPositive();

        Api.as(customer.session()).post("/api/notifications/" + confirmed.get("id") + "/read").expect(204);
        Api.as(customer.session()).post("/api/notifications/" + confirmed.get("id") + "/read").expect(204);
        Api.as(customer.session()).post("/api/notifications/00000000-0000-7000-8000-000000000000/read")
                .problem(404, "NOTIFICATION_NOT_FOUND");
        Api.as(customer.session()).post("/api/notifications/read-all").ok();
        Api.as(customer.session()).get("/api/notifications/unread-count").ok().has("count", 0);
        Api.as(customer.session()).get("/api/notifications?size=500").ok().has("size", 50);

        Api.as(customer.session()).get("/api/notifications/preferences").ok()
                .has("email.orders", true).has("email.account", true).has("email.approvals", true);
        Api.as(customer.session()).body(obj("email", obj("orders", false, "account", true, "approvals", true)))
                .put("/api/notifications/preferences").ok().has("email.orders", false);
    }

    @Test
    void the_stream_is_server_sent_events_and_needs_a_token() throws Exception {
        Api.anon().get("/api/notifications/stream").problem(401, "UNAUTHENTICATED");
        Session user = World.newCustomer("Stream").session();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(Config.GATEWAY_URL + "/api/notifications/stream"))
                .header("Authorization", "Bearer " + user.token()).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            String first = reader.readLine();
            while (first != null && first.isBlank()) {
                first = reader.readLine();
            }
            assertThat(first).isNotNull().matches("retry: ?3000");
        }
    }

    @ParameterizedTest(name = "dead letters of {0}")
    @ValueSource(strings = {"identity", "order", "payment", "notification", "catalog-query", "cart", "review", "reporting"})
    void every_consuming_service_exposes_its_dead_letters(String service) {
        Session ops = World.adminWithRole("OPS_ADMIN");
        Api.as(ops).get("/api/admin/ops/" + service + "/dead-letters?size=5").ok().page();
        Api.as(ops).get("/api/admin/ops/" + service + "/dead-letters/apit.DLT:0:999999999")
                .problem(404, "DEAD_LETTER_NOT_FOUND");
    }

    @Test
    void operations_endpoints_answer_with_the_documented_shapes() {
        Session ops = World.adminWithRole("OPS_ADMIN");
        Res connectors = Api.as(ops).get("/api/admin/system/connectors").ok();
        assertThat(connectors.map("$")).containsKey("order-outbox");
        assertThat(connectors.str("'order-outbox'.status.connector.state")).isEqualTo("RUNNING");
        Api.as(ops).get("/api/admin/orders/stuck").ok();
        assertThat(Api.as(ops).get("/api/admin/payments/reconciliation").ok().map("$"))
                .containsKeys("lastRunAt", "checked", "recovered", "failures", "lastRecovered");
        Api.as(ops).get("/api/admin/payments?size=5").ok().page();
        Api.as(ops).get("/api/admin/notifications/emails?size=5").ok().page();
        Api.as(ops).body(obj("consumerGroup", "apit-no-such-group")).post("/api/admin/ops/order/dead-letters/replay-all")
                .ok().has("attempted", 0);
    }

    @Test
    void ai_monitoring_and_audit() {
        Session ops = World.adminWithRole("OPS_ADMIN");
        Api.as(ops).get("/api/ai/status").ok();
        Res summary = Api.as(ops).get("/api/admin/ai/summary").ok();
        assertThat(summary.map("$")).containsKeys("date", "conversations", "messages", "toolCalls", "blocked", "failed",
                "approvals", "modelErrors", "provider", "available");
        assertThat(summary.map("approvals")).containsKeys("requested", "completed", "rejected", "expired", "failed",
                "cancelled", "pending");
        Api.as(ops).get("/api/admin/ai/tool-invocations?size=5").ok().page();
    }

    @Test
    void reports_answer_json_and_csv_and_limit_the_range() {
        Session finance = World.adminWithRole("FINANCE_ADMIN");
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        Res overview = Api.as(finance).get("/api/admin/reports/overview?from=" + today + "&to=" + today).ok();
        assertThat(overview.body()).contains("grossSales");
        Res csv = Api.as(finance).get("/api/admin/reports/overview?from=" + today + "&to=" + today + "&format=csv").ok();
        assertThat(csv.contentType()).startsWith("text/csv");
        assertThat(csv.body().lines().findFirst().orElse("")).contains("grossSales");
        for (String report : List.of("sales/daily", "stores/top", "products/top", "delivery/performance", "promotions",
                "ratings", "purchasing")) {
            Api.as(finance).get("/api/admin/reports/" + report + "?from=" + today + "&to=" + today).ok();
        }
        Res tooLong = Api.as(finance).get("/api/admin/reports/overview?from=2024-01-01&to=" + today);
        tooLong.problem(400, null);
    }
}
