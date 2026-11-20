import { useState } from 'react'
import { type AdminOrderSummary, isIsoDate } from '../../../features/admin-orders/api'
import { AdminOrderDrawer } from '../../../features/admin-orders/AdminOrderDrawer'
import { useAdminOrders } from '../../../features/admin-orders/hooks'
import { isOrderStatus, isOrderType, ORDER_STATUSES, ORDER_TYPES } from '../../../features/orders/api'
import { ORDER_STATUS_LABELS, ORDER_TYPE_LABELS, orderItemsSummary } from '../../../features/orders/labels'
import { OrderPaymentBadge, OrderStatusBadge } from '../../../features/orders/OrderBits'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Avatar,
  Button,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  type TableSort,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { formatDateTime, formatMoney } from '../../../shared/utils/format'

/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['order', 'total'] as const

const columns = (onView: (order: AdminOrderSummary) => void): Column<AdminOrderSummary>[] => [
  {
    key: 'order',
    header: 'Order',
    sortValue: (order) => order.orderNumber,
    cell: (order) => (
      <div className="min-w-0">
        <p className="font-mono font-semibold text-slate-900">{order.orderNumber}</p>
        <p className="flex items-center gap-1 text-xs whitespace-nowrap text-slate-500">
          <Icon name="clock" className="size-3.5" />
          {formatDateTime(order.createdAt)}
        </p>
      </div>
    ),
  },
  {
    key: 'store',
    header: 'Store',
    sortValue: (order) => order.store.name,
    cell: (order) => (
      <div className="flex min-w-0 items-center gap-3">
        <Avatar name={order.store.name} size="sm" />
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{order.store.name}</p>
          <p className="flex items-center gap-1 text-xs text-slate-500">
            <Icon name={order.type === 'RESTAURANT' ? 'cloche' : 'basket'} className="size-3.5" />
            {ORDER_TYPE_LABELS[order.type]}
          </p>
        </div>
      </div>
    ),
  },
  {
    key: 'items',
    header: 'Items',
    hideOnMobile: true,
    cell: (order) => <span className="line-clamp-2">{orderItemsSummary(order.firstItemName, order.itemCount)}</span>,
  },
  {
    key: 'customer',
    header: 'Customer',
    hideOnMobile: true,
    cell: (order) => (
      <span className="block max-w-32 truncate font-mono text-xs text-slate-500" title={order.customerId}>
        {order.customerId}
      </span>
    ),
  },
  {
    key: 'total',
    header: 'Total',
    className: 'text-right',
    sortValue: (order) => order.total,
    cell: (order) => <span className="font-semibold whitespace-nowrap text-slate-900 tabular-nums">{formatMoney(order.total, order.currency)}</span>,
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (order) => ORDER_STATUS_LABELS[order.status],
    cell: (order) => (
      <span className="flex flex-col items-start gap-1">
        <OrderStatusBadge status={order.status} />
        <OrderPaymentBadge status={order.payment.status} />
      </span>
    ),
  },
  {
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    cell: (order) => (
      <Button variant="secondary" size="sm" onClick={() => onView(order)} aria-label={`View order ${order.orderNumber}`}>
        <Icon name="eye" className="size-4" />
        View
      </Button>
    ),
  },
]

/**
 * `/admin/orders` — every order, newest first, filterable by status, type, order number and dates (in the URL;
 * `?customerId=` / `?storeId=` narrow it to one customer or store). Any admin may read; cancelling is gated.
 * `?order=<id>` opens that order's drawer (Phase 10: links from stuck orders and STUCK_ORDER notifications).
 */
