/** Building blocks of every report section: the export button, loading / error states, chart panels and filter strips. */

import { ApiError } from '../../shared/api/problemDetail'
import { ErrorState, Skeleton, Spinner } from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { cn } from '../../shared/utils/cn'
import { formatLongDay } from '../../shared/utils/indiaDate'
import { REPORT_PATHS } from './api'
import { KPI_GRID } from './reportFormat'

/** @typedef {import('../../shared/export').ExportParams} ExportParams */
/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./api').ReportName} ReportName */
/** @typedef {import('./api').ReportRange} ReportRange */
/** @typedef {import('@tanstack/react-query').UseQueryResult} UseQueryResult */

/**
 * @typedef {object} ReportExportProps
 * @property {ReportName} report
 * @property {ExportParams} params The section's query: the period and its own filters (sent with `format`).
 * @property {string} label The section's name: the menu's accessible name ("Export daily sales") and the toasts.
 * @property {ReportRange} range
 */

/**
 * "Export" on a report section: the same numbers as CSV, Excel or PDF (`?format=`), for the chosen period and the
 * section's own filters. The download runs in the shared export tray (progress, cancel, a toast at the end). Excel and
 * PDF have headings in words with the period and the filters on top; CSV keeps the report's field names.
 * @param {ReportExportProps} props
 */
export function ReportExportButton({ report, params, label, range }) {
  return (
    <ExportButton
      path={REPORT_PATHS[report]}
      params={params}
      label={label.toLowerCase()}
      scopeNote={`This section for ${formatLongDay(range.from)} to ${formatLongDay(range.to)}, with its filters.`}
    />
  )
}

/**
 * @template T
 * @typedef {object} QueryBodyProps
 * @property {UseQueryResult<T>} query
 * @property {string} label
 * @property {ReactNode} [skeleton] Shown while loading instead of the spinner (e.g. skeleton tiles); the loading message stays for screen readers.
 * @property {(data: T) => ReactNode} children
 */

/**
 * Loading / error / content of one report query.
 * @template T
 * @param {QueryBodyProps<T>} props
 */
export function QueryBody({ query, label, skeleton, children }) {
  if (query.isPending) {
    if (skeleton) {
      return (
        <>
          <span role="status" className="sr-only">
            Loading {label.toLowerCase()}
          </span>
          {skeleton}
        </>
      )
    }
    return (
      <div className="flex justify-center rounded-xl bg-slate-50/70 py-10 ring-1 ring-slate-100 ring-inset">
        <Spinner label={`Loading ${label.toLowerCase()}`} />
      </div>
    )
  }
  if (query.isError) {
    return (
      <ErrorState
        title={`Could not load ${label.toLowerCase()}`}
        message={query.error.message}
        correlationId={query.error instanceof ApiError ? query.error.correlationId : null}
        onRetry={() => void query.refetch()}
      />
    )
  }
  return <div className={cn('transition-opacity', query.isPlaceholderData && 'opacity-60')}>{children(query.data)}</div>
}

/**
 * A chart on a soft panel, so each picture reads as its own block inside a section card.
 * @param {{ children: ReactNode }} props
 */
export function ChartPanel({ children }) {
  return (
    <div className="min-w-0 rounded-xl bg-gradient-to-b from-slate-50/80 to-white p-3 ring-1 ring-slate-100 ring-inset sm:p-4">
      {children}
    </div>
  )
}

/**
 * A section's own filters, on a tinted strip at the top of its card.
 * @param {{ children: ReactNode }} props
 */
export function SectionFilters({ children }) {
  return (
    <div className="mb-5 grid grid-cols-1 gap-3 rounded-xl bg-gradient-to-br from-slate-50 to-emerald-50/40 p-3 ring-1 ring-slate-200/70 ring-inset sm:grid-cols-2 lg:grid-cols-4">
      {children}
    </div>
  )
}

export function KpiSkeleton() {
  return (
    <div aria-hidden="true" className={KPI_GRID}>
      {Array.from({ length: 8 }, (_, index) => (
        <div
          key={index}
          className="flex items-center gap-3 rounded-2xl border border-slate-200/70 bg-white px-4 py-3.5"
        >
          <Skeleton className="size-10 rounded-xl" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-24" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
      ))}
    </div>
  )
}
