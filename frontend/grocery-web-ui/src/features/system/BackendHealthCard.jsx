import { ApiError } from '../../shared/api/problemDetail'
import { Button, Card, ErrorState, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { useBackendHealth } from './useBackendHealth'

/** @param {{ status: string }} props */
function HealthStatus({ status }) {
  const isUp = status === 'UP'
  return (
    <div className="flex items-center gap-3">
      <span
        className={cn(
          'inline-flex items-center gap-2 rounded-full px-3 py-1 text-sm font-semibold',
          isUp ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700',
        )}
      >
        <span className={cn('size-2 rounded-full', isUp ? 'bg-emerald-500' : 'bg-red-500')} aria-hidden="true" />
        <span>{status}</span>
      </span>
      <p className="text-sm text-slate-600">
        {isUp
          ? 'Identity service is reachable through the API gateway.'
          : 'Identity service answered, but reports it is not healthy.'}
      </p>
    </div>
  )
}

export function BackendHealthCard() {
  const { data, error, isPending, isError, isFetching, refetch } = useBackendHealth()

  const retry = () => {
    void refetch()
  }

  return (
    <Card
      icon="pulse"
      title="Platform status"
      description="Live check of the backend through the API gateway."
      actions={
        <Button variant="ghost" size="sm" onClick={retry} disabled={isFetching}>
          {isFetching ? <Spinner size="sm" /> : null}
          Refresh
        </Button>
      }
    >
      {isPending ? (
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner size="sm" label="Checking backend" />
          <span>Checking backend…</span>
        </div>
      ) : isError ? (
        <ErrorState
          title="Backend unreachable"
          message={error.message}
          correlationId={error instanceof ApiError ? error.correlationId : null}
          onRetry={retry}
        />
      ) : (
        <HealthStatus status={data.status} />
      )}
    </Card>
  )
}
