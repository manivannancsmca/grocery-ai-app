import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Returns & partial refunds as the store and the admins decide them (order-service — Phase 12 contract §3).
 * The customer asks within the return window (48 h of delivery by default); the store approves (stating whether the
 * goods can be restocked — grocery only) or rejects with a comment within the decision time (24 h by default), after
 * which the request escalates to admins. Both times are platform settings. The refund is computed by the server.
 */

export const RETURN_STATUSES = ['REQUESTED', 'ESCALATED', 'APPROVED', 'REFUNDED', 'REJECTED']
/** @typedef {(typeof RETURN_STATUSES)[number]} ReturnStatus */

/**
 * @param {unknown} value
 * @returns {value is ReturnStatus}
 */
export function isReturnStatus(value) {
  return typeof value === 'string' && RETURN_STATUSES.includes(value)
}

/**
 * Waiting for a decision (store: REQUESTED; admins also ESCALATED).
 * @param {string} status
 * @returns {boolean}
 */
export function isOpenReturn(status) {
  return status === 'REQUESTED' || status === 'ESCALATED'
}

export const RETURN_REASONS = ['DAMAGED', 'WRONG_ITEM', 'MISSING', 'QUALITY', 'OTHER']
/** @typedef {(typeof RETURN_REASONS)[number]} ReturnReason */

export const RETURN_LIMITS = { comment: 300 }

export const RETURN_ERRORS = {
  notPending: 'RETURN_NOT_PENDING',
  notFound: 'RETURN_NOT_FOUND',
}

/**
 * @typedef {object} ReturnItem
 * @property {string} itemId
 * @property {string} name
 * @property {number} quantity
 * @property {number} refundAmount
 */

/**
 * `ReturnView`.
 * @typedef {object} ReturnView
 * @property {string} id
 * @property {string} returnNumber `RT-7K3M9QX2`.
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {'GROCERY' | 'RESTAURANT' | (string & {})} orderType
 * @property {string} storeId
 * @property {string | null} storeName
 * @property {ReturnStatus | (string & {})} status
 * @property {ReturnReason | (string & {})} reason
 * @property {string | null} note
 * @property {ReturnItem[]} items
 * @property {number} refundAmount
 * @property {boolean | null} restock Set at approval (always false for restaurant orders); null before.
 * @property {string | null} decideBy The request escalates to admins if the store has not decided by then.
 * @property {string} requestedAt
 * @property {string | null} decidedAt
 * @property {string | null} decidedByRole `VENDOR`, `RESTAURANT_OWNER`, `SUPPORT`, …
 * @property {string | null} comment The rejection comment.
 * @property {string | null} refundedAt
 */

/**
 * @typedef {object} ReturnListParams
 * @property {ReturnStatus} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

/** @typedef {ReturnListParams & { storeId?: string }} AdminReturnListParams */

/**
 * Who decides: the store (its own returns) or an admin (any, including escalated ones).
 * @typedef {'store' | 'admin'} ReturnDecider
 */

export const returnKeys = {
  all: ['store-returns'],
  store: () => [...returnKeys.all, 'store'],
  storeList: (params) => [...returnKeys.store(), params],
  admin: () => [...returnKeys.all, 'admin'],
  adminList: (params) => [...returnKeys.admin(), params],
}

/**
 * @param {ReturnView} view
 * @returns {ReturnView}
 */
function normalise(view) {
  return { ...view, items: view.items ?? [] }
}

/** @param {ReturnDecider} decider */
const base = (decider) => (decider === 'admin' ? '/admin/returns' : '/store/returns')

/**
 * The caller's store's returns (newest first).
 * @param {ReturnListParams} params
 * @returns {Promise<PageResponse<ReturnView>>}
 */
export async function listStoreReturns(params) {
  const page = toPage((await httpClient.get('/store/returns', { params: cleanParams(params) })).data)
  return { ...page, content: page.content.map(normalise) }
}

/**
 * Without `status`: REQUESTED and ESCALATED (the server's default).
 * @param {AdminReturnListParams} params
 * @returns {Promise<PageResponse<ReturnView>>}
 */
export async function listAdminReturns(params) {
  const page = toPage((await httpClient.get('/admin/returns', { params: cleanParams(params) })).data)
  return { ...page, content: page.content.map(normalise) }
}

/**
 * `restock` is forced to false by the server for restaurant orders. 409 RETURN_NOT_PENDING (`currentStatus`).
 * @param {ReturnDecider} decider
 * @param {string} id
 * @param {boolean} restock
 * @returns {Promise<ReturnView>}
 */
export async function approveReturn(decider, id, restock) {
  return normalise((await httpClient.post(`${base(decider)}/${segment(id)}/approve`, { restock })).data)
}

/**
 * `comment` required, ≤ 300; it is shown to the customer. 409 RETURN_NOT_PENDING (`currentStatus`).
 * @param {ReturnDecider} decider
 * @param {string} id
 * @param {string} comment
 * @returns {Promise<ReturnView>}
 */
export async function rejectReturn(decider, id, comment) {
  return normalise((await httpClient.post(`${base(decider)}/${segment(id)}/reject`, { comment })).data)
}
