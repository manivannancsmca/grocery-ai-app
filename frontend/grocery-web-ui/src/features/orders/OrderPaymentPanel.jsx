import { useId } from 'react'
import { Alert, Button, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCountdown, formatDateTime, formatPrice } from '../../shared/utils/format'
import { SimulatorPaymentDialog } from '../payments/SimulatorPaymentDialog'
import { usePayNow } from '../payments/usePayNow'

/** @typedef {import('./api').OrderView} OrderView */

/**
 * @typedef {object} OrderPaymentPanelProps
 * @property {OrderView} order
 * @property {() => void} onPaymentChange Re-read the order (after a payment, a decline or a closed window).
 */

/** The usual payment window (15 minutes from placing the order), for the bar when the order's own times look odd. */
const PAYMENT_WINDOW_MS = 15 * 60_000

const BUTTON_LABELS = {
  preparing: 'Preparing payment…',
  checkout: 'Waiting for your payment…',
  verifying: 'Confirming your payment…',
}

/**
 * "Pay now" while an order is AWAITING_PAYMENT, at the top of the order page: countdown to `payment.dueAt`, last
 * decline, retry. Inside the order hero (a container), so it lays itself out by the hero's width.
 * @param {OrderPaymentPanelProps} props
 */
export function OrderPaymentPanel({ order, onPaymentChange }) {
  const pay = usePayNow(order.id, onPaymentChange)
  const titleId = useId()
  const payable = order.payment.status === 'PENDING'
  const parsedDue = order.payment.dueAt ? Date.parse(order.payment.dueAt) : Number.NaN
  const dueAt = Number.isFinite(parsedDue) ? parsedDue : null
  const now = useNow(1_000, dueAt !== null)
  const paid = pay.paid || order.payment.status === 'PAID'
  // Once paid, the window no longer matters (the payment is being confirmed).
  const remaining = dueAt !== null && !paid ? dueAt - now : null
  const windowOver = remaining !== null && remaining <= 0
  const busy = pay.phase !== 'idle'
  // How much of the payment window (placed → due) is left, for the bar under the countdown.
  const created = Date.parse(order.createdAt)
  const placedToDue = dueAt !== null && Number.isFinite(created) ? dueAt - created : null
  const windowMs =
    placedToDue !== null && placedToDue > 0 && placedToDue <= 60 * 60_000 ? placedToDue : PAYMENT_WINDOW_MS
  const leftShare = remaining !== null ? Math.min(1, Math.max(0, remaining / windowMs)) : null
  const urgent = remaining !== null && remaining < 3 * 60_000

  return (
    <section
      aria-labelledby={titleId}
      className="animate-fade-up overflow-hidden rounded-2xl bg-gradient-to-br from-amber-50 via-amber-50/50 to-white ring-1 ring-amber-200/80 ring-inset"
    >
      <div className="p-4 @md:p-5">
        <div className="flex flex-col gap-3 @xl:flex-row @xl:items-start @xl:justify-between">
          <div className="flex min-w-0 items-start gap-3.5">
            <span
              aria-hidden="true"
              className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-white shadow-sm ring-1 ring-amber-100"
            >
              <Icon name="card" className="size-6" />
            </span>
            <div className="min-w-0">
              <h2 id={titleId} className="text-lg leading-snug font-bold tracking-tight text-slate-900 @md:text-xl">
                Pay {formatPrice(order.total)} to confirm your order
              </h2>
              {!windowOver && !paid && (
                <p className="mt-0.5 text-sm text-slate-600">
                  {order.store.name} holds your order until then. Unpaid orders are cancelled automatically.
                </p>
              )}
            </div>
          </div>
          {remaining !== null && !windowOver && (
            <p
              className={cn(
                'inline-flex shrink-0 items-center gap-2 self-start rounded-xl bg-white px-3 py-2 shadow-sm ring-1 ring-inset @xl:self-auto',
                urgent ? 'text-rose-700 ring-rose-200' : 'text-amber-900 ring-amber-200',
              )}
            >
              <Icon name="clock" className={cn('size-4', urgent && 'animate-pulse motion-reduce:animate-none')} />
              <span className="text-xs font-semibold">Pay within</span>{' '}
              <span
                role="timer"
                aria-label="Time left to pay"
                className="font-mono text-lg leading-none font-bold tabular-nums"
              >
                {formatCountdown(remaining)}
              </span>
            </p>
          )}
        </div>
        {leftShare !== null && !windowOver && (
          <div aria-hidden="true" className="mt-4 h-1.5 overflow-hidden rounded-full bg-amber-100">
            <div
              className={cn(
                'h-full rounded-full transition-[width] duration-1000 ease-linear motion-reduce:transition-none',
                urgent ? 'bg-rose-500' : 'bg-amber-500',
              )}
              style={{ width: `${leftShare * 100}%` }}
            />
          </div>
        )}

        <div className="mt-4 space-y-3">
          {windowOver && (
            <Alert tone="warning" role="status">
              The time to pay is over. This order is being cancelled
              {order.payment.dueAt ? ` (payment was due at ${formatDateTime(order.payment.dueAt)})` : ''}.
            </Alert>
          )}

          {pay.failure ? (
            <Alert tone="danger" role="alert">
              {pay.failure}
            </Alert>
          ) : (
            order.payment.lastFailure &&
            !paid && (
              <Alert tone="warning" title="Your last payment attempt failed">
                {order.payment.lastFailure.description} You can try again with the same or another payment method.
              </Alert>
            )
          )}

          {paid ? (
            <p
              role="status"
              className="flex items-center gap-2 rounded-xl bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-800 ring-1 ring-emerald-100"
            >
              <Spinner size="sm" /> Payment received. Confirming your order…
            </p>
          ) : payable ? (
            <div className="flex flex-col gap-3 @lg:flex-row-reverse @lg:items-center @lg:justify-between">
              <Button
                className="h-12 w-full px-6 text-base whitespace-nowrap @lg:w-auto @lg:min-w-56"
                disabled={busy || windowOver}
                onClick={() => void pay.start()}
              >
                {busy ? <Spinner size="sm" className="[&_svg]:text-white" /> : <Icon name="card" className="size-5" />}
                {pay.phase === 'idle' ? `Pay now · ${formatPrice(order.total)}` : BUTTON_LABELS[pay.phase]}
              </Button>
              <p className="flex items-center justify-center gap-2 text-xs text-slate-500 @lg:justify-start">
                <Icon name="shield" className="size-4" />
                Your order is confirmed as soon as the payment goes through.
              </p>
            </div>
          ) : (
            <p role="status" className="flex items-center gap-2 text-sm text-slate-600">
              <Spinner size="sm" /> Preparing payment…
            </p>
          )}
        </div>
      </div>

      {pay.simulator && (
        <SimulatorPaymentDialog
          session={pay.simulator.session}
          pending={pay.simulator.pending}
          error={pay.simulator.error}
          onPay={(outcome) => void pay.simulator?.pay(outcome)}
          onClose={() => pay.simulator?.close()}
        />
      )}
    </section>
  )
}
