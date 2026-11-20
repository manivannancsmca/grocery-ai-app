import { problemString } from '../../shared/api/problemDetail'
import { humanizeToken } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../reviews/api').ReviewStatus} ReviewStatus */
/** @typedef {import('../reviews/api').ReviewTargetType} ReviewTargetType */

/** @type {Record<ReviewTargetType, string>} */
export const REVIEW_TARGET_LABELS = {
  PRODUCT: 'Product',
  MENU_ITEM: 'Dish',
  VENDOR: 'Store',
  RESTAURANT: 'Restaurant',
  DELIVERY_PARTNER: 'Delivery partner',
}

/** @type {Record<ReviewStatus, string>} */
export const REVIEW_STATUS_LABELS = {
  PUBLISHED: 'Published',
  FLAGGED: 'Flagged',
  REJECTED: 'Rejected',
  REMOVED: 'Removed',
}

/** @type {Record<ReviewStatus, BadgeTone>} */
export const REVIEW_STATUS_TONES = {
  PUBLISHED: 'green',
  FLAGGED: 'amber',
  REJECTED: 'gray',
  REMOVED: 'red',
}

/**
 * Automatic moderation's reasons in words.
 * @type {Record<string, string>}
 */
export const FLAG_REASON_LABELS = {
  LINK: 'Contains a link',
  PHONE: 'Contains a phone number',
  EMAIL: 'Contains an email address',
  PROFANITY: 'Profanity',
  UPPERCASE: 'Mostly capital letters',
  SHOUTING: 'Mostly capital letters',
}

/**
 * @param {string} type
 * @returns {string}
 */
export function reviewTargetLabel(type) {
  return type in REVIEW_TARGET_LABELS ? REVIEW_TARGET_LABELS[type] : humanizeToken(type)
}

/**
 * @param {string} status
 * @returns {string}
 */
export function reviewStatusLabel(status) {
  return status in REVIEW_STATUS_LABELS ? REVIEW_STATUS_LABELS[status] : humanizeToken(status)
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function reviewStatusTone(status) {
  return status in REVIEW_STATUS_TONES ? REVIEW_STATUS_TONES[status] : 'gray'
}

/**
 * @param {string | null | undefined} reason
 * @returns {string | null}
 */
export function flagReasonLabel(reason) {
  if (!reason) return null
  return reason
    .split(/[,\s]+/)
    .filter(Boolean)
    .map((part) => FLAG_REASON_LABELS[part] ?? humanizeToken(part))
    .join(', ')
}

/**
 * 409 REVIEW_NOT_MODERATABLE in words.
 * @param {unknown} error
 * @returns {string}
 */
export function moderationConflictMessage(error) {
  const current = problemString(error, 'currentStatus')
  const now = current ? ` It is ${reviewStatusLabel(current).toLowerCase()} now.` : ''
  return `This review was already moderated, so nothing was changed.${now} The list has been refreshed.`
}
