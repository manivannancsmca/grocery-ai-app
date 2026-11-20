package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.AccountKind;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.CustomerProfileUpdated;
import com.grocery.notification.event.EventPayloads.PartnerLifecycle;
import com.grocery.notification.event.EventPayloads.RestaurantLifecycle;
import com.grocery.notification.event.EventPayloads.RestaurantListing;
import com.grocery.notification.event.EventPayloads.UserEvent;
import com.grocery.notification.event.EventPayloads.UserRegistered;
import com.grocery.notification.event.EventPayloads.VendorLifecycle;
import com.grocery.notification.repository.EmailAddressRepository;
import com.grocery.notification.repository.StoreOwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * Account lifecycles: vendor.events, restaurant.events, delivery.events (partners) and identity.events (users).
 * <ul>
 *   <li>every vendor / restaurant lifecycle event and RestaurantListingUpdated refresh {@code store_owner};</li>
 *   <li>Submitted → {@code APPROVAL_REQUESTED} to the admin roles of the kind; Approved / Rejected / Suspended /
 *       Reinstated → {@code ACCOUNT_*} to the owner; Suspended also ends the owner's streams ({@code REVOKED});</li>
 *   <li>UserSuspended → no notification, ends the user's streams; UserReactivated → {@code ACCOUNT_REINSTATED};</li>
 *   <li>Phase 12: UserRegistered fills the email address projection (notifies nobody); UserSuspended /
 *       UserReactivated switch the user's emails off / on (old events too); customer.events CustomerProfileUpdated
 *       renames the customer there (old events too);</li>
 *   <li>signals: {@code APPROVALS_CHANGED} (every step but Registered) to the admin roles of the kind,
 *       {@code ACCOUNT_UPDATED} to the owner (every lifecycle event), {@code RESTAURANT_UPDATED} (public,
 *       throttled) from RestaurantListingUpdated.</li>
 * </ul>
 */
@Component
public class AccountEventHandler implements EventHandler {

    static final String VENDOR_TOPIC = "vendor.events";
    static final String RESTAURANT_TOPIC = "restaurant.events";
    static final String DELIVERY_TOPIC = "delivery.events";
    static final String IDENTITY_TOPIC = "identity.events";
    /** A customer's profile name (customerId = userId): the email address projection only. */
    static final String CUSTOMER_TOPIC = "customer.events";
    static final String CUSTOMER_PROFILE_UPDATED = "CustomerProfileUpdated";
    static final String RESTAURANT_LISTING_UPDATED = "RestaurantListingUpdated";
    static final String USER_SUSPENDED = "UserSuspended";
    static final String USER_REACTIVATED = "UserReactivated";
    /** Phase 12: the email address projection only. */
    static final String USER_REGISTERED = "UserRegistered";
    private static final Logger log = LoggerFactory.getLogger(AccountEventHandler.class);

    /** Lifecycle step (event type without the Vendor / Restaurant / DeliveryPartner prefix) → account status. */
    private static final Map<String, String> STEP_STATUS = Map.of(
            "Registered", "DRAFT",
            "Submitted", "PENDING_APPROVAL",
            "Approved", "APPROVED",
            "Rejected", "REJECTED",
            "Suspended", "SUSPENDED",
            "Reinstated", "APPROVED");

    /** One lifecycle event, whatever its topic. */
    private record Lifecycle(AccountKind kind, String step, UUID accountId, UUID ownerUserId, String name,
                             String reason) {
    }

    private final StoreOwnerRepository owners;
    private final EmailAddressRepository addresses;
    private final JsonMapper json;

