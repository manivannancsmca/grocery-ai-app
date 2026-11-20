import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchRatingSummary, reviewKeys } from '../reviews/api'
import {
  type AdminReviewListParams,
  listAdminReviews,
  listStoreReviews,
  type ModerationAction,
  moderateReview,
  replyToReview,
  storeReviewKeys,
  type StoreReviewListParams,
} from './api'

export function useStoreReviews(params: StoreReviewListParams) {
  return useQuery({ queryKey: storeReviewKeys.storeList(params), queryFn: () => listStoreReviews(params), placeholderData: keepPreviousData })
}

/** The store's own rating (public summary), when the store id is known. */
export function useStoreRatingSummary(targetType: string, targetId: string | null) {
  return useQuery({
    queryKey: reviewKeys.summary(targetType, targetId ?? ''),
    queryFn: () => fetchRatingSummary(targetType, targetId ?? ''),
    enabled: Boolean(targetId),
  })
}

export function useReplyToReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, text }: { id: string; text: string }) => replyToReview(id, text),
    onSettled: () => Promise.all([queryClient.invalidateQueries({ queryKey: storeReviewKeys.store() }), queryClient.invalidateQueries({ queryKey: reviewKeys.publicAll() })]),
  })
}

export function useAdminReviews(params: AdminReviewListParams) {
  return useQuery({ queryKey: storeReviewKeys.adminList(params), queryFn: () => listAdminReviews(params), placeholderData: keepPreviousData })
}

/** Publish, reject or remove; the list refreshes afterwards — also after a 409 (another admin decided first). */
export function useModerateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action, reason }: { id: string; action: ModerationAction; reason?: string }) => moderateReview(id, action, reason),
    onSettled: () => Promise.all([queryClient.invalidateQueries({ queryKey: storeReviewKeys.admin() }), queryClient.invalidateQueries({ queryKey: reviewKeys.publicAll() })]),
  })
}
