import { useState } from 'react'
import { AdminDeliveryDrawer, DeliveryStatusBadge } from '../../../features/admin-deliveries/AdminDeliveryDrawer'
import { useAdminDeliveries } from '../../../features/admin-deliveries/hooks'
import { DELIVERY_STATUSES, isDeliveryStatus } from '../../../features/deliveries/api'
import { DELIVERY_STATUS_LABELS, deliveryStatusLabel, itemsText } from '../../../features/deliveries/labels'
import { ORDER_TYPE_LABELS } from '../../../features/orders/labels'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Avatar,
  Badge,
  Button,
  Checkbox,
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
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatDateTime } from '../../../shared/utils/format'

/** @typedef {import('../../../features/admin-deliveries/api').AdminDeliveryView} AdminDeliveryView */
/** @typedef {import('../../../shared/components/ui').Column} Column */

/**
 * Searching, and nobody accepted within the largest radius: the deliveries an admin should look at first.
 * @param {AdminDeliveryView} delivery
 * @returns {boolean}
 */
function needsPartner(delivery) {
  return !delivery.partnerName && delivery.assignmentFailed
}

/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['order']

/**
 * @param {(delivery: AdminDeliveryView) => void} onView
 * @returns {Column<AdminDeliveryView>[]}
 */
const columns = (onView) => [
  {
    key: 'order',
    header: 'Order',
    sortValue: (delivery) => delivery.orderNumber,
    cell: (delivery) => (
      <div className="min-w-0">
        <p className="font-mono font-semibold text-slate-900">{delivery.orderNumber}</p>
        <p className="text-xs whitespace-nowrap text-slate-500">
          {ORDER_TYPE_LABELS[delivery.orderType] ?? delivery.orderType}
          {delivery.timeline[0] ? ` · ${formatDateTime(delivery.timeline[0].at)}` : ''}
        </p>
      </div>
    ),
  },
  {
    key: 'pickup',
    header: 'Store',
    sortValue: (delivery) => delivery.pickup?.name,
    cell: (delivery) => (
      <div className="flex min-w-0 items-center gap-3">
        {delivery.pickup && <Avatar name={delivery.pickup.name} size="sm" />}
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{delivery.pickup?.name ?? '—'}</p>
          <p className="text-xs text-slate-500">{itemsText(delivery.itemCount)}</p>
        </div>
      </div>
    ),
  },
  {
    key: 'drop',
    header: 'Drop',
    hideOnMobile: true,
    cell: (delivery) => (
      <span className="flex items-start gap-1.5">
        <Icon name="mapPin" className="mt-0.5 size-4" />
        <span className="line-clamp-2">
          {[delivery.drop.landmark ?? delivery.drop.line2, delivery.drop.city].filter(Boolean).join(', ')}
        </span>
      </span>
    ),
  },
  {
    key: 'partner',
    header: 'Partner',
    sortValue: (delivery) => delivery.partnerName,
    cell: (delivery) =>
      delivery.partnerName ? (
        <div className="flex min-w-0 items-center gap-2.5">
          <Avatar name={delivery.partnerName} size="sm" />
          <span className="font-medium text-slate-900">{delivery.partnerName}</span>
        </div>
      ) : delivery.assignmentFailed ? (
        <Badge tone="red" dot>
          No partner found
        </Badge>
      ) : (
        <span className="text-slate-400">—</span>
      ),
  },
  {
    key: 'offers',
    header: 'Offers',
    hideOnMobile: true,
    className: 'text-right',
    sortValue: (delivery) => delivery.offersSent,
    cell: (delivery) => <span className="font-semibold text-slate-900 tabular-nums">{delivery.offersSent ?? '—'}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (delivery) => deliveryStatusLabel(delivery.status),
    cell: (delivery) => <DeliveryStatusBadge status={delivery.status} />,
  },
  {
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    cell: (delivery) => (
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onView(delivery)}
        aria-label={`View delivery ${delivery.orderNumber}`}
      >
        <Icon name="eye" className="size-4" />
        View
      </Button>
    ),
  },
]

