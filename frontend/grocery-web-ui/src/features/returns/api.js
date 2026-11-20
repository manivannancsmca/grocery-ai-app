import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Returns and partial refunds (order-service — Phase 12 contract §3). The customer of a DELIVERED order may ask for
 * lines and quantities back within the return window (a platform setting, 48 h by default; `OrderView.returnable`);
 * the refund is computed by the server (prorated by the order's coupon discount, plus the paid delivery fee when
 * everything left is returned).
 */

export const RETURN_REASONS = ['DAMAGED', 'WRONG_ITEM', 'MISSING', 'QUALITY', 'OTHER']
/** @typedef {(typeof RETURN_REASONS)[number]} ReturnReason */

export const RETURN_STATUSES = ['REQUESTED', 'ESCALATED', 'APPROVED', 'REFUNDED', 'REJECTED']
/** @typedef {(typeof RETURN_STATUSES)[number]} ReturnStatus */

export const RETURN_LIMITS = { note: 500 }
/** Hours after delivery a return may be requested (`ORDER_RETURN_WINDOW`). */
export const RETURN_WINDOW_HOURS = 48

export const RETURN_ERRORS = {
  /** Wrong state, window over, or another request is still open. */
  notAllowed: 'RETURN_NOT_ALLOWED',
  quantityExceeded: 'RETURN_QUANTITY_EXCEEDED',
}

/**
 * @typedef {object} ReturnItem
 * @property {string} itemId
 * @property {string} name
 * @property {number} quantity
 * @property {number} refundAmount This line's refund (after the order's coupon discount).
 */

/**
 * `ReturnView`.
 * @typedef {object} ReturnView
 * @property {string} id
 * @property {string} returnNumber e.g. `RT-7K3M9QX2`.
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {'GROCERY' | 'RESTAURANT' | (string & {})} orderType
 * @property {string} storeId
 * @property {string} storeName
 * @property {ReturnStatus | (string & {})} status
 * @property {ReturnReason | (string & {})} reason
 * @property {string | null} note
 * @property {ReturnItem[]} items
 * @property {number} refundAmount Total refund, incl. the paid delivery fee when the request returns everything that was left.
 * @property {boolean | null} restock Whether the goods went back to stock (null until decided; always false for restaurants).
 * @property {string | null} decideBy When the store must decide by (a platform setting, 24 h by default), after which it is escalated to admins.
 * @property {string} requestedAt
 * @property {string | null} decidedAt
 * @property {string | null} decidedByRole
 * @property {string | null} comment The store's or admin's comment (required on a rejection).
 * @property {string | null} refundedAt
 */

/**
 * `OrderView.returns` — the order's return requests, newest or oldest first.
 * @typedef {object} OrderReturnSummary
 * @property {string} id
 * @property {string} returnNumber
 * @property {ReturnStatus | (string & {})} status
 * @property {number} refundAmount
 * @property {string} requestedAt
 * @property {Array<{ itemId: string, quantity: number, name?: string }>} [items] The lines asked back (optional in the summary; used to work out what can still be returned).
 */

/**
 * @typedef {object} ReturnRequestBody
 * @property {Array<{ itemId: string, quantity: number }>} items
 * @property {ReturnReason} reason
 * @property {string} [note] ≤ 500.
 */

/**
 * @typedef {object} MyReturnParams
 * @property {number} [page]
 * @property {number} [size]
 */

export const MY_RETURNS_PAGE_SIZE = 10

export const returnKeys = {
  all: ['returns'],
  /** The customer's return list (`GET /api/returns`). */
  mine: () => [...returnKeys.all, 'mine'],
  mineList: (params) => [...returnKeys.mine(), params],
  /** One order's returns (`GET /api/orders/{id}/returns`). */
  forOrder: (orderId) => [...returnKeys.all, 'order', orderId],
}

/**
 * 201 ReturnView (with the computed refund); 422 RETURN_NOT_ALLOWED / RETURN_QUANTITY_EXCEEDED.
 * @param {string} orderId
 * @param {ReturnRequestBody} body
 * @returns {Promise<ReturnView>}
 */
export async function requestReturn(orderId, body) {
  return (await httpClient.post(`/orders/${segment(orderId)}/returns`, body)).data
}

/**
 * @param {string} orderId
 * @returns {Promise<ReturnView[]>}
 */
export async function fetchOrderReturns(orderId) {
  return (await httpClient.get(`/orders/${segment(orderId)}/returns`)).data
}

/**
 * The customer's own returns, newest first.
 * @param {MyReturnParams} params
 * @returns {Promise<PageResponse<ReturnView>>}
 */
export async function fetchMyReturns(params) {
  return (await httpClient.get('/returns', { params: cleanParams(params) })).data
}
