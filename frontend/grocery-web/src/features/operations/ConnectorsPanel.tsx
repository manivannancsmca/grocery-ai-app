import { useState } from 'react'
import { ApiError, errorMessage } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, EmptyState, ErrorState, Skeleton, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { HealthBanner, HealthPill, HealthTile } from '../system/SystemStatusGrid'
import { type ConnectorHealth, hasFailure, isConnectorHealthy } from './api'
import { useConnectors, useRestartConnector } from './hooks'
import { connectorStateLabel, connectorStateTone } from './labels'

/** A Java stack trace, folded away until asked for. */
export function TraceDetails({ trace, label = 'Error trace' }: { trace: string; label?: string }) {
  return (
    <details className="group mt-2">
      <summary className="inline-flex cursor-pointer list-none items-center gap-1 rounded-lg px-1.5 py-0.5 text-xs font-semibold text-red-700 transition-colors hover:bg-red-50 hover:text-red-800 [&::-webkit-details-marker]:hidden">
        <Icon name="chevronRight" className="size-3.5 transition-transform group-open:rotate-90 motion-reduce:transition-none" />
        {label}
      </summary>
      <pre className="mt-2 max-h-64 overflow-auto rounded-xl bg-slate-900 p-3 font-mono text-[11px] leading-relaxed whitespace-pre-wrap text-slate-100 shadow-inner">
        {trace}
      </pre>
    </details>
  )
}

/** A Kafka Connect state ("Running", "Failed"…) as a coloured pill; running ones pulse. */
function StatePill({ state }: { state: string }) {
  return <HealthPill tone={connectorStateTone(state)}>{connectorStateLabel(state)}</HealthPill>
}

interface ConnectorCardProps {
  connector: ConnectorHealth
  canRestart: boolean
  restarting: boolean
  onRestart: (name: string) => void
}

function ConnectorCard({ connector, canRestart, restarting, onRestart }: ConnectorCardProps) {
  const healthy = isConnectorHealthy(connector)
  const failed = hasFailure(connector)
  return (
    // The tile's colour: green when everything runs, red with a failure, amber otherwise (paused, no task…).
    <HealthTile tone={healthy ? 'green' : failed ? 'red' : 'amber'} label={`Connector ${connector.name}`}>
      <div className="flex items-start gap-3">
        <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-50 to-sky-50 ring-1 ring-violet-100">
          <Icon name="swap" className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="font-mono text-sm font-semibold break-all text-slate-900">{connector.name}</h3>
          <p className="text-xs text-slate-500">
            {connector.type ? `${connector.type.charAt(0).toUpperCase()}${connector.type.slice(1)} connector` : 'Connector'}
            {connector.workerId && <span className="font-mono break-all"> · {connector.workerId}</span>}
          </p>
        </div>
        <StatePill state={connector.state} />
      </div>
      {connector.trace && connector.state === 'FAILED' && <TraceDetails trace={connector.trace} label="Connector error trace" />}

      {connector.tasks.length === 0 ? (
        <p className="mt-3 flex items-center gap-2 rounded-xl bg-amber-50 px-3 py-2 text-xs text-amber-800 ring-1 ring-amber-100 ring-inset">
          <Icon name="warning" className="size-4" />
          No task is running, so no events flow.
        </p>
      ) : (
        <ul aria-label={`Tasks of ${connector.name}`} className="mt-3 space-y-2">
          {connector.tasks.map((task) => (
            <li
              key={task.id}
              className={cn('rounded-xl px-3 py-2 ring-1 ring-inset', task.state === 'FAILED' ? 'bg-red-50/60 ring-red-100' : 'bg-slate-50/80 ring-slate-100')}
            >
              <div className="flex items-center justify-between gap-3 text-sm">
                <span className="flex min-w-0 items-baseline gap-2">
                  <span className="font-medium text-slate-700">Task {task.id}</span>
                  {task.workerId && <span className="truncate font-mono text-xs text-slate-400">{task.workerId}</span>}
                </span>
                <StatePill state={task.state} />
              </div>
              {task.state === 'FAILED' && task.trace && <TraceDetails trace={task.trace} />}
            </li>
          ))}
        </ul>
      )}

      {failed && (
        <div className="mt-3 flex flex-wrap items-center justify-end gap-2 border-t border-slate-100 pt-3">
          {canRestart ? (
            <Button size="sm" variant="secondary" onClick={() => onRestart(connector.name)} disabled={restarting} aria-label={`Restart ${connector.name}`}>
              {restarting ? <Spinner size="sm" /> : <Icon name="refresh" className="size-4" />}
              Restart
            </Button>
          ) : (
            <p className="flex items-center gap-1.5 text-xs text-slate-500">
              <Icon name="lock" className="size-3.5" />
              Super and operations admins can restart it.
            </p>
          )}
        </div>
      )}
    </HealthTile>
  )
}

