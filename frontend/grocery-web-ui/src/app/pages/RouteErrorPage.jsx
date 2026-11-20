import { isRouteErrorResponse, useRouteError } from 'react-router'
import { ErrorState } from '../../shared/components/ui'
import { ErrorIllustration } from '../../shared/illustrations'

/** Shown when a route fails to load (e.g. a lazy chunk can't be fetched after a deploy). */
export function RouteErrorPage() {
  const error = useRouteError()

  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error
      ? error.message
      : 'Unknown error'

  return (
    <div className="mx-auto max-w-lg p-6">
      <ErrorIllustration className="mx-auto mb-4 w-48" />
      <ErrorState
        title="This page could not be loaded"
        message={message}
        onRetry={() => window.location.reload()}
        retryLabel="Reload page"
      />
    </div>
  )
}
