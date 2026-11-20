import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { StakeholderLifecycle, StakeholderListParams } from '../types'
import type { StakeholderAction, StakeholderKind } from './kind'

export function stakeholderListKey<T extends StakeholderLifecycle>(kind: StakeholderKind<T>, params: StakeholderListParams) {
  return [...kind.queryKey, 'list', params] as const
}

export function useStakeholderList<T extends StakeholderLifecycle>(kind: StakeholderKind<T>, params: StakeholderListParams) {
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    placeholderData: keepPreviousData,
  })
}

/** Number of submissions waiting for review (one-row page, only `totalElements` is used). */
export function usePendingCount<T extends StakeholderLifecycle>(kind: StakeholderKind<T>) {
  const params: StakeholderListParams = { status: 'PENDING_APPROVAL', page: 0, size: 1 }
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    select: (page) => page.totalElements,
  })
}

/**
 * The review queue at a glance: how many submissions wait and when the oldest was submitted. The services list
 * PENDING_APPROVAL oldest first, so the one-row page of {@link usePendingCount} (same cache entry) already holds it.
 */
export function usePendingSummary<T extends StakeholderLifecycle>(kind: StakeholderKind<T>) {
  const params: StakeholderListParams = { status: 'PENDING_APPROVAL', page: 0, size: 1 }
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    select: (page) => ({ count: page.totalElements, oldestSubmittedAt: page.content[0]?.submittedAt ?? null }),
  })
}

/** How many of this type are in `status` (e.g. APPROVED for the "112 approved" line); one-row page. */
export function useStatusCount<T extends StakeholderLifecycle>(kind: StakeholderKind<T>, status: StakeholderListParams['status']) {
  const params: StakeholderListParams = { status, page: 0, size: 1 }
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    select: (page) => page.totalElements,
  })
}

export function useStakeholderDetail<T extends StakeholderLifecycle>(kind: StakeholderKind<T>, id: string) {
  return useQuery({
    queryKey: [...kind.queryKey, 'detail', id],
    queryFn: () => kind.api.get(id),
  })
}

export function useStakeholderHistory<T extends StakeholderLifecycle>(kind: StakeholderKind<T>, id: string) {
  return useQuery({
    queryKey: [...kind.queryKey, 'history', id],
    queryFn: () => kind.api.history(id),
  })
}

interface ActionVariables {
  action: StakeholderAction
  id: string
  reason?: string
}

/** approve / reject / suspend / reinstate; refreshes every list, detail and history of the type afterwards. */
export function useStakeholderAction<T extends StakeholderLifecycle>(kind: StakeholderKind<T>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, id, reason }: ActionVariables): Promise<T> => {
      switch (action) {
        case 'approve':
          return kind.api.approve(id)
        case 'reject':
          return kind.api.reject(id, reason ?? '')
        case 'suspend':
          return kind.api.suspend(id, reason ?? '')
        case 'reinstate':
          return kind.api.reinstate(id)
      }
    },
    onSuccess: (view) => {
      queryClient.setQueryData([...kind.queryKey, 'detail', view.id], view)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: kind.queryKey }),
  })
}
