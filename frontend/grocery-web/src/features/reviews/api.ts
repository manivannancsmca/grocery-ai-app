import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/**
 * Reviews and ratings (review-service — Phase 12 contract §1). The customer reviews what a DELIVERED order brought
 * (each product or dish, the store, the delivery partner) within the review window (a platform setting, 30 days by
 * default), may edit a review for 7 days and delete it at any time. Published reviews and the rating summary of a
 * target are public.
 */

/** Reads that need no token (product and restaurant pages are public). */
const PUBLIC = { skipAuth: true } as const

export const REVIEW_TARGET_TYPES = ['PRODUCT', 'MENU_ITEM', 'VENDOR', 'RESTAURANT', 'DELIVERY_PARTNER'] as const
export type ReviewTargetType = (typeof REVIEW_TARGET_TYPES)[number]

export const REVIEW_STATUSES = ['PUBLISHED', 'FLAGGED', 'REJECTED', 'REMOVED'] as const
export type ReviewStatus = (typeof REVIEW_STATUSES)[number]

export const PUBLIC_REVIEW_SORTS = ['newest', 'highest', 'lowest'] as const
export type PublicReviewSort = (typeof PUBLIC_REVIEW_SORTS)[number]

/** Limits from the contract. */
export const REVIEW_LIMITS = { title: 80, comment: 1000, reply: 500 } as const
/** Days after delivery a review may be written (`REVIEW_WINDOW`). */
export const REVIEW_WINDOW_DAYS = 30
/** Days after writing it a review may be edited. */
export const REVIEW_EDIT_DAYS = 7

export const REVIEW_ERRORS = {
  notEligible: 'REVIEW_NOT_ELIGIBLE',
  alreadyReviewed: 'ALREADY_REVIEWED',
  editWindowOver: 'REVIEW_EDIT_WINDOW_OVER',
  notFound: 'REVIEW_NOT_FOUND',
} as const

/** The store's one public reply. */
export interface ReviewReply {
  text: string
  repliedAt: string
  /** Set when the reply was edited (optional). */
  updatedAt?: string | null
}

/** `ReviewView` — the customer's own review (every status). */
export interface ReviewView {
  id: string
  orderId: string
  orderNumber?: string | null
  targetType: ReviewTargetType | (string & {})
  targetId: string
  /** Product, dish, store or partner name (null when review-service does not know it yet). */
  targetName?: string | null
  rating: number
  title: string | null
  comment: string | null
  status: ReviewStatus | (string & {})
  /** Why a review was flagged, rejected or removed (optional). */
  moderationReason?: string | null
  reply?: ReviewReply | null
  /** "Asha S." (optional on the owner's view). */
  authorName?: string | null
  /** End of the 7-day edit window; derived from `createdAt` when absent. */
  editableUntil?: string | null
  edited?: boolean
  createdAt: string
  updatedAt?: string | null
}

/** `PublicReview` — a published review as everybody sees it. */
export interface PublicReview {
  id: string
  rating: number
  title: string | null
  comment: string | null
  /** First name and last initial, e.g. "Asha S.". */
  authorName: string
  reply?: ReviewReply | null
  edited?: boolean
  createdAt: string
  updatedAt?: string | null
}

/** `GET /api/reviews/summary`. `distribution` = counts of 1★ … 5★ (index 0 = 1 star). */
export interface RatingSummary {
  targetType: ReviewTargetType | (string & {})
  targetId: string
  /** 2 decimals; null (or 0) without ratings. */
  averageRating: number | null
  ratingCount: number
  distribution: number[]
}

/** One reviewable target of a delivered order. */
export interface EligibleReviewTarget {
  orderId: string
  orderNumber: string
  deliveredAt: string
  targetType: ReviewTargetType | (string & {})
  targetId: string
  targetName: string | null
  /** The customer's review of it, or null when not reviewed yet. */
  review: ReviewView | null
}

export interface ReviewRequest {
  orderId: string
  targetType: string
  targetId: string
  rating: number
  title?: string
  comment?: string
}

export interface ReviewUpdateRequest {
  rating: number
  title?: string
  comment?: string
}

export interface PublicReviewParams {
  sort?: PublicReviewSort
  page?: number
  size?: number
}

export interface MyReviewParams {
  page?: number
  size?: number
}

export const PUBLIC_REVIEW_PAGE_SIZE = 5
export const MY_REVIEW_PAGE_SIZE = 10

export const reviewKeys = {
  all: ['reviews'] as const,
  /** The customer's side: eligibility and their own reviews. */
  eligibleAll: () => [...reviewKeys.all, 'eligible'] as const,
  eligible: (orderId: string | null) => [...reviewKeys.eligibleAll(), orderId ?? 'all'] as const,
  mine: () => [...reviewKeys.all, 'mine'] as const,
  mineList: (params: MyReviewParams) => [...reviewKeys.mine(), params] as const,
  /** Public reviews and summaries of every target. */
  publicAll: () => [...reviewKeys.all, 'public'] as const,
  target: (targetType: string, targetId: string) => [...reviewKeys.publicAll(), targetType, targetId] as const,
  publicList: (targetType: string, targetId: string, params: PublicReviewParams) =>
    [...reviewKeys.target(targetType, targetId), 'list', params] as const,
  summary: (targetType: string, targetId: string) => [...reviewKeys.target(targetType, targetId), 'summary'] as const,
}

/** Without `orderId`: every delivered order still in the review window, newest first. */
export async function fetchEligibleReviews(orderId: string | null): Promise<EligibleReviewTarget[]> {
  return (await httpClient.get<EligibleReviewTarget[]>('/reviews/eligible', { params: cleanParams({ orderId }) })).data
}

/** 201; 422 REVIEW_NOT_ELIGIBLE, 409 ALREADY_REVIEWED. */
export async function createReview(body: ReviewRequest): Promise<ReviewView> {
  return (await httpClient.post<ReviewView>('/reviews', body)).data
}

/** 422 REVIEW_EDIT_WINDOW_OVER (it is re-moderated). */
export async function updateReview(id: string, body: ReviewUpdateRequest): Promise<ReviewView> {
  return (await httpClient.put<ReviewView>(`/reviews/${segment(id)}`, body)).data
}

/** 204. */
export async function deleteReview(id: string): Promise<void> {
  await httpClient.delete(`/reviews/${segment(id)}`)
}

/** All statuses, newest first. */
export async function fetchMyReviews(params: MyReviewParams): Promise<PageResponse<ReviewView>> {
  return (await httpClient.get<PageResponse<ReviewView>>('/reviews/mine', { params: cleanParams(params) })).data
}

/** Published reviews of one target. */
export async function fetchPublicReviews(
  targetType: string,
  targetId: string,
  params: PublicReviewParams,
): Promise<PageResponse<PublicReview>> {
  const query = cleanParams({ targetType, targetId, ...params })
  return (await httpClient.get<PageResponse<PublicReview>>('/reviews', { ...PUBLIC, params: query })).data
}

export async function fetchRatingSummary(targetType: string, targetId: string): Promise<RatingSummary> {
  return (await httpClient.get<RatingSummary>('/reviews/summary', { ...PUBLIC, params: { targetType, targetId } })).data
}
