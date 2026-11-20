import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createAdminUser, fetchUsers, reactivateUser, suspendUser, userKeys } from './api'

/** @typedef {import('./api').AdminUserListParams} AdminUserListParams */

/** @param {AdminUserListParams} params */
export function useUsers(params) {
  return useQuery({
    queryKey: userKeys.list(params),
    queryFn: () => fetchUsers(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * @template TVariables
 * @template TResult
 * @param {(variables: TVariables) => Promise<TResult>} mutationFn
 */
function useUserMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: userKeys.all }),
  })
}

export function useCreateAdminUser() {
  return useUserMutation((body) => createAdminUser(body))
}

export function useSuspendUser() {
  return useUserMutation(({ id, reason }) => suspendUser(id, reason))
}

export function useReactivateUser() {
  return useUserMutation((id) => reactivateUser(id))
}
