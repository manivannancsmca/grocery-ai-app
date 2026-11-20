import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
/** @typedef {import('../types').StakeholderLifecycle} StakeholderLifecycle */
/** @typedef {import('../types').StakeholderListParams} StakeholderListParams */
/** @typedef {import('./kind').StakeholderAction} StakeholderAction */
/** @typedef {import('./kind').StakeholderKind} StakeholderKind */

/**
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 * @param {StakeholderListParams} params
 */
export function stakeholderListKey(kind, params) {
  return [...kind.queryKey, 'list', params]
}

/**
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 * @param {StakeholderListParams} params
 */
export function useStakeholderList(kind, params) {
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * Number of submissions waiting for review (one-row page, only `totalElements` is used).
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 */
export function usePendingCount(kind) {
  const params = { status: 'PENDING_APPROVAL', page: 0, size: 1 }
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    select: (page) => page.totalElements,
  })
}

/**
 * The review queue at a glance: how many submissions wait and when the oldest was submitted. The services list
 * PENDING_APPROVAL oldest first, so the one-row page of {@link usePendingCount} (same cache entry) already holds it.
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 */
export function usePendingSummary(kind) {
  const params = { status: 'PENDING_APPROVAL', page: 0, size: 1 }
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    select: (page) => ({ count: page.totalElements, oldestSubmittedAt: page.content[0]?.submittedAt ?? null }),
  })
}

/**
 * How many of this type are in `status` (e.g. APPROVED for the "112 approved" line); one-row page.
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 * @param {StakeholderListParams['status']} status
 */
export function useStatusCount(kind, status) {
  const params = { status, page: 0, size: 1 }
  return useQuery({
    queryKey: stakeholderListKey(kind, params),
    queryFn: () => kind.api.list(params),
    select: (page) => page.totalElements,
  })
}

/**
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 * @param {string} id
 */
export function useStakeholderDetail(kind, id) {
  return useQuery({
    queryKey: [...kind.queryKey, 'detail', id],
    queryFn: () => kind.api.get(id),
  })
}

/**
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 * @param {string} id
 */
export function useStakeholderHistory(kind, id) {
  return useQuery({
    queryKey: [...kind.queryKey, 'history', id],
    queryFn: () => kind.api.history(id),
  })
}

/**
 * @typedef {object} ActionVariables
 * @property {StakeholderAction} action
 * @property {string} id
 * @property {string} [reason]
 */

/**
 * approve / reject / suspend / reinstate; refreshes every list, detail and history of the type afterwards.
 * @template {StakeholderLifecycle} T
 * @param {StakeholderKind<T>} kind
 */
export function useStakeholderAction(kind) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ action, id, reason }) => {
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
