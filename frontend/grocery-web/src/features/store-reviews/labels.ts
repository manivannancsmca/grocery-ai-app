import { problemString } from '../../shared/api/problemDetail'
import type { BadgeTone } from '../../shared/components/ui'
import { humanizeToken } from '../../shared/utils/format'
import type { ReviewStatus, ReviewTargetType } from '../reviews/api'

export const REVIEW_TARGET_LABELS: Record<ReviewTargetType, string> = {
  PRODUCT: 'Product',
  MENU_ITEM: 'Dish',
  VENDOR: 'Store',
  RESTAURANT: 'Restaurant',
  DELIVERY_PARTNER: 'Delivery partner',
}

export const REVIEW_STATUS_LABELS: Record<ReviewStatus, string> = {
  PUBLISHED: 'Published',
  FLAGGED: 'Flagged',
  REJECTED: 'Rejected',
  REMOVED: 'Removed',
}

export const REVIEW_STATUS_TONES: Record<ReviewStatus, BadgeTone> = {
  PUBLISHED: 'green',
  FLAGGED: 'amber',
  REJECTED: 'gray',
  REMOVED: 'red',
}

/** Automatic moderation's reasons in words. */
export const FLAG_REASON_LABELS: Record<string, string> = {
  LINK: 'Contains a link',
  PHONE: 'Contains a phone number',
  EMAIL: 'Contains an email address',
  PROFANITY: 'Profanity',
  UPPERCASE: 'Mostly capital letters',
  SHOUTING: 'Mostly capital letters',
}

export function reviewTargetLabel(type: string): string {
  return type in REVIEW_TARGET_LABELS ? REVIEW_TARGET_LABELS[type as ReviewTargetType] : humanizeToken(type)
}

export function reviewStatusLabel(status: string): string {
  return status in REVIEW_STATUS_LABELS ? REVIEW_STATUS_LABELS[status as ReviewStatus] : humanizeToken(status)
}

export function reviewStatusTone(status: string): BadgeTone {
  return status in REVIEW_STATUS_TONES ? REVIEW_STATUS_TONES[status as ReviewStatus] : 'gray'
}

export function flagReasonLabel(reason: string | null | undefined): string | null {
  if (!reason) return null
  return reason
    .split(/[,\s]+/)
    .filter(Boolean)
    .map((part) => FLAG_REASON_LABELS[part] ?? humanizeToken(part))
    .join(', ')
}

/** 409 REVIEW_NOT_MODERATABLE in words. */
export function moderationConflictMessage(error: unknown): string {
  const current = problemString(error, 'currentStatus')
  const now = current ? ` It is ${reviewStatusLabel(current).toLowerCase()} now.` : ''
  return `This review was already moderated, so nothing was changed.${now} The list has been refreshed.`
}
