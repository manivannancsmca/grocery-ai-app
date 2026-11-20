import { useId, useState } from 'react'
import { useAuth } from '../../shared/auth/useAuth'
import { DEFAULT_PAGE_SIZE } from '../../shared/api/types'
import {
  Alert,
  Button,
  Checkbox,
  type Column,
  DataTable,
  EmptyState,
  FilterBar,
  Pagination,
  SelectField,
  Spinner,
  TextField,
} from '../../shared/components/ui'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import { HealthDot, ServiceIcon } from '../system/SystemStatusGrid'
import {
  DEAD_LETTER_SERVICES,
  DEAD_LETTER_STATUSES,
  type DeadLetterService,
  type DeadLetterStatus,
  isDeadLetterService,
  isOpenDeadLetter,
  OPEN_DEAD_LETTER_STATUSES,
} from './api'
import { DeadLetterDrawer, DeadLetterStatusBadge } from './DeadLetterDrawer'
import { DEAD_LETTER_POLL_MS, type DeadLetterRow, useDeadLetters } from './hooks'
import { DEAD_LETTER_STATUS_LABELS, DEAD_LETTER_STATUS_TONES, deadLetterStatusLabel, serviceName, shortClassName } from './labels'
import { ReplayAllDialog } from './ReplayAllDialog'

const columns = (onView: (row: DeadLetterRow) => void): Column<DeadLetterRow>[] => [
  {
    key: 'when',
    header: 'Dead-lettered',
    sortValue: (row) => row.deadLetteredAt,
    cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.deadLetteredAt)}</span>,
  },
  {
    key: 'service',
    header: 'Service',
    sortValue: (row) => serviceName(row.service),
    cell: (row) => (
      <div className="flex min-w-0 items-center gap-2.5">
        <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-sky-50 to-violet-50 ring-1 ring-sky-100">
          <ServiceIcon service={row.service} className="size-4.5" />
        </span>
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{serviceName(row.service)}</p>
          <p className="max-w-48 truncate font-mono text-xs text-slate-500" title={row.consumerGroup}>
            {row.consumerGroup}
          </p>
        </div>
      </div>
    ),
  },
  {
    key: 'event',
    header: 'Event',
    sortValue: (row) => row.eventType,
    cell: (row) => (
      <div className="min-w-0">
        <p className={cn('font-medium', row.eventType ? 'text-slate-900' : 'text-slate-500 italic')}>{row.eventType ?? 'Unreadable event'}</p>
        <p className="font-mono text-xs whitespace-nowrap text-slate-500">
          {row.originalTopic} · p{row.originalPartition} · #{row.originalOffset}
        </p>
      </div>
    ),
  },
  {
    key: 'error',
    header: 'Error',
    hideOnMobile: true,
    sortValue: (row) => shortClassName(row.exceptionClass),
    cell: (row) => (
      <div className="max-w-72 min-w-0">
        {row.exceptionClass && <p className="font-mono text-xs font-semibold text-red-700">{shortClassName(row.exceptionClass)}</p>}
        {row.exceptionMessage && (
          <p className="line-clamp-2 text-xs text-slate-600" title={row.exceptionMessage}>
            {row.exceptionMessage}
          </p>
        )}
      </div>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (row) => deadLetterStatusLabel(row.status),
    cell: (row) => (
      <span className="flex flex-col items-start gap-1">
        <DeadLetterStatusBadge status={row.status} />
        {row.attempts > 0 && <span className="text-xs whitespace-nowrap text-slate-500">{row.attempts === 1 ? '1 replay' : `${row.attempts} replays`}</span>}
      </span>
    ),
  },
  {
    // One action per row (replay and discard are in the drawer, with their confirmation): a visible button.
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    cell: (row) => (
      <Button variant="secondary" size="sm" onClick={() => onView(row)} aria-label={`View dead letter ${row.id}`}>
        <Icon name="eye" className="size-4" />
        View
      </Button>
    ),
  },
]

/** `?status=` (repeated) → the selected statuses; none or nothing valid → PENDING + REPLAY_FAILED. */
function statusesFromParams(values: string[]): DeadLetterStatus[] {
  const valid = DEAD_LETTER_STATUSES.filter((status) => values.includes(status))
  return valid.length > 0 ? valid : [...OPEN_DEAD_LETTER_STATUSES]
}

function isDefaultSelection(statuses: readonly DeadLetterStatus[]): boolean {
  return statuses.length === OPEN_DEAD_LETTER_STATUSES.length && OPEN_DEAD_LETTER_STATUSES.every((status) => statuses.includes(status))
}

function serviceList(services: readonly DeadLetterService[]): string {
  const names = services.map(serviceName)
  return names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
}

/**
 * Phase 10 §1: dead letters of all 13 consuming services in one list, newest first. Filters (service, statuses,
 * event type, consumer group) live in the URL; a service that cannot be reached is named, the rest still show.
 */
