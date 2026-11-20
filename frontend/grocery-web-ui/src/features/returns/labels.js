import { errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { formatDate, formatDateTime, formatPrice } from '../../shared/utils/format'
import { RETURN_ERRORS } from './api'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../orders/api').OrderView} OrderView */
/** @typedef {import('./api').ReturnReason} ReturnReason */
/** @typedef {import('./api').ReturnStatus} ReturnStatus */
/** @typedef {import('./api').ReturnView} ReturnView */

/** @type {Record<ReturnReason, string>} */
export const RETURN_REASON_LABELS = {
  DAMAGED: 'Damaged or spoiled',
  WRONG_ITEM: 'Wrong item',
  MISSING: 'Missing item',
  QUALITY: 'Poor quality',
  OTHER: 'Something else',
}

/**
 * @param {string} reason
 * @returns {string}
 */
export function returnReasonLabel(reason) {
  return reason in RETURN_REASON_LABELS ? RETURN_REASON_LABELS[reason] : reason
}

/**
 * The customer's view of a return's progress.
 * @type {Record<ReturnStatus, string>}
 */
export const RETURN_STATUS_LABELS = {
  REQUESTED: 'Requested',
  ESCALATED: 'With support',
  APPROVED: 'Approved',
  REFUNDED: 'Refunded',
  REJECTED: 'Rejected',
}

/** @type {Record<ReturnStatus, BadgeTone>} */
export const RETURN_STATUS_TONES = {
  REQUESTED: 'amber',
  ESCALATED: 'violet',
  APPROVED: 'blue',
  REFUNDED: 'green',
  REJECTED: 'red',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function returnStatusLabel(status) {
  return status in RETURN_STATUS_LABELS ? RETURN_STATUS_LABELS[status] : status
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function returnStatusTone(status) {
  return status in RETURN_STATUS_TONES ? RETURN_STATUS_TONES[status] : 'gray'
}

/**
 * REQUESTED or ESCALATED: still waiting for a decision (only one per order at a time).
 * @param {string} status
 * @returns {boolean}
 */
export function isOpenReturn(status) {
  return status === 'REQUESTED' || status === 'ESCALATED'
}

/**
 * Restaurant orders use the same flow, worded as "report a problem" (never restocked).
 * @param {string} orderType
 * @returns {string}
 */
export function returnActionLabel(orderType) {
  return orderType === 'RESTAURANT' ? 'Report a problem' : 'Return items'
}

/**
 * One sentence on where a return stands, for the customer.
 * @param {Pick<ReturnView, 'status' | 'storeName' | 'decideBy' | 'refundAmount' | 'refundedAt' | 'comment'>} view
 * @returns {string}
 */
export function returnStatusText(view) {
  const refund = formatPrice(view.refundAmount)
  switch (view.status) {
    case 'REQUESTED':
      return `Waiting for ${view.storeName || 'the store'} to decide${view.decideBy ? ` (by ${formatDateTime(view.decideBy)})` : ''}. If approved, ${refund} will be refunded.`
    case 'ESCALATED':
      return `${view.storeName || 'The store'} did not decide in time, so our support team is looking at it. If approved, ${refund} will be refunded.`
    case 'APPROVED':
      return `Approved. ${refund} will be refunded to your original payment method.`
    case 'REFUNDED':
      return `${refund} was refunded to your original payment method${view.refundedAt ? ` on ${formatDate(view.refundedAt)}` : ''}.`
    case 'REJECTED':
      return view.comment ? `Rejected: “${view.comment}”` : 'Rejected.'
    default:
      return ''
  }
}

/**
 * How many of each line can still be asked back: the ordered quantity minus what other requests (except rejected
 * ones) already cover. Without per-line details in the order's return summaries the server checks it
 * (422 RETURN_QUANTITY_EXCEEDED).
 * @param {Pick<OrderView, 'items' | 'returns'>} order
 * @returns {Map<string, number>}
 */
export function returnableQuantities(order) {
  const left = new Map(order.items.map((item) => [item.itemId, item.quantity]))
  for (const request of order.returns ?? []) {
    if (request.status === 'REJECTED') continue
    for (const line of request.items ?? []) {
      left.set(line.itemId, Math.max(0, (left.get(line.itemId) ?? 0) - line.quantity))
    }
  }
  return left
}

/**
 * Friendly text for a refused return request.
 * @param {unknown} error
 * @returns {string}
 */
export function returnErrorMessage(error) {
  if (hasErrorCode(error, RETURN_ERRORS.notAllowed)) {
    const until = problemString(error, 'returnableUntil')
    return until
      ? `The return window of this order ended on ${formatDateTime(until)}.`
      : 'A return can no longer be requested for this order: its return window has ended, or another request for it is still open.'
  }
  if (hasErrorCode(error, RETURN_ERRORS.quantityExceeded)) {
    const name = problemString(error, 'itemName') ?? problemString(error, 'name')
    return name
      ? `You asked to return more ${name} than is left to return. Lower the quantity.`
      : 'You asked to return more of an item than is left to return. Lower the quantities.'
  }
  return errorMessage(error)
}
