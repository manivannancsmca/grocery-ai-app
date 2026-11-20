import { problemString } from '../../shared/api/problemDetail'
import { humanizeToken } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').PurchaseOrderStatus} PurchaseOrderStatus */

/** @type {Record<PurchaseOrderStatus, string>} */
export const PO_STATUS_LABELS = {
  DRAFT: 'Draft',
  SENT: 'Sent',
  PARTIALLY_RECEIVED: 'Partially received',
  RECEIVED: 'Received',
  CANCELLED: 'Cancelled',
  CLOSED: 'Closed (short)',
}

/** @type {Record<PurchaseOrderStatus, BadgeTone>} */
export const PO_STATUS_TONES = {
  DRAFT: 'gray',
  SENT: 'blue',
  PARTIALLY_RECEIVED: 'amber',
  RECEIVED: 'green',
  CANCELLED: 'red',
  CLOSED: 'violet',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function poStatusLabel(status) {
  return status in PO_STATUS_LABELS ? PO_STATUS_LABELS[status] : humanizeToken(status)
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function poStatusTone(status) {
  return status in PO_STATUS_TONES ? PO_STATUS_TONES[status] : 'gray'
}

/**
 * What may be done with a purchase order in each status (contract §4).
 * @param {string} status
 */
export function poActions(status) {
  return {
    edit: status === 'DRAFT',
    send: status === 'DRAFT',
    cancel: status === 'DRAFT' || status === 'SENT',
    receive: status === 'SENT' || status === 'PARTIALLY_RECEIVED',
    close: status === 'PARTIALLY_RECEIVED',
  }
}

/**
 * 409 INVALID_STATUS_TRANSITION in words, naming the status the order is in now.
 * @param {unknown} error
 * @returns {string}
 */
export function transitionConflictMessage(error) {
  const current = problemString(error, 'currentStatus')
  return current
    ? `This purchase order is ${poStatusLabel(current).toLowerCase()} now, so that is no longer possible. It has been refreshed.`
    : 'The purchase order changed in the meantime, so that is no longer possible. It has been refreshed.'
}

/**
 * `abc-uuid` key for one receipt submission: retries of the same submission reuse it.
 * @returns {string}
 */
export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `receipt-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
