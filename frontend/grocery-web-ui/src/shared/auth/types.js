/** @typedef {import('./roles').SelfRegistrationRole} SelfRegistrationRole */
/** @typedef {import('./roles').UserRole} UserRole */

/**
 * Status of a business linked to a user, as identity sees it.
 * @typedef {'PENDING' | 'ACTIVE' | 'REJECTED' | 'SUSPENDED'} BusinessLinkStatus
 */

/**
 * @typedef {object} BusinessLink
 * @property {'VENDOR' | 'RESTAURANT' | (string & {})} type
 * @property {string} businessId
 * @property {BusinessLinkStatus | (string & {})} status
 */

/** @typedef {'ACTIVE' | 'SUSPENDED' | (string & {})} UserStatus */

/**
 * `UserView` — the signed-in user (contract §3).
 * @typedef {object} UserView
 * @property {string} id
 * @property {string} email
 * @property {string} fullName
 * @property {string | null} phone
 * @property {UserStatus} status
 * @property {UserRole[]} roles
 * @property {string | null} vendorId Set only while the vendor is APPROVED (mirrors the `vendor_id` claim).
 * @property {string | null} restaurantId Set only while the restaurant is APPROVED (mirrors the `restaurant_id` claim).
 * @property {BusinessLink[]} [businesses]
 */

/**
 * @typedef {object} AuthResponse
 * @property {string} accessToken
 * @property {'Bearer' | (string & {})} tokenType
 * @property {number} expiresIn Seconds until the access token expires (900).
 * @property {UserView} user
 */

/**
 * @typedef {object} LoginRequest
 * @property {string} email
 * @property {string} password
 */

/**
 * `POST /api/auth/password/change` (signed in).
 * @typedef {object} ChangePasswordRequest
 * @property {string} currentPassword
 * @property {string} newPassword
 */

/**
 * `POST /api/auth/password/reset`: the token of the emailed link.
 * @typedef {object} ResetPasswordRequest
 * @property {string} token
 * @property {string} newPassword
 */

/**
 * @typedef {object} RegisterRequest
 * @property {string} email
 * @property {string} password
 * @property {string} fullName
 * @property {string} [phone]
 * @property {SelfRegistrationRole} role
 */

// Types only (JSDoc); this file has no runtime code.
export {}
