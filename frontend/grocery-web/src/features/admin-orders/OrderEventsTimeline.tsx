import { ApiError } from '../../shared/api/problemDetail'
import { Badge, ErrorState, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import type { OrderEventEntry } from './api'
import { useOrderEvents } from './hooks'
import {
  EVENT_DIRECTION_LABELS,
  EVENT_DIRECTION_TONES,
  EVENT_OUTCOME_LABELS,
  EVENT_OUTCOME_TONES,
  labelOf,
  toneOf,
} from './labels'

function EventItem({ entry, last }: { entry: OrderEventEntry; last: boolean }) {
  const ignored = entry.outcome === 'IGNORED'
  return (
    <li className="relative">
      <span
        className={cn(
          'absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-white',
          ignored ? 'bg-amber-400' : last ? 'bg-emerald-500' : 'bg-slate-300',
        )}
        aria-hidden="true"
      />
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-900">{entry.eventType}</span>
        <Badge tone={toneOf(EVENT_DIRECTION_TONES, entry.direction)}>{labelOf(EVENT_DIRECTION_LABELS, entry.direction)}</Badge>
        <Badge tone={toneOf(EVENT_OUTCOME_TONES, entry.outcome)} dot>
          {labelOf(EVENT_OUTCOME_LABELS, entry.outcome)}
        </Badge>
      </div>
      {entry.detail && (
        <p className={cn('mt-1 text-sm', ignored ? 'text-amber-800' : 'text-slate-700')}>
          {ignored ? 'Ignored: ' : ''}
          {entry.detail}
        </p>
      )}
      <dl className="mt-1 grid gap-x-4 gap-y-0.5 text-xs text-slate-500 sm:grid-cols-2">
        {entry.sourceTopic && (
          <div className="flex gap-1">
            <dt>Topic</dt>
            <dd className="font-mono break-all text-slate-700">{entry.sourceTopic}</dd>
          </div>
        )}
        <div className="flex gap-1">
          <dt>Occurred</dt>
          <dd className="text-slate-700">{formatDateTime(entry.occurredAt)}</dd>
        </div>
        <div className="flex gap-1">
          <dt>Recorded</dt>
          <dd className="text-slate-700">{formatDateTime(entry.recordedAt)}</dd>
        </div>
        {entry.correlationId && (
          <div className="flex gap-1 sm:col-span-2">
            <dt>Correlation ID</dt>
            <dd className="font-mono break-all text-slate-700">{entry.correlationId}</dd>
          </div>
        )}
      </dl>
    </li>
  )
}

/**
 * Phase 10 §5: every event order-service published or consumed for the order, oldest first, with what it did
 * (applied, ignored by a state guard with its reason, or a duplicate).
 */
export function OrderEventsTimeline({ orderId }: { orderId: string }) {
  const events = useOrderEvents(orderId)

  if (events.isPending) {
    return (
      <div className="flex justify-center py-8">
        <Spinner label="Loading order events" />
      </div>
    )
  }
  if (events.isError) {
    return (
      <ErrorState
        title="Could not load the order’s events"
        message={events.error.message}
        correlationId={events.error instanceof ApiError ? events.error.correlationId : null}
        onRetry={() => void events.refetch()}
      />
    )
  }
  if (events.data.length === 0) {
    return <p className="text-sm text-slate-500">No events recorded for this order yet. Orders placed before the event log existed have none.</p>
  }
  return (
    <div className="space-y-3">
      <p className="text-xs text-slate-500">
        What order-service published and consumed for this order, oldest first. “Ignored” events were refused by a status
        guard; “Duplicate” ones had already been processed.
      </p>
      <ol aria-label="Order events" className="space-y-4 border-l border-slate-200 pl-4">
        {events.data.map((entry, index) => (
          <EventItem key={`${entry.eventId}-${entry.direction}-${index}`} entry={entry} last={index === events.data.length - 1} />
        ))}
      </ol>
    </div>
  )
}
