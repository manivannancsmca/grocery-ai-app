import { Alert, Badge, Button, Dialog, Spinner } from '../../shared/components/ui'
import { formatMoney } from '../../shared/utils/format'

/** @typedef {import('./api').CheckoutSession} CheckoutSession */
/** @typedef {import('./api').SimulatorOutcome} SimulatorOutcome */

/**
 * @typedef {object} SimulatorPaymentDialogProps
 * @property {CheckoutSession} session
 * @property {SimulatorOutcome | null} pending
 * @property {string | null} error
 * @property {(outcome: SimulatorOutcome) => void} onPay
 * @property {() => void} onClose
 */

/**
 * Stand-in for Razorpay Checkout when payment-service runs in simulator mode (no Razorpay keys on a
 * local machine). Clearly marked as a test: no money moves.
 * @param {SimulatorPaymentDialogProps} props
 */
export function SimulatorPaymentDialog({ session, pending, error, onPay, onClose }) {
  return (
    <Dialog
      open
      size="sm"
      onClose={onClose}
      dismissible={pending === null}
      title={
        <span className="flex items-center gap-2">
          Test payment <Badge tone="violet">Local test</Badge>
        </span>
      }
      description="Simulator mode: payment-service has no Razorpay keys, so no real money moves."
      footer={
        <>
          <Button
            variant="secondary"
            className="text-red-700"
            disabled={pending !== null}
            onClick={() => onPay('FAIL')}
          >
            {pending === 'FAIL' && <Spinner size="sm" />}
            Decline
          </Button>
          <Button disabled={pending !== null} onClick={() => onPay('SUCCESS')}>
            {pending === 'SUCCESS' && <Spinner size="sm" className="[&_svg]:text-white" />}
            Pay successfully
          </Button>
        </>
      }
    >
      <div className="space-y-4 text-sm text-slate-600">
        <dl className="space-y-1.5">
          <div className="flex justify-between gap-4">
            <dt>Order</dt>
            <dd className="font-mono text-slate-900">{session.orderNumber}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt>Amount</dt>
            <dd className="font-semibold text-slate-900 tabular-nums">
              {formatMoney(session.displayAmount, session.currency)}
            </dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt>Test order id</dt>
            <dd className="font-mono text-xs break-all text-slate-500">{session.razorpayOrderId}</dd>
          </div>
        </dl>
        <p>
          Choose what the test payment does. “Pay successfully” confirms the order exactly like a real payment;
          “Decline” records a failed attempt.
        </p>
        {error && (
          <Alert tone="danger" role="alert">
            {error}
          </Alert>
        )}
      </div>
    </Dialog>
  )
}
