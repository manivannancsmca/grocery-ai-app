package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import com.grocery.apitests.support.World.Customer;
import com.grocery.apitests.support.World.Store;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phases 5–6 and 12: cart rules, checkout preview, placing orders (idempotency, live checks), payments, refunds. */
@Tag("api")
@Order(4)
@DisplayName("Cart, orders and payments API")
class CartOrderPaymentApiTest {

    @Test
    void an_empty_cart_and_cart_line_rules() {
        Customer customer = World.newCustomer("Cart");
        Store store = World.storeA();
        Api.as(customer.session()).get("/api/cart").ok().has("store", null).has("itemCount", 0);
        assertThat(Api.as(customer.session()).get("/api/cart").<Object>list("lines")).isEmpty();

        Res cart = Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", store.rice().id(), "quantity", 2))
                .post("/api/cart/items").ok()
                .has("store.id", store.vendorId()).has("store.type", "GROCERY").has("itemCount", 2)
                .has("subtotal", new BigDecimal("200.00")).has("mrpTotal", new BigDecimal("240.00"))
                .has("savings", new BigDecimal("40.00")).has("currency", "INR");
        assertThat(cart.<Map<String, Object>>list("lines")).singleElement()
                .satisfies(l -> assertThat(l).containsEntry("itemId", store.rice().id()).containsEntry("type", "PRODUCT"));

        Res limit = Api.as(customer.session()).body(obj("quantity", 21)).put("/api/cart/items/" + store.rice().id());
        assertThat(limit.status()).as(limit.describe()).isIn(400, 422);
        Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", store.rice().id(), "quantity", 19))
                .post("/api/cart/items").problem(422, "QUANTITY_LIMIT");
        Api.as(customer.session()).body(obj("quantity", 3)).put("/api/cart/items/00000000-0000-7000-8000-000000000000")
                .problem(404, "CART_ITEM_NOT_FOUND");

        Store other = World.storeB();
        Res conflict = Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", other.rice().id(), "quantity", 1))
                .post("/api/cart/items").problem(409, "CART_STORE_CONFLICT");
        conflict.has("currentStoreId", store.vendorId()).has("currentStoreType", "GROCERY");
        assertThat(conflict.str("currentStoreName")).isNotBlank();
        Api.as(customer.session()).body(obj("type", "PRODUCT", "productId", other.rice().id(), "quantity", 1,
                "replaceCart", true)).post("/api/cart/items").ok().has("store.id", other.vendorId());
        Api.as(customer.session()).delete("/api/cart").expect(204);
    }

    @Test
    void the_checkout_preview_computes_fee_and_total_and_reports_blocking_issues() {
        Customer customer = World.newCustomer("Preview");
        Store store = World.storeA();
        Res empty = Api.as(customer.session()).get("/api/cart/preview").ok().has("canCheckout", false);
        assertThat(empty.<Map<String, Object>>list("issues")).extracting(i -> i.get("code")).contains("EMPTY_CART");

        World.fillCart(customer, store.rice(), 1);
        Res preview = Api.as(customer.session()).get("/api/cart/preview?addressId=" + customer.addressId()).ok()
                .has("canCheckout", true).has("address.id", customer.addressId());
        BigDecimal fee = preview.dec("delivery.fee");
        assertThat(preview.dec("total")).isEqualByComparingTo(new BigDecimal("100.00").add(fee)
                .subtract(preview.dec("discount")).subtract(preview.dec("deliveryFeeDiscount")));
        assertThat(preview.dec("delivery.distanceKm")).isPositive();

        Api.as(customer.session()).body(obj("code", "NOPE-" + System.nanoTime() % 100000)).put("/api/cart/coupon")
                .problem(422, "COUPON_NOT_APPLICABLE").has("reason", "COUPON_NOT_FOUND");
    }

