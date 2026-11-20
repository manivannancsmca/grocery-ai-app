package com.grocery.notification;

import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.TestEvents.Review;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static com.grocery.notification.RealtimeRecorder.kind;
import static com.grocery.notification.RealtimeRecorder.mentioning;
import static com.grocery.notification.RealtimeRecorder.signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 12 §1: review.events in group {@code notification-service.dispatch} → NEW_REVIEW to the store owner (never
 * another store's, never for delivery-partner reviews), REVIEW_FLAGGED to the moderators, REVIEW_NOT_PUBLISHED and
 * REVIEW_REPLY to the customer, the names from the projections (item_name from the order lines, store_owner), and the
 * signals STORE_REVIEWS_CHANGED, REVIEW_UPDATED, ADMIN_REVIEWS_CHANGED. Events travel as Avro with the real schemas
 * 1.5.0.
 */
class ReviewNotificationsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    KafkaListenerEndpointRegistry kafkaListeners;
    @Autowired
    AvroEventEncoder avroEncoder;
    @Autowired
    JsonAvroConverter avroConverter;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    private AvroRoundTrip avro;
    private UUID customer;
    private UUID vendorId;
    private UUID vendorOwner;
    private UUID otherVendorOwner;
    private UUID productId;

    @BeforeEach
    void knownStoreAndProduct() {
        avro = new AvroRoundTrip(avroEncoder, avroConverter, consumerFactory);
        customer = UUID.randomUUID();
        vendorId = UUID.randomUUID();
        vendorOwner = knownVendor(vendorId, "Green Basket");
        otherVendorOwner = knownVendor(UUID.randomUUID(), "Other Mart");
        productId = UUID.randomUUID();
        // the delivered order's line names the product (an old OrderCreated: projections only)
        Order order = TestEvents.groceryOrder(customer, vendorId);
        dispatch(TestEvents.old(TestEvents.orderCreatedWithItem(order, productId, "PRODUCT", "Basmati Rice")));
    }

    @Test
    void theDispatchConsumerReadsReviewEvents() {
        assertThat(kafkaListeners.getListenerContainer("notification-dispatch").getContainerProperties().getTopics())
                .contains("review.events", "order.events", "ai.events");
    }

    @Test
    void aPublishedReviewTellsTheStoreOwnerOnly() throws Exception {
        Review review = TestEvents.review("PRODUCT", productId, vendorId, customer);
        ConsumerRecord<String, String> submitted = avro.via(TestEvents.reviewSubmitted(review, 4, "PUBLISHED", null,
                false));

        dispatch(submitted);

        Stored notification = notificationOf(submitted, "NEW_REVIEW");
        assertThat(notificationsOf(submitted)).hasSize(1);
        assertThat(notification.category()).isEqualTo("REVIEW");
        assertThat(notification.severity()).isEqualTo("INFO");
        assertThat(notification.title()).isEqualTo("New 4★ review");
        assertThat(notification.body()).isEqualTo("Basmati Rice");
        assertThat(notification.link()).isEqualTo("/vendor/reviews");
        assertThat(notification.audiences()).containsExactly("user:" + vendorOwner);
        assertThat(notification.data().get("reviewId").asString()).isEqualTo(review.reviewId().toString());
        assertThat(notification.data().get("targetType").asString()).isEqualTo("PRODUCT");
        assertThat(notification.data().get("targetId").asString()).isEqualTo(productId.toString());
        assertThat(notification.data().get("rating").asInt()).isEqualTo(4);

        // privacy: another store, the customer and admins get no notification
        assertThat(inboxAbout(review.reviewId(), otherVendorOwner, "VENDOR")).isEmpty();
        assertThat(inboxAbout(review.reviewId(), customer, "CUSTOMER")).isEmpty();
        assertThat(inboxAbout(review.reviewId(), UUID.randomUUID(), "SUPER_ADMIN", "OPS_ADMIN", "SUPPORT")).isEmpty();
        assertThat(inboxAbout(review.reviewId(), vendorOwner, "VENDOR")).hasSize(1);

        JsonNode store = single(signal("STORE_REVIEWS_CHANGED").and(mentioning(review.reviewId())));
        assertThat(audiences(store)).containsExactly("user:" + vendorOwner);
        assertThat(store.at("/payload/data/event").asString()).isEqualTo("ReviewSubmitted");
        assertThat(store.at("/payload/data/targetId").asString()).isEqualTo(productId.toString());
        JsonNode mine = single(signal("REVIEW_UPDATED").and(mentioning(review.reviewId())));
        assertThat(audiences(mine)).containsExactly("user:" + customer);
        assertThat(mine.at("/payload/data/status").asString()).isEqualTo("PUBLISHED");
        JsonNode admins = single(signal("ADMIN_REVIEWS_CHANGED").and(mentioning(review.reviewId())));
        assertThat(audiences(admins)).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN",
                "role:FINANCE_ADMIN", "role:SUPPORT", "role:DELIVERY_MANAGER");
    }

    @Test
    void restaurantReviewsGoToTheRestaurantPageWithTheDishOrRestaurantName() {
        UUID restaurantId = UUID.randomUUID();
        UUID restaurantOwner = knownRestaurant(restaurantId, "Spice Route");
        UUID dishId = UUID.randomUUID();
        dispatch(TestEvents.old(TestEvents.orderCreatedWithItem(TestEvents.restaurantOrder(customer, restaurantId),
                dishId, "MENU_ITEM", "Butter Chicken")));

        ConsumerRecord<String, String> dish = avro.via(TestEvents.reviewSubmitted(
                TestEvents.review("MENU_ITEM", dishId, restaurantId, customer), 5, "PUBLISHED", null, true));
        ConsumerRecord<String, String> place = avro.via(TestEvents.reviewSubmitted(
                TestEvents.review("RESTAURANT", restaurantId, restaurantId, customer), 2, "PUBLISHED", null, false));
        ConsumerRecord<String, String> unknownDish = avro.via(TestEvents.reviewSubmitted(
                TestEvents.review("MENU_ITEM", UUID.randomUUID(), restaurantId, customer), 3, "PUBLISHED", null,
                false));
        dispatch(dish);
        dispatch(place);
        dispatch(unknownDish);

        Stored edited = notificationOf(dish, "NEW_REVIEW");
        assertThat(edited.title()).isEqualTo("New 5★ review");
        assertThat(edited.body()).isEqualTo("Butter Chicken (edited)");
        assertThat(edited.link()).isEqualTo("/restaurant/reviews");
        assertThat(edited.audiences()).containsExactly("user:" + restaurantOwner);
        assertThat(notificationOf(place, "NEW_REVIEW").body()).isEqualTo("Spice Route");
        assertThat(notificationOf(unknownDish, "NEW_REVIEW").body()).isEqualTo("your dish");
    }

    @Test
    void aDeliveryPartnerReviewIsNotTheStoresBusiness() {
        UUID partner = UUID.randomUUID();
        Review review = TestEvents.review("DELIVERY_PARTNER", partner, vendorId, customer);
        ConsumerRecord<String, String> submitted = avro.via(TestEvents.reviewSubmitted(review, 1, "PUBLISHED", null,
                false));
        dispatch(submitted);

        assertThat(processed(submitted)).isTrue();
        assertThat(notificationsOf(submitted)).isEmpty();
        assertThat(recorder.await(signal("REVIEW_UPDATED").and(mentioning(review.reviewId())), 1, WAIT)).hasSize(1);
        awaitMarker();
        assertThat(recorder.matching(signal("STORE_REVIEWS_CHANGED").and(mentioning(review.reviewId())))).isEmpty();
    }

    @Test
    void aFlaggedReviewGoesToTheModeratorsAndNotToTheStore() throws Exception {
        Review review = TestEvents.review("PRODUCT", productId, vendorId, customer);
        ConsumerRecord<String, String> submitted = avro.via(TestEvents.reviewSubmitted(review, 1, "FLAGGED", "LINK",
                false));
        dispatch(submitted);

        Stored flagged = notificationOf(submitted, "REVIEW_FLAGGED");
        assertThat(notificationsOf(submitted)).hasSize(1);                 // one row for the three roles
        assertThat(flagged.category()).isEqualTo("REVIEW");
        assertThat(flagged.severity()).isEqualTo("WARNING");
        assertThat(flagged.title()).isEqualTo("A 1★ review needs moderation");
        assertThat(flagged.body()).isEqualTo("Basmati Rice · contains a link");
        assertThat(flagged.link()).isEqualTo("/admin/reviews");
        assertThat(flagged.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN", "role:SUPPORT");
        assertThat(flagged.data().get("flagReason").asString()).isEqualTo("LINK");
        assertThat(inboxAbout(review.reviewId(), UUID.randomUUID(), "SUPPORT")).hasSize(1);
        assertThat(inboxAbout(review.reviewId(), UUID.randomUUID(), "FINANCE_ADMIN")).isEmpty();
        assertThat(inboxAbout(review.reviewId(), vendorOwner, "VENDOR")).isEmpty();

        assertThat(recorder.await(signal("ADMIN_REVIEWS_CHANGED").and(mentioning(review.reviewId())), 1, WAIT))
                .hasSize(1);
        awaitMarker();
        assertThat(recorder.matching(signal("STORE_REVIEWS_CHANGED").and(mentioning(review.reviewId())))).isEmpty();
    }

    @Test
    void moderationTellsTheCustomerWhenTheirReviewIsNotPublished() throws Exception {
        Review rejected = TestEvents.review("PRODUCT", productId, vendorId, customer);
        Review removed = TestEvents.review("VENDOR", vendorId, vendorId, customer);
        Review published = TestEvents.review("PRODUCT", productId, vendorId, customer);
        ConsumerRecord<String, String> reject = avro.via(TestEvents.reviewModerated(rejected, "REJECTED",
                "Contains a phone number"));
        ConsumerRecord<String, String> remove = avro.via(TestEvents.reviewModerated(removed, "REMOVED",
                "Abusive language"));
        ConsumerRecord<String, String> publish = avro.via(TestEvents.reviewModerated(published, "PUBLISHED", null));
        dispatch(reject);
        dispatch(remove);
        dispatch(publish);

        Stored notPublished = notificationOf(reject, "REVIEW_NOT_PUBLISHED");
        assertThat(notPublished.category()).isEqualTo("REVIEW");
        assertThat(notPublished.severity()).isEqualTo("WARNING");
        assertThat(notPublished.title()).isEqualTo("Your review of Basmati Rice was not published");
        assertThat(notPublished.body()).isEqualTo("Contains a phone number");
        assertThat(notPublished.link()).isEqualTo("/account/reviews");
        assertThat(notPublished.audiences()).containsExactly("user:" + customer);
        assertThat(notPublished.data().get("decision").asString()).isEqualTo("REJECTED");

        Stored wasRemoved = notificationOf(remove, "REVIEW_NOT_PUBLISHED");
        assertThat(wasRemoved.title()).isEqualTo("Your review of Green Basket was removed");
        assertThat(wasRemoved.body()).isEqualTo("Abusive language");
        assertThat(notificationsOf(publish)).isEmpty();
        assertThat(inboxAbout(rejected.reviewId(), vendorOwner, "VENDOR")).isEmpty();

        // the store's page changes when a review appears or disappears — not for a rejected (never shown) one
        assertThat(audiences(single(signal("STORE_REVIEWS_CHANGED").and(mentioning(removed.reviewId())))))
                .containsExactly("user:" + vendorOwner);
        assertThat(audiences(single(signal("STORE_REVIEWS_CHANGED").and(mentioning(published.reviewId())))))
                .containsExactly("user:" + vendorOwner);
        assertThat(single(signal("REVIEW_UPDATED").and(mentioning(rejected.reviewId())))
                .at("/payload/data/status").asString()).isEqualTo("REJECTED");
        awaitMarker();
        assertThat(recorder.matching(signal("STORE_REVIEWS_CHANGED").and(mentioning(rejected.reviewId())))).isEmpty();
    }

    @Test
    void aStoreReplyTellsTheCustomer() throws Exception {
        Review review = TestEvents.review("PRODUCT", productId, vendorId, customer);
        ConsumerRecord<String, String> replied = avro.via(TestEvents.reviewReplied(review, vendorOwner));
        dispatch(replied);

        Stored reply = notificationOf(replied, "REVIEW_REPLY");
        assertThat(notificationsOf(replied)).hasSize(1);
        assertThat(reply.category()).isEqualTo("REVIEW");
        assertThat(reply.severity()).isEqualTo("INFO");
        assertThat(reply.title()).isEqualTo("Green Basket replied to your review");
        assertThat(reply.body()).isEqualTo("Your review of Basmati Rice.");
        assertThat(reply.link()).isEqualTo("/account/reviews");
        assertThat(reply.audiences()).containsExactly("user:" + customer);
        assertThat(inboxAbout(review.reviewId(), customer, "CUSTOMER")).hasSize(1);
        assertThat(inboxAbout(review.reviewId(), UUID.randomUUID(), "CUSTOMER")).isEmpty();
        assertThat(audiences(single(signal("STORE_REVIEWS_CHANGED").and(mentioning(review.reviewId())))))
                .containsExactly("user:" + vendorOwner);
    }

    @Test
    void anUnknownStoreOwnerSkipsOnlyTheStoresPartAndNamesFallBack() {
        UUID unknownVendor = UUID.randomUUID();
        Review review = TestEvents.review("PRODUCT", UUID.randomUUID(), unknownVendor, customer);
        ConsumerRecord<String, String> submitted = TestEvents.reviewSubmitted(review, 3, "PUBLISHED", null, false);
        ConsumerRecord<String, String> replied = TestEvents.reviewReplied(review, UUID.randomUUID());
        ConsumerRecord<String, String> rejected = TestEvents.reviewModerated(review, "REJECTED", null);
        dispatch(submitted);
        dispatch(replied);
        dispatch(rejected);

        assertThat(processed(submitted)).isTrue();
        assertThat(notificationsOf(submitted)).isEmpty();
        Stored reply = notificationOf(replied, "REVIEW_REPLY");
        assertThat(reply.title()).isEqualTo("The store replied to your review");
        assertThat(reply.body()).isEqualTo("Your review of a product.");
        Stored notPublished = notificationOf(rejected, "REVIEW_NOT_PUBLISHED");
        assertThat(notPublished.title()).isEqualTo("Your review of a product was not published");
        assertThat(notPublished.body()).isEmpty();
    }

    @Test
    void oldDuplicateBrokenAndUnusedEvents() {
        Review review = TestEvents.review("PRODUCT", productId, vendorId, customer);
        ConsumerRecord<String, String> old = TestEvents.old(TestEvents.reviewSubmitted(review, 4, "PUBLISHED", null,
                false));
        dispatch(old);
        assertThat(processed(old)).isTrue();
        assertThat(notificationsOf(old)).isEmpty();

        ConsumerRecord<String, String> fresh = TestEvents.reviewSubmitted(review, 4, "PUBLISHED", null, false);
        dispatch(fresh);
        dispatch(fresh);                                            // redelivered
        assertThat(notificationsOf(fresh)).hasSize(1);

        ConsumerRecord<String, String> summary = TestEvents.ratingSummaryUpdated("PRODUCT", productId);
        dispatch(summary);
        assertThat(processed(summary)).as("catalog-query's event, not used here").isFalse();

        ConsumerRecord<String, String> noReviewId = TestEvents.event("review.events", "ReviewSubmitted",
                UUID.randomUUID(), TestEvents.map("customerId", customer, "targetType", "PRODUCT", "status",
                        "PUBLISHED"));
        ConsumerRecord<String, String> noStatus = TestEvents.event("review.events", "ReviewSubmitted",
                UUID.randomUUID(), TestEvents.map("reviewId", UUID.randomUUID(), "customerId", customer,
                        "targetType", "PRODUCT"));
        ConsumerRecord<String, String> noDecision = TestEvents.event("review.events", "ReviewModerated",
                UUID.randomUUID(), TestEvents.map("reviewId", UUID.randomUUID(), "customerId", customer,
                        "targetType", "PRODUCT"));
        assertThatThrownBy(() -> dispatch(noReviewId)).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("reviewId");
        assertThatThrownBy(() -> dispatch(noStatus)).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> dispatch(noDecision)).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("decision");
        assertThat(processed(noReviewId)).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode single(Predicate<JsonNode> predicate) {
        List<JsonNode> found = recorder.await(predicate, 1, WAIT);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static List<String> audiences(JsonNode message) {
        List<String> audiences = new ArrayList<>();
        message.get("audiences").forEach(a -> audiences.add(a.asString()));
        return audiences;
    }

    /** A marker after the events of a test: once it arrived, anything published before it has arrived too. */
    private void awaitMarker() {
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        assertThat(recorder.await(kind("signal").and(mentioning(marker)), 1, WAIT)).isNotEmpty();
    }
}
