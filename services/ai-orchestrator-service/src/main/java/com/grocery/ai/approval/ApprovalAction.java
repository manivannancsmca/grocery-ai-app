package com.grocery.ai.approval;

import com.grocery.common.security.Roles;

import java.util.Set;

/**
 * The sensitive actions and their decision rules (Phase 11 contract §4 table and §5 audiences; Phase 12 §6).
 *
 * @param audience            the target service, the token's {@code aud}
 * @param decider             who decides it: the customer it is for, the requester alone (a store owner's own
 *                            change), or anyone holding one of {@code deciderRoles}
 * @param deciderRoles        roles that decide a {@link Decider#ROLES} action; empty otherwise
 * @param fourEyes            when the requester may not decide it
 * @param takesIdempotencyKey whether the target endpoint takes {@code Idempotency-Key} (sent = approvalId)
 */
public enum ApprovalAction {

    // shopping (the customer decides; obo = the customer)
    PLACE_ORDER("order-service", Decider.CUSTOMER, Set.of(), FourEyes.NEVER, true),
    CANCEL_ORDER("order-service", Decider.CUSTOMER, Set.of(), FourEyes.NEVER, false),
    REQUEST_RETURN("order-service", Decider.CUSTOMER, Set.of(), FourEyes.NEVER, false),
    // admin-bi
    REFUND_PAYMENT("payment-service", Decider.ROLES, Set.of(Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN),
            FourEyes.ABOVE_THRESHOLD, true),
    ADMIN_CANCEL_ORDER("order-service", Decider.ROLES, Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN), FourEyes.NEVER,
            false),
    SUSPEND_VENDOR("vendor-service", Decider.ROLES, Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN), FourEyes.ALWAYS,
            false),
    SUSPEND_RESTAURANT("restaurant-service", Decider.ROLES, Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN),
            FourEyes.ALWAYS, false),
    // vendor (the vendor decides their own change)
    ADJUST_STOCK("inventory-service", Decider.REQUESTER, Set.of(), FourEyes.NEVER, false),
    CHANGE_PRICE("pricing-service", Decider.REQUESTER, Set.of(), FourEyes.NEVER, false),
    SEND_PURCHASE_ORDER("vendor-service", Decider.REQUESTER, Set.of(), FourEyes.NEVER, false),
    // restaurant (the owner decides)
    SET_MENU_ITEM_AVAILABILITY("restaurant-service", Decider.REQUESTER, Set.of(), FourEyes.NEVER, false),
    ACCEPT_STORE_ORDER("restaurant-service", Decider.REQUESTER, Set.of(), FourEyes.NEVER, false),
    REJECT_STORE_ORDER("restaurant-service", Decider.REQUESTER, Set.of(), FourEyes.NEVER, false),
    // delivery (a delivery manager or an operations admin decides; the requesting manager may)
    REASSIGN_DELIVERY("delivery-service", Decider.ROLES,
            Set.of(Roles.DELIVERY_MANAGER, Roles.OPS_ADMIN, Roles.SUPER_ADMIN), FourEyes.NEVER, false);

    /** Who decides. */
    public enum Decider {
        /** The customer it is for (= the requester); {@code obo} = the customer. */
        CUSTOMER,
        /** The requester alone (a vendor's or restaurant owner's own change); no {@code obo}. */
        REQUESTER,
        /** Anyone holding one of the decider roles (admin actions). */
        ROLES
    }

    /** When the requester may not decide their own request. */
    public enum FourEyes { NEVER, ABOVE_THRESHOLD, ALWAYS }

    private final String audience;
    private final Decider decider;
    private final Set<String> deciderRoles;
    private final FourEyes fourEyes;
    private final boolean takesIdempotencyKey;

    ApprovalAction(String audience, Decider decider, Set<String> deciderRoles, FourEyes fourEyes,
                   boolean takesIdempotencyKey) {
        this.audience = audience;
        this.decider = decider;
        this.deciderRoles = deciderRoles;
        this.fourEyes = fourEyes;
        this.takesIdempotencyKey = takesIdempotencyKey;
    }

    public String audience() {
        return audience;
    }

    public Decider decider() {
        return decider;
    }

    public Set<String> deciderRoles() {
        return deciderRoles;
    }

    public FourEyes fourEyes() {
        return fourEyes;
    }

    public boolean takesIdempotencyKey() {
        return takesIdempotencyKey;
    }

    /**
     * Decided by the requester alone ({@code approverUserId} = requester, as for PLACE_ORDER): a customer's purchase
     * or return, a store owner's own change. Expires after the customer TTL.
     */
    public boolean isSelfDecided() {
        return decider != Decider.ROLES;
    }

    /** Decided by admin roles: any admin may see it. */
    public boolean isAdminAction() {
        return decider == Decider.ROLES;
    }
}
