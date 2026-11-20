import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/auth/roles').AdminRole} AdminRole */
/** @typedef {import('../../shared/auth/roles').UserRole} UserRole */

/** @typedef {'ACTIVE' | 'SUSPENDED'} AdminUserStatus */

/**
 * `AdminUserView` (contract §3).
 * @typedef {object} AdminUserView
 * @property {string} id
 * @property {string} email
 * @property {string} fullName
 * @property {string | null} phone
 * @property {UserRole[]} roles
 * @property {AdminUserStatus | (string & {})} status
 * @property {string} createdAt
 * @property {string | null} lastLoginAt
 */

/**
 * `CreateAdminRequest` — roles ⊆ admin roles.
 * @typedef {object} CreateAdminRequest
 * @property {string} email
 * @property {string} fullName
 * @property {string} password
 * @property {string} [phone]
 * @property {AdminRole[]} roles
 */

/**
 * @typedef {object} AdminUserListParams
 * @property {string} [query]
 * @property {UserRole} [role]
 * @property {AdminUserStatus} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

export const userKeys = {
  all: ['admin', 'users'],
  list: (params) => [...userKeys.all, 'list', params],
}

/**
 * @param {AdminUserListParams} params
 * @returns {Promise<PageResponse<AdminUserView>>}
 */
export async function fetchUsers(params) {
  return (await httpClient.get('/admin/users', { params: cleanParams(params) })).data
}

/**
 * @param {CreateAdminRequest} body
 * @returns {Promise<AdminUserView>}
 */
export async function createAdminUser(body) {
  return (await httpClient.post('/admin/users', body)).data
}

/**
 * @param {string} id
 * @param {string} reason
 * @returns {Promise<AdminUserView>}
 */
export async function suspendUser(id, reason) {
  return (await httpClient.post(`/admin/users/${segment(id)}/suspend`, { reason })).data
}

/**
 * @param {string} id
 * @returns {Promise<AdminUserView>}
 */
export async function reactivateUser(id) {
  return (await httpClient.post(`/admin/users/${segment(id)}/reactivate`)).data
}
