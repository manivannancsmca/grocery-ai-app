import type { UseQueryResult } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiError, hasErrorCode } from '../api/problemDetail'
import { Alert, Button, ErrorState, Spinner } from './ui'

interface ProfileGateProps<T> {
  query: UseQueryResult<T>
  /** e.g. `VENDOR_NOT_FOUND`: the profile is still being created after registration. */
  notFoundCode: string
  children: (data: T) => ReactNode
}

/** Loading / "still setting up" / error states for a "my profile" query. */
export function ProfileGate<T>({ query, notFoundCode, children }: ProfileGateProps<T>) {
  if (query.isPending) {
    const provisioning = hasErrorCode(query.failureReason, notFoundCode)
    return (
      <div className="flex min-h-[30vh] flex-col items-center justify-center gap-3 text-sm text-slate-500">
        <Spinner size="lg" label="Loading your profile" />
        <p>{provisioning ? 'Setting up your profile — this takes a moment after sign-up…' : 'Loading your profile…'}</p>
      </div>
    )
  }

  if (query.isError) {
    if (hasErrorCode(query.error, notFoundCode)) {
      return (
        <Alert
          tone="warning"
          title="Your profile is still being set up"
          actions={
            <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
              Check again
            </Button>
          }
        >
          It is created automatically right after registration. If this message stays, try again in a minute.
        </Alert>
      )
    }
    return (
      <ErrorState
        title="Could not load your profile"
        message={query.error.message}
        correlationId={query.error instanceof ApiError ? query.error.correlationId : null}
        onRetry={() => void query.refetch()}
      />
    )
  }

  return <>{children(query.data)}</>
}
