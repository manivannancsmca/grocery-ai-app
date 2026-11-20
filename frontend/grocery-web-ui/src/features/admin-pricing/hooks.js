import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminPriceKeys, approvePriceRequest, listAdminPriceRequests, rejectPriceRequest } from './api'

/** @typedef {import('./api').AdminPriceRequestListParams} AdminPriceRequestListParams */

/** @param {AdminPriceRequestListParams} params */
export function useAdminPriceRequests(params) {
  return useQuery({
    queryKey: adminPriceKeys.list(params),
    queryFn: () => listAdminPriceRequests(params),
    placeholderData: keepPreviousData,
  })
}

/** Number of price changes waiting for a decision (one-row page; only `totalElements` is used). */
export function usePendingPriceRequestCount() {
  const params = { status: 'PENDING', page: 0, size: 1 }
  return useQuery({
    queryKey: adminPriceKeys.list(params),
    queryFn: () => listAdminPriceRequests(params),
    select: (page) => page.totalElements,
  })
}

/**
 * @typedef {(
 *   | { action: 'approve', id: string, note: string | null }
 *   | { action: 'reject', id: string, reason: string }
 * )} Decision
 */

/** Approve or reject; every list (and the pending count) is refreshed afterwards, also after a 409. */
export function usePriceDecision() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (decision) =>
      decision.action === 'approve'
        ? approvePriceRequest(decision.id, decision.note)
        : rejectPriceRequest(decision.id, decision.reason),
    onSettled: () => queryClient.invalidateQueries({ queryKey: adminPriceKeys.all }),
  })
}
