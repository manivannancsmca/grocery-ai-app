import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * `CustomerProfile` (contract §4). customerId = userId.
 * @typedef {object} CustomerProfile
 * @property {string} id
 * @property {string} email
 * @property {string} fullName
 * @property {string | null} phone
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * @typedef {object} UpdateCustomerProfileRequest
 * @property {string} fullName
 * @property {string | null} [phone]
 */

/**
 * `AddressRequest` — country defaults to "IN"; postalCode `^[1-9][0-9]{5}$`; lat -90..90, lng -180..180.
 * @typedef {object} AddressRequest
 * @property {string} label
 * @property {string} recipientName
 * @property {string} phone
 * @property {string} line1
 * @property {string | null} line2
 * @property {string | null} landmark
 * @property {string} city
 * @property {string} state
 * @property {string} postalCode
 * @property {string} country
 * @property {number | null} latitude
 * @property {number | null} longitude
 * @property {boolean} isDefault
 */

/** @typedef {AddressRequest & { id: string, createdAt: string, updatedAt: string }} Address */

/**
 * @typedef {object} AdminCustomerListParams
 * @property {string} [query]
 * @property {number} [page]
 * @property {number} [size]
 */

/** A customer can keep at most this many addresses (`422 ADDRESS_LIMIT_REACHED`). */
export const MAX_ADDRESSES = 10

export const customerKeys = {
  all: ['customer'],
  profile: () => [...customerKeys.all, 'profile'],
  addresses: () => [...customerKeys.all, 'addresses'],
  adminAll: ['admin', 'customers'],
  adminList: (params) => [...customerKeys.adminAll, params],
}

/** @returns {Promise<CustomerProfile>} */
export async function fetchMyProfile() {
  return (await httpClient.get('/customers/me')).data
}

/**
 * @param {UpdateCustomerProfileRequest} body
 * @returns {Promise<CustomerProfile>}
 */
export async function updateMyProfile(body) {
  return (await httpClient.put('/customers/me', body)).data
}

/** @returns {Promise<Address[]>} */
export async function fetchMyAddresses() {
  return (await httpClient.get('/customers/me/addresses')).data
}

/**
 * @param {AddressRequest} body
 * @returns {Promise<Address>}
 */
export async function createAddress(body) {
  return (await httpClient.post('/customers/me/addresses', body)).data
}

/**
 * @param {string} id
 * @param {AddressRequest} body
 * @returns {Promise<Address>}
 */
export async function updateAddress(id, body) {
  return (await httpClient.put(`/customers/me/addresses/${segment(id)}`, body)).data
}

/**
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteAddress(id) {
  await httpClient.delete(`/customers/me/addresses/${segment(id)}`)
}

/**
 * @param {string} id
 * @returns {Promise<Address>}
 */
export async function setDefaultAddress(id) {
  return (await httpClient.post(`/customers/me/addresses/${segment(id)}/default`)).data
}

/**
 * @param {AdminCustomerListParams} params
 * @returns {Promise<PageResponse<CustomerProfile>>}
 */
export async function fetchAdminCustomers(params) {
  return (await httpClient.get('/admin/customers', { params: cleanParams(params) })).data
}
