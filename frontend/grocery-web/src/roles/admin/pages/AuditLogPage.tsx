import {
  AUDIT_ACTOR_ROLE_LABELS,
  AUDIT_CATEGORIES,
  AUDIT_CATEGORY_LABELS,
  AUDIT_TARGET_LABELS,
  type AuditEntryView,
  auditActionLabel,
  auditCategoryLabel,
  useAuditTrail,
} from '../../../features/audit/api'
import { ApiError } from '../../../shared/api/problemDetail'
import {
  Badge,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { formatDateTime } from '../../../shared/utils/format'
import { isIsoDay } from '../../../shared/utils/indiaDate'

const PAGE_SIZE = 50

/** Sign-in events worth a second look: highlighted in the list. */
const ALARMING = new Set(['LOGIN_FAILURE', 'LOGIN_LOCKED', 'LOGIN_SUSPENDED', 'REFRESH_REUSE_DETECTED', 'PASSWORD_RESET_FAILURE'])

/** "Narrow to this" links inside a row: small, quiet, and a real button for keyboards and screen readers. */
function FilterLink({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="text-xs font-semibold text-emerald-700 underline-offset-2 hover:text-emerald-800 hover:underline"
    >
      {label}
    </button>
  )
}

function actorText(entry: AuditEntryView): string {
  return entry.actor.name ?? entry.actor.email ?? AUDIT_ACTOR_ROLE_LABELS[entry.actor.role] ?? entry.actor.role
}

/**
 * `/admin/audit` — one search across the platform's audit logs (`GET /api/admin/audit`, reporting-service): sign-ins
 * and account changes, store and partner decisions, prices, stock, orders, returns, refunds, reviews, the assistants'
 * approvals and settings changes, newest first. Filters live in the URL; a row's person or target narrows the list to
 * them. SUPER_ADMIN and OPS_ADMIN only (the trail has sign-in addresses and e-mails).
 */
export function AuditLogPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const text = searchParams.get('q') ?? ''
  const q = useDebouncedValue(text.trim(), 300) || undefined
  const categoryParam = searchParams.get('category')
  const category = categoryParam && (AUDIT_CATEGORIES as readonly string[]).includes(categoryParam) ? categoryParam : undefined
  const personInput = searchParams.get('actorId') ?? ''
  const actorId = useDebouncedValue(personInput.trim(), 300) || undefined
  const targetType = searchParams.get('targetType') || undefined
  const targetId = searchParams.get('targetId') || undefined
  const targetLabel = searchParams.get('targetLabel') || undefined
  const fromParam = searchParams.get('from')
  const from = isIsoDay(fromParam) ? fromParam : undefined
  const toParam = searchParams.get('to')
  const to = isIsoDay(toParam) ? toParam : undefined
  const page = pageFromParam(searchParams.get('page'))

  const filters = { q, category, actorId, targetType, targetId, from, to }
  const entries = useAuditTrail({ ...filters, page, size: PAGE_SIZE })
  const activeFilters = [text.trim(), category, personInput.trim(), targetId, from, to].filter(Boolean).length
  const clearAll = () =>
    updateParams({ q: null, category: null, actorId: null, targetType: null, targetId: null, targetLabel: null, from: null, to: null, page: null })

  const columns: Column<AuditEntryView>[] = [
    {
      key: 'when',
      header: 'Time',
      cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.occurredAt)}</span>,
    },
    {
      key: 'what',
      header: 'What happened',
      cell: (row) => (
        <div className="max-w-96 min-w-0">
          <p className="font-medium text-slate-900">{row.summary}</p>
          {row.detail && (
            <p className="line-clamp-2 text-xs text-slate-600" title={row.detail}>
              {row.detail}
            </p>
          )}
          <p className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
            <Badge tone={row.category === 'SECURITY' ? 'amber' : 'gray'}>{auditCategoryLabel(row.category)}</Badge>
            <span className="font-mono" title={auditActionLabel(row.action)}>
              {row.action}
            </span>
          </p>
        </div>
      ),
    },
    {
      key: 'who',
      header: 'Who',
      cell: (row) => (
        <div className="max-w-56 min-w-0 text-xs">
          <p className="truncate text-sm font-medium text-slate-900" title={row.actor.email ?? undefined}>
            {actorText(row)}
          </p>
          {/* the role under a name or e-mail; without them the first line already says it */}
          {(row.actor.name || row.actor.email) && (
            <p className="text-slate-500">
              {AUDIT_ACTOR_ROLE_LABELS[row.actor.role] ?? row.actor.role}
              {row.actor.name && row.actor.email ? ` · ${row.actor.email}` : ''}
            </p>
          )}
          {row.actor.id && (
            <p className="flex flex-wrap items-center gap-x-2">
              <span className="max-w-32 truncate font-mono text-slate-400" title={row.actor.id}>
                {row.actor.id}
              </span>
              {row.actor.id !== actorId && (
                <FilterLink label="Only this person" onClick={() => updateParams({ actorId: row.actor.id, page: null })} />
              )}
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'target',
      header: 'Target',
      hideOnMobile: true,
      cell: (row) =>
        row.target ? (
          <div className="max-w-56 min-w-0 text-xs">
            <p className="text-slate-500">{AUDIT_TARGET_LABELS[row.target.type] ?? row.target.type}</p>
            {row.target.label && <p className="truncate text-sm text-slate-900">{row.target.label}</p>}
            {row.target.id && row.target.id !== targetId && (
              <FilterLink
                label="Everything about this"
                onClick={() =>
                  updateParams({
                    targetType: row.target?.type ?? null,
                    targetId: row.target?.id ?? null,
                    targetLabel: row.target?.label ?? null,
                    page: null,
                  })
                }
              />
            )}
          </div>
        ) : (
          <span className="text-slate-400">—</span>
        ),
    },
    {
      key: 'source',
      header: 'From',
      hideOnMobile: true,
      cell: (row) => (
        <div className="text-xs whitespace-nowrap text-slate-500">
          <p>{row.source}</p>
          {row.ipAddress && (
            <p className="font-mono" title={row.userAgent ?? undefined}>
              {row.ipAddress}
            </p>
          )}
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <PageHeader
        icon="shield"
        title="Audit log"
        description="Who did what, across the whole platform: sign-ins and account changes, store and partner decisions, prices, stock, orders, refunds, reviews, the assistants' approvals and settings changes."
      />

      <section aria-labelledby="audit-entries" className="space-y-4">
        <div className="flex items-center gap-3">
          <h2 id="audit-entries" className="text-lg font-semibold text-slate-900">
            Entries
          </h2>
          {entries.isFetching && !entries.isPending && <Spinner size="sm" label="Updating" />}
          <ExportButton
            className="ml-auto"
            path="/admin/audit"
            label="audit entries"
            params={filters}
            total={entries.data?.totalElements}
          />
        </div>
        <FilterBar activeCount={activeFilters} onClear={clearAll} note="Dates are days in India time, both inclusive. Without dates: the last 30 days.">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-5">
            <TextField
              label="Search"
              type="search"
              value={text}
              placeholder="E-mail, order number, IP, words…"
              autoComplete="off"
              onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
            />
            <SelectField label="Category" value={category ?? ''} onChange={(event) => updateParams({ category: event.target.value, page: null })}>
              <option value="">All categories</option>
              {AUDIT_CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {AUDIT_CATEGORY_LABELS[value]}
                </option>
              ))}
            </SelectField>
            <TextField
              label="Person (user ID)"
              type="search"
              value={personInput}
              placeholder="Exact user ID"
              autoComplete="off"
              onChange={(event) => updateParams({ actorId: event.target.value, page: null }, { replace: true })}
            />
            <TextField label="From" type="date" value={from ?? ''} max={to} onChange={(event) => updateParams({ from: event.target.value, page: null })} />
            <TextField label="To" type="date" value={to ?? ''} min={from} onChange={(event) => updateParams({ to: event.target.value, page: null })} />
          </div>
          {targetId && (
            <p className="mt-3 flex flex-wrap items-center gap-2 text-sm text-slate-700">
              Only entries about{' '}
              <strong>
                {AUDIT_TARGET_LABELS[targetType ?? ''] ?? targetType} {targetLabel ?? targetId}
              </strong>
              <FilterLink label="Show everything" onClick={() => updateParams({ targetType: null, targetId: null, targetLabel: null, page: null })} />
            </p>
          )}
        </FilterBar>

        {entries.isPending ? (
          <div className="flex justify-center rounded-2xl bg-white/60 py-12 ring-1 ring-slate-200/70">
            <Spinner size="lg" label="Loading the audit log" />
          </div>
        ) : entries.isError ? (
          <ErrorState
            title="Could not load the audit log"
            message={entries.error.message}
            correlationId={entries.error instanceof ApiError ? entries.error.correlationId : null}
            onRetry={() => void entries.refetch()}
          />
        ) : (
          <div>
            <DataTable
              caption="Audit entries"
              columns={columns}
              rows={entries.data.content}
              getRowKey={(row) => row.id}
              fetching={entries.isFetching && entries.isPlaceholderData}
              rowClassName={(row) => (ALARMING.has(row.action) ? 'bg-amber-50/70 hover:bg-amber-50' : undefined)}
              empty={
                <EmptyState
                  title="No entries"
                  description={activeFilters > 0 ? 'Nothing matches these filters.' : 'Actions taken on the platform appear here.'}
                />
              }
            />
            <Pagination
              page={entries.data.page}
              size={entries.data.size}
              totalElements={entries.data.totalElements}
              totalPages={entries.data.totalPages}
              disabled={entries.isFetching}
              onPageChange={(next) => updateParams({ page: pageToParam(next) })}
            />
          </div>
        )}
      </section>
    </div>
  )
}
