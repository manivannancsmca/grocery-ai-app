import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  type AdminUserListParams,
  createAdminUser,
  type CreateAdminRequest,
  fetchUsers,
  reactivateUser,
  suspendUser,
  userKeys,
} from './api'

export function useUsers(params: AdminUserListParams) {
  return useQuery({ queryKey: userKeys.list(params), queryFn: () => fetchUsers(params), placeholderData: keepPreviousData })
}

function useUserMutation<TVariables, TResult>(mutationFn: (variables: TVariables) => Promise<TResult>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: userKeys.all }),
  })
}

export function useCreateAdminUser() {
  return useUserMutation((body: CreateAdminRequest) => createAdminUser(body))
}

export function useSuspendUser() {
  return useUserMutation(({ id, reason }: { id: string; reason: string }) => suspendUser(id, reason))
}

export function useReactivateUser() {
  return useUserMutation((id: string) => reactivateUser(id))
}
