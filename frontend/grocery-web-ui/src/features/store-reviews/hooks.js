import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchRatingSummary, reviewKeys } from '../reviews/api'
import { listAdminReviews, listStoreReviews, moderateReview, replyToReview, storeReviewKeys } from './api'

/** @typedef {import('./api').AdminReviewListParams} AdminReviewListParams */
/** @typedef {import('./api').StoreReviewListParams} StoreReviewListParams */

/** @param {StoreReviewListParams} params */
export function useStoreReviews(params) {
  return useQuery({
    queryKey: storeReviewKeys.storeList(params),
    queryFn: () => listStoreReviews(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * The store's own rating (public summary), when the store id is known.
 * @param {string} targetType
 * @param {string | null} targetId
 */
export function useStoreRatingSummary(targetType, targetId) {
  return useQuery({
    queryKey: reviewKeys.summary(targetType, targetId ?? ''),
    queryFn: () => fetchRatingSummary(targetType, targetId ?? ''),
    enabled: Boolean(targetId),
  })
}

export function useReplyToReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, text }) => replyToReview(id, text),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: storeReviewKeys.store() }),
        queryClient.invalidateQueries({ queryKey: reviewKeys.publicAll() }),
      ]),
  })
}

/** @param {AdminReviewListParams} params */
export function useAdminReviews(params) {
  return useQuery({
    queryKey: storeReviewKeys.adminList(params),
    queryFn: () => listAdminReviews(params),
    placeholderData: keepPreviousData,
  })
}

/** Publish, reject or remove; the list refreshes afterwards — also after a 409 (another admin decided first). */
export function useModerateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action, reason }) => moderateReview(id, action, reason),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: storeReviewKeys.admin() }),
        queryClient.invalidateQueries({ queryKey: reviewKeys.publicAll() }),
      ]),
  })
}
