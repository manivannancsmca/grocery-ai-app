import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { ReasonDialog } from '../../shared/components/ReasonDialog'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, DescriptionList, Dialog, ErrorState, Spinner, Tabs } from '../../shared/components/ui'
import { formatDateTime, formatDistanceKm, formatMoney } from '../../shared/utils/format'
import { refundableAmount } from '../admin-payments/api'
import { useOrderPayment } from '../admin-payments/hooks'
import { PaymentAttempts, PaymentRefunds, PaymentSummary } from '../admin-payments/PaymentBits'
import { RefundDialog } from '../admin-payments/RefundDialog'
import { ORDER_ERRORS, ORDER_LIMITS } from '../orders/api'
import { adminCancellationText, cancelledByLabel, ORDER_TYPE_LABELS, storeRejectionDetails } from '../orders/labels'
import {
  OrderAddressBlock,
  OrderItemsList,
  OrderPaymentBadge,
  OrderStatusBadge,
  OrderTimeline,
  OrderTotals,
} from '../orders/OrderBits'
import { PAYMENT_ERRORS } from '../payments/api'
import { adminCanCancel } from './api'
import { useAdminCancelOrder, useAdminOrder } from './hooks'
import { OrderEventsTimeline } from './OrderEventsTimeline'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/ui').TabItem} TabItem */
/** @typedef {import('../payments/api').PaymentView} PaymentView */
/** @typedef {import('./api').AdminOrderSummary} AdminOrderSummary */
/** @typedef {import('./api').AdminOrderView} AdminOrderView */

/** @param {number} value */
const money = (value) => formatMoney(value)

/** @typedef {'details' | 'events'} DrawerTab */

/**
 * Phase 10: "Events" shows the order's event log (published and consumed events).
 * @type {readonly TabItem<DrawerTab>[]}
 */
const DRAWER_TABS = [
  { id: 'details', label: 'Details' },
  { id: 'events', label: 'Events' },
]
const linkClass = 'font-semibold text-emerald-700 hover:text-emerald-800 hover:underline'

/** Phase 9: delivery-service creates the delivery when the order is confirmed (paid). */
const HAS_DELIVERY = new Set([
  'CONFIRMED',
  'ACCEPTED',
  'PREPARING',
  'READY_FOR_PICKUP',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'DELIVERY_FAILED',
])

/** @param {{ title: string, children: ReactNode }} props */
function Section({ title, children }) {
  return (
    <section>
      <h3 className="mb-3 text-sm font-semibold text-slate-900">{title}</h3>
      {children}
    </section>
  )
}

/**
 * The order's payment (`GET /api/payments/orders/{orderId}`): amounts, attempts and refunds.
 * @param {{ order: AdminOrderView, onRefund: ((payment: PaymentView) => void) | null }} props
 */
function OrderPaymentSection({ order, onRefund }) {
  const payment = useOrderPayment(order.id)

  if (payment.isPending) return <Spinner size="sm" label="Loading payment" />
  if (payment.isError) {
    if (hasErrorCode(payment.error, PAYMENT_ERRORS.notFound)) {
      return (
        <p className="text-sm text-slate-500">
          {order.status === 'REJECTED' || order.payment.status === 'NOT_REQUIRED'
            ? 'No payment was created for this order.'
            : 'No payment yet. It is created once the order is waiting for payment.'}
        </p>
      )
    }
    return (
      <ErrorState
        title="Could not load the payment"
        message={payment.error.message}
        correlationId={payment.error instanceof ApiError ? payment.error.correlationId : null}
        onRetry={() => void payment.refetch()}
      />
    )
  }

  const view = payment.data
  const refundable = refundableAmount(view)
  return (
    <div className="space-y-5">
      <PaymentSummary payment={view} />
      <div>
        <h4 className="mb-2 text-xs font-medium tracking-wide text-slate-500 uppercase">Attempts</h4>
        <PaymentAttempts payment={view} />
      </div>
      <div>
        <h4 className="mb-2 text-xs font-medium tracking-wide text-slate-500 uppercase">Refunds</h4>
        <PaymentRefunds payment={view} />
      </div>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <Link to={`/admin/payments?orderId=${encodeURIComponent(order.id)}`} className={linkClass}>
          Open in payments
        </Link>
        {onRefund && refundable > 0 && (
          <Button size="sm" variant="secondary" onClick={() => onRefund(view)}>
            Issue refund
          </Button>
        )}
      </div>
    </div>
  )
}

