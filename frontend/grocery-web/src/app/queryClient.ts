import { QueryClient } from '@tanstack/react-query'
import { shouldRetry } from '../shared/api/retry'

export { shouldRetry }

export function createQueryClient(): QueryClient {
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
