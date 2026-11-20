import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { ReviewStatus, ReviewView } from '../reviews/api'

/**
 * Reviews as the store and the admins see them (review-service — Phase 12 contract §1): the vendor or restaurant
 * owner reads the published reviews of its store and of its products / dishes and writes one public reply each;
 * admins moderate (flagged reviews stay hidden until one decides).
 */

/** A review in the store and admin lists: the customer's `ReviewView` plus moderation details. */
export interface ModeratedReviewView extends ReviewView {
  /** Why automatic moderation flagged it (`LINK`, `PHONE`, `EMAIL`, `PROFANITY`, `SHOUTING`, …). */
  flagReason?: string | null
  storeId?: string | null
  storeName?: string | null
}

export const STORE_REVIEW_ERRORS = {
  /** 404 — the review is not the caller's store's. */
  notFound: 'REVIEW_NOT_FOUND',
  /** 409 — `currentStatus`. */
  notModeratable: 'REVIEW_NOT_MODERATABLE',
} as const

export const MODERATION_LIMITS = { reason: 300 } as const

export interface StoreReviewListParams {
  targetType?: string
  /** 1–5 stars. */
  rating?: number
  page?: number
  size?: number
}

export interface AdminReviewListParams {
  /** Without it the server lists FLAGGED ones. */
  status?: ReviewStatus
  targetType?: string
  q?: string
  page?: number
  size?: number
}

export type ModerationAction = 'publish' | 'reject' | 'remove'

export const storeReviewKeys = {
  all: ['store-reviews'] as const,
  store: () => [...storeReviewKeys.all, 'store'] as const,
  storeList: (params: StoreReviewListParams) => [...storeReviewKeys.store(), params] as const,
  admin: () => [...storeReviewKeys.all, 'admin'] as const,
  adminList: (params: AdminReviewListParams) => [...storeReviewKeys.admin(), params] as const,
}

/** Published reviews of the caller's store and of its products or dishes, newest first. */
export async function listStoreReviews(params: StoreReviewListParams): Promise<PageResponse<ModeratedReviewView>> {
  return toPage((await httpClient.get<PageResponse<ModeratedReviewView>>('/store/reviews', { params: cleanParams(params) })).data)
}

/** Writes or replaces the store's one public reply (≤ 500). 404 when the review is not the store's. */
export async function replyToReview(id: string, text: string): Promise<ModeratedReviewView> {
  return (await httpClient.put<ModeratedReviewView>(`/store/reviews/${segment(id)}/reply`, { text })).data
}

export async function listAdminReviews(params: AdminReviewListParams): Promise<PageResponse<ModeratedReviewView>> {
  return toPage((await httpClient.get<PageResponse<ModeratedReviewView>>('/admin/reviews', { params: cleanParams(params) })).data)
}

/**
 * `publish` (FLAGGED → PUBLISHED), `reject` (FLAGGED → REJECTED, reason), `remove` (PUBLISHED → REMOVED, reason).
 * 409 REVIEW_NOT_MODERATABLE (`currentStatus`).
 */
export async function moderateReview(id: string, action: ModerationAction, reason?: string): Promise<ModeratedReviewView> {
  const body = action === 'publish' ? undefined : { reason }
  return (await httpClient.post<ModeratedReviewView>(`/admin/reviews/${segment(id)}/${action}`, body)).data
}