/**
 * Phase 10 §7: every Debezium connector and task with its Kafka Connect state (`GET /api/admin/system/connectors`),
 * the error trace of a failed one, and — for SUPER_ADMIN / OPS_ADMIN — "Restart" for the failed tasks.
 * Shown on both System status and Operations.
 */
export function ConnectorsPanel() {
  const { can } = useAuth()
  const toast = useToast()
  const connectors = useConnectors()
  const restart = useRestartConnector()
  const [restarting, setRestarting] = useState<string | null>(null)
  const canRestart = can('connectors.restart')

  async function restartConnector(name: string) {
    setRestarting(name)
    try {
      await restart.mutateAsync(name)
      toast.success(`Restart requested for ${name}`, { description: 'Its failed tasks start again; the state refreshes in a few seconds.' })
    } catch (error) {
      toast.error(`Could not restart ${name}`, {
        description: [errorMessage(error), error instanceof ApiError && error.correlationId ? `Reference: ${error.correlationId}` : null]
          .filter(Boolean)
          .join(' '),
      })
    } finally {
      setRestarting(null)
    }
  }

  if (connectors.isPending) {
    return (
      <div className="space-y-4">
        <p className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner size="sm" /> Checking connectors…
        </p>
        <div aria-hidden="true" className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {[0, 1].map((index) => (
            <div key={index} className="space-y-3 rounded-2xl bg-white p-4 ring-1 ring-slate-200/80">
              <div className="flex items-start gap-3">
                <Skeleton className="size-10 rounded-xl" />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-4 w-1/2" />
                  <Skeleton className="h-3 w-1/3" />
                </div>
              </div>
              <Skeleton className="h-9 w-full rounded-xl" />
            </div>
          ))}
        </div>
      </div>
    )
  }
  if (connectors.isError && !connectors.data) {
    return (
      <ErrorState
        title="Could not load the connector status"
        message={connectors.error.message}
        correlationId={connectors.error instanceof ApiError ? connectors.error.correlationId : null}
        onRetry={() => void connectors.refetch()}
      />
    )
  }

  const list = connectors.data
  const problems = list.filter((connector) => !isConnectorHealthy(connector)).length
  const refreshButton = (
    <Button variant="secondary" onClick={() => void connectors.refetch()} disabled={connectors.isFetching} className="shrink-0">
      {connectors.isFetching ? <Spinner size="sm" /> : <Icon name="refresh" className="size-4" />}
      Refresh now
    </Button>
  )

  return (
    <div className="space-y-4">
      {list.length > 0 ? (
        <HealthBanner
          state={problems === 0 ? 'ok' : 'problem'}
          message={problems === 0 ? `All ${list.length} connectors and their tasks are running.` : `${problems} of ${list.length} connectors need attention.`}
          checkedAt={connectors.dataUpdatedAt}
          action={refreshButton}
        />
      ) : (
        <div className="flex justify-end">{refreshButton}</div>
      )}
      {connectors.isError && (
        <Alert tone="warning">Could not refresh the connector status: {connectors.error.message} Showing the last known state.</Alert>
      )}

      {list.length === 0 ? (
        <EmptyState title="No connectors registered" description="Kafka Connect has no Debezium connector yet, so no outbox events are published." />
      ) : (
        <ul aria-label="Connectors" className="grid grid-cols-1 gap-4 lg:grid-cols-2 [&>*]:min-w-0">
          {list.map((connector) => (
            <ConnectorCard
              key={connector.name}
              connector={connector}
              canRestart={canRestart}
              restarting={restarting === connector.name}
              onRestart={(name) => void restartConnector(name)}
            />
          ))}
        </ul>
      )}
      <p className="flex items-start gap-2 text-xs text-slate-500">
        <Icon name="info" className="mt-px size-4" />
        <span>
          The connect watchdog restarts a failed task by itself (at most 3 times per task in 10 minutes); after that it stays failed and visible
          here until someone restarts it.
        </span>
      </p>
    </div>
  )
}
