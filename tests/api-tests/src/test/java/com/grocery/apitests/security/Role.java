package com.grocery.apitests.security;

import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The caller identities of the authorization matrix: anonymous, the 4 self-registered roles and the 5 admin roles. */
public enum Role {
    ANONYMOUS, CUSTOMER, VENDOR, RESTAURANT_OWNER, DELIVERY_PARTNER,
    SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER;

    public static final Set<Role> ADMINS = EnumSet.of(SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER);
    public static final Set<Role> SIGNED_IN = EnumSet.complementOf(EnumSet.of(ANONYMOUS));
    public static final Set<Role> EVERYONE = EnumSet.allOf(Role.class);

    private static final Map<Role, Session> SESSIONS = new ConcurrentHashMap<>();

    /** An approved user of this role (null for ANONYMOUS); one per role and run. */
    public Session session() {
        if (this == ANONYMOUS) {
            return null;
        }
        return SESSIONS.computeIfAbsent(this, role -> switch (role) {
            case CUSTOMER -> World.newCustomer("Matrix").session();
            case VENDOR -> World.storeA().vendor();
            case RESTAURANT_OWNER -> World.kitchen().owner();
            case DELIVERY_PARTNER -> World.partner().session();
            default -> World.adminWithRole(role.name());
        });
    }

    public static Set<Role> of(Role... roles) {
        return roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.of(roles[0], roles);
    }

    public static Set<Role> adminsAnd(Role... roles) {
        Set<Role> set = EnumSet.copyOf(ADMINS);
        set.addAll(of(roles));
        return set;
    }
}
