import { ApiError } from '../../shared/api/problemDetail'
import { EmptyState, ErrorState, PageHeader, Pagination, SelectField, Spinner } from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { isReturnStatus, RETURN_STATUSES } from './api'
import { useStoreReturns } from './hooks'
import { RETURN_STATUS_LABELS } from './labels'
import { ReturnCard } from './ReturnCard'

const PAGE_SIZE = 10
/** `?status=ALL` lists every return; no parameter = the ones waiting for the store. */
const ALL = 'ALL'

/**
 * `/vendor/returns` and `/restaurant/returns` (Phase 12 §3) — the store's return requests, the undecided ones first
 * by default. Approve (vendors say whether the goods can be restocked; restaurants never restock) or reject with a
 * comment for the customer, before the decision time runs out and it escalates to admins.
 */
export function StoreReturnsView({ kind }: { kind: 'VENDOR' | 'RESTAURANT' }) {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = statusParam === ALL ? undefined : isReturnStatus(statusParam) ? statusParam : 'REQUESTED'
  const page = pageFromParam(searchParams.get('page'))
  const returns = useStoreReturns({ status, page, size: PAGE_SIZE })

  return (
    <div className="space-y-4">
      <PageHeader
        icon="return"
        title={kind === 'RESTAURANT' ? 'Returns and problems' : 'Returns'}
        description={
          kind === 'RESTAURANT'
            ? 'Problems customers reported with delivered orders. Decide before each request’s deadline, or an admin will.'
            : 'Return requests for delivered orders. Decide before each request’s deadline, or an admin will. Refunds are calculated for you.'
        }
        actions={<ExportButton path="/store/returns/export" label="returns" params={{ status }} total={returns.data?.totalElements} />}
      />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <SelectField
          label="Status"
          value={statusParam === ALL ? ALL : (status ?? 'REQUESTED')}
          onChange={(event) => updateParams({ status: event.target.value === 'REQUESTED' ? null : event.target.value, page: null })}
        >
          {RETURN_STATUSES.map((value) => (
            <option key={value} value={value}>
              {RETURN_STATUS_LABELS[value]}
            </option>
          ))}
          <option value={ALL}>All returns</option>
        </SelectField>
      </div>

      {returns.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading returns" />
        </div>
      ) : returns.isError ? (
        <ErrorState
          title="Could not load returns"
          message={returns.error.message}
          correlationId={returns.error instanceof ApiError ? returns.error.correlationId : null}
          onRetry={() => void returns.refetch()}
        />
      ) : returns.data.content.length === 0 ? (
        <EmptyState
          title={status === 'REQUESTED' ? 'Nothing to decide' : 'No returns'}
          description={status === 'REQUESTED' ? 'New requests appear here, and you are notified.' : 'None with this status.'}
        />
      ) : (
        <div>
          <ul aria-label="Returns" className="grid gap-4 lg:grid-cols-2">
            {returns.data.content.map((view) => (
              <li key={view.id}>
                <ReturnCard view={view} decider="store" canDecide storeKind={kind} />
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
