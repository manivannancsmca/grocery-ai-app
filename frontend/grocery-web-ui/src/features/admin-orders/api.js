import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../orders/api').OrderStatus} OrderStatus */
/** @typedef {import('../orders/api').OrderSummary} OrderSummary */
/** @typedef {import('../orders/api').OrderType} OrderType */
/** @typedef {import('../orders/api').OrderView} OrderView */

/** Admin order monitoring (order-service — Phase 6 contract §4). Reads: any admin role. */

/**
 * Phase 8: admins may cancel any non-terminal order up to READY_FOR_PICKUP, which `adminCancellable` reports
 * (customers only until the store accepts — `cancellable`). Older answers without it fall back to `cancellable`.
 * @typedef {object} AdminCancelRule
 * @property {boolean} [adminCancellable]
 */

/** @typedef {OrderSummary & AdminCancelRule & { customerId: string }} AdminOrderSummary */
/** @typedef {OrderView & AdminCancelRule & { customerId: string }} AdminOrderView */

/**
 * Phase 9: once a partner picked the order up, nobody can cancel it — not even support.
 * @type {readonly OrderStatus[]}
 */
const PAST_PICKUP = ['OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED']

/**
 * Whether the admin "Cancel order" action applies to this order.
 * @param {AdminCancelRule & { cancellable?: boolean, status?: OrderStatus }} order
 * @returns {boolean}
 */
export function adminCanCancel(order) {
  if (order.status && PAST_PICKUP.includes(order.status)) return false
  return order.adminCancellable ?? order.cancellable ?? false
}

/**
 * @typedef {object} AdminOrderListParams
 * @property {OrderStatus} [status]
 * @property {OrderType} [type]
 * @property {string} [customerId]
 * @property {string} [storeId]
 * @property {string} [q] Order number: exact, case-insensitive.
 * @property {string} [from] Dates (`YYYY-MM-DD`, India time), inclusive.
 * @property {string} [to]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * `GET /api/admin/stats` — one day in India time.
 * @typedef {object} OrderStats
 * @property {string} date
 * @property {string} timezone
 * @property {number} orders Orders created that day.
 * @property {Partial<Record<OrderStatus, number>>} byStatus
 * @property {Partial<Record<OrderType, number>>} byType
 * @property {number} confirmedRevenue Sum of totals of orders confirmed (paid) that day.
 * @property {number} averageOrderValue
 * @property {string} currency
 */

/** Phase 10 §4: why the watchdog flagged an order (status → threshold). */
export const STUCK_ORDER_REASONS = [
  'STOCK_CHECK_OVERDUE',
  'PAYMENT_EXPIRY_OVERDUE',
  'STORE_RESPONSE_OVERDUE',
  'PREPARATION_OVERDUE',
  'PICKUP_OVERDUE',
  'DELIVERY_OVERDUE',
]
/** @typedef {(typeof STUCK_ORDER_REASONS)[number]} StuckOrderReason */

/**
 * One entry of `GET /api/admin/orders/stuck` (longest overdue first).
 * @typedef {object} StuckOrder
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {OrderType} type
 * @property {OrderStatus} status
 * @property {StuckOrderReason | (string & {})} reason
 * @property {string} since When the order entered `status`.
 * @property {string} detectedAt
 * @property {number} minutesOverdue
 * @property {{ id: string, name: string }} store
 * @property {string} customerId
 */

/** @typedef {'PUBLISHED' | 'CONSUMED'} OrderEventDirection */
/**
 * `IGNORED` = rejected by a state guard (reason in `detail`); `DUPLICATE` = already processed.
 * @typedef {'APPLIED' | 'IGNORED' | 'DUPLICATE'} OrderEventOutcome
 */

/**
 * One entry of `GET /api/admin/orders/{id}/events` (Phase 10 §5), oldest first.
 * @typedef {object} OrderEventEntry
 * @property {string} eventId
 * @property {string} eventType
 * @property {string | null} sourceTopic
 * @property {OrderEventDirection | (string & {})} direction
 * @property {OrderEventOutcome | (string & {})} outcome
 * @property {string | null} detail
 * @property {string | null} occurredAt
 * @property {string} recordedAt
 * @property {string | null} correlationId
 */

export const adminOrderKeys = {
  all: ['admin', 'orders'],
  list: (params) => [...adminOrderKeys.all, 'list', params],
  detail: (id) => [...adminOrderKeys.all, 'detail', id],
  /** Under `all`, so every ADMIN_ORDERS_CHANGED signal (and STUCK_ORDER notification) refreshes them. */
  stuck: () => [...adminOrderKeys.all, 'stuck'],
  events: (id) => [...adminOrderKeys.all, 'events', id],
  /** Every day's stats — the prefix of `stats(date)`. */
  allStats: () => ['admin', 'order-stats'],
  stats: (date) => [...adminOrderKeys.allStats(), date],
}

/**
 * `YYYY-MM-DD` — the `from` / `to` filters of the admin order and payment lists (`<input type="date">` value).
 * @param {unknown} value
 * @returns {value is string}
 */
export function isIsoDate(value) {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
}

const base = '/admin/orders'

/**
 * @param {AdminOrderListParams} params
 * @returns {Promise<PageResponse<AdminOrderSummary>>}
 */
export async function listAdminOrders(params) {
  return (await httpClient.get(base, { params: cleanParams(params) })).data
}

/**
 * @param {string} id
 * @returns {Promise<AdminOrderView>}
 */
export async function fetchAdminOrder(id) {
  return (await httpClient.get(`${base}/${segment(id)}`)).data
}

/**
 * SUPER_ADMIN, OPS_ADMIN, SUPPORT · reason required (≤ 300) · 409 ORDER_NOT_CANCELLABLE.
 * @param {string} id
 * @param {string} reason
 * @returns {Promise<AdminOrderView>}
 */
export async function adminCancelOrder(id, reason) {
  return (await httpClient.post(`${base}/${segment(id)}/cancel`, { reason })).data
}

/**
 * Both Phase 10 lists are plain JSON arrays; anything else is reported instead of breaking the page.
 * @template T
 * @param {unknown} data
 * @returns {T[]}
 */
function listOf(data) {
  if (!Array.isArray(data)) throw new Error('Unexpected answer from the order service.')
  return data
}

/**
 * Phase 10 §4 · any admin · longest overdue first.
 * @returns {Promise<StuckOrder[]>}
 */
export async function fetchStuckOrders() {
  return listOf((await httpClient.get(`${base}/stuck`)).data)
}

/**
 * Phase 10 §5 · any admin · oldest first · 404 ORDER_NOT_FOUND.
 * @param {string} id
 * @returns {Promise<OrderEventEntry[]>}
 */
export async function fetchOrderEvents(id) {
  return listOf((await httpClient.get(`${base}/${segment(id)}/events`)).data)
}

/**
 * Without `date`: today in India time.
 * @param {string | null} [date=null]
 * @returns {Promise<OrderStats>}
 */
export async function fetchOrderStats(date = null) {
  return (await httpClient.get('/admin/stats', { params: cleanParams({ date }) })).data
}
