package com.grocery.common.security;

import java.util.Set;

/**
 * Role names carried in the JWT {@code roles} claim (authorities become {@code ROLE_<name>}).
 * This is shared authorization vocabulary, not domain logic.
 */
public final class Roles {

    // Self-registered stakeholders
    public static final String CUSTOMER = "CUSTOMER";
    public static final String VENDOR = "VENDOR";
    public static final String RESTAURANT_OWNER = "RESTAURANT_OWNER";
    public static final String DELIVERY_PARTNER = "DELIVERY_PARTNER";

    // Platform operators (created by a SUPER_ADMIN)
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String OPS_ADMIN = "OPS_ADMIN";
    public static final String FINANCE_ADMIN = "FINANCE_ADMIN";
    public static final String SUPPORT = "SUPPORT";
    public static final String DELIVERY_MANAGER = "DELIVERY_MANAGER";

    public static final Set<String> SELF_REGISTRATION = Set.of(CUSTOMER, VENDOR, RESTAURANT_OWNER, DELIVERY_PARTNER);
    public static final Set<String> ADMIN_ROLES = Set.of(SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER);

    /** SpEL for {@code @PreAuthorize}: any admin role may read admin views. */
    public static final String ANY_ADMIN =
            "hasAnyRole('SUPER_ADMIN','OPS_ADMIN','FINANCE_ADMIN','SUPPORT','DELIVERY_MANAGER')";
    /** SpEL for {@code @PreAuthorize}: stakeholder approvals and suspensions. */
    public static final String OPERATIONS_ADMIN = "hasAnyRole('SUPER_ADMIN','OPS_ADMIN')";

    private Roles() {
    }
}
