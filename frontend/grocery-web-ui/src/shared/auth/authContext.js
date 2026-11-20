import { createContext } from 'react'
/** @typedef {import('./permissions').Permission} Permission */
/** @typedef {import('./roles').RoleArea} RoleArea */
/** @typedef {import('./roles').UserRole} UserRole */
/** @typedef {import('./session').SessionEndReason} SessionEndReason */
/** @typedef {import('./session').SessionStatus} SessionStatus */
/** @typedef {import('./types').LoginRequest} LoginRequest */
/** @typedef {import('./types').RegisterRequest} RegisterRequest */
/** @typedef {import('./types').UserView} UserView */

/**
 * @typedef {object} AuthContextValue
 * @property {SessionStatus} status
 * @property {UserView | null} user
 * @property {readonly UserRole[]} roles
 * @property {RoleArea | null} area The UI area the user belongs to; null while signed out.
 * @property {boolean} isAuthenticated
 * @property {boolean} isAdmin
 * @property {SessionEndReason | null} endReason
 * @property {string | null} suspendedMessage
 * @property {(...roles: readonly UserRole[]) => boolean} hasRole
 * @property {(permission: Permission) => boolean} can
 * @property {(request: LoginRequest) => Promise<UserView>} login
 * @property {(request: RegisterRequest) => Promise<UserView>} register
 * @property {() => Promise<void>} logout
 * @property {() => Promise<UserView>} refresh Renews the access token now (e.g. after an approval, to pick up `vendor_id`).
 */

export const AuthContext = createContext(null)
