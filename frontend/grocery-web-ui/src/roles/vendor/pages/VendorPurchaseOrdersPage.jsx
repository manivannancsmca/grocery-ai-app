import { Link } from 'react-router'
import { isPurchaseOrderStatus, orderTotal, PO_STATUSES } from '../../../features/purchasing/api'
import { usePurchaseOrders, useSuppliers } from '../../../features/purchasing/hooks'
import { PO_STATUS_LABELS } from '../../../features/purchasing/labels'
import { PoStatusBadge } from '../../../features/purchasing/PurchaseOrderBits'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { formatDate, formatMoney } from '../../../shared/utils/format'

const linkClass =
  'inline-flex h-10 items-center rounded-lg bg-emerald-600 px-4 text-sm font-medium text-white hover:bg-emerald-700'

/**
 * `/vendor/purchase-orders` (Phase 12 §4) — the vendor's purchase orders, newest first, by status and supplier
 * (in the URL). A new order starts as a DRAFT; it is sent, received (in parts) and closed from its page.
 */
export function VendorPurchaseOrdersPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = isPurchaseOrderStatus(statusParam) ? statusParam : undefined
  const supplierId = searchParams.get('supplierId') || undefined
  const page = pageFromParam(searchParams.get('page'))
  const orders = usePurchaseOrders({ status, supplierId, page, size: DEFAULT_PAGE_SIZE })
  const suppliers = useSuppliers()
  const supplierNames = new Map((suppliers.data ?? []).map((supplier) => [supplier.id, supplier.name]))

  const columns = [
    {
      key: 'number',
      header: 'Order',
      cell: (order) => (
        <Link
          to={`/vendor/purchase-orders/${encodeURIComponent(order.id)}`}
          className="font-mono font-semibold text-emerald-700 hover:text-emerald-800"
        >
          {order.number}
        </Link>
      ),
    },
    {
      key: 'supplier',
      header: 'Supplier',
      cell: (order) => order.supplierName ?? supplierNames.get(order.supplierId) ?? '—',
    },
    { key: 'status', header: 'Status', cell: (order) => <PoStatusBadge status={order.status} /> },
    {
      key: 'expected',
      header: 'Expected',
      hideOnMobile: true,
      cell: (order) => (order.expectedAt ? formatDate(order.expectedAt) : '—'),
    },
    {
      key: 'lines',
      header: 'Products',
      hideOnMobile: true,
      className: 'text-right',
      cell: (order) => <span className="tabular-nums">{order.lines.length}</span>,
    },
    {
      key: 'total',
      header: 'Total',
      className: 'text-right',
      cell: (order) => (
        <span className="whitespace-nowrap tabular-nums">
          {formatMoney(orderTotal(order), order.currency ?? 'INR')}
        </span>
      ),
    },
    { key: 'created', header: 'Created', hideOnMobile: true, cell: (order) => formatDate(order.createdAt) },
  ]

  return (
    <div className="space-y-4">
      <PageHeader
        icon="clipboard"
        title="Purchase orders"
        description="Order stock from your suppliers, then record what arrives — your stock goes up as you receive it."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ExportButton
              path="/vendor/purchase-orders/export"
              label="purchase orders"
              params={{ status, supplierId }}
              total={orders.data?.totalElements}
            />
            <Link to="/vendor/purchase-orders/new" className={linkClass}>
              New purchase order
            </Link>
          </div>
        }
      />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
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
        <SelectField
          label="Supplier"
          value={supplierId ?? ''}
          onChange={(event) => updateParams({ supplierId: event.target.value, page: null })}
        >
          <option value="">All suppliers</option>
          {(suppliers.data ?? []).map((supplier) => (
            <option key={supplier.id} value={supplier.id}>
              {supplier.name}
            </option>
          ))}
        </SelectField>
      </div>

      {orders.isPending ? (
        <div className="flex justify-center py-12">
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
            fetching={orders.isFetching && orders.isPlaceholderData}
            empty={
              <EmptyState
                title="No purchase orders"
                description={
                  status || supplierId ? 'Try different filters.' : 'Create one to order stock from a supplier.'
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
    </div>
  )
}
