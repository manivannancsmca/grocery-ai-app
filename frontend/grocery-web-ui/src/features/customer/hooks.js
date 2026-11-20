import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { provisioningRetryDelay, retryWhileProvisioning } from '../../shared/api/retry'
import { patchSessionUser } from '../../shared/auth/session'
import {
  createAddress,
  customerKeys,
  deleteAddress,
  fetchMyAddresses,
  fetchMyProfile,
  setDefaultAddress,
  updateAddress,
  updateMyProfile,
} from './api'

/** The profile is created ~1 s after registration, so `404 CUSTOMER_PROFILE_NOT_FOUND` is retried briefly. */
const provisioning = {
  retry: retryWhileProvisioning('CUSTOMER_PROFILE_NOT_FOUND'),
  retryDelay: provisioningRetryDelay,
}

export function useCustomerProfile() {
  return useQuery({ queryKey: customerKeys.profile(), queryFn: fetchMyProfile, ...provisioning })
}

export function useUpdateCustomerProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body) => updateMyProfile(body),
    onSuccess: (profile) => {
      queryClient.setQueryData(customerKeys.profile(), profile)
      // The header shows the account's name. It reaches identity by event a moment later; show the new one now.
      patchSessionUser({ fullName: profile.fullName, phone: profile.phone })
    },
  })
}

/**
 * `enabled: false` lets public pages skip the call for guests and other roles.
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useAddresses(options = {}) {
  return useQuery({
    queryKey: customerKeys.addresses(),
    queryFn: fetchMyAddresses,
    ...provisioning,
    enabled: options.enabled ?? true,
  })
}

/**
 * Every address change can move the default flag, so the whole list is refetched afterwards.
 * @template TVariables
 * @template TResult
 * @param {(variables: TVariables) => Promise<TResult>} mutationFn
 */
function useAddressMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: customerKeys.addresses() }),
  })
}

export function useCreateAddress() {
  return useAddressMutation((body) => createAddress(body))
}

export function useUpdateAddress() {
  return useAddressMutation(({ id, body }) => updateAddress(id, body))
}

export function useDeleteAddress() {
  return useAddressMutation((id) => deleteAddress(id))
}

export function useSetDefaultAddress() {
  return useAddressMutation((id) => setDefaultAddress(id))
}
