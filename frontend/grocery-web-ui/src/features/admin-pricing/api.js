import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../vendor-pricing/api').PriceChangeRequestView} PriceChangeRequestView */
/** @typedef {import('../vendor-pricing/api').PriceChangeStatus} PriceChangeStatus */

/**
 * @typedef {object} AdminPriceRequestListParams
 * @property {PriceChangeStatus} [status] The server defaults to PENDING (oldest first); other statuses are newest first.
 * @property {string} [vendorId]
 * @property {number} [page]
 * @property {number} [size]
 */

export const adminPriceKeys = {
  all: ['admin', 'price-change-requests'],
  list: (params) => [...adminPriceKeys.all, 'list', params],
}

const base = '/admin/price-change-requests'
/** @param {string} id */
const item = (id) => `${base}/${segment(id)}`

/**
 * @param {AdminPriceRequestListParams} params
 * @returns {Promise<PageResponse<PriceChangeRequestView>>}
 */
export async function listAdminPriceRequests(params) {
  return (await httpClient.get(base, { params: cleanParams(params) })).data
}

/**
 * The requested price becomes the live price. 409 PRICE_CHANGE_NOT_PENDING / PRODUCT_ARCHIVED.
 * @param {string} id
 * @param {string | null} note
 * @returns {Promise<PriceChangeRequestView>}
 */
export async function approvePriceRequest(id, note) {
  return (await httpClient.post(`${item(id)}/approve`, note ? { note } : {})).data
}

/**
 * `reason` is required (≤ 300). 409 PRICE_CHANGE_NOT_PENDING.
 * @param {string} id
 * @param {string} reason
 * @returns {Promise<PriceChangeRequestView>}
 */
export async function rejectPriceRequest(id, reason) {
  return (await httpClient.post(`${item(id)}/reject`, { reason })).data
}
