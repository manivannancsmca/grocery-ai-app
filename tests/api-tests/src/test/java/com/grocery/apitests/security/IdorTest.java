package com.grocery.apitests.security;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Insecure direct object references: user B uses the real ids of user A's order, payment, address, cart preview,
 * review, return, approval, notification and conversation (and store B those of store A, partner B those of partner A).
 * Every answer must be 404 — the platform does not even reveal that the object exists.
 */
@Tag("security")
@Order(11)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("IDOR: another user's objects are 404")
class IdorTest {

    World.Delivered a;
    World.Customer b;

    @BeforeAll
    void setUp() {
        a = World.deliveredOrder();
        World.deliveredReview();
        World.deliveredReturn();
        b = World.newCustomer("Intruder");
    }

    @Test
    void another_customers_order_payment_tracking_and_returns() {
        String order = a.order().id();
        Session intruder = b.session();
        Api.as(intruder).get("/api/orders/" + order).problem(404, "ORDER_NOT_FOUND");
        Api.as(intruder).body(obj("reason", "mine now")).post("/api/orders/" + order + "/cancel")
                .problem(404, "ORDER_NOT_FOUND");
        Api.as(intruder).get("/api/payments/orders/" + order).problem(404, "PAYMENT_NOT_FOUND");
        Api.as(intruder).get("/api/payments/orders/" + order + "/checkout").problem(404, "PAYMENT_NOT_FOUND");
        Api.as(intruder).get("/api/delivery/orders/" + order + "/tracking").problem(404, "TRACKING_NOT_FOUND");
        Api.as(intruder).get("/api/orders/" + order + "/returns").problem(404, null);
        Api.as(intruder).body(obj("items", List.of(obj("itemId", a.store().dal().id(), "quantity", 1)),
                "reason", "DAMAGED")).post("/api/orders/" + order + "/returns").problem(404, null);
        Api.as(intruder).get("/api/orders").ok().bodyDoesNotContain(order);
        Api.as(intruder).get("/api/returns").ok().bodyDoesNotContain(World.deliveredReturn());
        // the owner still sees it (the 404s above are about ownership, not a broken id)
        Api.as(a.customer().session()).get("/api/orders/" + order).ok();
    }

    @Test
    void another_customers_address_and_cart_preview() {
        Session intruder = b.session();
        String address = a.customer().addressId();
        Api.as(intruder).get("/api/cart/preview?addressId=" + address).problem(404, "ADDRESS_NOT_FOUND");
        Api.as(intruder).body(World.address("Mine", World.HOME_LAT, World.HOME_LNG))
                .put("/api/customers/me/addresses/" + address).problem(404, null);
        Api.as(intruder).delete("/api/customers/me/addresses/" + address).problem(404, null);
        Api.as(intruder).post("/api/customers/me/addresses/" + address + "/default").problem(404, null);
        World.fillCart(b, a.store().rice(), 1);
        Api.as(intruder).idempotencyKey(World.key("idor")).body(obj("addressId", address, "expectedTotal", 1))
                .post("/api/orders").problem(404, "ADDRESS_NOT_FOUND");
        Api.as(intruder).delete("/api/cart").expect(204);
        Api.as(a.customer().session()).get("/api/customers/me/addresses").ok().bodyContains(address);
    }

    @Test
    void another_customers_review() {
        String review = World.deliveredReview();
        Session intruder = b.session();
        Api.as(intruder).body(obj("rating", 1, "title", "hijacked")).put("/api/reviews/" + review).problem(404, null);
        Api.as(intruder).delete("/api/reviews/" + review).problem(404, null);
        Api.as(intruder).get("/api/reviews/mine").ok().bodyDoesNotContain(review);
        Api.anon().get("/api/reviews?targetType=PRODUCT&targetId=" + a.store().rice().id()).ok().bodyContains(review)
                .bodyDoesNotContain("hijacked");
    }

