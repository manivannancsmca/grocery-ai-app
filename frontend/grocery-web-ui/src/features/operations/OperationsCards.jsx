import { useId } from 'react'
import { Link } from 'react-router'
import { Badge, Spinner } from '../../shared/components/ui'
import { formatCount } from '../../shared/utils/format'
import { useStuckOrders } from '../admin-orders/hooks'
import { formatOverdue, stuckReasonLabel } from '../admin-orders/labels'
import { isConnectorHealthy } from './api'
import { PIPELINE_POLL_MS, useConnectors, usePendingDeadLetterSummary } from './hooks'
import { serviceName } from './labels'

/** @typedef {import('react').ReactNode} ReactNode */

const cardClass = 'flex flex-col rounded-xl border bg-white p-5 shadow-sm'
const linkClass = 'mt-auto pt-3 text-sm font-medium text-emerald-700 hover:text-emerald-800'

/** @param {{ id: string, children: ReactNode }} props */
function CardTitle({ id, children }) {
  return (
    <h2 id={id} className="text-sm font-medium text-slate-500">
      {children}
    </h2>
  )
}

/** Dashboard: how many orders the watchdog flagged, the longest overdue one, and a link to the list. */
export function StuckOrdersCard() {
  const titleId = useId()
  const stuck = useStuckOrders()
  const count = stuck.data?.length
  const first = stuck.data?.[0]

  return (
    <section aria-labelledby={titleId} className={`${cardClass} ${count ? 'border-amber-300' : 'border-slate-200'}`}>
      <CardTitle id={titleId}>Stuck orders</CardTitle>
      <p
        className={`mt-2 text-3xl font-bold tracking-tight tabular-nums ${count ? 'text-amber-700' : 'text-slate-900'}`}
      >
        {stuck.isPending ? <Spinner size="sm" label="Loading" /> : count === undefined ? '—' : formatCount(count)}
      </p>
      <p className="mt-1 text-sm text-slate-500">
        {stuck.isError && count === undefined
          ? `Could not check: ${stuck.error.message}`
          : first
            ? `Longest: ${first.orderNumber} · ${stuckReasonLabel(first.reason)} · ${formatOverdue(first.minutesOverdue)} overdue`
            : count === 0
              ? 'Every open order is moving on time.'
              : 'Orders that stay too long in one status.'}
      </p>
      <Link to="/admin/operations?tab=stuck-orders" className={linkClass}>
        View stuck orders →
      </Link>
    </section>
  )
}

/** Dashboard: are the Debezium connectors running, and how many dead letters wait across the services? */
export function PipelineCard() {
  const titleId = useId()
  const connectors = useConnectors(PIPELINE_POLL_MS)
  const deadLetters = usePendingDeadLetterSummary()
  const list = connectors.data
  const unhealthy = list?.filter((connector) => !isConnectorHealthy(connector)).length
  const attention = Boolean(unhealthy) || deadLetters.total > 0

  return (
    <section aria-labelledby={titleId} className={`${cardClass} ${attention ? 'border-red-200' : 'border-slate-200'}`}>
      <CardTitle id={titleId}>Pipeline</CardTitle>
      <dl className="mt-2 space-y-2 text-sm">
        <div className="flex items-center justify-between gap-3">
          <dt className="text-slate-600">Connectors</dt>
          <dd>
            {connectors.isPending ? (
              <Spinner size="sm" label="Checking connectors" />
            ) : !list ? (
              <Badge tone="amber">Unknown</Badge>
            ) : unhealthy === 0 ? (
              <Badge tone="green" dot>
                {list.length === 0 ? 'None registered' : `All ${list.length} running`}
              </Badge>
            ) : (
              <Badge tone="red" dot>
                {unhealthy} of {list.length} need attention
              </Badge>
            )}
          </dd>
        </div>
        <div className="flex items-center justify-between gap-3">
          <dt className="text-slate-600">Dead letters pending</dt>
          <dd className={`text-lg font-bold tabular-nums ${deadLetters.total > 0 ? 'text-red-700' : 'text-slate-900'}`}>
            {deadLetters.loading && deadLetters.total === 0 ? (
              <Spinner size="sm" label="Counting dead letters" />
            ) : (
              formatCount(deadLetters.total)
            )}
          </dd>
        </div>
      </dl>
      {deadLetters.unavailable.length > 0 && (
        <p className="mt-2 text-xs text-amber-700">
          Not counted (unavailable): {deadLetters.unavailable.map(serviceName).join(', ')}
        </p>
      )}
      <Link to={unhealthy ? '/admin/operations?tab=connectors' : '/admin/operations'} className={linkClass}>
        Open operations →
      </Link>
    </section>
  )
}
