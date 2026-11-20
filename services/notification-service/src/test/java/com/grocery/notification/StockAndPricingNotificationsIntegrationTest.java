package com.grocery.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract §4, inventory.events and pricing.events rows. */
class StockAndPricingNotificationsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void lowStockGoesToTheVendorOwnerOnly() throws Exception {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        UUID otherOwner = knownVendor(UUID.randomUUID(), "Other Farms");
        UUID productId = UUID.randomUUID();

        ConsumerRecord<String, String> low = TestEvents.lowStock(productId, vendorId, "Toor Dal 1 kg", 3, 5);
        dispatch(low);

        Stored notification = notificationOf(low, "LOW_STOCK");
        assertThat(notification.category()).isEqualTo("STOCK");
        assertThat(notification.severity()).isEqualTo("WARNING");
        assertThat(notification.title()).isEqualTo("Low stock: Toor Dal 1 kg");
        assertThat(notification.body()).isEqualTo("3 left (alert at 5).");
        assertThat(notification.link()).isEqualTo("/vendor/inventory");
        assertThat(notification.audiences()).containsExactly("user:" + owner);
        assertThat(inboxAbout(productId, otherOwner, "VENDOR")).isEmpty();
    }

    @Test
    void lowStockWithoutAProductNameUsesANeutralWord() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");

        ConsumerRecord<String, String> low = TestEvents.lowStock(UUID.randomUUID(), vendorId, null, 0, 5);
        dispatch(low);

        assertThat(notificationOf(low, "LOW_STOCK").title()).isEqualTo("Low stock: a product");
    }

    @Test
    void lowStockOfAnUnknownVendorNotifiesNobody() {
        ConsumerRecord<String, String> low = TestEvents.lowStock(UUID.randomUUID(), UUID.randomUUID(), "Rice", 1, 5);
        dispatch(low);

        assertThat(processed(low)).isTrue();
        assertThat(notificationsOf(low)).isEmpty();
    }

    @Test
    void priceChangeRequestsGoToSuperOpsAndFinanceAdminsAsOneNotification() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        UUID requestId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        ConsumerRecord<String, String> requested = TestEvents.priceChangeRequested(requestId, productId, vendorId,
                "Basmati Rice 5 kg", "88.00", "45.00", "-48.98");
        dispatch(requested);

        assertThat(notificationsOf(requested)).hasSize(1);
        Stored notification = notificationOf(requested, "PRICE_APPROVAL_REQUESTED");
        assertThat(notification.category()).isEqualTo("APPROVAL");
        assertThat(notification.severity()).isEqualTo("INFO");
        assertThat(notification.title()).isEqualTo("Price change waiting: Basmati Rice 5 kg");
        assertThat(notification.body()).isEqualTo("-48.98% — ₹88.00 → ₹45.00");
        assertThat(notification.link()).isEqualTo("/admin/price-approvals");
        assertThat(notification.data().get("requestId").asString()).isEqualTo(requestId.toString());
        assertThat(notification.audiences())
                .containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN", "role:FINANCE_ADMIN");
    }

    @Test
    void anIncreaseShowsItsSign() {
        ConsumerRecord<String, String> requested = TestEvents.priceChangeRequested(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Ghee 1 l", "500.00", "1250.00", "150.00");
        dispatch(requested);

        assertThat(notificationOf(requested, "PRICE_APPROVAL_REQUESTED").body())
                .isEqualTo("+150.00% — ₹500.00 → ₹1,250.00");
    }

    @Test
    void approvedAndRejectedPriceChangesGoToTheVendorOwnerOnly() {
        UUID vendorId = UUID.randomUUID();
        UUID owner = knownVendor(vendorId, "Fresh Farms");
        UUID productId = UUID.randomUUID();

        ConsumerRecord<String, String> approved = TestEvents.priceChange("PriceChangeApproved", UUID.randomUUID(),
                productId, vendorId, null);
        ConsumerRecord<String, String> rejected = TestEvents.priceChange("PriceChangeRejected", UUID.randomUUID(),
                productId, vendorId, "The new price is below cost.");
        ConsumerRecord<String, String> cancelled = TestEvents.priceChange("PriceChangeCancelled", UUID.randomUUID(),
                productId, vendorId, "Product archived");
        dispatch(approved);
        dispatch(rejected);
        dispatch(cancelled);

        Stored ok = notificationOf(approved, "PRICE_CHANGE_APPROVED");
        assertThat(ok.category()).isEqualTo("PRICING");
        assertThat(ok.severity()).isEqualTo("SUCCESS");
        assertThat(ok.title()).isEqualTo("Price change approved");
        assertThat(ok.body()).isEqualTo("The new price is live.");
        assertThat(ok.link()).isEqualTo("/vendor/pricing");
        assertThat(ok.audiences()).containsExactly("user:" + owner);

        Stored no = notificationOf(rejected, "PRICE_CHANGE_REJECTED");
        assertThat(no.severity()).isEqualTo("WARNING");
        assertThat(no.title()).isEqualTo("Price change rejected");
        assertThat(no.body()).isEqualTo("The new price is below cost.");
        assertThat(no.link()).isEqualTo("/vendor/pricing");
        assertThat(no.audiences()).containsExactly("user:" + owner);

        assertThat(notificationsOf(cancelled)).isEmpty();
        assertThat(processed(cancelled)).isTrue();
    }

    @Test
    void stockAdjustmentsAvailabilityAndPriceChangesNotifyNobody() {
        UUID vendorId = UUID.randomUUID();
        knownVendor(vendorId, "Fresh Farms");
        UUID productId = UUID.randomUUID();
        ConsumerRecord<String, String> adjusted = TestEvents.stockAdjusted(productId, vendorId);
        ConsumerRecord<String, String> availability = TestEvents.availability(productId, vendorId, false);
        ConsumerRecord<String, String> price = TestEvents.priceChanged(productId, vendorId);

        dispatch(adjusted);
        dispatch(availability);
        dispatch(price);

        assertThat(notificationsOf(adjusted)).isEmpty();
        assertThat(notificationsOf(availability)).isEmpty();
        assertThat(notificationsOf(price)).isEmpty();
    }
}
