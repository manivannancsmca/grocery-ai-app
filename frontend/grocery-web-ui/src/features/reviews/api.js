import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Reviews and ratings (review-service — Phase 12 contract §1). The customer reviews what a DELIVERED order brought
 * (each product or dish, the store, the delivery partner) within the review window (a platform setting, 30 days by
 * default), may edit a review for 7 days and delete it at any time. Published reviews and the rating summary of a
 * target are public.
 */

/** Reads that need no token (product and restaurant pages are public). */
const PUBLIC = { skipAuth: true }

export const REVIEW_TARGET_TYPES = ['PRODUCT', 'MENU_ITEM', 'VENDOR', 'RESTAURANT', 'DELIVERY_PARTNER']
/** @typedef {(typeof REVIEW_TARGET_TYPES)[number]} ReviewTargetType */

export const REVIEW_STATUSES = ['PUBLISHED', 'FLAGGED', 'REJECTED', 'REMOVED']
/** @typedef {(typeof REVIEW_STATUSES)[number]} ReviewStatus */

export const PUBLIC_REVIEW_SORTS = ['newest', 'highest', 'lowest']
/** @typedef {(typeof PUBLIC_REVIEW_SORTS)[number]} PublicReviewSort */

/** Limits from the contract. */
export const REVIEW_LIMITS = { title: 80, comment: 1000, reply: 500 }
/** Days after delivery a review may be written (`REVIEW_WINDOW`). */
export const REVIEW_WINDOW_DAYS = 30
/** Days after writing it a review may be edited. */
export const REVIEW_EDIT_DAYS = 7

export const REVIEW_ERRORS = {
  notEligible: 'REVIEW_NOT_ELIGIBLE',
  alreadyReviewed: 'ALREADY_REVIEWED',
  editWindowOver: 'REVIEW_EDIT_WINDOW_OVER',
  notFound: 'REVIEW_NOT_FOUND',
}

/**
 * The store's one public reply.
 * @typedef {object} ReviewReply
 * @property {string} text
 * @property {string} repliedAt
 * @property {string | null} [updatedAt] Set when the reply was edited (optional).
 */

/**
 * `ReviewView` — the customer's own review (every status).
 * @typedef {object} ReviewView
 * @property {string} id
 * @property {string} orderId
 * @property {string | null} [orderNumber]
 * @property {ReviewTargetType | (string & {})} targetType
 * @property {string} targetId
 * @property {string | null} [targetName] Product, dish, store or partner name (null when review-service does not know it yet).
 * @property {number} rating
 * @property {string | null} title
 * @property {string | null} comment
 * @property {ReviewStatus | (string & {})} status
 * @property {string | null} [moderationReason] Why a review was flagged, rejected or removed (optional).
 * @property {ReviewReply | null} [reply]
 * @property {string | null} [authorName] "Asha S." (optional on the owner's view).
 * @property {string | null} [editableUntil] End of the 7-day edit window; derived from `createdAt` when absent.
 * @property {boolean} [edited]
 * @property {string} createdAt
 * @property {string | null} [updatedAt]
 */

/**
 * `PublicReview` — a published review as everybody sees it.
 * @typedef {object} PublicReview
 * @property {string} id
 * @property {number} rating
 * @property {string | null} title
 * @property {string | null} comment
 * @property {string} authorName First name and last initial, e.g. "Asha S.".
 * @property {ReviewReply | null} [reply]
 * @property {boolean} [edited]
 * @property {string} createdAt
 * @property {string | null} [updatedAt]
 */

/**
 * `GET /api/reviews/summary`. `distribution` = counts of 1★ … 5★ (index 0 = 1 star).
 * @typedef {object} RatingSummary
 * @property {ReviewTargetType | (string & {})} targetType
 * @property {string} targetId
 * @property {number | null} averageRating 2 decimals; null (or 0) without ratings.
 * @property {number} ratingCount
 * @property {number[]} distribution
 */

