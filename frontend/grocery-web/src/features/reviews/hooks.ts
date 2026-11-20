import { keepPreviousData, type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createReview,
  deleteReview,
  fetchEligibleReviews,
  fetchMyReviews,
  fetchPublicReviews,
  fetchRatingSummary,
  type MyReviewParams,
  type PublicReviewParams,
  type ReviewRequest,
  reviewKeys,
  type ReviewUpdateRequest,
  type ReviewView,
  updateReview,
} from './api'

/** What may be reviewed: one order's targets, or (null) every delivered order still in the review window. */
export function useEligibleReviews(orderId: string | null, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: reviewKeys.eligible(orderId),
    queryFn: () => fetchEligibleReviews(orderId),
    enabled: options.enabled ?? true,
  })
}

export function useMyReviews(params: MyReviewParams) {
  return useQuery({
    queryKey: reviewKeys.mineList(params),
    queryFn: () => fetchMyReviews(params),
    placeholderData: keepPreviousData,
  })
}

/** Published reviews of a product, dish, store or partner; the previous page stays while the next one loads. */
export function usePublicReviews(targetType: string, targetId: string, params: PublicReviewParams) {
  return useQuery({
    queryKey: reviewKeys.publicList(targetType, targetId, params),
    queryFn: () => fetchPublicReviews(targetType, targetId, params),
    enabled: targetId !== '',
    placeholderData: keepPreviousData,
  })
}

export function useRatingSummary(targetType: string, targetId: string) {
  return useQuery({
    queryKey: reviewKeys.summary(targetType, targetId),
    queryFn: () => fetchRatingSummary(targetType, targetId),
    enabled: targetId !== '',
  })
}

/** A write changes the eligibility lists, the customer's reviews and that target's public reviews and summary. */
function refreshAfterWrite(queryClient: QueryClient, review: Pick<ReviewView, 'targetType' | 'targetId'> | null) {
  void queryClient.invalidateQueries({ queryKey: reviewKeys.eligibleAll() })
  void queryClient.invalidateQueries({ queryKey: reviewKeys.mine() })
  void queryClient.invalidateQueries({ queryKey: review ? reviewKeys.target(review.targetType, review.targetId) : reviewKeys.publicAll() })
}

export function useCreateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ReviewRequest) => createReview(body),
    onSuccess: (review) => refreshAfterWrite(queryClient, review),
    // 409 ALREADY_REVIEWED: another tab wrote it; show the review that exists.
    onError: () => refreshAfterWrite(queryClient, null),
  })
}

export function useUpdateReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ReviewUpdateRequest }) => updateReview(id, body),
    onSuccess: (review) => refreshAfterWrite(queryClient, review),
  })
}

export function useDeleteReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (review: Pick<ReviewView, 'id' | 'targetType' | 'targetId'>) => deleteReview(review.id),
    onSettled: (_data, _error, review) => refreshAfterWrite(queryClient, review),
  })
}
