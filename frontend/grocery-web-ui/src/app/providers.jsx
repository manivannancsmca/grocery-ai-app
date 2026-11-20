import { QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { RealtimeProvider } from '../features/realtime/RealtimeProvider'
import { DeliveryLocationProvider } from '../features/stores/DeliveryLocationProvider'
import { AuthProvider } from '../shared/auth/AuthProvider'
import { ErrorBoundary } from '../shared/components/ErrorBoundary'
import { ToastProvider } from '../shared/components/toast/ToastProvider'
import { TooltipLayer } from '../shared/components/tooltip/TooltipLayer'
import { ExportProvider } from '../shared/export/ExportProvider'
import { createQueryClient } from './queryClient'

/** @typedef {import('@tanstack/react-query').QueryClient} QueryClient */
/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} AppProvidersProps
 * @property {ReactNode} children
 * @property {QueryClient} [queryClient] Tests pass their own client (no retries); the app creates one per mount.
 */

/** @param {AppProvidersProps} props */
export function AppProviders({ children, queryClient }) {
  const [client] = useState(() => queryClient ?? createQueryClient())

  return (
    <ErrorBoundary>
      <QueryClientProvider client={client}>
        <ToastProvider>
          <AuthProvider>
            {/* CSV / Excel / PDF downloads of list pages: they keep going while the user moves between pages. */}
            <ExportProvider>
              {/* Live updates (SSE) while signed in; offline otherwise. */}
              <RealtimeProvider>
                {/* Phase 15: one delivery location for the whole app ("Deliver to" in the header). */}
                <DeliveryLocationProvider>{children}</DeliveryLocationProvider>
              </RealtimeProvider>
            </ExportProvider>
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
      {/* Styled tooltips instead of the browser's default `title` ones, for the whole app. */}
      <TooltipLayer />
    </ErrorBoundary>
  )
}
