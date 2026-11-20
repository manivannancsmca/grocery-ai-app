import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { Button, Dialog, ErrorState, Spinner } from '../../shared/components/ui'
import { refundableAmount } from './api'
import { useAdminPayment } from './hooks'
import { PaymentAttempts, PaymentRefunds, PaymentStatusBadge, PaymentSummary } from './PaymentBits'
import { RefundDialog } from './RefundDialog'

/** @typedef {import('../payments/api').PaymentView} PaymentView */

/**
 * @typedef {object} AdminPaymentDrawerProps
 * @property {string} id
 * @property {PaymentView} [initial] The clicked row (without attempts), shown while the full record loads.
 * @property {() => void} onClose
 */

/**
 * One payment for admins: amounts, attempts, refunds and — for finance — "Refund".
 * @param {AdminPaymentDrawerProps} props
 */
export function AdminPaymentDrawer({ id, initial, onClose }) {
  const { can } = useAuth()
  const detail = useAdminPayment(id)
  const payment = detail.data ?? initial
  const [refunding, setRefunding] = useState(null)
  const refundable = payment ? refundableAmount(payment) : 0
  const canRefund = can('payments.refund')

  return (
    <>
      <Dialog
        open
        variant="drawer"
        icon="card"
        onClose={onClose}
        title={payment ? `Payment for order ${payment.orderNumber}` : 'Loading payment…'}
        description={payment ? <PaymentStatusBadge status={payment.status} /> : undefined}
        footer={
          payment && canRefund && refundable > 0 ? (
            <Button onClick={() => setRefunding(detail.data ?? payment)}>Issue refund</Button>
          ) : undefined
        }
      >
        {!payment && detail.isPending ? (
          <div className="flex justify-center py-10">
            <Spinner size="lg" label="Loading payment" />
          </div>
        ) : !payment && detail.isError ? (
          <ErrorState
            message={detail.error.message}
            correlationId={detail.error instanceof ApiError ? detail.error.correlationId : null}
            onRetry={() => void detail.refetch()}
          />
        ) : payment ? (
          <div className="space-y-6">
            {!canRefund && payment.status === 'CAPTURED' && (
              <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
                Your admin role can view payments but not refund them. Super and finance admins issue refunds.
              </p>
            )}
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Payment</h3>
              <PaymentSummary payment={payment} />
            </section>
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Order</h3>
              <p className="text-sm text-slate-700">
                <Link
                  to={`/admin/orders?q=${encodeURIComponent(payment.orderNumber)}`}
                  className="font-semibold text-emerald-700 hover:text-emerald-800 hover:underline"
                >
                  {payment.orderNumber}
                </Link>{' '}
                · customer <span className="font-mono text-xs">{payment.customerId}</span>
              </p>
            </section>
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Attempts</h3>
              {detail.isPending && !payment.attempts ? (
                <Spinner size="sm" label="Loading attempts" />
              ) : (
                <PaymentAttempts payment={payment} />
              )}
            </section>
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Refunds</h3>
              <PaymentRefunds payment={payment} />
            </section>
          </div>
        ) : null}
      </Dialog>
      <RefundDialog payment={refunding} onClose={() => setRefunding(null)} />
    </>
  )
}
