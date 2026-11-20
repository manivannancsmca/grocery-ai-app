package com.grocery.notification.entity;

import com.grocery.common.security.Roles;

import java.util.List;

/**
 * The account kinds of the lifecycle events (the {@code kind} of the {@code ACCOUNT_UPDATED} and
 * {@code APPROVALS_CHANGED} signals), with what the texts and links of contract §4 need about each.
 */
public enum AccountKind {

    VENDOR("store", "a vendor", "New vendor", "/vendor",
            List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN)),
    RESTAURANT("restaurant", "a restaurant", "New restaurant", "/restaurant",
            List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN)),
    DELIVERY_PARTNER("partner account", "a delivery partner", "New delivery partner", "/delivery",
            List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.DELIVERY_MANAGER)),
    /** identity.events (UserSuspended / UserReactivated): no store, no approval queue. */
    USER("account", "a user", "New user", "/", List.of());

    private final String accountNoun;
    private final String neutralName;
    private final String approvalBody;
    private final String home;
    private final List<String> approverRoles;

    AccountKind(String accountNoun, String neutralName, String approvalBody, String home, List<String> approverRoles) {
        this.accountNoun = accountNoun;
        this.neutralName = neutralName;
        this.approvalBody = approvalBody;
        this.home = home;
        this.approverRoles = approverRoles;
    }

    /** "Your {store / restaurant / partner account} is approved". */
    public String accountNoun() {
        return accountNoun;
    }

    /** The name used when the event carries none ("a vendor is waiting for approval"). */
    public String neutralName() {
        return neutralName;
    }

    /** The body of {@code APPROVAL_REQUESTED}. */
    public String approvalBody() {
        return approvalBody;
    }

    /** The owner's home route in the React app. */
    public String home() {
        return home;
    }

    /** The admin roles that handle this kind's approval queue. */
    public List<String> approverRoles() {
        return approverRoles;
    }
}
