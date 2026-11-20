import { useState } from 'react'
import { isPurchaseOrderStatus, orderTotal, PO_STATUSES } from '../../../features/purchasing/api'
import { useAdminPurchaseOrders } from '../../../features/purchasing/hooks'
import { PO_STATUS_LABELS, poStatusLabel } from '../../../features/purchasing/labels'
import { PoStatusBadge, PurchaseOrderLinesTable, ReceiptsList } from '../../../features/purchasing/PurchaseOrderBits'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Avatar,
  Button,
  DataTable,
  DescriptionList,
  Dialog,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  StatCard,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { formatCount, formatDate, formatDateTime, formatMoney } from '../../../shared/utils/format'

/** @typedef {import('../../../features/purchasing/api').PurchaseOrderView} PurchaseOrderView */

/** @param {{ order: PurchaseOrderView, onClose: () => void }} props */
function PurchaseOrderDrawer({ order, onClose }) {
  return (
    <Dialog
      open
      variant="drawer"
      icon="clipboard"
      onClose={onClose}
      title={`Purchase order ${order.number}`}
      description={<PoStatusBadge status={order.status} />}
    >
      <div className="space-y-6">
        <DescriptionList
          items={[
            { label: 'Vendor', value: order.vendorName ?? order.vendorId },
            { label: 'Supplier', value: order.supplierName ?? order.supplierId },
            { label: 'Expected on', value: order.expectedAt ? formatDate(order.expectedAt) : null },
            { label: 'Created', value: formatDateTime(order.createdAt) },
            { label: 'Notes', value: order.notes, wide: true },
          ]}
        />
        <PurchaseOrderLinesTable order={order} />
        {order.status !== 'DRAFT' && (
          <section className="space-y-3">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
              <Icon name="truck" className="size-5" />
              Receipts
            </h3>
            <ReceiptsList order={order} />
          </section>
        )}
      </div>
    </Dialog>
  )
}

/**
 * Orders still waiting for (some of) their goods.
 * @param {PurchaseOrderView} order
 * @returns {boolean}
 */
function awaitingGoods(order) {
  return order.status === 'SENT' || order.status === 'PARTIALLY_RECEIVED'
}

/**
 * Headline numbers: how many purchase orders match, and what the orders on this page are worth and still wait for.
 * @param {{ total: number, rows: readonly PurchaseOrderView[], filtered: boolean }} props
 */
function PurchaseOrderStats({ total, rows, filtered }) {
  const waiting = rows.filter(awaitingGoods).length
  const value = rows.reduce((sum, order) => sum + orderTotal(order), 0)
  return (
    <section aria-label="Purchase orders summary" className="animate-fade-up motion-reduce:animate-none">
      {/* Two tiles per row on phones; the amount gets a full row there so it never has to wrap. */}
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard
          label="Purchase orders"
          value={formatCount(total)}
          hint={filtered ? 'Matching the filters' : 'All vendors'}
          icon="clipboard"
          accent="emerald"
        />
        <StatCard
          label="Awaiting goods on this page"
          value={formatCount(waiting)}
          hint="Sent or partially received"
          icon="truck"
          accent="amber"
        />
        <StatCard
          label="Value on this page"
          value={formatMoney(value)}
          hint="Sum of the order totals"
          icon="receipt"
          accent="sky"
          className="col-span-2 lg:col-span-1"
        />
      </dl>
    </section>
  )
}

/** `/admin/purchase-orders` (Phase 12 §4) — every vendor's purchase orders, read-only, by vendor and status (in the URL). */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['number', 'created']

export function AdminPurchaseOrdersPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState(null)
  const [sort, setSort] = useState(null)
  const statusParam = searchParams.get('status')
  const status = isPurchaseOrderStatus(statusParam) ? statusParam : undefined
  const vendorText = searchParams.get('vendorId') ?? ''
  const vendorId = useDebouncedValue(vendorText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const orders = useAdminPurchaseOrders({ vendorId: vendorId || undefined, status, page, size: DEFAULT_PAGE_SIZE })
  const activeFilters = (vendorText.trim() ? 1 : 0) + (status ? 1 : 0)

  const columns = [
    {
      key: 'number',
      header: 'Order',
      sortValue: (order) => order.number,
      cell: (order) => <span className="font-mono font-semibold whitespace-nowrap text-slate-900">{order.number}</span>,
    },
    {
      key: 'vendor',
      header: 'Vendor / supplier',
      sortValue: (order) => order.vendorName ?? order.vendorId,
      cell: (order) => (
        <div className="flex min-w-0 items-center gap-3">
          {/* Initials only for a real name; a bare vendor id gets the store picture and reads as an id. */}
          {order.vendorName ? (
            <Avatar name={order.vendorName} size="sm" />
          ) : (
            <span
              aria-hidden="true"
              className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-amber-50 ring-1 ring-amber-100"
            >
              <Icon name="store" className="size-4" />
            </span>
          )}
          <div className="min-w-0">
            {order.vendorName ? (
              <p className="font-medium text-slate-900">{order.vendorName}</p>
            ) : (
              <p className="max-w-56 truncate font-mono text-xs text-slate-700" title={order.vendorId ?? undefined}>
                <span className="font-sans text-slate-500">Vendor </span>
                {order.vendorId ?? '—'}
              </p>
            )}
            <p className="flex items-center gap-1 text-xs text-slate-500">
              <Icon name="truck" className="size-3.5" />
              {order.supplierName ?? order.supplierId}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (order) => poStatusLabel(order.status),
      cell: (order) => <PoStatusBadge status={order.status} />,
    },
    {
      key: 'total',
      header: 'Total',
      className: 'text-right',
      sortValue: (order) => orderTotal(order),
      cell: (order) => (
        <span className="font-semibold whitespace-nowrap text-slate-900 tabular-nums">
          {formatMoney(orderTotal(order), order.currency ?? 'INR')}
        </span>
      ),
    },
    {
      key: 'created',
      header: 'Created',
      hideOnMobile: true,
      sortValue: (order) => order.createdAt,
      cell: (order) => <span className="whitespace-nowrap">{formatDate(order.createdAt)}</span>,
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (order) => (
        // A single action: a visible button rather than a "⋯" menu.
        <Button size="sm" variant="secondary" aria-label={`View ${order.number}`} onClick={() => setSelected(order)}>
          <Icon name="eye" className="size-4" />
          View
        </Button>
      ),
    },
  ]

  return (
    <div className="space-y-5">
      <PageHeader
        icon="clipboard"
        title="Purchase orders"
        description="What vendors order from their suppliers and what they received. Read-only."
        actions={
          <ExportButton
            path="/admin/purchase-orders/export"
            label="purchase orders"
            params={{ vendorId: vendorId || undefined, status }}
            total={orders.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />
      <FilterBar activeCount={activeFilters} onClear={() => updateParams({ vendorId: null, status: null, page: null })}>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <TextField
            label="Vendor ID"
            type="search"
            value={vendorText}
            autoComplete="off"
            onChange={(event) => updateParams({ vendorId: event.target.value, page: null }, { replace: true })}
          />
          <SelectField
            label="Status"
            value={status ?? ''}
            onChange={(event) => updateParams({ status: event.target.value, page: null })}
          >
            <option value="">All statuses</option>
            {PO_STATUSES.map((value) => (
              <option key={value} value={value}>
                {PO_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
        </div>
      </FilterBar>

      {orders.data && (
        <PurchaseOrderStats total={orders.data.totalElements} rows={orders.data.content} filtered={activeFilters > 0} />
      )}

      {orders.isPending ? (
        <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
          <Spinner size="lg" label="Loading purchase orders" />
        </div>
      ) : orders.isError ? (
        <ErrorState
          title="Could not load purchase orders"
          message={orders.error.message}
          correlationId={orders.error instanceof ApiError ? orders.error.correlationId : null}
          onRetry={() => void orders.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Purchase orders"
            columns={columns}
            rows={orders.data.content}
            getRowKey={(order) => order.id}
            sort={sort}
            onSortChange={setSort}
            fetching={orders.isFetching && orders.isPlaceholderData}
            empty={
              <EmptyState
                title="No purchase orders"
                description={
                  status || vendorId ? 'Try different filters.' : 'Vendors have not ordered from suppliers yet.'
                }
              />
            }
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

      {selected && <PurchaseOrderDrawer order={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}
