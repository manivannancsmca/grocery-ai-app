import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { Avatar, DataTable, EmptyState, ErrorState, Spinner, StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatDateTime } from '../../shared/utils/format'
import { useStuckOrders } from '../admin-orders/hooks'
import { adminOrderLink, formatOverdue, stuckReasonLabel } from '../admin-orders/labels'
import { ORDER_TYPE_LABELS } from '../orders/labels'
import { OrderStatusBadge } from '../orders/OrderBits'

/** @typedef {import('../../shared/components/ui').Column} Column */
/** @typedef {import('../admin-orders/api').StuckOrder} StuckOrder */

/** @type {Column<StuckOrder>[]} */
const columns = [
  {
    key: 'order',
    header: 'Order',
    sortValue: (order) => order.orderNumber,
    cell: (order) => (
      <div className="flex min-w-0 items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-amber-50 to-orange-50 ring-1 ring-amber-100"
        >
          <Icon name={order.type === 'RESTAURANT' ? 'cloche' : 'basket'} className="size-5" />
        </span>
        <div className="min-w-0">
          <p className="font-mono font-semibold text-slate-900">{order.orderNumber}</p>
          <p className="text-xs text-slate-500">{ORDER_TYPE_LABELS[order.type]}</p>
        </div>
      </div>
    ),
  },
  {
    key: 'status',
    header: 'Stuck',
    sortValue: (order) => stuckReasonLabel(order.reason),
    cell: (order) => (
      <span className="flex flex-col items-start gap-1">
        <OrderStatusBadge status={order.status} />
        <span className="inline-flex items-center gap-1 text-xs font-medium text-amber-800">
          <Icon name="hourglass" className="size-3.5" />
          {stuckReasonLabel(order.reason)}
        </span>
      </span>
    ),
  },
  {
    key: 'since',
    header: 'Since',
    hideOnMobile: true,
    sortValue: (order) => order.since,
    cell: (order) => <span className="whitespace-nowrap">{formatDateTime(order.since)}</span>,
  },
  {
    key: 'overdue',
    header: 'Overdue',
    className: 'text-right',
    sortValue: (order) => order.minutesOverdue,
    cell: (order) => (
      <span className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-red-50 to-rose-50 px-2.5 py-1 text-xs font-semibold whitespace-nowrap text-red-700 tabular-nums ring-1 ring-red-200 ring-inset">
        <Icon name="clock" className="size-3.5" />
        {formatOverdue(order.minutesOverdue)}
      </span>
    ),
  },
  {
    key: 'store',
    header: 'Store',
    hideOnMobile: true,
    sortValue: (order) => order.store.name,
    cell: (order) => (
      <span className="flex min-w-0 items-center gap-2">
        <Avatar name={order.store.name} size="sm" />
        <span className="truncate">{order.store.name}</span>
      </span>
    ),
  },
  {
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    cell: (order) => (
      <Link
        to={adminOrderLink(order)}
        aria-label={`Open order ${order.orderNumber}`}
        className="inline-flex items-center gap-1.5 rounded-xl bg-white px-3 py-1.5 text-sm font-semibold whitespace-nowrap text-emerald-700 shadow-xs ring-1 ring-slate-300 transition-colors ring-inset hover:bg-emerald-50 hover:text-emerald-800 hover:ring-emerald-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
      >
        Open order
        <Icon name="arrowRight" className="size-4" />
      </Link>
    ),
  },
]

/**
 * Headline numbers of the list: how many, the longest overdue, how many stores.
 * @param {{ orders: readonly StuckOrder[] }} props
 */
function StuckSummary({ orders }) {
  const longest = orders.reduce(
    (worst, order) => (worst === null || order.minutesOverdue > worst.minutesOverdue ? order : worst),
    null,
  )
  const stores = new Set(orders.map((order) => order.store.id)).size
  return (
    <dl aria-label="Stuck orders summary" className="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <StatCard
        icon="hourglass"
        accent="amber"
        label="Stuck orders"
        value={orders.length}
        valueClassName="text-amber-700"
      />
      <StatCard
        icon="clock"
        accent="rose"
        label="Longest overdue"
        value={longest ? formatOverdue(longest.minutesOverdue) : '—'}
        valueClassName="text-red-700"
        hint={longest ? <span className="font-mono">{longest.orderNumber}</span> : undefined}
      />
      <StatCard icon="store" accent="sky" label="Stores affected" value={stores} />
    </dl>
  )
}

/**
 * Phase 10 §4: orders the watchdog flagged as stuck (`GET /api/admin/orders/stuck`), longest overdue first.
 * Refreshed by ADMIN_ORDERS_CHANGED (detection and resolution) and a slow poll.
 */
export function StuckOrdersPanel() {
  const stuck = useStuckOrders()

  if (stuck.isPending) {
    return (
      <div className="flex justify-center rounded-2xl bg-white/60 py-12 ring-1 ring-slate-200/70">
        <Spinner size="lg" label="Loading stuck orders" />
      </div>
    )
  }
  if (stuck.isError && !stuck.data) {
    return (
      <ErrorState
        title="Could not load stuck orders"
        message={stuck.error.message}
        correlationId={stuck.error instanceof ApiError ? stuck.error.correlationId : null}
        onRetry={() => void stuck.refetch()}
      />
    )
  }
  return (
    <div className="space-y-4">
      <p className="flex items-start gap-2 text-sm text-slate-500">
        <Icon name="info" className="mt-0.5 size-4" />
        <span>
          Orders that stayed too long in one status. A flag clears itself as soon as the order moves on; open one to see
          its events and act on it.
        </span>
      </p>
      {stuck.data.length > 0 && <StuckSummary orders={stuck.data} />}
      <DataTable
        caption="Stuck orders"
        columns={columns}
        rows={stuck.data}
        getRowKey={(order) => `${order.orderId}-${order.reason}`}
        empty={
          <EmptyState title="No stuck orders" description="Every open order is moving through its steps on time." />
        }
      />
    </div>
  )
}
