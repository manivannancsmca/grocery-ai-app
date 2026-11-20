import { humanizeToken } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').OrderEventDirection} OrderEventDirection */
/** @typedef {import('./api').OrderEventOutcome} OrderEventOutcome */
/** @typedef {import('./api').StuckOrderReason} StuckOrderReason */

/**
 * The admin orders page with this order's drawer open and the list narrowed to it (`?q=` + `?order=`).
 * @param {{ orderId: string, orderNumber?: string | null }} order
 * @returns {string}
 */
export function adminOrderLink(order) {
  const params = new URLSearchParams()
  if (order.orderNumber) params.set('q', order.orderNumber)
  params.set('order', order.orderId)
  return `/admin/orders?${params.toString()}`
}

/**
 * Phase 10 §4: what is overdue, per watchdog reason.
 * @type {Record<StuckOrderReason, string>}
 */
export const STUCK_REASON_LABELS = {
  STOCK_CHECK_OVERDUE: 'Stock check overdue',
  PAYMENT_EXPIRY_OVERDUE: 'Payment expiry overdue',
  STORE_RESPONSE_OVERDUE: 'Store response overdue',
  PREPARATION_OVERDUE: 'Preparation overdue',
  PICKUP_OVERDUE: 'Pickup overdue',
  DELIVERY_OVERDUE: 'Delivery overdue',
}

/**
 * @param {string} reason
 * @returns {string}
 */
export function stuckReasonLabel(reason) {
  return reason in STUCK_REASON_LABELS ? STUCK_REASON_LABELS[reason] : humanizeToken(reason)
}

/**
 * `45` → `45 min`, `90` → `1 h 30 min`, `120` → `2 h`.
 * @param {number} minutes
 * @returns {string}
 */
export function formatOverdue(minutes) {
  const whole = Math.max(0, Math.round(minutes))
  if (whole < 60) return `${whole} min`
  const hours = Math.floor(whole / 60)
  const rest = whole % 60
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/** @type {Record<OrderEventDirection, string>} */
export const EVENT_DIRECTION_LABELS = { PUBLISHED: 'Published', CONSUMED: 'Consumed' }
/** @type {Record<OrderEventDirection, BadgeTone>} */
export const EVENT_DIRECTION_TONES = { PUBLISHED: 'blue', CONSUMED: 'violet' }

/** @type {Record<OrderEventOutcome, string>} */
export const EVENT_OUTCOME_LABELS = { APPLIED: 'Applied', IGNORED: 'Ignored', DUPLICATE: 'Duplicate' }
/** @type {Record<OrderEventOutcome, BadgeTone>} */
export const EVENT_OUTCOME_TONES = { APPLIED: 'green', IGNORED: 'amber', DUPLICATE: 'gray' }

/**
 * @template {string} T
 * @param {Record<T, string>} labels
 * @param {string} value
 * @returns {string}
 */
export function labelOf(labels, value) {
  return value in labels ? labels[value] : humanizeToken(value)
}

/**
 * @template {string} T
 * @param {Record<T, BadgeTone>} tones
 * @param {string} value
 * @returns {BadgeTone}
 */
export function toneOf(tones, value) {
  return value in tones ? tones[value] : 'gray'
}
