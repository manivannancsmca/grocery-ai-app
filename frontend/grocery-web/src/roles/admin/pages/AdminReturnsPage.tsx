import { isReturnStatus, RETURN_STATUSES, type ReturnView } from '../../../features/store-returns/api'
import { useAdminReturns } from '../../../features/store-returns/hooks'
import { RETURN_STATUS_LABELS } from '../../../features/store-returns/labels'
import { ReturnCard } from '../../../features/store-returns/ReturnCard'
import { ApiError } from '../../../shared/api/problemDetail'
import { useAuth } from '../../../shared/auth/useAuth'
import { EmptyState, ErrorState, FilterBar, PageHeader, Pagination, SelectField, Skeleton, StatCard, TextField } from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatCount, formatMoney } from '../../../shared/utils/format'

const PAGE_SIZE = 10

/** Headline numbers: how many returns match the filters, and what the returns on this page add up to. */
function ReturnStats({ total, rows, filterLabel }: { total: number; rows: readonly ReturnView[]; filterLabel: string }) {
  const escalated = rows.filter((view) => view.status === 'ESCALATED').length
  const refunds = rows.reduce((sum, view) => sum + view.refundAmount, 0)
  return (
    <section aria-label="Returns summary" className="animate-fade-up motion-reduce:animate-none">
      {/* Two tiles per row on phones; the amount gets a full row there so it never has to wrap. */}
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard label="Returns found" value={formatCount(total)} hint={filterLabel} icon="return" accent="emerald" />
        <StatCard
          label="Escalated on this page"
          value={formatCount(escalated)}
          hint="Not decided by the store in time"
          icon="returnAlert"
          accent="rose"
          valueClassName={escalated > 0 ? 'text-rose-700' : undefined}
        />
        <StatCard
          label="Refunds on this page"
          value={formatMoney(refunds)}
          hint="Sum of the refund amounts"
          icon="savings"
          accent="violet"
          className="col-span-2 lg:col-span-1"
        />
      </dl>
    </section>
  )
}

/** Two placeholder cards while the first page loads. */
function SkeletonCards() {
  return (
    <div className="grid gap-4 lg:grid-cols-2" aria-hidden="true">
      {[0, 1].map((index) => (
        <div key={index} className="rounded-2xl bg-white p-5 ring-1 ring-slate-200/80">
          <div className="flex items-start gap-3">
            <Skeleton className="size-11 rounded-xl" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-3 w-1/3" />
            </div>
          </div>
          <Skeleton className="mt-5 h-10 w-full rounded-xl" />
          <Skeleton className="mt-3 h-4 w-3/4" />
          <Skeleton className="mt-3 h-9 w-full rounded-xl" />
        </div>
      ))}
    </div>
  )
}

/**
 * `/admin/returns` (Phase 12 §3) — return requests across stores; by default the open ones (requested and escalated,
 * the server's default). Super, operations admins and support approve or reject them; every admin may read.
 */
export function AdminReturnsPage() {
  const { can } = useAuth()
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = isReturnStatus(statusParam) ? statusParam : undefined
  const storeText = searchParams.get('storeId') ?? ''
  const storeId = useDebouncedValue(storeText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const returns = useAdminReturns({ status, storeId: storeId || undefined, page, size: PAGE_SIZE })
  const canDecide = can('returns.decide')
  const activeFilters = (status ? 1 : 0) + (storeText.trim() ? 1 : 0)
  const refreshing = returns.isFetching && returns.isPlaceholderData

  return (
    <div className="space-y-5">
      <PageHeader
        icon="return"
        title="Returns"
        description="Return requests and reported problems. Escalated ones were not decided by the store in time."
        actions={
          <ExportButton
            path="/admin/returns/export"
            label="returns"
            params={{ status, storeId: storeId || undefined }}
            total={returns.data?.totalElements}
          />
        }
      />
      <FilterBar activeCount={activeFilters} onClear={() => updateParams({ status: null, storeId: null, page: null })}>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <SelectField label="Status" value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
            <option value="">Open (waiting or escalated)</option>
            {RETURN_STATUSES.map((value) => (
              <option key={value} value={value}>
                {RETURN_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Store ID"
            type="search"
            value={storeText}
            autoComplete="off"
            placeholder="A vendor or restaurant ID"
            onChange={(event) => updateParams({ storeId: event.target.value, page: null }, { replace: true })}
          />
        </div>
      </FilterBar>
      {!canDecide && (
        <p className="flex items-center gap-2 text-sm text-slate-500">
          <Icon name="info" className="size-4" />
          Your role can view returns; support and operations admins decide them.
        </p>
      )}

      {returns.data && (
        <ReturnStats
          total={returns.data.totalElements}
          rows={returns.data.content}
          filterLabel={`${status ? RETURN_STATUS_LABELS[status] : 'Open (waiting or escalated)'}${storeId ? ' · one store' : ''}`}
        />
      )}

      {returns.isPending ? (
        <>
          <span className="sr-only" role="status">
            Loading returns
          </span>
          <SkeletonCards />
        </>
      ) : returns.isError ? (
        <ErrorState
          title="Could not load returns"
          message={returns.error.message}
          correlationId={returns.error instanceof ApiError ? returns.error.correlationId : null}
          onRetry={() => void returns.refetch()}
        />
      ) : returns.data.content.length === 0 ? (
        <EmptyState title="No returns" description={status || storeId ? 'Try different filters.' : 'No return is waiting for a decision.'} />
      ) : (
        <div>
          <ul aria-label="Returns" aria-busy={refreshing} className={cn('grid gap-4 transition-opacity lg:grid-cols-2', refreshing && 'opacity-60')}>
            {returns.data.content.map((view, index) => (
              // A short stagger so the cards arrive one after another.
              <li key={view.id} className="min-w-0 animate-fade-up motion-reduce:animate-none" style={{ animationDelay: `${Math.min(index, 8) * 45}ms` }}>
                <ReturnCard view={view} decider="admin" canDecide={canDecide} showStore />
              </li>
            ))}
          </ul>
          <Pagination
            page={returns.data.page}
            size={returns.data.size}
            totalElements={returns.data.totalElements}
            totalPages={returns.data.totalPages}
            disabled={returns.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}
    </div>
  )
}
