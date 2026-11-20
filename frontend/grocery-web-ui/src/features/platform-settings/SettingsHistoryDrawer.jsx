import { useState } from 'react'
import { ApiError, errorMessage } from '../../shared/api/problemDetail'
import { Avatar, Dialog, EmptyState, ErrorState, Pagination, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import { groupBySave } from './history'
import { useAreaHistory } from './hooks'

/** @typedef {import('./fields').AreaDefinition} AreaDefinition */
/** @typedef {import('./history').ChangeLine} ChangeLine */
/** @typedef {import('./history').SavedChanges} SavedChanges */

/** Saves of a busy area fill pages quickly; 20 rows keep a page short enough to scan on a phone. */
const HISTORY_PAGE_SIZE = 20

/**
 * @typedef {object} SettingsHistoryDrawerProps
 * @property {AreaDefinition} definition
 * @property {() => void} onClose
 */

/**
 * Who changed an area's settings, when, and from what to what (`GET /api/admin/settings/{area}/history`), newest first
 * and grouped by save. A drawer: full screen on a phone, beside the page on a wide screen. Mounted only while open, so
 * it reads the history only when asked and always starts at the newest page.
 * @param {SettingsHistoryDrawerProps} props
 */
export function SettingsHistoryDrawer({ definition, onClose }) {
  const [page, setPage] = useState(0)
  const history = useAreaHistory(definition.area, page, HISTORY_PAGE_SIZE)

  let content
  if (history.isPending) {
    content = <HistorySkeleton />
  } else if (history.isError) {
    content = (
      <ErrorState
        title="The history could not be loaded"
        message={errorMessage(history.error)}
        correlationId={history.error instanceof ApiError ? history.error.correlationId : null}
        onRetry={() => void history.refetch()}
      />
    )
  } else if (history.data.content.length === 0) {
    content = (
      <EmptyState
        title="No changes yet"
        description="These settings still follow the server configuration. Every saved change shows up here with who made it and when."
      />
    )
  } else {
    content = (
      <>
        <ol
          aria-label="Saved changes"
          className={cn('space-y-3 transition-opacity', history.isPlaceholderData && 'opacity-60')}
        >
          {groupBySave(definition, history.data.content).map((save) => (
            <SaveEntry key={save.key} save={save} />
          ))}
        </ol>
        <Pagination
          page={history.data.page}
          size={history.data.size}
          totalElements={history.data.totalElements}
          totalPages={history.data.totalPages}
          disabled={history.isFetching}
          onPageChange={setPage}
        />
      </>
    )
  }

  return (
    <Dialog
      open
      variant="drawer"
      size="md"
      icon={definition.icon}
      title={`${definition.title}: change history`}
      description="Every saved change, newest first. “Default” is the value from the server configuration."
      onClose={onClose}
    >
      {content}
    </Dialog>
  )
}

/**
 * One save: the admin and the time, then each field it changed.
 * @param {{ save: SavedChanges }} props
 */
function SaveEntry({ save }) {
  return (
    <li className="animate-fade-up rounded-2xl border border-slate-200/80 bg-white p-3.5 shadow-sm shadow-slate-900/[0.04] motion-reduce:animate-none">
      <div className="flex items-center gap-3">
        <Avatar name={save.by} size="sm" />
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-slate-900">{save.by}</p>
          <p className="text-xs text-slate-500">
            <time dateTime={save.changedAt}>{formatDateTime(save.changedAt)}</time>
          </p>
        </div>
      </div>
      <ul aria-label={`Changed by ${save.by}`} className="mt-3 space-y-2">
        {save.lines.map((line, index) => (
          <ChangeRow key={`${line.field}-${index}`} line={line} />
        ))}
      </ul>
    </li>
  )
}

/**
 * A field: its label, then old → new (read out as "from … to …").
 * @param {{ line: ChangeLine }} props
 */
function ChangeRow({ line }) {
  return (
    <li className="rounded-xl bg-slate-50/80 px-3 py-2 ring-1 ring-slate-200/60 ring-inset">
      <p className="text-sm font-medium break-words text-slate-700">{line.label}</p>
      <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm tabular-nums">
        <span className="sr-only">from </span>
        <Value text={line.from} />
        <Icon name="arrowRight" className="size-3.5 shrink-0 text-slate-400" />
        <span className="sr-only"> to </span>
        <Value text={line.to} current />
      </p>
    </li>
  )
}

/**
 * A value with its unit, or a "Default" pill. The new value stands out; the old one is muted.
 * @param {{ text: string | null, current?: boolean }} props
 */
function Value({ text, current = false }) {
  if (text === null) {
    return (
      <span
        className={cn(
          'rounded-md px-1.5 py-0.5 text-xs font-semibold ring-1 ring-inset',
          current ? 'bg-sky-50 text-sky-800 ring-sky-200' : 'bg-slate-100 text-slate-600 ring-slate-200',
        )}
      >
        Default
      </span>
    )
  }
  return <span className={cn('break-words', current ? 'font-semibold text-slate-900' : 'text-slate-500')}>{text}</span>
}

function HistorySkeleton() {
  return (
    <div role="status" aria-label="Loading the history" className="space-y-3">
      {Array.from({ length: 3 }, (_, index) => (
        <div key={index} className="space-y-3 rounded-2xl border border-slate-200/80 p-3.5">
          <div className="flex items-center gap-3">
            <Skeleton className="size-8 rounded-lg" />
            <div className="flex-1 space-y-1.5">
              <Skeleton className="h-4 w-1/3" />
              <Skeleton className="h-3 w-1/4" />
            </div>
          </div>
          <Skeleton className="h-12 w-full rounded-xl" />
        </div>
      ))}
    </div>
  )
}
