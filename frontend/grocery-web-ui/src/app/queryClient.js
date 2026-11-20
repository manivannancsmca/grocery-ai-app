import { QueryClient } from '@tanstack/react-query'
import { shouldRetry } from '../shared/api/retry'

export { shouldRetry }

/** @returns {QueryClient} */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: shouldRetry,
      },
      mutations: {
        retry: false,
      },
    },
  })
}
