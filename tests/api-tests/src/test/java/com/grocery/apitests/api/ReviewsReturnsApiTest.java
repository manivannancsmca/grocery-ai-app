package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12 §1 and §3 on the run's delivered order: reviews, moderation, replies, returns and their decisions. */
@Tag("api")
@Order(6)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Reviews and returns API")
class ReviewsReturnsApiTest {

    @Test
    @Order(1)
    void the_customer_may_review_exactly_what_was_received() {
        World.Delivered d = World.deliveredOrder();
        Res eligible = Api.as(d.customer().session()).get("/api/reviews/eligible?orderId=" + d.order().id()).ok();
        List<Map<String, Object>> targets = eligible.list("$");
        assertThat(targets).extracting(t -> t.get("targetType") + ":" + t.get("targetId")).contains(
                "PRODUCT:" + d.store().rice().id(), "PRODUCT:" + d.store().dal().id(), "VENDOR:" + d.store().vendorId(),
                "DELIVERY_PARTNER:" + d.partner().partnerId());

        String review = World.deliveredReview();
        Res mine = Api.as(d.customer().session()).get("/api/reviews/mine").ok().page();
        assertThat(mine.<Map<String, Object>>list("content")).anySatisfy(r -> assertThat(r)
                .containsEntry("id", review).containsEntry("status", "PUBLISHED").containsEntry("rating", 5));

        Api.as(d.customer().session()).body(obj("orderId", d.order().id(), "targetType", "PRODUCT",
                "targetId", d.store().rice().id(), "rating", 4)).post("/api/reviews").problem(409, "ALREADY_REVIEWED");
        Api.as(d.customer().session()).body(obj("orderId", d.order().id(), "targetType", "PRODUCT",
                "targetId", World.storeB().rice().id(), "rating", 4)).post("/api/reviews").problem(422, "REVIEW_NOT_ELIGIBLE");
        Api.as(d.customer().session()).body(obj("orderId", d.order().id(), "targetType", "PRODUCT",
                "targetId", d.store().dal().id(), "rating", 6)).post("/api/reviews").problem(400, "VALIDATION_FAILED");

        World.Customer stranger = World.newCustomer("Stranger");
        Api.as(stranger.session()).body(obj("orderId", d.order().id(), "targetType", "PRODUCT",
                "targetId", d.store().rice().id(), "rating", 1)).post("/api/reviews").problem(422, "REVIEW_NOT_ELIGIBLE");
    }

    @Test
    @Order(2)
    void a_flagged_review_stays_hidden_until_an_admin_publishes_it_and_the_store_can_reply() {
        World.Delivered d = World.deliveredOrder();
        Res flagged = Api.as(d.customer().session()).body(obj("orderId", d.order().id(), "targetType", "VENDOR",
                "targetId", d.store().vendorId(), "rating", 4, "title", "Good store",
                "comment", "Cheaper at http://cheap-rice.example")).post("/api/reviews").created().has("status", "FLAGGED");
        String id = flagged.str("id");
        Api.anon().get("/api/reviews?targetType=VENDOR&targetId=" + d.store().vendorId()).ok().page()
                .bodyDoesNotContain(id);
        var support = World.adminWithRole("SUPPORT");
        Api.as(support).get("/api/admin/reviews").ok().page();
        Api.as(support).post("/api/admin/reviews/" + id + "/publish").ok().has("status", "PUBLISHED");
        Api.as(support).post("/api/admin/reviews/" + id + "/publish").conflict("REVIEW_NOT_MODERATABLE", "PUBLISHED");
        Api.as(support).body(obj()).post("/api/admin/reviews/" + id + "/remove").problem(400, "VALIDATION_FAILED");
        Res published = Await.until("published review is public", Await.seconds(20),
                () -> Api.anon().get("/api/reviews?targetType=VENDOR&targetId=" + d.store().vendorId()),
                r -> r.status() == 200 && r.body().contains(id));
        assertThat(published.body()).doesNotContain(d.customer().session().email());

        String review = World.deliveredReview();
        Api.as(d.store().vendor()).body(obj("text", "Thank you!")).put("/api/store/reviews/" + review + "/reply").ok();
        Await.until("reply is public", Await.seconds(20),
                () -> Api.anon().get("/api/reviews?targetType=PRODUCT&targetId=" + d.store().rice().id()),
                r -> r.status() == 200 && r.body().contains("Thank you!"));
        Api.anon().get("/api/reviews/summary?targetType=PRODUCT&targetId=" + d.store().rice().id()).ok()
                .has("ratingCount", 1).has("averageRating", new BigDecimal("5"));
        assertThat(Api.anon().get("/api/reviews/summary?targetType=PRODUCT&targetId=" + d.store().rice().id())
                .<Integer>list("distribution")).hasSize(5);
    }

    @Test
    @Order(3)
    void a_return_refunds_the_prorated_amount_and_follows_its_lifecycle() {
        World.Delivered d = World.deliveredOrder();
        String path = "/api/orders/" + d.order().id() + "/returns";
        Api.as(d.customer().session()).body(obj("items", List.of(obj("itemId", d.store().rice().id(), "quantity", 5)),
                "reason", "DAMAGED")).post(path).problem(422, "RETURN_QUANTITY_EXCEEDED");
        Api.as(d.customer().session()).body(obj("items", List.of(obj("itemId", d.store().rice().id(), "quantity", 1)),
                "reason", "BROKEN")).post(path).problem(400, null);

        String returnId = World.deliveredReturn();
        Res returns = Api.as(d.customer().session()).get(path).ok();
        Map<String, Object> view = returns.<Map<String, Object>>list("$").stream()
                .filter(r -> returnId.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(view).containsEntry("status", "REQUESTED").containsEntry("reason", "DAMAGED")
                .containsEntry("orderId", d.order().id()).containsKeys("returnNumber", "decideBy", "items");
        assertThat(new BigDecimal(String.valueOf(view.get("refundAmount")))).isEqualByComparingTo("100.00");
        assertThat(String.valueOf(view.get("returnNumber"))).matches("RT-[A-Z0-9]{8}");

        Api.as(d.customer().session()).body(obj("items", List.of(obj("itemId", d.store().dal().id(), "quantity", 1)),
                "reason", "QUALITY")).post(path).problem(422, "RETURN_NOT_ALLOWED");
        Api.as(d.customer().session()).get("/api/returns").ok().page().bodyContains(returnId);
        Api.as(d.store().vendor()).get("/api/store/returns").ok().page().bodyContains(returnId);
        Api.as(d.store().vendor()).body(obj()).post("/api/store/returns/" + returnId + "/reject")
                .problem(400, "VALIDATION_FAILED");
        Api.as(d.store().vendor()).body(obj("restock", true)).post("/api/store/returns/" + returnId + "/approve").ok()
                .has("status", "APPROVED").has("restock", true);
        Res again = Api.as(d.store().vendor()).body(obj("restock", true)).post("/api/store/returns/" + returnId + "/approve");
        again.problem(409, "RETURN_NOT_PENDING");
        assertThat(again.str("currentStatus")).isIn("APPROVED", "REFUNDED");
        Await.until("return REFUNDED", Await.seconds(40), () -> Api.as(d.customer().session()).get(path),
                r -> r.status() == 200 && r.<Map<String, Object>>list("$").stream()
                        .anyMatch(x -> returnId.equals(x.get("id")) && "REFUNDED".equals(x.get("status"))));
        Api.as(World.adminWithRole("SUPPORT")).get("/api/admin/returns?status=REFUNDED").ok().page();
    }
}
