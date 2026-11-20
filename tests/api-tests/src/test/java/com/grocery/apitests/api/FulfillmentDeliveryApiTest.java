package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import com.grocery.apitests.support.World.Customer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phases 8–9: store boards (vendor and restaurant), sales reports, the partner API, tracking and the admin monitor. */
@Tag("api")
@Order(5)
@DisplayName("Fulfillment and delivery API")
class FulfillmentDeliveryApiTest {

    @Test
    void the_vendor_board_accept_reject_and_transition_conflicts() {
        Customer customer = World.newCustomer("Board");
        World.Store store = World.storeA();
        World.Order order = World.paidGroceryOrder(customer, store.rice(), 1);
        Res board = World.awaitStoreOrder(store.vendor(), "/api/vendor/orders", order.id(), "NEW")
                .has("orderNumber", order.number()).has("storeId", store.vendorId());
        assertThat(board.str("acceptBy")).isNotBlank();
        assertThat(board.map("customer")).containsKeys("name", "area");
        assertThat(board.body()).as("store board must not reveal the customer's phone").doesNotContain("+919876543210");
        Api.as(store.vendor()).get("/api/vendor/orders?status=ACTIVE&size=100").ok().page().bodyContains(order.id());

        Api.as(store.vendor()).body(obj("reason", "OTHER")).post("/api/vendor/orders/" + order.id() + "/reject")
                .problem(400, null);
        Api.as(store.vendor()).post("/api/vendor/orders/" + order.id() + "/preparing")
                .conflict("INVALID_STORE_ORDER_TRANSITION", "NEW");
        Api.as(store.vendor()).body(obj("readyInMinutes", 2)).post("/api/vendor/orders/" + order.id() + "/accept")
                .problem(400, null);
        Api.as(store.vendor()).body(obj("readyInMinutes", 15)).post("/api/vendor/orders/" + order.id() + "/accept").ok()
                .has("status", "ACCEPTED").has("readyInMinutes", 15);
        Api.as(store.vendor()).body(obj("readyInMinutes", 15)).post("/api/vendor/orders/" + order.id() + "/accept").ok()
                .has("status", "ACCEPTED");
        Api.as(store.vendor()).body(obj("reason", "TOO_BUSY")).post("/api/vendor/orders/" + order.id() + "/reject")
                .conflict("STORE_ORDER_NOT_PENDING", "ACCEPTED");
        Api.as(store.vendor()).post("/api/vendor/orders/" + order.id() + "/preparing").ok().has("status", "PREPARING");
        World.awaitOrderStatus(customer, order.id(), "PREPARING");
        Api.as(store.vendor()).get("/api/vendor/orders/00000000-0000-7000-8000-000000000000")
                .problem(404, "STORE_ORDER_NOT_FOUND");

        Res report = Api.as(store.vendor()).get("/api/vendor/reports/sales").ok()
                .has("timezone", "Asia/Kolkata").has("currency", "INR");
        assertThat(report.<Object>list("daily")).hasSize(7);
        assertThat(report.integer("ordersReceived")).isPositive();
        Api.as(store.vendor()).get("/api/vendor/reports/sales?from=2026-01-01&to=2026-12-31")
                .problem(400, "INVALID_PARAMETER");
    }

    @Test
    void a_store_rejection_cancels_and_refunds_the_order() {
        Customer customer = World.newCustomer("Rejected");
        World.Store store = World.storeA();
        World.Order order = World.paidGroceryOrder(customer, store.rice(), 1);
        World.awaitStoreOrder(store.vendor(), "/api/vendor/orders", order.id(), "NEW");
        Api.as(store.vendor()).body(obj("reason", "OUT_OF_STOCK")).post("/api/vendor/orders/" + order.id() + "/reject")
                .ok().has("status", "REJECTED").has("rejection.reason", "OUT_OF_STOCK").has("rejection.auto", false);
        Res cancelled = World.awaitOrderStatus(customer, order.id(), "CANCELLED")
                .has("cancellation.reason", "STORE_REJECTED").has("cancellation.by", "STORE");
        assertThat(cancelled.bool("cancellable")).isFalse();
        Await.until("refund after the store rejected", Await.seconds(30),
                () -> Api.as(customer.session()).get("/api/orders/" + order.id()),
                r -> "REFUNDED".equals(r.str("payment.status")));
    }