    public AccountEventHandler(StoreOwnerRepository owners, EmailAddressRepository addresses, JsonMapper json) {
        this.owners = owners;
        this.addresses = addresses;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return switch (topic) {
            case VENDOR_TOPIC -> step(eventType, "Vendor") != null;
            case RESTAURANT_TOPIC -> RESTAURANT_LISTING_UPDATED.equals(eventType)
                    || step(eventType, "Restaurant") != null;
            case DELIVERY_TOPIC -> step(eventType, "DeliveryPartner") != null;
            case IDENTITY_TOPIC -> USER_SUSPENDED.equals(eventType) || USER_REACTIVATED.equals(eventType)
                    || USER_REGISTERED.equals(eventType);
            case CUSTOMER_TOPIC -> CUSTOMER_PROFILE_UPDATED.equals(eventType);
            default -> false;
        };
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        switch (event.topic()) {
            case IDENTITY_TOPIC -> user(event, plan);
            case CUSTOMER_TOPIC -> customerProfile(event, plan);
            case RESTAURANT_TOPIC -> {
                if (RESTAURANT_LISTING_UPDATED.equals(event.eventType())) {
                    listing(event, plan);
                } else {
                    lifecycle(restaurant(event), plan);
                }
            }
            case VENDOR_TOPIC -> lifecycle(vendor(event), plan);
            default -> lifecycle(partner(event), plan);
        }
    }

    private void lifecycle(Lifecycle event, DispatchPlan plan) {
        StoreType storeType = switch (event.kind()) {
            case VENDOR -> StoreType.VENDOR;
            case RESTAURANT -> StoreType.RESTAURANT;
            default -> null;
        };
        if (storeType != null) {
            owners.upsert(new StoreOwner(storeType, event.accountId(), event.ownerUserId(), event.name()), plan.now());
        }
        if (!plan.fresh()) {
            return;
        }
        AccountKind kind = event.kind();
        List<String> owner = List.of(Audiences.user(event.ownerUserId()));
        List<String> approvers = Audiences.roles(kind.approverRoles());
        switch (event.step()) {
            case "Submitted" -> plan.notify(
                    NotificationTemplates.approvalRequested(kind, event.accountId(), event.name()), approvers);
            case "Approved" -> plan.notify(NotificationTemplates.accountApproved(kind, event.accountId()), owner);
            case "Rejected" -> plan.notify(
                    NotificationTemplates.accountRejected(kind, event.accountId(), event.reason()), owner);
            case "Suspended" -> plan.notify(
                    NotificationTemplates.accountSuspended(kind, event.accountId(), event.reason()), owner);
            case "Reinstated" -> plan.notify(NotificationTemplates.accountReinstated(kind, event.accountId()), owner);
            default -> {
                // Registered: the owner's own draft, nothing to tell anybody
            }
        }
        if (!"Registered".equals(event.step())) {
            plan.signal(SignalType.APPROVALS_CHANGED, data("kind", kind.name()), approvers);
        }
        plan.signal(SignalType.ACCOUNT_UPDATED, data("kind", kind.name(), "status", STEP_STATUS.get(event.step())),
                owner);
        if ("Suspended".equals(event.step())) {
            plan.endSessions(SessionReason.REVOKED, owner);
        }
    }

    private void user(EventEnvelope event, DispatchPlan plan) {
        if (USER_REGISTERED.equals(event.eventType())) {
            registered(event, plan);
            return;
        }
        UserEvent user = event.payloadAs(json, UserEvent.class);
        UUID userId = Payloads.require(event, user.userId(), "userId");
        // Phase 12: a suspended user gets no email (old events too: a projection)
        addresses.setActive(userId, USER_REACTIVATED.equals(event.eventType()), plan.now());
        if (!plan.fresh()) {
            return;
        }
        List<String> audience = List.of(Audiences.user(userId));
        if (USER_SUSPENDED.equals(event.eventType())) {
            // no notification (contract §4): the user is signed out
            plan.signal(SignalType.ACCOUNT_UPDATED, data("kind", AccountKind.USER.name(), "status", "SUSPENDED"),
                    audience);
            plan.endSessions(SessionReason.REVOKED, audience);
        } else {
            plan.notify(NotificationTemplates.accountReinstated(AccountKind.USER, userId), audience);
            plan.signal(SignalType.ACCOUNT_UPDATED, data("kind", AccountKind.USER.name(), "status", "ACTIVE"),
                    audience);
        }
    }

