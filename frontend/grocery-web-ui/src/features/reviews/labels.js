import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { REVIEW_EDIT_DAYS, REVIEW_ERRORS, REVIEW_WINDOW_DAYS } from './api'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').ReviewStatus} ReviewStatus */
/** @typedef {import('./api').ReviewTargetType} ReviewTargetType */
/** @typedef {import('./api').ReviewView} ReviewView */

const DAY_MS = 24 * 60 * 60 * 1000

/** @type {Record<ReviewTargetType, string>} */
export const TARGET_TYPE_LABELS = {
  PRODUCT: 'Product',
  MENU_ITEM: 'Dish',
  VENDOR: 'Store',
  RESTAURANT: 'Restaurant',
  DELIVERY_PARTNER: 'Delivery partner',
}

/**
 * @param {string} type
 * @returns {string}
 */
export function targetTypeLabel(type) {
  return type in TARGET_TYPE_LABELS ? TARGET_TYPE_LABELS[type] : 'Item'
}

/**
 * What a review is about, grouped on the "Rate your order" page: the items, the store, the delivery.
 * @typedef {'items' | 'store' | 'delivery'} TargetGroup
 */

/**
 * @param {string} type
 * @returns {TargetGroup}
 */
export function targetGroup(type) {
  if (type === 'VENDOR' || type === 'RESTAURANT') return 'store'
  if (type === 'DELIVERY_PARTNER') return 'delivery'
  return 'items'
}

/**
 * The name of a target in a sentence, with a fallback when review-service has none.
 * @param {string} type
 * @param {string | null | undefined} name
 * @returns {string}
 */
export function targetDisplayName(type, name) {
  if (name && name.trim()) return name
  switch (type) {
    case 'VENDOR':
    case 'RESTAURANT':
      return 'The store'
    case 'DELIVERY_PARTNER':
      return 'Your delivery partner'
    case 'MENU_ITEM':
      return 'This dish'
    default:
      return 'This product'
  }
}

/**
 * The customer's own review: published, hidden while an admin checks it, or not published.
 * @type {Record<ReviewStatus, string>}
 */
export const REVIEW_STATUS_LABELS = {
  PUBLISHED: 'Published',
  FLAGGED: 'Under review',
  REJECTED: 'Not published',
  REMOVED: 'Removed',
}

/** @type {Record<ReviewStatus, BadgeTone>} */
export const REVIEW_STATUS_TONES = {
  PUBLISHED: 'green',
  FLAGGED: 'amber',
  REJECTED: 'red',
  REMOVED: 'gray',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function reviewStatusLabel(status) {
  return status in REVIEW_STATUS_LABELS ? REVIEW_STATUS_LABELS[status] : status
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function reviewStatusTone(status) {
  return status in REVIEW_STATUS_TONES ? REVIEW_STATUS_TONES[status] : 'gray'
}

/**
 * One sentence under a review that is not public.
 * @param {string} status
 * @returns {string | null}
 */
export function reviewStatusHint(status) {
  switch (status) {
    case 'FLAGGED':
      return 'Our team is checking this review. It stays hidden until it is approved.'
    case 'REJECTED':
      return 'Our team did not publish this review.'
    case 'REMOVED':
      return 'Our team removed this review, so it is no longer shown.'
    default:
      return null
  }
}

/**
 * Words for each star (the star input's labels).
 * @type {Record<number, string>}
 */
export const RATING_WORDS = { 1: 'Terrible', 2: 'Poor', 3: 'Okay', 4: 'Good', 5: 'Excellent' }

/**
 * @param {number} rating
 * @returns {string}
 */
export function starsLabel(rating) {
  return `${rating} star${rating === 1 ? '' : 's'}`
}

/**
 * `4.333` → `4.3`, `5` → `5.0`.
 * @param {number | null | undefined} value
 * @returns {string}
 */
export function formatRating(value) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return value.toFixed(1)
}

/**
 * `1 rating`, `1,204 ratings`.
 * @param {number} count
 * @returns {string}
 */
export function ratingCountLabel(count) {
  return `${count.toLocaleString('en-IN')} rating${count === 1 ? '' : 's'}`
}

/**
 * End of the 7-day edit window: the server's `editableUntil`, else `createdAt + 7 days`.
 * @param {Pick<ReviewView, 'editableUntil' | 'createdAt'>} review
 * @returns {number}
 */
export function editableUntil(review) {
  const explicit = review.editableUntil ? Date.parse(review.editableUntil) : Number.NaN
  if (Number.isFinite(explicit)) return explicit
  return Date.parse(review.createdAt) + REVIEW_EDIT_DAYS * DAY_MS
}

/**
 * @param {Pick<ReviewView, 'editableUntil' | 'createdAt' | 'status'>} review
 * @param {number} now
 * @returns {boolean}
 */
export function canEditReview(review, now) {
  return review.status !== 'REMOVED' && editableUntil(review) > now
}

/**
 * Whether a delivered order can still be reviewed (the server has the last word).
 * @param {string | null | undefined} deliveredAt
 * @param {number} now
 * @returns {boolean}
 */
export function reviewWindowOpen(deliveredAt, now) {
  if (!deliveredAt) return false
  const delivered = Date.parse(deliveredAt)
  return Number.isFinite(delivered) && now - delivered < REVIEW_WINDOW_DAYS * DAY_MS
}

/**
 * Friendly text for a refused review write.
 * @param {unknown} error
 * @returns {string}
 */
export function reviewErrorMessage(error) {
  if (hasErrorCode(error, REVIEW_ERRORS.notEligible)) {
    return 'This can no longer be reviewed: the time for reviewing this order has ended.'
  }
  if (hasErrorCode(error, REVIEW_ERRORS.alreadyReviewed))
    return 'You have already reviewed this. You can edit your review instead.'
  if (hasErrorCode(error, REVIEW_ERRORS.editWindowOver)) {
    return 'Reviews can only be edited for 7 days after you write them. You can still delete it.'
  }
  if (hasErrorCode(error, REVIEW_ERRORS.notFound)) return 'This review no longer exists.'
  return errorMessage(error)
}
