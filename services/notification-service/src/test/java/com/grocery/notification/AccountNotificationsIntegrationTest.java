package com.grocery.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract §4, account rows: approval requests to the admin roles of the kind, lifecycle news to the owner. */
class AccountNotificationsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void vendorAndRestaurantSubmissionsGoToSuperAndOpsAdmins() {
        ConsumerRecord<String, String> vendor = TestEvents.vendor("VendorSubmitted", UUID.randomUUID(),
                UUID.randomUUID(), "Fresh Farms", null);
        ConsumerRecord<String, String> restaurant = TestEvents.restaurant("RestaurantSubmitted", UUID.randomUUID(),
                UUID.randomUUID(), "Spice Route", null);
        dispatch(vendor);
        dispatch(restaurant);

        Stored forVendor = notificationOf(vendor, "APPROVAL_REQUESTED");
        assertThat(forVendor.category()).isEqualTo("APPROVAL");
        assertThat(forVendor.severity()).isEqualTo("INFO");
        assertThat(forVendor.title()).isEqualTo("Fresh Farms is waiting for approval");
        assertThat(forVendor.body()).isEqualTo("New vendor");
        assertThat(forVendor.link()).isEqualTo("/admin/approvals");
        assertThat(forVendor.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN");

        Stored forRestaurant = notificationOf(restaurant, "APPROVAL_REQUESTED");
        assertThat(forRestaurant.title()).isEqualTo("Spice Route is waiting for approval");
        assertThat(forRestaurant.body()).isEqualTo("New restaurant");
        assertThat(forRestaurant.audiences()).containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN");
    }

    @Test
    void deliveryPartnerSubmissionsAlsoGoToDeliveryManagers() {
        ConsumerRecord<String, String> partner = TestEvents.partner("DeliveryPartnerSubmitted", UUID.randomUUID(),
                "Ravi Kumar", null);
        dispatch(partner);

        Stored notification = notificationOf(partner, "APPROVAL_REQUESTED");
        assertThat(notification.title()).isEqualTo("Ravi Kumar is waiting for approval");
        assertThat(notification.body()).isEqualTo("New delivery partner");
        assertThat(notification.audiences())
                .containsExactlyInAnyOrder("role:SUPER_ADMIN", "role:OPS_ADMIN", "role:DELIVERY_MANAGER");
    }

    @Test
    void aMissingNameFallsBackToANeutralWord() {
        ConsumerRecord<String, String> vendor = TestEvents.vendor("VendorSubmitted", UUID.randomUUID(),
                UUID.randomUUID(), null, null);
        dispatch(vendor);

        assertThat(notificationOf(vendor, "APPROVAL_REQUESTED").title()).isEqualTo("A vendor is waiting for approval");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "vendor     | Approved   | ACCOUNT_APPROVED   | SUCCESS | Your store is approved            | You can start now.   | /vendor",
            "vendor     | Rejected   | ACCOUNT_REJECTED   | WARNING | Your application needs changes    | Add a GST number.    | /vendor",
            "vendor     | Suspended  | ACCOUNT_SUSPENDED  | ERROR   | Your account is suspended         | Add a GST number.    | ",
            "vendor     | Reinstated | ACCOUNT_REINSTATED | SUCCESS | Your account is active again      |                      | /vendor",
            "restaurant | Approved   | ACCOUNT_APPROVED   | SUCCESS | Your restaurant is approved       | You can start now.   | /restaurant",
            "restaurant | Rejected   | ACCOUNT_REJECTED   | WARNING | Your application needs changes    | Add a GST number.    | /restaurant",
            "restaurant | Suspended  | ACCOUNT_SUSPENDED  | ERROR   | Your account is suspended         | Add a GST number.    | ",
            "restaurant | Reinstated | ACCOUNT_REINSTATED | SUCCESS | Your account is active again      |                      | /restaurant",
            "partner    | Approved   | ACCOUNT_APPROVED   | SUCCESS | Your partner account is approved  | You can start now.   | /delivery",
            "partner    | Rejected   | ACCOUNT_REJECTED   | WARNING | Your application needs changes    | Add a GST number.    | /delivery",
            "partner    | Suspended  | ACCOUNT_SUSPENDED  | ERROR   | Your account is suspended         | Add a GST number.    | ",
            "partner    | Reinstated | ACCOUNT_REINSTATED | SUCCESS | Your account is active again      |                      | /delivery"
    })
    void lifecycleEventsNotifyTheOwnerOnly(String kind, String step, String type, String severity, String title,
                                           String body, String link) {
        UUID accountId = UUID.randomUUID();
        UUID owner = "partner".equals(kind) ? accountId : UUID.randomUUID();
        String reason = "Add a GST number.";
        ConsumerRecord<String, String> event = switch (kind) {
            case "vendor" -> TestEvents.vendor("Vendor" + step, accountId, owner, "Fresh Farms", reason);
            case "restaurant" -> TestEvents.restaurant("Restaurant" + step, accountId, owner, "Spice Route", reason);
            default -> TestEvents.partner("DeliveryPartner" + step, accountId, "Ravi Kumar", reason);
        };
        dispatch(event);

        assertThat(notificationsOf(event)).hasSize(1);
        Stored notification = notificationOf(event, type);
        assertThat(notification.category()).isEqualTo("ACCOUNT");
        assertThat(notification.severity()).isEqualTo(severity);
        assertThat(notification.title()).isEqualTo(title);
        assertThat(notification.body()).isEqualTo(body == null ? "" : body);
        assertThat(notification.link()).isEqualTo(link);
        assertThat(notification.audiences()).containsExactly("user:" + owner);
    }

    @Test
    void userReactivatedTellsTheUserAndUserSuspendedNotifiesNobody() {
        UUID userId = UUID.randomUUID();
        ConsumerRecord<String, String> suspended = TestEvents.user("UserSuspended", userId);
        ConsumerRecord<String, String> reactivated = TestEvents.user("UserReactivated", userId);

        dispatch(suspended);
        dispatch(reactivated);

        assertThat(processed(suspended)).isTrue();
        assertThat(notificationsOf(suspended)).isEmpty();
        Stored notification = notificationOf(reactivated, "ACCOUNT_REINSTATED");
        assertThat(notification.title()).isEqualTo("Your account is active again");
        assertThat(notification.body()).isEmpty();
        assertThat(notification.link()).isEqualTo("/");
        assertThat(notification.audiences()).containsExactly("user:" + userId);
    }

    @Test
    void registrationsNotifyNobody() {
        ConsumerRecord<String, String> vendor = TestEvents.vendor("VendorRegistered", UUID.randomUUID(),
                UUID.randomUUID(), "Fresh Farms", null);
        ConsumerRecord<String, String> user = TestEvents.event("identity.events", "UserRegistered", UUID.randomUUID(),
                TestEvents.map("userId", UUID.randomUUID(), "email", "a@example.com", "fullName", "A",
                        "phone", null, "role", "CUSTOMER", "registeredAt", "2026-11-11T10:00:00Z"));
        dispatch(vendor);
        dispatch(user);

        assertThat(notificationsOf(vendor)).isEmpty();
        assertThat(processed(vendor)).isTrue();
        // Phase 12: UserRegistered feeds the email address projection, and still notifies nobody
        assertThat(processed(user)).isTrue();
        assertThat(notificationsOf(user)).isEmpty();
    }
}