    @Test
    void placing_an_order_checks_the_key_the_cart_and_the_total_and_replays_idempotently() {
        Customer customer = World.newCustomer("Placer");
        Store store = World.storeA();
        Api.as(customer.session()).body(obj("expectedTotal", 1)).post("/api/orders")
                .problem(400, "IDEMPOTENCY_KEY_REQUIRED");
        Api.as(customer.session()).idempotencyKey("bad key!").body(obj("expectedTotal", 1)).post("/api/orders")
                .problem(400, "IDEMPOTENCY_KEY_INVALID");
        Res blocked = Api.as(customer.session()).idempotencyKey(World.key("empty")).body(obj("expectedTotal", 1))
                .post("/api/orders").problem(409, "CHECKOUT_BLOCKED");
        assertThat(blocked.<Map<String, Object>>list("issues")).extracting(i -> i.get("code")).contains("EMPTY_CART");

        World.fillCart(customer, store.rice(), 2);
        BigDecimal total = World.previewTotal(customer);
        Res changed = Api.as(customer.session()).idempotencyKey(World.key("stale"))
                .body(obj("addressId", customer.addressId(), "expectedTotal", new BigDecimal("1.00")))
                .post("/api/orders").problem(409, "TOTAL_CHANGED");
        assertThat(changed.dec("currentTotal")).isEqualByComparingTo(total);
        assertThat(changed.dec("expectedTotal")).isEqualByComparingTo("1.00");

        String key = World.key("place");
        Map<String, Object> body = obj("addressId", customer.addressId(), "expectedTotal", total, "note", "Ring twice");
        Res created = Api.as(customer.session()).idempotencyKey(key).body(body).post("/api/orders").created();
        World.track(created.str("id"));
        created.has("type", "GROCERY").has("store.id", store.vendorId()).has("total", total).has("currency", "INR")
                .has("note", "Ring twice").has("cancellable", true);
        assertThat(created.str("orderNumber")).matches("GA-[A-Z0-9]{8}");
        assertThat(created.str("status")).isIn("CREATED", "AWAITING_PAYMENT");
        assertThat(created.str("payment.dueAt")).isNotBlank();
        assertThat(created.<Map<String, Object>>list("timeline")).isNotEmpty();

        Res replay = Api.as(customer.session()).idempotencyKey(key).body(body).post("/api/orders").created();
        assertThat(replay.str("id")).isEqualTo(created.str("id"));
        assertThat(replay.header("Idempotent-Replayed")).isEqualToIgnoringCase("true");
        Api.as(customer.session()).idempotencyKey(key)
                .body(obj("addressId", customer.addressId(), "expectedTotal", total, "note", "Different"))
                .post("/api/orders").problem(422, "IDEMPOTENCY_KEY_REUSED");

        World.awaitOrderStatus(customer, created.str("id"), "AWAITING_PAYMENT");
        Res open = Api.as(customer.session()).get("/api/orders?status=OPEN").ok().page().has("totalElements", 1);
        assertThat(open.<Map<String, Object>>list("content")).singleElement()
                .satisfies(o -> assertThat(o).containsEntry("id", created.str("id")).containsKeys("firstItemName",
                        "itemCount", "payment"));
        // While it awaits payment the list row says when the payment window ends, exactly as the order does.
        open.has("content[0].payment.dueAt", created.str("payment.dueAt"));
        Api.as(customer.session()).get("/api/orders?status=SOMETHING").problem(400, "INVALID_PARAMETER");
        Await.field("cart emptied by OrderCreated", Await.seconds(20),
                () -> Api.as(customer.session()).get("/api/cart"), "itemCount", 0);
    }

    @Test
    void payment_checkout_session_verify_webhook_and_refunds() {
        Customer customer = World.newCustomer("Payer");
        Store store = World.storeA();
        World.Order order = World.groceryOrder(customer, store.rice(), 1);
        World.awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        Res session = Await.status("checkout session", Await.seconds(30),
                () -> Api.as(customer.session()).get("/api/payments/orders/" + order.id() + "/checkout"), 200);
        session.has("orderId", order.id()).has("currency", "INR").has("orderNumber", order.number());
        assertThat(session.integer("amount")).isEqualTo(order.total().movePointRight(2).intValueExact());
        assertThat(session.str("provider")).isIn("SIMULATOR", "RAZORPAY");
        assertThat(session.body()).doesNotContainIgnoringCase("secret");

        Api.as(customer.session()).body(obj("razorpayOrderId", session.str("razorpayOrderId"),
                "razorpayPaymentId", "pay_forged", "razorpaySignature", "00")).post("/api/payments/razorpay/verify")
                .problem(400, "INVALID_SIGNATURE");
        Api.anon().header("X-Razorpay-Signature", "00").body("{\"event\":\"payment.captured\"}")
                .post("/api/payments/razorpay/webhook").problem(400, "INVALID_SIGNATURE");

        World.pay(customer, order.id());
        Api.as(customer.session()).get("/api/payments/orders/" + order.id() + "/checkout")
                .problem(409, "PAYMENT_ALREADY_CAPTURED");
        Res payment = Api.as(customer.session()).get("/api/payments/orders/" + order.id()).ok()
                .has("status", "CAPTURED").has("refundStatus", "NONE");
        assertThat(payment.dec("amount")).isEqualByComparingTo(order.total());

        Res tooMuch = Api.as(World.adminWithRole("FINANCE_ADMIN")).idempotencyKey(World.key("refund-big"))
                .body(obj("orderId", order.id(), "amount", new BigDecimal("99999.00"), "note", "Too much"))
                .post("/api/admin/refunds").problem(422, "REFUND_EXCEEDS_CAPTURED");
        assertThat(tooMuch.dec("refundable")).isEqualByComparingTo(order.total());
        String refundKey = World.key("refund");
        Res refund = Api.as(World.adminWithRole("FINANCE_ADMIN")).idempotencyKey(refundKey)
                .body(obj("orderId", order.id(), "amount", new BigDecimal("10.00"), "note", "Damaged packet"))
                .post("/api/admin/refunds").created().has("reason", "ADMIN").has("amount", new BigDecimal("10.00"));
        Res again = Api.as(World.adminWithRole("FINANCE_ADMIN")).idempotencyKey(refundKey)
                .body(obj("orderId", order.id(), "amount", new BigDecimal("10.00"), "note", "Damaged packet"))
                .post("/api/admin/refunds");
        assertThat(again.status()).as(again.describe()).isIn(200, 201);
        assertThat(again.str("id")).isEqualTo(refund.str("id"));
        Await.until("partial refund on the order", Await.seconds(30),
                () -> Api.as(customer.session()).get("/api/orders/" + order.id()),
                r -> "PARTIALLY_REFUNDED".equals(r.str("payment.status")) && "CONFIRMED".equals(r.str("status")));
    }

