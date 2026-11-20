import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { approveReturn, listAdminReturns, listStoreReturns, rejectReturn, RETURN_ERRORS, returnKeys } from './api'

/** @typedef {import('./api').AdminReturnListParams} AdminReturnListParams */
/** @typedef {import('./api').ReturnDecider} ReturnDecider */
/** @typedef {import('./api').ReturnListParams} ReturnListParams */

/** @param {ReturnListParams} params */
export function useStoreReturns(params) {
  return useQuery({
    queryKey: returnKeys.storeList(params),
    queryFn: () => listStoreReturns(params),
    placeholderData: keepPreviousData,
  })
}

/** @param {AdminReturnListParams} params */
export function useAdminReturns(params) {
  return useQuery({
    queryKey: returnKeys.adminList(params),
    queryFn: () => listAdminReturns(params),
    placeholderData: keepPreviousData,
  })
}

/** @typedef {{ kind: 'approve', id: string, restock: boolean } | { kind: 'reject', id: string, comment: string }} ReturnDecision */

/**
 * Approve or reject; the lists refresh afterwards — also after a 409 (someone else decided first).
 * @param {ReturnDecider} decider
 */
export function useReturnDecision(decider) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (decision) =>
      decision.kind === 'approve'
        ? approveReturn(decider, decision.id, decision.restock)
        : rejectReturn(decider, decision.id, decision.comment),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: returnKeys.all }),
    onError: (error) => {
      if (hasErrorCode(error, RETURN_ERRORS.notPending, RETURN_ERRORS.notFound))
        return queryClient.invalidateQueries({ queryKey: returnKeys.all })
    },
  })
}