    @Test
    void a_restaurant_order_goes_straight_to_payment_and_onto_the_kitchen_board() {
        Customer customer = World.newCustomer("Diner");
        World.Kitchen kitchen = World.kitchen();
        Api.as(customer.session()).body(obj("type", "MENU_ITEM", "restaurantId", kitchen.restaurantId(),
                "menuItemId", kitchen.dosaId(), "quantity", 2, "replaceCart", true)).post("/api/cart/items").ok()
                .has("store.type", "RESTAURANT").has("subtotal", new BigDecimal("240.00"));
        World.Order order = World.place(customer, World.key("restaurant"));
        order.created().has("type", "RESTAURANT").has("status", "AWAITING_PAYMENT");
        World.pay(customer, order.id());
        World.awaitStoreOrder(kitchen.owner(), "/api/restaurant/orders", order.id(), "NEW");
        Api.as(kitchen.owner()).body(obj()).post("/api/restaurant/orders/" + order.id() + "/accept").ok()
                .has("status", "ACCEPTED").has("readyInMinutes", 20);
        Api.as(kitchen.owner()).get("/api/restaurant/reports/sales").ok().has("currency", "INR");
        Api.as(kitchen.owner()).get("/api/restaurant/menu").ok().has("restaurantId", kitchen.restaurantId())
                .has("restaurantStatus", "APPROVED");
    }

    @Test
    void the_partner_api_rules() {
        World.Partner partner = World.newPartner("Sita");
        Api.as(partner.session()).get("/api/delivery/partners/me/status").ok().has("online", false)
                .has("partnerId", partner.partnerId());
        Api.as(partner.session()).body(obj("latitude", World.STORE_LAT, "longitude", World.STORE_LNG))
                .put("/api/delivery/partners/me/location").problem(409, "PARTNER_OFFLINE");
        Api.as(partner.session()).get("/api/delivery/offers").ok();
        Api.as(partner.session()).get("/api/delivery/deliveries/active").expect(204);
        Api.as(partner.session()).get("/api/delivery/history").ok().page();
        assertThat(Api.as(partner.session()).get("/api/delivery/history/summary").ok().map("$"))
                .containsKeys("deliveredToday", "deliveredThisWeek", "failedThisWeek");
        Api.as(partner.session()).post("/api/delivery/offers/00000000-0000-7000-8000-000000000000/accept")
                .problem(404, "OFFER_NOT_FOUND");

        var draft = com.grocery.apitests.support.Session.register("DELIVERY_PARTNER", "Apit Unapproved");
        Await.status("unapproved partner draft", Await.seconds(30),
                () -> Api.as(draft).get("/api/delivery/partners/me"), 200);
        Api.as(draft).body(obj("online", true)).put("/api/delivery/partners/me/availability")
                .problem(403, "PARTNER_NOT_ACTIVE");
    }

    @Test
    void a_delivered_order_tracking_history_and_admin_monitor() {
        World.Delivered delivered = World.deliveredOrder();
        Customer customer = delivered.customer();
        Res order = Api.as(customer.session()).get("/api/orders/" + delivered.order().id()).ok()
                .has("status", "DELIVERED").has("trackable", false).has("returnable", true);
        assertThat(order.<Map<String, Object>>list("timeline")).extracting(t -> t.get("status"))
                .contains("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY",
                        "DELIVERED");
        assertThat(order.str("delivery.partnerName")).startsWith("Ravi");

        Res tracking = Api.as(customer.session()).get("/api/delivery/orders/" + delivered.order().id() + "/tracking").ok()
                .has("status", "DELIVERED").has("partnerLocation", null);
        assertThat(tracking.str("partner.name")).isEqualTo("Ravi");
        // The partner's number and plate are only shared while they hold the delivery, not after it.
        tracking.has("partner.phone", null).has("partner.vehicleNumber", null);

        Api.as(delivered.partner().session()).get("/api/delivery/deliveries/" + delivered.deliveryId()).ok()
                .has("status", "DELIVERED").has("drop.phone", "+919876543210");
        Res history = Api.as(delivered.partner().session()).get("/api/delivery/history").ok().page();
        assertThat(history.<Map<String, Object>>list("content")).extracting(d -> d.get("orderNumber"))
                .contains(delivered.order().number());
        Api.as(delivered.partner().session()).post("/api/delivery/deliveries/" + delivered.deliveryId() + "/deliver")
                .ok().has("status", "DELIVERED");
        Api.as(delivered.partner().session()).post("/api/delivery/deliveries/" + delivered.deliveryId() + "/pickup")
                .conflict("INVALID_DELIVERY_TRANSITION", "DELIVERED");

        World.awaitStoreOrder(delivered.store().vendor(), "/api/vendor/orders", delivered.order().id(), "PICKED_UP");
        var manager = World.adminWithRole("DELIVERY_MANAGER");
        Res monitor = Api.as(manager).get("/api/admin/deliveries?q=" + delivered.order().number()).ok().page();
        List<Map<String, Object>> rows = monitor.list("content");
        assertThat(rows).singleElement().satisfies(d -> assertThat(d).containsEntry("status", "DELIVERED")
                .containsEntry("partnerId", delivered.partner().partnerId()));
        Api.as(manager).get("/api/admin/deliveries/" + delivered.deliveryId()).ok().has("deliveryId", delivered.deliveryId());
        Api.as(manager).body(obj("reason", "too late")).post("/api/admin/deliveries/" + delivered.deliveryId() + "/reassign")
                .conflict("INVALID_DELIVERY_TRANSITION", "DELIVERED");
        Api.as(manager).get("/api/admin/delivery-partners/online").ok();
    }
}
