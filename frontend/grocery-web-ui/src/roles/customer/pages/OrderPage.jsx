import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ORDER_ERRORS } from '../../../features/orders/api'
import { CancelOrderDialog } from '../../../features/orders/CancelOrderDialog'
import { useOrder } from '../../../features/orders/hooks'
import { isPastPickup, isTrackable } from '../../../features/orders/labels'
import { OrderAddressBlock, OrderTotals } from '../../../features/orders/OrderBits'
import { OrderStatusHero } from '../../../features/orders/OrderStatusHero'
import { OrderLinesList, Panel } from '../../../features/orders/OrderVisuals'
import { OrderTrackingPanel } from '../../../features/order-tracking/OrderTrackingPanel'
import { OrderReturnsCard } from '../../../features/returns/OrderReturnsCard'
import { ReturnRequestDialog } from '../../../features/returns/ReturnRequestDialog'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { Alert, EmptyState, ErrorState, Skeleton } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { LostIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { formatPrice } from '../../../shared/utils/format'

/** @typedef {import('../../../features/orders/api').OrderView} OrderView */

const primaryLink =
  'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * Savings on MRP plus the coupon's discounts, when there are any.
 * @param {{ order: OrderView }} props
 */
function SavingsNote({ order }) {
  const total = order.savings + (order.discount ?? 0) + (order.deliveryFeeDiscount ?? 0)
  if (total <= 0 || order.status === 'CANCELLED' || order.status === 'REJECTED') return null
  return (
    <p className="mt-4 flex items-center gap-2 rounded-xl bg-emerald-50 px-3 py-2.5 text-sm font-semibold text-emerald-800 ring-1 ring-emerald-100">
      <Icon name="tag" className="size-4" />
      You saved {formatPrice(total)} on this order
    </p>
  )
}

/**
 * `/orders/:orderId` — one order: a status hero (where the order is, the progress, payment while it waits for it,
 * rate / return once delivered), live tracking beside it while a partner has it, then the items, the bill and the
 * address. Live status comes from `ORDER_UPDATED` over the realtime stream, with a slow poll while it is down.
 */
export function OrderPage() {
  const { orderId = '' } = useParams()
  const order = useOrder(orderId)
  const [cancelling, setCancelling] = useState(false)
  const [returning, setReturning] = useState(false)

  if (order.isPending) {
    return (
      <div role="status" aria-label="Loading your order" className="space-y-5">
        <Skeleton className="h-5 w-28" />
        <Skeleton className="h-80 w-full rounded-2xl" />
        <Skeleton className="h-56 w-full rounded-2xl" />
      </div>
    )
  }

  // Only a failed first load replaces the page; a failed poll keeps the last answer on screen.
  if (order.isLoadingError) {
    if (
      hasErrorCode(order.error, ORDER_ERRORS.notFound) ||
      (order.error instanceof ApiError && order.error.status === 404)
    ) {
      return (
        <div className="mx-auto max-w-xl py-8">
          <EmptyState
            size="lg"
            illustration={<LostIllustration />}
            title="Order not found"
            description="The link may be wrong, or the order belongs to another account."
            action={
              <Link to="/orders" className={primaryLink}>
                See my orders
              </Link>
            }
          />
        </div>
      )
    }
    return (
      <ErrorState
        title="Could not load this order"
        message={order.error.message}
        correlationId={order.error instanceof ApiError ? order.error.correlationId : null}
        onRetry={() => void order.refetch()}
      />
    )
  }

  const view = order.data
  const refresh = () => void order.refetch()
  // Phase 9: never after pickup, whatever an older answer says.
  const canCancel = view.cancellable && !isPastPickup(view.status)
  const live = isTrackable(view)

  return (
    <div className="@container/order w-full">
      {/* Back to the list on the left; a quiet way to complain about this order on the right. */}
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
        <Link
          to="/orders"
          className="inline-flex items-center gap-1.5 rounded-lg text-sm font-semibold text-slate-500 transition-colors hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          <Icon name="arrowLeft" className="size-4" />
          My orders
        </Link>
        <Link
          to={`/account/complaints?orderId=${encodeURIComponent(view.id)}`}
          className="inline-flex min-h-10 items-center gap-1.5 rounded-lg text-sm font-medium text-slate-500 transition-colors hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          <Icon name="chat" className="size-4" />
          Need help with this order?
        </Link>
      </div>

      {order.isRefetchError && (
        <Alert tone="warning" role="status" className="mb-5">
          We could not refresh this order just now ({order.error.message}). Showing the last known status — we keep
          trying.
        </Alert>
      )}

      {/* While a partner has the order, the map sits beside the status on wide screens (under it on narrow ones). */}
      <div className={cn('grid gap-5', live && '@4xl/order:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]')}>
        <OrderStatusHero
          order={view}
          onPaymentChange={refresh}
          onCancel={canCancel ? () => setCancelling(true) : undefined}
          onReturn={() => setReturning(true)}
        />
        {live && (
          // Beside a long status (all updates open), the map stays in view instead of stretching.
          <div className="min-w-0 @4xl/order:sticky @4xl/order:top-20 @4xl/order:self-start">
            <OrderTrackingPanel order={view} />
          </div>
        )}
      </div>

      <div className="mt-5 space-y-5">
        <Panel
          title="Items"
          icon={view.type === 'RESTAURANT' ? 'cutlery' : 'bag'}
          className="@container"
          aside={
            <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-600">
              {view.itemCount} item{view.itemCount === 1 ? '' : 's'}
            </span>
          }
        >
          <OrderLinesList items={view.items} />
        </Panel>
        <OrderReturnsCard order={view} />
        <div className="grid gap-5 @2xl/order:grid-cols-2">
          <Panel title="Bill details" icon="receipt">
            <OrderTotals order={view} />
            <SavingsNote order={view} />
            <p className="mt-3 text-xs text-slate-500">
              Prices are fixed when the order is placed. Inclusive of all taxes.
            </p>
          </Panel>
          <Panel title="Delivery address" icon="mapPin">
            <OrderAddressBlock address={view.deliveryAddress} />
            {view.note && (
              <p className="mt-3 flex items-start gap-2 rounded-xl bg-amber-50 px-3 py-2.5 text-sm text-slate-700 ring-1 ring-amber-100">
                <Icon name="chat" className="mt-0.5 size-4" />
                <span>
                  <span className="font-medium">Instructions:</span> {view.note}
                </span>
              </p>
            )}
          </Panel>
        </div>
      </div>

      <CancelOrderDialog order={view} open={cancelling} onClose={() => setCancelling(false)} />
      <ReturnRequestDialog order={view} open={returning} onClose={() => setReturning(false)} />
    </div>
  )
}