/**
 * One reviewable target of a delivered order.
 * @typedef {object} EligibleReviewTarget
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {string} deliveredAt
 * @property {ReviewTargetType | (string & {})} targetType
 * @property {string} targetId
 * @property {string | null} targetName
 * @property {ReviewView | null} review The customer's review of it, or null when not reviewed yet.
 */

/**
 * @typedef {object} ReviewRequest
 * @property {string} orderId
 * @property {string} targetType
 * @property {string} targetId
 * @property {number} rating
 * @property {string} [title]
 * @property {string} [comment]
 */

/**
 * @typedef {object} ReviewUpdateRequest
 * @property {number} rating
 * @property {string} [title]
 * @property {string} [comment]
 */

/**
 * @typedef {object} PublicReviewParams
 * @property {PublicReviewSort} [sort]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} MyReviewParams
 * @property {number} [page]
 * @property {number} [size]
 */

export const PUBLIC_REVIEW_PAGE_SIZE = 5
export const MY_REVIEW_PAGE_SIZE = 10

export const reviewKeys = {
  all: ['reviews'],
  /** The customer's side: eligibility and their own reviews. */
  eligibleAll: () => [...reviewKeys.all, 'eligible'],
  eligible: (orderId) => [...reviewKeys.eligibleAll(), orderId ?? 'all'],
  mine: () => [...reviewKeys.all, 'mine'],
  mineList: (params) => [...reviewKeys.mine(), params],
  /** Public reviews and summaries of every target. */
  publicAll: () => [...reviewKeys.all, 'public'],
  target: (targetType, targetId) => [...reviewKeys.publicAll(), targetType, targetId],
  publicList: (targetType, targetId, params) => [...reviewKeys.target(targetType, targetId), 'list', params],
  summary: (targetType, targetId) => [...reviewKeys.target(targetType, targetId), 'summary'],
}

/**
 * Without `orderId`: every delivered order still in the review window, newest first.
 * @param {string | null} orderId
 * @returns {Promise<EligibleReviewTarget[]>}
 */
export async function fetchEligibleReviews(orderId) {
  return (await httpClient.get('/reviews/eligible', { params: cleanParams({ orderId }) })).data
}

/**
 * 201; 422 REVIEW_NOT_ELIGIBLE, 409 ALREADY_REVIEWED.
 * @param {ReviewRequest} body
 * @returns {Promise<ReviewView>}
 */
export async function createReview(body) {
  return (await httpClient.post('/reviews', body)).data
}

/**
 * 422 REVIEW_EDIT_WINDOW_OVER (it is re-moderated).
 * @param {string} id
 * @param {ReviewUpdateRequest} body
 * @returns {Promise<ReviewView>}
 */
export async function updateReview(id, body) {
  return (await httpClient.put(`/reviews/${segment(id)}`, body)).data
}

/**
 * 204.
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteReview(id) {
  await httpClient.delete(`/reviews/${segment(id)}`)
}

/**
 * All statuses, newest first.
 * @param {MyReviewParams} params
 * @returns {Promise<PageResponse<ReviewView>>}
 */
export async function fetchMyReviews(params) {
  return (await httpClient.get('/reviews/mine', { params: cleanParams(params) })).data
}

/**
 * Published reviews of one target.
 * @param {string} targetType
 * @param {string} targetId
 * @param {PublicReviewParams} params
 * @returns {Promise<PageResponse<PublicReview>>}
 */
export async function fetchPublicReviews(targetType, targetId, params) {
  const query = cleanParams({ targetType, targetId, ...params })
  return (await httpClient.get('/reviews', { ...PUBLIC, params: query })).data
}

/**
 * @param {string} targetType
 * @param {string} targetId
 * @returns {Promise<RatingSummary>}
 */
export async function fetchRatingSummary(targetType, targetId) {
  return (await httpClient.get('/reviews/summary', { ...PUBLIC, params: { targetType, targetId } })).data
}
