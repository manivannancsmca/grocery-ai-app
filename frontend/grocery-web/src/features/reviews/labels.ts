import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import type { BadgeTone } from '../../shared/components/ui'
import { REVIEW_EDIT_DAYS, REVIEW_ERRORS, REVIEW_WINDOW_DAYS, type ReviewStatus, type ReviewTargetType, type ReviewView } from './api'

const DAY_MS = 24 * 60 * 60 * 1000

export const TARGET_TYPE_LABELS: Record<ReviewTargetType, string> = {
  PRODUCT: 'Product',
  MENU_ITEM: 'Dish',
  VENDOR: 'Store',
  RESTAURANT: 'Restaurant',
  DELIVERY_PARTNER: 'Delivery partner',
}

export function targetTypeLabel(type: string): string {
  return type in TARGET_TYPE_LABELS ? TARGET_TYPE_LABELS[type as ReviewTargetType] : 'Item'
}

/** What a review is about, grouped on the "Rate your order" page: the items, the store, the delivery. */
export type TargetGroup = 'items' | 'store' | 'delivery'

export function targetGroup(type: string): TargetGroup {
  if (type === 'VENDOR' || type === 'RESTAURANT') return 'store'
  if (type === 'DELIVERY_PARTNER') return 'delivery'
  return 'items'
}

/** The name of a target in a sentence, with a fallback when review-service has none. */
export function targetDisplayName(type: string, name: string | null | undefined): string {
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

/** The customer's own review: published, hidden while an admin checks it, or not published. */
export const REVIEW_STATUS_LABELS: Record<ReviewStatus, string> = {
  PUBLISHED: 'Published',
  FLAGGED: 'Under review',
  REJECTED: 'Not published',
  REMOVED: 'Removed',
}

export const REVIEW_STATUS_TONES: Record<ReviewStatus, BadgeTone> = {
  PUBLISHED: 'green',
  FLAGGED: 'amber',
  REJECTED: 'red',
  REMOVED: 'gray',
}

export function reviewStatusLabel(status: string): string {
  return status in REVIEW_STATUS_LABELS ? REVIEW_STATUS_LABELS[status as ReviewStatus] : status
}

export function reviewStatusTone(status: string): BadgeTone {
  return status in REVIEW_STATUS_TONES ? REVIEW_STATUS_TONES[status as ReviewStatus] : 'gray'
}

/** One sentence under a review that is not public. */
export function reviewStatusHint(status: string): string | null {
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

/** Words for each star (the star input's labels). */
export const RATING_WORDS: Record<number, string> = { 1: 'Terrible', 2: 'Poor', 3: 'Okay', 4: 'Good', 5: 'Excellent' }

export function starsLabel(rating: number): string {
  return `${rating} star${rating === 1 ? '' : 's'}`
}

/** `4.333` → `4.3`, `5` → `5.0`. */
export function formatRating(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return value.toFixed(1)
}

/** `1 rating`, `1,204 ratings`. */
export function ratingCountLabel(count: number): string {
  return `${count.toLocaleString('en-IN')} rating${count === 1 ? '' : 's'}`
}

/** End of the 7-day edit window: the server's `editableUntil`, else `createdAt + 7 days`. */
export function editableUntil(review: Pick<ReviewView, 'editableUntil' | 'createdAt'>): number {
  const explicit = review.editableUntil ? Date.parse(review.editableUntil) : Number.NaN
  if (Number.isFinite(explicit)) return explicit
  return Date.parse(review.createdAt) + REVIEW_EDIT_DAYS * DAY_MS
}

export function canEditReview(review: Pick<ReviewView, 'editableUntil' | 'createdAt' | 'status'>, now: number): boolean {
  return review.status !== 'REMOVED' && editableUntil(review) > now
}

/** Whether a delivered order can still be reviewed (the server has the last word). */
export function reviewWindowOpen(deliveredAt: string | null | undefined, now: number): boolean {
  if (!deliveredAt) return false
  const delivered = Date.parse(deliveredAt)
  return Number.isFinite(delivered) && now - delivered < REVIEW_WINDOW_DAYS * DAY_MS
}

/** Friendly text for a refused review write. */
export function reviewErrorMessage(error: unknown): string {
  if (hasErrorCode(error, REVIEW_ERRORS.notEligible)) {
    return 'This can no longer be reviewed: the time for reviewing this order has ended.'
  }
  if (hasErrorCode(error, REVIEW_ERRORS.alreadyReviewed)) return 'You have already reviewed this. You can edit your review instead.'
  if (hasErrorCode(error, REVIEW_ERRORS.editWindowOver)) {
    return 'Reviews can only be edited for 7 days after you write them. You can still delete it.'
  }
  if (hasErrorCode(error, REVIEW_ERRORS.notFound)) return 'This review no longer exists.'
  return errorMessage(error)
}