/**
 * @typedef {object} AdminOrderDrawerProps
 * @property {string} id
 * @property {AdminOrderSummary} [initial] The clicked row, shown in the title while the full order loads.
 * @property {() => void} onClose
 */

/**
 * One order for admins: status, timeline, items, bill, address, customer, the payment with its attempts
 * and refunds, and — for SUPER_ADMIN / OPS_ADMIN / SUPPORT — "Cancel order" with a required reason.
 * Phase 10: an "Events" tab shows the order's event log (loaded when the tab is opened).
 * @param {AdminOrderDrawerProps} props
 */
export function AdminOrderDrawer({ id, initial, onClose }) {
  const { can } = useAuth()
  const toast = useToast()
  const detail = useAdminOrder(id)
  const cancel = useAdminCancelOrder()
  const [cancelOpen, setCancelOpen] = useState(false)
  const [refunding, setRefunding] = useState(null)
  const [tab, setTab] = useState('details')

  const order = detail.data
  const orderNumber = order?.orderNumber ?? initial?.orderNumber
  const canCancel = can('orders.cancel')
  const cancellable = order ? adminCanCancel(order) : false
  const status = order?.status ?? initial?.status
  const paymentStatus = order?.payment.status ?? initial?.payment.status

  async function cancelOrder(reason) {
    try {
      await cancel.mutateAsync({ id, reason })
      setCancelOpen(false)
      toast.success(`Order ${orderNumber ?? ''} cancelled. Stock is released and a paid order is refunded in full.`)
    } catch (error) {
      if (hasErrorCode(error, ORDER_ERRORS.notCancellable)) {
        // The order moved on meanwhile (the hook refreshes it).
        setCancelOpen(false)
        toast.error('This order can no longer be cancelled. It has been refreshed.')
        return
      }
      throw error
    }
  }

  return (
    <>
      <Dialog
        open
        variant="drawer"
        icon="receipt"
        onClose={onClose}
        title={orderNumber ? `Order ${orderNumber}` : 'Loading order…'}
        description={
          status && paymentStatus ? (
            <span className="flex flex-wrap gap-1.5">
              <OrderStatusBadge status={status} />
              <OrderPaymentBadge status={paymentStatus} />
            </span>
          ) : undefined
        }
        footer={
          order && canCancel && cancellable ? (
            <Button variant="danger" onClick={() => setCancelOpen(true)}>
              Cancel order
            </Button>
          ) : undefined
        }
      >
        {detail.isPending ? (
          <div className="flex justify-center py-10">
            <Spinner size="lg" label="Loading order" />
          </div>
        ) : detail.isLoadingError ? (
          <ErrorState
            title="Could not load this order"
            message={detail.error.message}
            correlationId={detail.error instanceof ApiError ? detail.error.correlationId : null}
            onRetry={() => void detail.refetch()}
          />
        ) : order ? (
          <Tabs label="Order sections" tabs={DRAWER_TABS} value={tab} onChange={setTab}>
            {tab === 'events' ? (
              <OrderEventsTimeline orderId={order.id} />
            ) : (
              <div className="space-y-6">
                {order.cancellation && (
                  <Alert tone="warning" title={adminCancellationText(order.cancellation)}>
                    {order.cancellation.reason === 'STORE_REJECTED'
                      ? order.cancellation.note && (
                          <p>Store’s reason: {storeRejectionDetails(order.cancellation.note)}</p>
                        )
                      : order.cancellation.note && <p>“{order.cancellation.note}”</p>}
                    <p className="text-xs">
                      By {cancelledByLabel(order.cancellation.by)} · {formatDateTime(order.cancellation.at)}
                    </p>
                  </Alert>
                )}
                {order.rejection && (
                  <Alert tone="danger" title="Rejected: not enough stock">
                    {order.rejection.unavailableItems.length > 0 ? (
                      <ul aria-label="Unavailable items" className="list-disc space-y-0.5 pl-5">
                        {order.rejection.unavailableItems.map((item) => (
                          <li key={item.itemId}>
                            {item.name}: requested {item.requested}, available {item.available}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p>{order.rejection.reason}</p>
                    )}
                  </Alert>
                )}
                {!canCancel && cancellable && (
                  <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
                    Your admin role can view orders but not cancel them. Super, operations and support admins can
                    cancel.
                  </p>
                )}

                <Section title="Details">
                  <DescriptionList
                    items={[
                      { label: 'Order number', value: <span className="font-mono">{order.orderNumber}</span> },
                      { label: 'Type', value: ORDER_TYPE_LABELS[order.type] },
                      {
                        label: 'Store',
                        value: (
                          <>
                            {order.store.name}
                            <span className="block font-mono text-xs text-slate-500">{order.store.id}</span>
                          </>
                        ),
                      },
                      {
                        label: 'Customer ID',
                        value: (
                          <Link
                            to={`/admin/orders?customerId=${encodeURIComponent(order.customerId)}`}
                            onClick={onClose}
                            title="Show this customer's orders"
                            className="font-mono text-xs text-emerald-700 hover:underline"
                          >
                            {order.customerId}
                          </Link>
                        ),
                      },
                      { label: 'Placed', value: formatDateTime(order.createdAt) },
                      { label: 'Last update', value: formatDateTime(order.updatedAt) },
                      { label: 'Payment due', value: order.payment.dueAt ? formatDateTime(order.payment.dueAt) : null },
                      { label: 'Paid', value: order.payment.paidAt ? formatDateTime(order.payment.paidAt) : null },
                      {
                        label: 'Accepted by the store',
                        value: order.acceptedAt ? formatDateTime(order.acceptedAt) : null,
                      },
                      {
                        label: 'Ready by',
                        value: order.readyBy
                          ? `${formatDateTime(order.readyBy)}${order.readyInMinutes ? ` (${order.readyInMinutes} min)` : ''}`
                          : null,
                      },
                      { label: 'Delivery note', value: order.note, wide: true },
                    ]}
                  />
                </Section>

                <Section title="Items">
                  <OrderItemsList items={order.items} money={money} />
                  <div className="mt-4 border-t border-slate-100 pt-4">
                    <OrderTotals order={order} money={money} />
                  </div>
                </Section>

                <Section title="Delivery address">
                  <OrderAddressBlock address={order.deliveryAddress} />
                  {order.deliveryDistanceKm !== null && (
                    <p className="mt-1 text-xs text-slate-500">
                      {formatDistanceKm(order.deliveryDistanceKm)} from the store
                    </p>
                  )}
                </Section>

                {(order.delivery || HAS_DELIVERY.has(order.status)) && (
                  <Section title="Delivery">
                    {order.delivery?.partnerName ? (
                      <DescriptionList
                        items={[
                          { label: 'Delivery partner', value: order.delivery.partnerName },
                          {
                            label: 'Assigned',
                            value: order.delivery.assignedAt ? formatDateTime(order.delivery.assignedAt) : null,
                          },
                          {
                            label: 'Picked up',
                            value: order.delivery.pickedUpAt ? formatDateTime(order.delivery.pickedUpAt) : null,
                          },
                          {
                            label: 'Delivered',
                            value: order.delivery.deliveredAt ? formatDateTime(order.delivery.deliveredAt) : null,
                          },
                        ]}
                      />
                    ) : (
                      <p className="text-sm text-slate-500">No delivery partner assigned yet.</p>
                    )}
                    <Link
                      to={`/admin/deliveries?q=${encodeURIComponent(order.orderNumber)}`}
                      onClick={onClose}
                      className={`mt-3 inline-block text-sm ${linkClass}`}
                    >
                      Open in deliveries
                    </Link>
                  </Section>
                )}

                <Section title="Status history">
                  <OrderTimeline timeline={order.timeline} />
                </Section>

                <Section title="Payment">
                  <OrderPaymentSection order={order} onRefund={can('payments.refund') ? setRefunding : null} />
                </Section>
              </div>
            )}
          </Tabs>
        ) : null}
      </Dialog>

      <ReasonDialog
        open={cancelOpen}
        title={`Cancel order ${orderNumber ?? ''}`}
        description="The customer sees that support cancelled the order and the store takes it off its board. Stock is released and a paid order is refunded in full."
        confirmLabel="Cancel order"
        cancelLabel="Keep order"
        maxLength={ORDER_LIMITS.cancelReason}
        onClose={() => setCancelOpen(false)}
        onSubmit={cancelOrder}
      />
      <RefundDialog payment={refunding} onClose={() => setRefunding(null)} />
    </>
  )
}