    @Test
    void another_users_notifications_and_conversations() {
        Res inbox = Await.until("customer A has notifications", Await.seconds(30),
                () -> Api.as(a.customer().session()).get("/api/notifications"),
                r -> r.status() == 200 && !r.list("content").isEmpty());
        String notification = String.valueOf(inbox.<Map<String, Object>>list("content").getFirst().get("id"));
        Api.as(b.session()).post("/api/notifications/" + notification + "/read").problem(404, "NOTIFICATION_NOT_FOUND");

        assumeTrue(scriptedAssistant(), "the assistant is not running the scripted model");
        Chat chat = Chat.send(a.customer().session(), "shopping", "show my cart").streamed();
        String conversation = chat.conversationId();
        Api.as(b.session()).get("/api/ai/conversations/" + conversation).problem(404, "CONVERSATION_NOT_FOUND");
        Api.as(b.session()).delete("/api/ai/conversations/" + conversation).problem(404, "CONVERSATION_NOT_FOUND");
        Chat hijack = Chat.send(b.session(), "shopping", "show my cart", conversation);
        hijack.response().problem(404, "CONVERSATION_NOT_FOUND");
        Api.as(a.customer().session()).get("/api/ai/conversations/" + conversation).ok().has("id", conversation);
    }

    @Test
    void another_customers_approval_is_invisible_to_customers_and_admins() {
        assumeTrue(scriptedAssistant(), "the assistant is not running the scripted model");
        World.Customer owner = World.newCustomer("Approver");
        World.storeA();
        Chat.send(owner.session(), "shopping", "add 1 " + World.storeA().rice().name()).streamed()
                .tool("addToCart", "SUCCEEDED");
        String approval = Chat.send(owner.session(), "shopping", "checkout").streamed()
                .tool("placeOrder", "APPROVAL_REQUIRED").approvalId();
        for (Session other : List.of(b.session(), World.adminWithRole("SUPER_ADMIN"), World.adminWithRole("SUPPORT"))) {
            Api.as(other).get("/api/approvals/" + approval).problem(404, "APPROVAL_NOT_FOUND");
            Api.as(other).body(obj()).post("/api/approvals/" + approval + "/approve").problem(404, "APPROVAL_NOT_FOUND");
            Api.as(other).body(obj("comment", "no")).post("/api/approvals/" + approval + "/reject")
                    .problem(404, "APPROVAL_NOT_FOUND");
            Api.as(other).post("/api/approvals/" + approval + "/cancel").problem(404, "APPROVAL_NOT_FOUND");
            Api.as(other).get("/api/approvals?scope=to-decide").ok().bodyDoesNotContain(approval);
        }
        Api.as(owner.session()).get("/api/approvals/" + approval).ok().has("status", "PENDING_APPROVAL");
        Api.as(owner.session()).post("/api/approvals/" + approval + "/cancel").ok().has("status", "CANCELLED");
    }