export function DeadLettersPanel() {
  const { can } = useAuth()
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState<DeadLetterRow | null>(null)
  const [replayAllOpen, setReplayAllOpen] = useState(false)
  const eventTypesId = useId()
  const groupsId = useId()
  const canManage = can('deadLetters.manage')

  const serviceParam = searchParams.get('service')
  const service = isDeadLetterService(serviceParam) ? serviceParam : null
  const statuses = statusesFromParams(searchParams.getAll('status'))
  const eventTypeText = searchParams.get('eventType') ?? ''
  const groupText = searchParams.get('group') ?? ''
  const eventType = useDebouncedValue(eventTypeText.trim(), 300)
  const consumerGroup = useDebouncedValue(groupText.trim(), 300)
  const requestedPage = pageFromParam(searchParams.get('page'))

  const overview = useDeadLetters({ service, statuses, eventType, consumerGroup })
  const totalPages = Math.max(1, Math.ceil(overview.rows.length / DEFAULT_PAGE_SIZE))
  const page = Math.min(requestedPage, totalPages - 1)
  const pageRows = overview.rows.slice(page * DEFAULT_PAGE_SIZE, (page + 1) * DEFAULT_PAGE_SIZE)
  const eventTypes = [...new Set(overview.rows.map((row) => row.eventType).filter((value): value is string => Boolean(value)))].sort()
  const groups = [...new Set(overview.rows.map((row) => row.consumerGroup))].sort()
  const filtered = Boolean(service || eventType || consumerGroup) || !isDefaultSelection(statuses)
  const servicesWithRows = overview.services.filter((state) => state.count > 0)
  const firstOpen = overview.rows.find((row) => isOpenDeadLetter(row.status))?.service
  // For the filter card: each filter that differs from the default counts once (the statuses together count once).
  const activeFilters = [service, eventTypeText.trim(), groupText.trim(), !isDefaultSelection(statuses)].filter(Boolean).length

  function toggleStatus(status: DeadLetterStatus, checked: boolean) {
    const next = checked ? DEAD_LETTER_STATUSES.filter((value) => value === status || statuses.includes(value)) : statuses.filter((value) => value !== status)
    if (next.length === 0) return
    updateParams({ status: isDefaultSelection(next) ? null : next, page: null })
  }

  return (
    <div className="space-y-4">
      <FilterBar
        activeCount={activeFilters}
        onClear={() => updateParams({ service: null, status: null, eventType: null, group: null, page: null })}
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <SelectField label="Service" value={service ?? ''} onChange={(event) => updateParams({ service: event.target.value, page: null })}>
            <option value="">All services</option>
            {DEAD_LETTER_SERVICES.map((value) => (
              <option key={value} value={value}>
                {serviceName(value)}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Event type"
            type="search"
            value={eventTypeText}
            placeholder="e.g. VendorSubmitted"
            autoComplete="off"
            list={eventTypesId}
            onChange={(event) => updateParams({ eventType: event.target.value, page: null }, { replace: true })}
          />
          <TextField
            label="Consumer group"
            type="search"
            value={groupText}
            placeholder="e.g. notification-service.dispatch"
            autoComplete="off"
            list={groupsId}
            onChange={(event) => updateParams({ group: event.target.value, page: null }, { replace: true })}
          />
        </div>
        <datalist id={eventTypesId}>
          {eventTypes.map((value) => (
            <option key={value} value={value} />
          ))}
        </datalist>
        <datalist id={groupsId}>
          {groups.map((value) => (
            <option key={value} value={value} />
          ))}
        </datalist>
        <fieldset className="mt-4">
          <legend className="mb-2 text-sm font-medium text-slate-700">Status</legend>
          <div className="flex flex-wrap gap-2">
            {DEAD_LETTER_STATUSES.map((status) => {
              const checked = statuses.includes(status)
              // At least one status stays selected.
              const locked = checked && statuses.length === 1
              return (
                <label
                  key={status}
                  className={cn(
                    'inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-sm font-medium ring-1 transition-colors ring-inset motion-reduce:transition-none',
                    checked ? 'bg-emerald-50 text-emerald-800 ring-emerald-200' : 'bg-white text-slate-600 ring-slate-200 hover:ring-emerald-200',
                    locked ? 'cursor-not-allowed' : 'cursor-pointer',
                  )}
                >
                  <Checkbox checked={checked} disabled={locked} onChange={(event) => toggleStatus(status, event.target.checked)} />
                  <HealthDot tone={DEAD_LETTER_STATUS_TONES[status]} />
                  {DEAD_LETTER_STATUS_LABELS[status]}
                </label>
              )
            })}
          </div>
        </fieldset>
        <p className="mt-3 text-xs text-slate-500">Event type and consumer group must match exactly. Pending and failed replays are shown by default.</p>
      </FilterBar>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="flex flex-wrap items-center gap-2 text-sm text-slate-500">
          {overview.loading ? (
            <>
              <Spinner size="sm" /> Checking {service ? serviceName(service) : `${DEAD_LETTER_SERVICES.length} services`}…
            </>
          ) : (
            <>
              <span className="inline-flex items-center gap-1.5 rounded-full bg-white px-3 py-1 font-semibold text-slate-800 shadow-xs ring-1 ring-slate-200 ring-inset">
                <Icon name="mail" className="size-4" />
                {overview.rows.length === 1 ? '1 dead letter' : `${overview.rows.length} dead letters`}
              </span>
              {overview.updatedAt > 0 && (
                <span className="inline-flex items-center gap-1 text-xs">
                  <Icon name="clock" className="size-3.5" />
                  Updated {new Date(overview.updatedAt).toLocaleTimeString('en-IN')}, every {DEAD_LETTER_POLL_MS / 1000} s
                </span>
              )}
            </>
          )}
        </p>
        <div className="flex shrink-0 flex-wrap gap-2">
          <Button variant="secondary" onClick={overview.refetch} disabled={overview.fetching}>
            {overview.fetching ? <Spinner size="sm" /> : <Icon name="refresh" className="size-4" />}
            Refresh
          </Button>
          {canManage && (
            <Button variant="secondary" onClick={() => setReplayAllOpen(true)}>
              <Icon name="return" className="size-4" />
              Replay all…
            </Button>
          )}
        </div>
      </div>

      {overview.unavailable.length > 0 && (
        <Alert
          tone="warning"
          role="alert"
          title={`${serviceList(overview.unavailable.map((state) => state.service))} ${overview.unavailable.length === 1 ? 'is' : 'are'} unavailable`}
          actions={
            <Button variant="secondary" size="sm" onClick={overview.refetch} disabled={overview.fetching}>
              Try again
            </Button>
          }
        >
          <p>Their dead letters are not in this list. The other services are shown.</p>
          <ul className="mt-1 list-disc pl-5 text-xs">
            {overview.unavailable.map((state) => (
              <li key={state.service}>
                {serviceName(state.service)}: {state.error?.message}
              </li>
            ))}
          </ul>
        </Alert>
      )}
      {overview.truncated.length > 0 && (
        <Alert tone="info">
          {serviceList(overview.truncated.map((state) => state.service))} {overview.truncated.length === 1 ? 'has' : 'have'} more matching dead
          letters than shown (the newest 100 per status). Narrow the filters to see older ones.
        </Alert>
      )}

      {servicesWithRows.length > 0 && !service && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-semibold tracking-wide text-slate-500 uppercase">By service</span>
          {/* A chip's text is exactly "<service> <count>": the picture inside has no text. */}
          <ul aria-label="Dead letters by service" className="flex flex-wrap gap-2">
            {servicesWithRows.map((state) => (
              <li key={state.service}>
                <button
                  type="button"
                  onClick={() => updateParams({ service: state.service, page: null })}
                  className="inline-flex items-center gap-1.5 rounded-full bg-white py-1 pr-1 pl-2 text-xs font-medium text-slate-700 shadow-xs ring-1 ring-slate-200 transition-all ring-inset hover:-translate-y-px hover:text-emerald-800 hover:ring-emerald-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 motion-reduce:transition-none motion-reduce:hover:translate-y-0"
                >
                  <ServiceIcon service={state.service} className="size-4" />
                  {serviceName(state.service)}{' '}
                  <span className="rounded-full bg-rose-50 px-1.5 py-0.5 font-bold text-rose-700 tabular-nums ring-1 ring-rose-100 ring-inset">{state.total}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {overview.loading ? (
        <div className="flex justify-center rounded-2xl bg-white/60 py-12 ring-1 ring-slate-200/70">
          <Spinner size="lg" label="Loading dead letters" />
        </div>
      ) : (
        <div>
          <DataTable
            caption="Dead letters"
            columns={columns(setSelected)}
            rows={pageRows}
            getRowKey={(row) => `${row.service}:${row.id}`}
            fetching={overview.changingFilters}
            empty={
              <EmptyState
                title="No dead letters"
                description={filtered ? 'Nothing matches these filters.' : 'Every event was processed: nothing is waiting for a replay.'}
              />
            }
          />
          <Pagination
            page={page}
            size={DEFAULT_PAGE_SIZE}
            totalElements={overview.rows.length}
            totalPages={totalPages}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}

      {selected && <DeadLetterDrawer key={`${selected.service}:${selected.id}`} row={selected} onClose={() => setSelected(null)} />}
      <ReplayAllDialog
        open={replayAllOpen}
        initialService={service ?? firstOpen ?? DEAD_LETTER_SERVICES[0]}
        eventType={eventType}
        consumerGroup={consumerGroup}
        onClose={() => setReplayAllOpen(false)}
      />
    </div>
  )
}