export function AdminOrdersPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState<AdminOrderSummary | null>(null)
  const [sort, setSort] = useState<TableSort | null>(null)
  const linkedOrderId = searchParams.get('order') || null
  const drawerId = selected?.id ?? linkedOrderId

  function closeDrawer() {
    setSelected(null)
    if (linkedOrderId) updateParams({ order: null }, { replace: true })
  }

  const statusParam = searchParams.get('status')
  const status = isOrderStatus(statusParam) ? statusParam : undefined
  const typeParam = searchParams.get('type')
  const type = isOrderType(typeParam) ? typeParam : undefined
  const queryText = searchParams.get('q') ?? ''
  const q = useDebouncedValue(queryText.trim(), 300)
  const fromParam = searchParams.get('from')
  const from = isIsoDate(fromParam) ? fromParam : undefined
  const toParam = searchParams.get('to')
  const to = isIsoDate(toParam) ? toParam : undefined
  const customerId = searchParams.get('customerId') || undefined
  const storeId = searchParams.get('storeId') || undefined
  const page = pageFromParam(searchParams.get('page'))

  const orders = useAdminOrders({ status, type, q: q || undefined, from, to, customerId, storeId, page, size: DEFAULT_PAGE_SIZE })
  const filtered = Boolean(status || type || q || from || to || customerId || storeId)
  const activeFilters = [queryText.trim(), status, type, from, to, customerId, storeId].filter(Boolean).length

  // Removes only the filters: `?order=` (the open drawer) stays.
  function clearFilters() {
    updateParams({ q: null, status: null, type: null, from: null, to: null, customerId: null, storeId: null, page: null })
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon="receipt"
        title="Orders"
        description="Every order across stores, newest first. Open one to see its timeline, payment and refunds."
        actions={
          <ExportButton
            path="/admin/orders/export"
            label="orders"
            params={{ status, type, q: q || undefined, from, to, customerId, storeId }}
            total={orders.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />

      <FilterBar
        activeCount={activeFilters}
        onClear={clearFilters}
        note="Dates are days in India time, both inclusive. The order number must match exactly."
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <TextField
            label="Order number"
            type="search"
            value={queryText}
            placeholder="e.g. GA-7K3F9Q2M"
            autoComplete="off"
            onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
          />
          <SelectField label="Status" value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
            <option value="">All statuses</option>
            {ORDER_STATUSES.map((value) => (
              <option key={value} value={value}>
                {ORDER_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <SelectField label="Type" value={type ?? ''} onChange={(event) => updateParams({ type: event.target.value, page: null })}>
            <option value="">Grocery and restaurant</option>
            {ORDER_TYPES.map((value) => (
              <option key={value} value={value}>
                {ORDER_TYPE_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <TextField
            label="From"
            type="date"
            value={from ?? ''}
            max={to}
            onChange={(event) => updateParams({ from: event.target.value, page: null })}
          />
          <TextField
            label="To"
            type="date"
            value={to ?? ''}
            min={from}
            onChange={(event) => updateParams({ to: event.target.value, page: null })}
          />
        </div>

        {/* Set by links from a customer or a store: shown as chips under the fields. */}
        {(customerId || storeId) && (
          <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3 text-xs">
            {customerId && (
              <span className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-sky-50 px-2.5 py-1 text-sky-800 ring-1 ring-sky-200 ring-inset">
                <Icon name="user" className="size-3.5" />
                Customer <span className="font-mono break-all">{customerId}</span>
              </span>
            )}
            {storeId && (
              <span className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-violet-50 px-2.5 py-1 text-violet-800 ring-1 ring-violet-200 ring-inset">
                <Icon name="store" className="size-3.5" />
                Store <span className="font-mono break-all">{storeId}</span>
              </span>
            )}
            <Button variant="ghost" size="sm" onClick={() => updateParams({ customerId: null, storeId: null, page: null })}>
              Show all customers and stores
            </Button>
          </div>
        )}
      </FilterBar>

      {orders.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading orders" />
        </div>
      ) : orders.isError ? (
        <ErrorState
          title="Could not load orders"
          message={orders.error.message}
          correlationId={orders.error instanceof ApiError ? orders.error.correlationId : null}
          onRetry={() => void orders.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Orders"
            columns={columns(setSelected)}
            rows={orders.data.content}
            getRowKey={(order) => order.id}
            sort={sort}
            onSortChange={setSort}
            fetching={orders.isFetching && orders.isPlaceholderData}
            empty={<EmptyState title="No orders found" description={filtered ? 'Try different filters.' : 'Orders appear here once customers place them.'} />}
          />
          <Pagination
            page={orders.data.page}
            size={orders.data.size}
            totalElements={orders.data.totalElements}
            totalPages={orders.data.totalPages}
            disabled={orders.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}

      {drawerId && <AdminOrderDrawer key={drawerId} id={drawerId} initial={selected ?? undefined} onClose={closeDrawer} />}
    </div>
  )
}