    @Test
    void cancellation_rules_customer_and_admin() {
        Customer customer = World.newCustomer("Canceller");
        Store store = World.storeA();
        World.Order unpaid = World.groceryOrder(customer, store.rice(), 1);
        World.awaitOrderStatus(customer, unpaid.id(), "AWAITING_PAYMENT");
        Api.as(customer.session()).body(obj("reason", "Changed my mind")).post("/api/orders/" + unpaid.id() + "/cancel")
                .ok().has("status", "CANCELLED").has("cancellation.reason", "CUSTOMER_CANCELLED")
                .has("cancellation.by", "CUSTOMER");
        Api.as(customer.session()).body(obj()).post("/api/orders/" + unpaid.id() + "/cancel").ok().has("status", "CANCELLED");
        Await.until("payment session cancelled", Await.seconds(20),
                () -> Api.as(customer.session()).get("/api/payments/orders/" + unpaid.id()),
                r -> "CANCELLED".equals(r.str("status")));

        World.Order accepted = World.paidGroceryOrder(customer, store.rice(), 1);
        World.awaitStoreOrder(store.vendor(), "/api/vendor/orders", accepted.id(), "NEW");
        Api.as(store.vendor()).body(obj("readyInMinutes", 20)).post("/api/vendor/orders/" + accepted.id() + "/accept").ok();
        World.awaitOrderStatus(customer, accepted.id(), "ACCEPTED");
        Api.as(customer.session()).body(obj()).post("/api/orders/" + accepted.id() + "/cancel")
                .conflict("ORDER_NOT_CANCELLABLE", "ACCEPTED");
        Api.as(World.adminWithRole("SUPPORT")).body(obj()).post("/api/admin/orders/" + accepted.id() + "/cancel")
                .problem(400, "VALIDATION_FAILED");
        Res adminView = Api.as(World.adminWithRole("SUPPORT")).get("/api/admin/orders/" + accepted.id()).ok()
                .has("adminCancellable", true).has("customerId", customer.userId());
        assertThat(adminView.str("readyBy")).isNotBlank();
        Api.as(World.adminWithRole("SUPPORT")).body(obj("reason", "Customer called support"))
                .post("/api/admin/orders/" + accepted.id() + "/cancel").ok().has("status", "CANCELLED")
                .has("cancellation.reason", "ADMIN_CANCELLED");
        Await.until("paid order refunded in full", Await.seconds(30),
                () -> Api.as(customer.session()).get("/api/orders/" + accepted.id()),
                r -> "REFUNDED".equals(r.str("payment.status")));
    }

    @Test
    void admin_order_monitor_stats_and_event_timeline() {
        Customer customer = World.newCustomer("Monitor");
        World.Order order = World.groceryOrder(customer, World.storeA().rice(), 1);
        World.awaitOrderStatus(customer, order.id(), "AWAITING_PAYMENT");
        var admin = World.adminWithRole("OPS_ADMIN");
        Res found = Api.as(admin).get("/api/admin/orders?q=" + order.number().toLowerCase()).ok().page();
        assertThat(found.<Map<String, Object>>list("content")).extracting(o -> o.get("id")).containsExactly(order.id());
        Res stats = Api.as(admin).get("/api/admin/stats").ok().has("timezone", "Asia/Kolkata").has("currency", "INR");
        assertThat(stats.map("byStatus")).containsKeys("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "CANCELLED");
        Res events = Api.as(admin).get("/api/admin/orders/" + order.id() + "/events").ok();
        List<Map<String, Object>> list = events.list("$");
        assertThat(list).extracting(e -> e.get("eventType") + "/" + e.get("direction"))
                .contains("OrderCreated/PUBLISHED", "OrderAwaitingPayment/PUBLISHED", "StockReserved/CONSUMED");
        assertThat(list).allSatisfy(e -> assertThat(e).containsKeys("eventId", "outcome", "occurredAt", "correlationId"));
        Api.as(admin).get("/api/admin/orders/00000000-0000-7000-8000-000000000000/events").problem(404, "ORDER_NOT_FOUND");
    }
}
