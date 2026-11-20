import { ApiError } from '../../../shared/api/problemDetail'
import { Avatar, Button, DataTable, EmptyState, ErrorState, Pagination, Spinner } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { formatDateTime } from '../../../shared/utils/format'
import { StatusBadge } from '../StatusBadge'
import { STATUS_LABELS } from '../types'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../../shared/components/ui').Column} Column */
/** @typedef {import('../../../shared/components/ui').TableSort} TableSort */
/** @typedef {import('../types').StakeholderLifecycle} StakeholderLifecycle */
/** @typedef {import('./kind').StakeholderKind} StakeholderKind */

/**
 * @template {StakeholderLifecycle} T
 * @typedef {object} StakeholderTableProps
 * @property {StakeholderKind<T>} kind
 * @property {{ data: PageResponse<T> | undefined, isPending: boolean, isError: boolean, isFetching: boolean, isPlaceholderData: boolean, error: Error | null, refetch: () => unknown }} query
 * @property {string} actionLabel
 * @property {string} emptyTitle
 * @property {string} [emptyDescription]
 * @property {ReactNode} [emptyAction] Shown under the empty state's text (e.g. a "Clear filters" button).
 * @property {(item: T) => void} onSelect
 * @property {(page: number) => void} onPageChange
 * @property {Column<T>[]} [extraColumns] Extra column(s) placed before the action button.
 * @property {TableSort | null} [sort] The column sort, when the page keeps it (e.g. to export in the same order).
 * @property {(sort: TableSort | null) => void} [onSortChange]
 */

/**
 * Paged table of stakeholders with status, dates and a per-row "open" action. Columns with a `sortValue` sort the page.
 * @template {StakeholderLifecycle} T
 * @param {StakeholderTableProps<T>} props
 */
export function StakeholderTable({
  kind,
  query,
  actionLabel,
  emptyTitle,
  emptyDescription,
  emptyAction,
  onSelect,
  onPageChange,
  extraColumns = [],
  sort,
  onSortChange,
}) {
  if (query.isPending) {
    return (
      <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
        <Spinner size="lg" label={`Loading ${kind.plural.toLowerCase()}`} />
      </div>
    )
  }
  if (query.isError || !query.data) {
    return (
      <ErrorState
        title={`Could not load ${kind.plural.toLowerCase()}`}
        message={query.error?.message ?? 'Unknown error'}
        correlationId={query.error instanceof ApiError ? query.error.correlationId : null}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const page = query.data
  const columns = [
    {
      key: 'name',
      header: 'Name',
      sortValue: (item) => kind.displayName(item),
      cell: (item) => (
        <div className="flex min-w-0 items-center gap-3">
          <Avatar name={kind.displayName(item)} size="sm" />
          <div className="min-w-0">
            <p className="font-medium text-slate-900">{kind.displayName(item)}</p>
            <p className="truncate text-xs text-slate-500">{kind.summary(item)}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (item) => STATUS_LABELS[item.status],
      cell: (item) => <StatusBadge status={item.status} />,
    },
    {
      key: 'submitted',
      header: 'Submitted',
      hideOnMobile: true,
      // ISO timestamps sort correctly as text; never-submitted rows (null) go last.
      sortValue: (item) => item.submittedAt,
      cell: (item) => <span className="whitespace-nowrap">{formatDateTime(item.submittedAt)}</span>,
    },
    ...extraColumns,
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (item) => (
        <Button
          variant="secondary"
          size="sm"
          onClick={() => onSelect(item)}
          aria-label={`${actionLabel} ${kind.displayName(item)}`}
        >
          <Icon name="eye" className="size-4" />
          {actionLabel}
        </Button>
      ),
    },
  ]

  return (
    <div>
      <DataTable
        caption={kind.plural}
        columns={columns}
        rows={page.content}
        getRowKey={(item) => item.id}
        sort={sort}
        onSortChange={onSortChange}
        fetching={query.isFetching && query.isPlaceholderData}
        empty={<EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />}
      />
      <Pagination
        page={page.page}
        size={page.size}
        totalElements={page.totalElements}
        totalPages={page.totalPages}
        onPageChange={onPageChange}
        disabled={query.isFetching}
      />
    </div>
  )
}
