/** Roles a user can pick when registering (contract §2). */
export const SELF_REGISTRATION_ROLES = ['CUSTOMER', 'VENDOR', 'RESTAURANT_OWNER', 'DELIVERY_PARTNER'] as const
/** Admin roles, created by a SUPER_ADMIN. A user is an admin if they hold any of them. */
export const ADMIN_ROLES = ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN', 'SUPPORT', 'DELIVERY_MANAGER'] as const
export const USER_ROLES = [...SELF_REGISTRATION_ROLES, ...ADMIN_ROLES] as const

export type SelfRegistrationRole = (typeof SELF_REGISTRATION_ROLES)[number]
export type AdminRole = (typeof ADMIN_ROLES)[number]
/** A role as it appears in the JWT `roles` claim and in `UserView.roles`. */
export type UserRole = (typeof USER_ROLES)[number]

export const ROLE_LABELS: Record<UserRole, string> = {
  CUSTOMER: 'Customer',
  VENDOR: 'Vendor',
  RESTAURANT_OWNER: 'Restaurant owner',
  DELIVERY_PARTNER: 'Delivery partner',
  SUPER_ADMIN: 'Super admin',
  OPS_ADMIN: 'Operations admin',
  FINANCE_ADMIN: 'Finance admin',
  SUPPORT: 'Support',
  DELIVERY_MANAGER: 'Delivery manager',
}

export function isUserRole(value: unknown): value is UserRole {
  return typeof value === 'string' && (USER_ROLES as readonly string[]).includes(value)
}

export function isAdminRole(value: unknown): value is AdminRole {
  return typeof value === 'string' && (ADMIN_ROLES as readonly string[]).includes(value)
}

export function isAdmin(roles: readonly string[]): boolean {
  return roles.some(isAdminRole)
}

export function roleLabel(role: string): string {
  return isUserRole(role) ? ROLE_LABELS[role] : role
}

/** The UI areas: one per stakeholder, all admin roles share the admin area. */
export const ROLE_AREAS = ['CUSTOMER', 'VENDOR', 'RESTAURANT_OWNER', 'DELIVERY_PARTNER', 'ADMIN'] as const
export type RoleArea = (typeof ROLE_AREAS)[number]

interface AreaMeta {
  label: string
  /** Landing page of the area. */
  homePath: string
}

export const AREA_META: Record<RoleArea, AreaMeta> = {
  // Guests shop on the public storefront at `/`; a signed-in customer lands on their own home page.
  CUSTOMER: { label: 'Customer', homePath: '/customer/home' },
  VENDOR: { label: 'Vendor', homePath: '/vendor' },
  RESTAURANT_OWNER: { label: 'Restaurant owner', homePath: '/restaurant' },
  DELIVERY_PARTNER: { label: 'Delivery partner', homePath: '/delivery' },
  ADMIN: { label: 'Admin', homePath: '/admin' },
}

/** Which area a user lands in. Admin wins; self-registered users hold exactly one role. */
export function areaForRoles(roles: readonly string[]): RoleArea {
  if (isAdmin(roles)) return 'ADMIN'
  if (roles.includes('VENDOR')) return 'VENDOR'
  if (roles.includes('RESTAURANT_OWNER')) return 'RESTAURANT_OWNER'
  if (roles.includes('DELIVERY_PARTNER')) return 'DELIVERY_PARTNER'
  return 'CUSTOMER'
}

export function homePathForRoles(roles: readonly string[]): string {
  return AREA_META[areaForRoles(roles)].homePath
}
