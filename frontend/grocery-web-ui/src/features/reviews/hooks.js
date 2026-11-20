import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createReview,
  deleteReview,
  fetchEligibleReviews,
  fetchMyReviews,
  fetchPublicReviews,
  fetchRatingSummary,
  reviewKeys,
  updateReview,
} from './api'

/** @typedef {import('@tanstack/react-query').QueryClient} QueryClient */
/** @typedef {import('./api').MyReviewParams} MyReviewParams */
/** @typedef {import('./api').PublicReviewParams} PublicReviewParams */
/** @typedef {import('./api').ReviewView} ReviewView */

/**
 * What may be reviewed: one order's targets, or (null) every delivered order still in the review window.
 * @param {string | null} orderId
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useEligibleReviews(orderId, options = {}) {
  return useQuery({
    queryKey: reviewKeys.eligible(orderId),
    queryFn: () => fetchEligibleReviews(orderId),
    enabled: options.enabled ?? true,
  })
}

/** @param {MyReviewParams} params */
export function useMyReviews(params) {
  return useQuery({
    queryKey: reviewKeys.mineList(params),
    queryFn: () => fetchMyReviews(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * Published reviews of a product, dish, store or partner; the previous page stays while the next one loads.
 * @param {string} targetType
 * @param {string} targetId
 * @param {PublicReviewParams} params
 */
export function usePublicReviews(targetType, targetId, params) {
  return useQuery({
    queryKey: reviewKeys.publicList(targetType, targetId, params),
    queryFn: () => fetchPublicReviews(targetType, targetId, params),
    enabled: targetId !== '',
    placeholderData: keepPreviousData,
  })
}

/**
 * @param {string} targetType
 * @param {string} targetId
 */
export function useRatingSummary(targetType, targetId) {
  return useQuery({
    queryKey: reviewKeys.summary(targetType, targetId),
    queryFn: () => fetchRatingSummary(targetType, targetId),
    enabled: targetId !== '',
  })
}

/**
 * A write changes the eligibility lists, the customer's reviews and that target's public reviews and summary.
 * @param {QueryClient} queryClient
 * @param {Pick<ReviewView, 'targetType' | 'targetId'> | null} review
 */
function refreshAfterWrite(queryClient, review) {
  void queryClient.invalidateQueries({ queryKey: reviewKeys.eligibleAll() })
  void queryClient.invalidateQueries({ queryKey: reviewKeys.mine() })
  void queryClient.invalidateQueries({
    queryKey: review ? reviewKeys.target(review.targetType, review.targetId) : reviewKeys.publicAll(),
  })
}

export function useCreateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body) => createReview(body),
    onSuccess: (review) => refreshAfterWrite(queryClient, review),
    // 409 ALREADY_REVIEWED: another tab wrote it; show the review that exists.
    onError: () => refreshAfterWrite(queryClient, null),
  })
}

export function useUpdateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }) => updateReview(id, body),
    onSuccess: (review) => refreshAfterWrite(queryClient, review),
  })
}

export function useDeleteReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (review) => deleteReview(review.id),
    onSettled: (_data, _error, review) => refreshAfterWrite(queryClient, review),
  })
}