    /**
     * Phase 12 §5, UserRegistered: the email address projection (address, full name, the role it registered with).
     * Notifies nobody. An address that is not usable is ignored (logged), never dead-lettered.
     */
    private void registered(EventEnvelope event, DispatchPlan plan) {
        UserRegistered user = event.payloadAs(json, UserRegistered.class);
        UUID userId = Payloads.require(event, user.userId(), "userId");
        if (!EmailAddressRepository.usable(user.email())) {
            log.warn("UserRegistered {} of user {} has no usable email address: not emailable", event.eventId(),
                    userId);
            return;
        }
        addresses.registered(userId, user.email(), user.fullName(), user.role(), plan.now());
    }

    /**
     * customer.events CustomerProfileUpdated: the name the customer gave their profile becomes the name on their emails
     * (the To header). A projection: old events too; a user the projection does not know is skipped. Notifies nobody.
     */
    private void customerProfile(EventEnvelope event, DispatchPlan plan) {
        CustomerProfileUpdated profile = event.payloadAs(json, CustomerProfileUpdated.class);
        UUID customerId = Payloads.require(event, profile.customerId(), "customerId");
        addresses.renamed(customerId, profile.fullName(), plan.now());
    }

    private void listing(EventEnvelope event, DispatchPlan plan) {
        RestaurantListing listing = event.payloadAs(json, RestaurantListing.class);
        UUID restaurantId = Payloads.require(event, listing.restaurantId(), "restaurantId");
        if (listing.ownerUserId() != null) {
            owners.upsert(new StoreOwner(StoreType.RESTAURANT, restaurantId, listing.ownerUserId(), listing.name()),
                    plan.now());
        }
        if (!plan.fresh()) {
            return;
        }
        plan.publicSignal(SignalType.RESTAURANT_UPDATED, data("restaurantId", restaurantId,
                        "acceptingOrders", listing.acceptingOrders(), "status", listing.status()),
                SignalType.RESTAURANT_UPDATED + ":" + restaurantId);
    }

    private Lifecycle vendor(EventEnvelope event) {
        VendorLifecycle vendor = event.payloadAs(json, VendorLifecycle.class);
        return new Lifecycle(AccountKind.VENDOR, step(event.eventType(), "Vendor"),
                Payloads.require(event, vendor.vendorId(), "vendorId"),
                Payloads.require(event, vendor.ownerUserId(), "ownerUserId"), vendor.businessName(), vendor.reason());
    }

    private Lifecycle restaurant(EventEnvelope event) {
        RestaurantLifecycle restaurant = event.payloadAs(json, RestaurantLifecycle.class);
        return new Lifecycle(AccountKind.RESTAURANT, step(event.eventType(), "Restaurant"),
                Payloads.require(event, restaurant.restaurantId(), "restaurantId"),
                Payloads.require(event, restaurant.ownerUserId(), "ownerUserId"), restaurant.name(),
                restaurant.reason());
    }

    /** The partner id is the user id. */
    private Lifecycle partner(EventEnvelope event) {
        PartnerLifecycle partner = event.payloadAs(json, PartnerLifecycle.class);
        UUID partnerId = Payloads.require(event, partner.partnerId(), "partnerId");
        return new Lifecycle(AccountKind.DELIVERY_PARTNER, step(event.eventType(), "DeliveryPartner"), partnerId,
                partnerId, partner.fullName(), partner.reason());
    }

    /** "VendorApproved" with prefix "Vendor" → "Approved"; anything else → null. */
    private static String step(String eventType, String prefix) {
        if (eventType == null || !eventType.startsWith(prefix)) {
            return null;
        }
        String step = eventType.substring(prefix.length());
        return STEP_STATUS.containsKey(step) ? step : null;
    }
}