/**
 * `/admin/deliveries` — every delivery, newest first, filterable by status, "No partner found" and order number
 * (in the URL). Any admin may read; reassigning is gated. Refreshed live by ADMIN_DELIVERIES_CHANGED and
 * NO_DELIVERY_PARTNER.
 */
export function AdminDeliveriesPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState(null)
  const [sort, setSort] = useState(null)

  const statusParam = searchParams.get('status')
  const status = isDeliveryStatus(statusParam) ? statusParam : undefined
  const noPartner = searchParams.get('noPartner') === '1'
  const queryText = searchParams.get('q') ?? ''
  const q = useDebouncedValue(queryText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))

  const deliveries = useAdminDeliveries({
    status,
    assignmentFailed: noPartner ? true : undefined,
    q: q || undefined,
    page,
    size: DEFAULT_PAGE_SIZE,
  })
  const filtered = Boolean(status || noPartner || q)
  const activeFilters = [queryText.trim(), status, noPartner].filter(Boolean).length

  function clearFilters() {
    updateParams({ q: null, status: null, noPartner: null, page: null })
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon="truck"
        title="Deliveries"
        description="Every delivery, newest first. Find the ones without a partner and reassign deliveries by hand."
        actions={
          <ExportButton
            path="/admin/deliveries/export"
            label="deliveries"
            params={{ status, assignmentFailed: noPartner ? true : undefined, q: q || undefined }}
            total={deliveries.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />

      <FilterBar activeCount={activeFilters} onClear={clearFilters}>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 sm:items-end lg:grid-cols-3">
          <TextField
            label="Order number"
            type="search"
            value={queryText}
            placeholder="e.g. GA-7K3F9Q2M"
            autoComplete="off"
            onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
          />
          <SelectField
            label="Status"
            value={status ?? ''}
            onChange={(event) => updateParams({ status: event.target.value, page: null })}
          >
            <option value="">All statuses</option>
            {DELIVERY_STATUSES.map((value) => (
              <option key={value} value={value}>
                {DELIVERY_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          {/* A toggle chip: it turns red while only the deliveries without a partner are shown. */}
          <label
            className={cn(
              'flex h-10 cursor-pointer items-center gap-2 rounded-xl px-3 text-sm font-medium ring-1 transition-colors ring-inset motion-reduce:transition-none',
              noPartner
                ? 'bg-red-50 text-red-800 ring-red-200'
                : 'bg-white text-slate-700 ring-slate-300 hover:bg-slate-50',
            )}
          >
            <Checkbox
              checked={noPartner}
              onChange={(event) => updateParams({ noPartner: event.target.checked ? '1' : null, page: null })}
            />
            No partner found
          </label>
        </div>
      </FilterBar>

      {deliveries.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading deliveries" />
        </div>
      ) : deliveries.isError ? (
        <ErrorState
          title="Could not load deliveries"
          message={deliveries.error.message}
          correlationId={deliveries.error instanceof ApiError ? deliveries.error.correlationId : null}
          onRetry={() => void deliveries.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Deliveries"
            columns={columns(setSelected)}
            rows={deliveries.data.content}
            getRowKey={(delivery) => delivery.deliveryId}
            sort={sort}
            onSortChange={setSort}
            fetching={deliveries.isFetching && deliveries.isPlaceholderData}
            rowClassName={(delivery) => (needsPartner(delivery) ? 'bg-red-50/50 hover:bg-red-50' : undefined)}
            empty={
              <EmptyState
                title="No deliveries found"
                description={
                  filtered ? 'Try different filters.' : 'Deliveries appear here once customers pay for orders.'
                }
              />
            }
          />
          <Pagination
            page={deliveries.data.page}
            size={deliveries.data.size}
            totalElements={deliveries.data.totalElements}
            totalPages={deliveries.data.totalPages}
            disabled={deliveries.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}

      {selected && (
        <AdminDeliveryDrawer id={selected.deliveryId} initial={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  )
}
