import { QueryClient } from '@tanstack/react-query'

/** Query client for tests: no retries or polling, so error states appear immediately. */
export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchInterval: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  })
}
