import { useQuery } from '@tanstack/react-query'
import { fetchIdentityHealth } from './api'

/** Same key as the identity tile of the System Status page, so both share one cache entry. */
export const backendHealthQueryKey = ['system', 'health', 'identity'] as const

export function useBackendHealth() {
  return useQuery({
    queryKey: backendHealthQueryKey,
    queryFn: fetchIdentityHealth,
    refetchInterval: 15_000,
  })
}
