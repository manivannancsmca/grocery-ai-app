import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hasErrorCode } from '../../shared/api/problemDetail'
import {
  type AdminReturnListParams,
  approveReturn,
  listAdminReturns,
  listStoreReturns,
  rejectReturn,
  RETURN_ERRORS,
  type ReturnDecider,
  returnKeys,
  type ReturnListParams,
} from './api'

export function useStoreReturns(params: ReturnListParams) {
  return useQuery({ queryKey: returnKeys.storeList(params), queryFn: () => listStoreReturns(params), placeholderData: keepPreviousData })
}

export function useAdminReturns(params: AdminReturnListParams) {
  return useQuery({ queryKey: returnKeys.adminList(params), queryFn: () => listAdminReturns(params), placeholderData: keepPreviousData })
}

export type ReturnDecision = { kind: 'approve'; id: string; restock: boolean } | { kind: 'reject'; id: string; comment: string }

/** Approve or reject; the lists refresh afterwards — also after a 409 (someone else decided first). */
export function useReturnDecision(decider: ReturnDecider) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (decision: ReturnDecision) =>
      decision.kind === 'approve' ? approveReturn(decider, decision.id, decision.restock) : rejectReturn(decider, decision.id, decision.comment),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: returnKeys.all }),
    onError: (error) => {
      if (hasErrorCode(error, RETURN_ERRORS.notPending, RETURN_ERRORS.notFound)) return queryClient.invalidateQueries({ queryKey: returnKeys.all })
    },
  })
}
