import { problemString } from '../../shared/api/problemDetail'
import { roleLabel } from '../../shared/auth/roles'
import { humanizeToken } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').ReturnReason} ReturnReason */
/** @typedef {import('./api').ReturnStatus} ReturnStatus */

/** @type {Record<ReturnStatus, string>} */
export const RETURN_STATUS_LABELS = {
  REQUESTED: 'Waiting for decision',
  ESCALATED: 'Escalated to admins',
  APPROVED: 'Approved — refunding',
  REFUNDED: 'Refunded',
  REJECTED: 'Rejected',
}

/** @type {Record<ReturnStatus, BadgeTone>} */
export const RETURN_STATUS_TONES = {
  REQUESTED: 'amber',
  ESCALATED: 'red',
  APPROVED: 'blue',
  REFUNDED: 'green',
  REJECTED: 'gray',
}

/** @type {Record<ReturnReason, string>} */
export const RETURN_REASON_LABELS = {
  DAMAGED: 'Damaged',
  WRONG_ITEM: 'Wrong item',
  MISSING: 'Missing',
  QUALITY: 'Quality problem',
  OTHER: 'Other',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function returnStatusLabel(status) {
  return status in RETURN_STATUS_LABELS ? RETURN_STATUS_LABELS[status] : humanizeToken(status)
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function returnStatusTone(status) {
  return status in RETURN_STATUS_TONES ? RETURN_STATUS_TONES[status] : 'gray'
}

/**
 * @param {string} reason
 * @returns {string}
 */
export function returnReasonLabel(reason) {
  return reason in RETURN_REASON_LABELS ? RETURN_REASON_LABELS[reason] : humanizeToken(reason)
}

/**
 * "the store" / "Support" …
 * @param {string | null} role
 * @returns {string | null}
 */
export function deciderLabel(role) {
  if (!role) return null
  if (role === 'VENDOR' || role === 'RESTAURANT_OWNER' || role === 'STORE') return 'the store'
  return roleLabel(role)
}

/**
 * 409 RETURN_NOT_PENDING in words, naming the status the return is in now.
 * @param {string} returnNumber
 * @param {unknown} error
 * @returns {string}
 */
export function returnConflictMessage(returnNumber, error) {
  const current = problemString(error, 'currentStatus')
  const now = current ? ` (now: ${returnStatusLabel(current).toLowerCase()})` : ''
  return `Return ${returnNumber} was already decided${now}, so nothing was changed. The list has been refreshed.`
}
