/** Roles a user can pick when registering (contract §2). */
export const SELF_REGISTRATION_ROLES = ['CUSTOMER', 'VENDOR', 'RESTAURANT_OWNER', 'DELIVERY_PARTNER']
/** Admin roles, created by a SUPER_ADMIN. A user is an admin if they hold any of them. */
export const ADMIN_ROLES = ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN', 'SUPPORT', 'DELIVERY_MANAGER']
export const USER_ROLES = [...SELF_REGISTRATION_ROLES, ...ADMIN_ROLES]

/** @typedef {(typeof SELF_REGISTRATION_ROLES)[number]} SelfRegistrationRole */
/** @typedef {(typeof ADMIN_ROLES)[number]} AdminRole */
/**
 * A role as it appears in the JWT `roles` claim and in `UserView.roles`.
 * @typedef {(typeof USER_ROLES)[number]} UserRole
 */

/** @type {Record<UserRole, string>} */
export const ROLE_LABELS = {
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

/**
 * @param {unknown} value
 * @returns {value is UserRole}
 */
export function isUserRole(value) {
  return typeof value === 'string' && USER_ROLES.includes(value)
}

/**
 * @param {unknown} value
 * @returns {value is AdminRole}
 */
export function isAdminRole(value) {
  return typeof value === 'string' && ADMIN_ROLES.includes(value)
}

/**
 * @param {readonly string[]} roles
 * @returns {boolean}
 */
export function isAdmin(roles) {
  return roles.some(isAdminRole)
}

/**
 * @param {string} role
 * @returns {string}
 */
export function roleLabel(role) {
  return isUserRole(role) ? ROLE_LABELS[role] : role
}

/** The UI areas: one per stakeholder, all admin roles share the admin area. */
export const ROLE_AREAS = ['CUSTOMER', 'VENDOR', 'RESTAURANT_OWNER', 'DELIVERY_PARTNER', 'ADMIN']
/** @typedef {(typeof ROLE_AREAS)[number]} RoleArea */

/**
 * @typedef {object} AreaMeta
 * @property {string} label
 * @property {string} homePath Landing page of the area.
 */

/** @type {Record<RoleArea, AreaMeta>} */
export const AREA_META = {
  // Guests shop on the public storefront at `/`; a signed-in customer lands on their own home page.
  CUSTOMER: { label: 'Customer', homePath: '/customer/home' },
  VENDOR: { label: 'Vendor', homePath: '/vendor' },
  RESTAURANT_OWNER: { label: 'Restaurant owner', homePath: '/restaurant' },
  DELIVERY_PARTNER: { label: 'Delivery partner', homePath: '/delivery' },
  ADMIN: { label: 'Admin', homePath: '/admin' },
}

/**
 * Which area a user lands in. Admin wins; self-registered users hold exactly one role.
 * @param {readonly string[]} roles
 * @returns {RoleArea}
 */
export function areaForRoles(roles) {
  if (isAdmin(roles)) return 'ADMIN'
  if (roles.includes('VENDOR')) return 'VENDOR'
  if (roles.includes('RESTAURANT_OWNER')) return 'RESTAURANT_OWNER'
  if (roles.includes('DELIVERY_PARTNER')) return 'DELIVERY_PARTNER'
  return 'CUSTOMER'
}

/**
 * @param {readonly string[]} roles
 * @returns {string}
 */
export function homePathForRoles(roles) {
  return AREA_META[areaForRoles(roles)].homePath
}
