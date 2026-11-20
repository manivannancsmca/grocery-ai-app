package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 conventions that hold for every service: public health through the gateway, correlation ids, the
 * ProblemDetail error shape and the gateway's own 401.
 */
@Tag("api")
@Tag("smoke")
@Order(1)
@DisplayName("Health and API conventions")
class HealthAndConventionsApiTest {

    @ParameterizedTest(name = "GET /api/health/{0} → 200 UP")
    @ValueSource(strings = {"gateway", "identity", "customer", "vendor", "restaurant", "delivery", "catalog-command",
            "catalog-query", "inventory", "pricing", "cart", "order", "payment", "notification", "ai", "review",
            "reporting"})
    void every_service_is_up_through_the_gateway(String service) {
        Api.anon().get("/api/health/" + service).ok().has("status", "UP");
    }

    @Test
    void a_valid_correlation_id_is_echoed_and_an_unsafe_one_is_replaced() {
        String mine = "apit-" + Config.RUN + "-echo";
        Res echoed = Api.anon().correlationId(mine).get("/api/health/order").ok();
        assertThat(echoed.header("X-Correlation-Id")).isEqualTo(mine);

        Res replaced = Api.anon().correlationId("bad id <script>").get("/api/health/order").ok();
        assertThat(replaced.header("X-Correlation-Id")).isNotBlank().isNotEqualTo("bad id <script>")
                .matches("[A-Za-z0-9._-]{8,64}");
    }

    @Test
    void a_missing_token_is_a_401_problem_detail_with_the_request_correlation_id() {
        String mine = "apit-" + Config.RUN + "-unauth";
        Res r = Api.anon().correlationId(mine).get("/api/orders").problem(401, "UNAUTHENTICATED");
        assertThat(r.str("correlationId")).isEqualTo(mine);
        assertThat(r.header("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    void a_service_error_is_a_problem_detail_with_errorCode_and_correlationId() {
        Res r = Api.anon().get("/api/catalog/products/00000000-0000-7000-8000-000000000000")
                .problem(404, "PRODUCT_NOT_FOUND");
        assertThat(r.str("correlationId")).isNotBlank();
        assertThat(r.str("detail")).isNotBlank();
    }

    /**
     * Phase 2 §1 "every response echoes one", also for responses the gateway writes itself: the edge 401/403 and
     * {@code /api/health/gateway} carry {@code X-Correlation-Id}, and an edge 401/403 has {@code correlationId} in its
     * ProblemDetail. (Found by this suite as a defect in Phase 13 and fixed with the gateway's CorrelationIdWebFilter.)
     */
    @Test
    void gateway_generated_responses_echo_a_correlation_id() {
        assertThat(Api.anon().correlationId("apit-" + Config.RUN + "-gw").get("/api/health/gateway").ok()
                .header("X-Correlation-Id")).isNotBlank();
        Res unauthenticated = Api.anon().get("/api/orders").problem(401, "UNAUTHENTICATED");
        assertThat(unauthenticated.header("X-Correlation-Id")).isNotBlank();
        assertThat(unauthenticated.str("correlationId")).isNotBlank();
    }

    @Test
    void public_catalog_reads_need_no_token() {
        Api.anon().get("/api/catalog/categories").ok();
        Api.anon().get("/api/search/products?q=rice&size=5").ok().page();
        // the store directory (vendor-service): the stores delivering to a point in Selaiyur, Chennai
        Res nearby = Api.anon().get("/api/stores/nearby?lat=12.9202&lng=80.1445").ok();
        assertThat(nearby.dec("deliveryLimitKm")).isEqualByComparingTo("15.0");
        assertThat(nearby.<Object>list("content")).isNotNull();
        Api.anon().get("/api/stores/00000000-0000-7000-8000-000000000000").problem(404, "STORE_NOT_FOUND");
        Api.anon().get("/api/stores/nearby?lat=12.9202").problem(400, "INVALID_PARAMETER");
    }
}
