import { Spinner } from '../../../shared/components/ui'
import { formatDateTime } from '../../../shared/utils/format'
import { StatusBadge } from '../StatusBadge'
import type { StatusChange } from '../types'

interface StatusHistoryProps {
  changes: StatusChange[] | undefined
  isPending: boolean
  error: Error | null
}

/** Timeline of status changes, newest first. */
export function StatusHistory({ changes, isPending, error }: StatusHistoryProps) {
  if (isPending) {
    return (
      <div className="flex items-center gap-2 text-sm text-slate-500">
        <Spinner size="sm" label="Loading history" /> Loading history…
      </div>
    )
  }
  if (error) return <p className="text-sm text-red-600">Could not load the history: {error.message}</p>
  if (!changes || changes.length === 0) return <p className="text-sm text-slate-500">No status changes yet.</p>

  return (
    <ol className="space-y-4 border-l border-slate-200 pl-4">
      {changes.map((change) => (
        <li key={`${change.changedAt}-${change.toStatus}`} className="relative">
          <span className="absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-white bg-slate-300" aria-hidden="true" />
          <div className="flex flex-wrap items-center gap-2 text-sm">
            {change.fromStatus && (
              <>
                <StatusBadge status={change.fromStatus} />
                <span className="text-slate-400" aria-label="to">
                  →
                </span>
              </>
            )}
            <StatusBadge status={change.toStatus} />
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {formatDateTime(change.changedAt)} · by <span className="font-mono">{change.changedBy}</span>
          </p>
          {change.reason && <p className="mt-1 text-sm text-slate-700">“{change.reason}”</p>}
        </li>
      ))}
    </ol>
  )
}
