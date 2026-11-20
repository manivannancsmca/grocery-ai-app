import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../reviews/api').ReviewStatus} ReviewStatus */
/** @typedef {import('../reviews/api').ReviewView} ReviewView */

/**
 * Reviews as the store and the admins see them (review-service — Phase 12 contract §1): the vendor or restaurant
 * owner reads the published reviews of its store and of its products / dishes and writes one public reply each;
 * admins moderate (flagged reviews stay hidden until one decides).
 */

/**
 * A review in the store and admin lists: the customer's `ReviewView` plus moderation details.
 * @typedef {ReviewView & { flagReason?: string | null, storeId?: string | null, storeName?: string | null }} ModeratedReviewView
 * - `flagReason`: Why automatic moderation flagged it (`LINK`, `PHONE`, `EMAIL`, `PROFANITY`, `SHOUTING`, …).
 */

export const STORE_REVIEW_ERRORS = {
  /** 404 — the review is not the caller's store's. */
  notFound: 'REVIEW_NOT_FOUND',
  /** 409 — `currentStatus`. */
  notModeratable: 'REVIEW_NOT_MODERATABLE',
}

export const MODERATION_LIMITS = { reason: 300 }

/**
 * @typedef {object} StoreReviewListParams
 * @property {string} [targetType]
 * @property {number} [rating] 1–5 stars.
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} AdminReviewListParams
 * @property {ReviewStatus} [status] Without it the server lists FLAGGED ones.
 * @property {string} [targetType]
 * @property {string} [q]
 * @property {number} [page]
 * @property {number} [size]
 */

/** @typedef {'publish' | 'reject' | 'remove'} ModerationAction */

export const storeReviewKeys = {
  all: ['store-reviews'],
  store: () => [...storeReviewKeys.all, 'store'],
  storeList: (params) => [...storeReviewKeys.store(), params],
  admin: () => [...storeReviewKeys.all, 'admin'],
  adminList: (params) => [...storeReviewKeys.admin(), params],
}

/**
 * Published reviews of the caller's store and of its products or dishes, newest first.
 * @param {StoreReviewListParams} params
 * @returns {Promise<PageResponse<ModeratedReviewView>>}
 */
export async function listStoreReviews(params) {
  return toPage((await httpClient.get('/store/reviews', { params: cleanParams(params) })).data)
}

/**
 * Writes or replaces the store's one public reply (≤ 500). 404 when the review is not the store's.
 * @param {string} id
 * @param {string} text
 * @returns {Promise<ModeratedReviewView>}
 */
export async function replyToReview(id, text) {
  return (await httpClient.put(`/store/reviews/${segment(id)}/reply`, { text })).data
}

/**
 * @param {AdminReviewListParams} params
 * @returns {Promise<PageResponse<ModeratedReviewView>>}
 */
export async function listAdminReviews(params) {
  return toPage((await httpClient.get('/admin/reviews', { params: cleanParams(params) })).data)
}

/**
 * `publish` (FLAGGED → PUBLISHED), `reject` (FLAGGED → REJECTED, reason), `remove` (PUBLISHED → REMOVED, reason).
 * 409 REVIEW_NOT_MODERATABLE (`currentStatus`).
 * @param {string} id
 * @param {ModerationAction} action
 * @param {string} [reason]
 * @returns {Promise<ModeratedReviewView>}
 */
export async function moderateReview(id, action, reason) {
  const body = action === 'publish' ? undefined : { reason }
  return (await httpClient.post(`/admin/reviews/${segment(id)}/${action}`, body)).data
}
