package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12 §2 and §4: coupons (store and platform) through cart and order, and vendor purchasing. */
@Tag("api")
@Order(7)
@DisplayName("Coupons and purchasing API")
class CouponsPurchasingApiTest {

    private static final String VALID_UNTIL = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

    @Test
    void a_store_coupon_applies_in_the_cart_and_the_order() {
        World.Store store = World.storeA();
        String code = ("APS" + Config.RUN).toUpperCase();
        Res coupon = Api.as(store.vendor()).body(obj("code", code, "name", "Twenty off", "scope", "STORE",
                "discountType", "FLAT", "value", new BigDecimal("20.00"), "minOrderSubtotal", new BigDecimal("150.00"),
                "validUntil", VALID_UNTIL, "perCustomerLimit", 5, "public", true)).post("/api/vendor/coupons").created()
                .has("code", code).has("scope", "STORE");
        assertThat(coupon.str("id")).isNotBlank();
        Api.as(store.vendor()).body(obj("code", code, "name", "Again", "scope", "STORE", "discountType", "FLAT",
                "value", new BigDecimal("5.00"), "validUntil", VALID_UNTIL)).post("/api/vendor/coupons")
                .problem(409, "COUPON_CODE_TAKEN");
        Api.as(store.vendor()).body(obj("code", code + "X", "name", "Platform", "scope", "PLATFORM",
                "discountType", "FLAT", "value", new BigDecimal("5.00"), "validUntil", VALID_UNTIL))
                .post("/api/vendor/coupons").expect(403);

        World.Customer customer = World.newCustomer("Coupon");
        Api.as(customer.session()).get("/api/coupons?storeId=" + store.vendorId()).ok().bodyContains(code);
        World.fillCart(customer, store.rice(), 1);
        Api.as(customer.session()).body(obj("code", code.toLowerCase())).put("/api/cart/coupon")
                .problem(422, "COUPON_NOT_APPLICABLE").has("reason", "COUPON_MIN_ORDER")
                .has("minOrderSubtotal", new BigDecimal("150.00"));
        World.fillCart(customer, store.rice(), 2);
        Api.as(customer.session()).body(obj("code", code.toLowerCase())).put("/api/cart/coupon").ok()
                .has("coupon.code", code).has("coupon.valid", true);
        Res preview = Api.as(customer.session()).get("/api/cart/preview").ok()
                .has("discount", new BigDecimal("20.00")).has("canCheckout", true);
        assertThat(preview.dec("total")).isEqualByComparingTo(new BigDecimal("200.00").add(preview.dec("delivery.fee"))
                .subtract(new BigDecimal("20.00")).subtract(preview.dec("deliveryFeeDiscount")));
        World.Order order = World.place(customer, World.key("coupon"));
        order.created().has("couponCode", code).has("discount", new BigDecimal("20.00")).has("total", preview.dec("total"));
        Api.as(World.adminWithRole("FINANCE_ADMIN")).get("/api/admin/coupons/" + coupon.str("id") + "/usage").ok();
        Api.as(customer.session()).delete("/api/cart/coupon").ok().has("coupon", null);
    }

    @Test
    void platform_coupons_are_for_finance_and_ops_only() {
        String code = ("APP" + Config.RUN).toUpperCase();
        Map<String, Object> body = obj("code", code, "name", "Platform ten", "scope", "PLATFORM", "storeType", "ANY",
                "discountType", "PERCENT", "value", 10, "maxDiscount", new BigDecimal("50.00"), "validUntil", VALID_UNTIL,
                "totalUsageLimit", 10, "perCustomerLimit", 1, "public", false);
        Api.as(World.adminWithRole("SUPPORT")).body(body).post("/api/admin/coupons").expect(403);
        String id = Api.as(World.adminWithRole("FINANCE_ADMIN")).body(body).post("/api/admin/coupons").created()
                .has("scope", "PLATFORM").str("id");
        Api.as(World.adminWithRole("OPS_ADMIN")).post("/api/admin/coupons/" + id + "/pause").ok().has("active", false);
        Api.as(World.adminWithRole("OPS_ADMIN")).post("/api/admin/coupons/" + id + "/resume").ok().has("active", true);
        Api.as(World.adminWithRole("SUPPORT")).get("/api/admin/coupons/" + id).ok().has("code", code);
        Map<String, Object> invalid = obj("code", "x!", "name", "Bad", "scope", "PLATFORM", "discountType", "PERCENT",
                "value", 95, "validUntil", VALID_UNTIL);
        Api.as(World.adminWithRole("FINANCE_ADMIN")).body(invalid).post("/api/admin/coupons").problem(400, null);
    }

    @Test
    void purchase_orders_receive_stock_once_and_refuse_over_receipt() {
        World.Store store = World.storeA();
        World.Product product = World.createProduct(store.vendor(), "Apit Purchase " + Config.TAG, "60.00", "50.00", 0);
        String supplier = Api.as(store.vendor()).body(obj("name", "Apit Mills " + Config.RUN, "contactName", "Suresh",
                "phone", "+919811111111", "email", "orders@mills.example", "leadTimeDays", 2))
                .post("/api/vendor/suppliers").created().str("id");
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        Res po = Api.as(store.vendor()).body(obj("supplierId", supplier, "expectedAt", today,
                "lines", List.of(obj("productId", product.id(), "orderedQuantity", 5, "unitCost", new BigDecimal("40.00")))))
                .post("/api/vendor/purchase-orders").created().has("status", "DRAFT");
        assertThat(po.str("number")).matches("PO-[A-Z0-9]{8}");
        String id = po.str("id");
        Api.as(store.vendor()).body(obj()).post("/api/vendor/purchase-orders/" + id + "/send").ok().has("status", "SENT");
        Api.as(store.vendor()).body(obj()).post("/api/vendor/purchase-orders/" + id + "/send")
                .conflict("INVALID_STATUS_TRANSITION", "SENT");
        Api.as(store.vendor()).idempotencyKey(World.key("recv-over"))
                .body(obj("lines", List.of(obj("productId", product.id(), "quantity", 6))))
                .post("/api/vendor/purchase-orders/" + id + "/receive").problem(422, "RECEIPT_EXCEEDS_ORDERED");
        String key = World.key("recv");
        Map<String, Object> receipt = obj("lines", List.of(obj("productId", product.id(), "quantity", 3)), "note", "Truck 1");
        Api.as(store.vendor()).idempotencyKey(key).body(receipt).post("/api/vendor/purchase-orders/" + id + "/receive")
                .ok().has("status", "PARTIALLY_RECEIVED");
        Api.as(store.vendor()).idempotencyKey(key).body(receipt).post("/api/vendor/purchase-orders/" + id + "/receive")
                .ok().has("status", "PARTIALLY_RECEIVED");
        Await.field("stock +3 from the receipt", Await.seconds(30),
                () -> Api.as(store.vendor()).get("/api/vendor/inventory/" + product.id()), "onHand", 3);
        Api.sleep(3000);
        Api.as(store.vendor()).get("/api/vendor/inventory/" + product.id()).ok().has("onHand", 3);
        Api.as(store.vendor()).body(obj()).post("/api/vendor/purchase-orders/" + id + "/close").ok().has("status", "CLOSED");
        Api.as(World.adminWithRole("SUPPORT")).get("/api/admin/purchase-orders?vendorId=" + store.vendorId()).ok().page()
                .bodyContains(id);
    }
}