    @Test
    void another_vendors_products_stock_prices_orders_returns_reviews_and_purchasing() {
        World.Store mine = a.store();
        World.Store other = World.storeB();
        Session intruder = other.vendor();
        String product = mine.rice().id();
        Api.as(intruder).get("/api/vendor/products/" + product).problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).body(obj("name", "Hijacked", "categoryId", World.category(), "unitValue", 1, "unitOfMeasure", "KG"))
                .put("/api/vendor/products/" + product).problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).post("/api/vendor/products/" + product + "/unpublish").problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).get("/api/vendor/inventory/" + product).problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).body(obj("type", "SET", "quantity", 0, "reason", "CORRECTION"))
                .post("/api/vendor/inventory/" + product + "/adjustments").problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).get("/api/vendor/prices/" + product).problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).body(obj("mrp", new BigDecimal("120.00"), "sellingPrice", new BigDecimal("1.00")))
                .put("/api/vendor/prices/" + product).problem(404, "PRODUCT_NOT_FOUND");
        Api.as(intruder).get("/api/vendor/orders/" + a.order().id()).problem(404, "STORE_ORDER_NOT_FOUND");
        Api.as(intruder).body(obj("readyInMinutes", 10)).post("/api/vendor/orders/" + a.order().id() + "/accept")
                .problem(404, "STORE_ORDER_NOT_FOUND");
        Api.as(intruder).body(obj("restock", false)).post("/api/store/returns/" + World.deliveredReturn() + "/approve")
                .problem(404, null);
        Api.as(intruder).body(obj("comment", "no")).post("/api/store/returns/" + World.deliveredReturn() + "/reject")
                .problem(404, null);
        Api.as(intruder).body(obj("text", "Not my store")).put("/api/store/reviews/" + World.deliveredReview() + "/reply")
                .problem(404, null);
        Api.as(intruder).get("/api/store/returns").ok().bodyDoesNotContain(World.deliveredReturn());

        String supplier = Api.as(mine.vendor()).body(obj("name", "Apit IDOR Supplier " + Config.RUN))
                .post("/api/vendor/suppliers").created().str("id");
        String po = Api.as(mine.vendor()).body(obj("supplierId", supplier,
                "expectedAt", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString(),
                "lines", List.of(obj("productId", product, "orderedQuantity", 1, "unitCost", new BigDecimal("1.00")))))
                .post("/api/vendor/purchase-orders").created().str("id");
        Api.as(intruder).get("/api/vendor/suppliers/" + supplier).problem(404, null);
        Api.as(intruder).get("/api/vendor/purchase-orders/" + po).problem(404, null);
        Api.as(intruder).body(obj()).post("/api/vendor/purchase-orders/" + po + "/send").problem(404, null);
        String coupon = Api.as(mine.vendor()).body(obj("code", ("API" + Config.RUN).toUpperCase(), "name", "Mine",
                "scope", "STORE", "discountType", "FLAT", "value", new BigDecimal("5.00"),
                "validUntil", Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()))
                .post("/api/vendor/coupons").created().str("id");
        Api.as(intruder).get("/api/vendor/coupons/" + coupon).problem(404, null);
        Api.as(intruder).post("/api/vendor/coupons/" + coupon + "/pause").problem(404, null);
        Api.as(mine.vendor()).body(obj()).post("/api/vendor/purchase-orders/" + po + "/cancel").ok();
    }

    @Test
    void another_restaurants_menu_and_another_partners_delivery() {
        World.Kitchen kitchen = World.kitchen();
        Session otherOwner = Session.register("RESTAURANT_OWNER", "Apit Other Chef");
        Await.status("second restaurant draft", Await.seconds(30), () -> Api.as(otherOwner).get("/api/restaurants/me"), 200);
        Api.as(otherOwner).body(obj("available", false))
                .patch("/api/restaurant/menu/items/" + kitchen.dosaId() + "/availability").problem(404, "MENU_ITEM_NOT_FOUND");
        Api.as(otherOwner).delete("/api/restaurant/menu/items/" + kitchen.dosaId()).problem(404, "MENU_ITEM_NOT_FOUND");
        Api.anon().get("/api/catalog/restaurants/" + kitchen.restaurantId()).ok().bodyContains(kitchen.dosaId());

        World.Partner otherRider = World.newPartner("Gopal");
        Api.as(otherRider.session()).get("/api/delivery/deliveries/" + a.deliveryId()).problem(404, "DELIVERY_NOT_FOUND");
        Api.as(otherRider.session()).post("/api/delivery/deliveries/" + a.deliveryId() + "/deliver")
                .problem(404, "DELIVERY_NOT_FOUND");
        Api.as(otherRider.session()).body(obj("reason", "OTHER", "note", "not mine"))
                .post("/api/delivery/deliveries/" + a.deliveryId() + "/fail").problem(404, "DELIVERY_NOT_FOUND");
    }

    static boolean scriptedAssistant() {
        Res status = Api.as(World.admin()).get("/api/ai/status");
        return status.status() == 200 && "scripted".equals(status.str("provider")) && Boolean.TRUE.equals(status.bool("available"));
    }
}
