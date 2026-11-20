import { useId, useState } from 'react'
import { Link } from 'react-router'
import { Button, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatPrice, formatTime } from '../../shared/utils/format'
import { storePath } from '../cart/format'
import { etaValueText, liveEtaMinutes } from '../order-tracking/eta'
import { useOrderTracking } from '../order-tracking/hooks'
import { AfterDeliveryActions } from './AfterDeliveryActions'
import {
  cancellationText,
  isPastPickup,
  isTerminalStatus,
  isTrackable,
  isWithStore,
  ORDER_TYPE_LABELS,
  refundText,
  storeRejectionDetails,
} from './labels'
import { OrderPaymentPanel } from './OrderPaymentPanel'
import { OrderProgressTracker, OrderUpdatesList, PaymentStatusPill, StoreTile } from './OrderVisuals'
import { orderStatusLook } from './statusSummary'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').OrderView} OrderView */
/** @typedef {import('./statusSummary').StatusTone} StatusTone */

/** @type {Record<StatusTone, { box: string, icon: string }>} */
const TONES = {
  info: { box: 'from-sky-50 via-sky-50/50 to-white ring-sky-200/70', icon: 'ring-sky-100' },
  success: { box: 'from-emerald-50 via-emerald-50/50 to-white ring-emerald-200/70', icon: 'ring-emerald-100' },
  warning: { box: 'from-amber-50 via-amber-50/50 to-white ring-amber-200/70', icon: 'ring-amber-100' },
  danger: { box: 'from-rose-50 via-rose-50/50 to-white ring-rose-200/70', icon: 'ring-rose-100' },
  neutral: { box: 'from-slate-100/80 via-slate-50 to-white ring-slate-200/80', icon: 'ring-slate-200' },
}

const shopAgainClass =
  'inline-flex min-h-9 w-full items-center justify-center gap-2 rounded-xl px-3.5 py-1.5 text-center text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 @md:w-auto'

/**
 * A line the customer must not miss inside the status (the refund): white, on its own row.
 * @param {{ icon: IconName, children: ReactNode }} props
 */
function Callout({ icon, children }) {
  return (
    <p className="mt-3 flex items-start gap-2.5 rounded-xl bg-white/90 px-3.5 py-2.5 font-medium text-slate-800 shadow-xs ring-1 ring-slate-200/80">
      <Icon name={icon} className="mt-0.5 size-4" />
      <span className="min-w-0">{children}</span>
    </p>
  )
}

/**
 * The sentences under the status title: what happens now, why it ended, and what happens to the money.
 * @param {{ order: OrderView }} props
 */
function StatusDetails({ order }) {
  const refund = refundText(order)
  const refundLine = refund && <Callout icon="return">{refund}</Callout>
  const store = order.store.name
  const partner = order.delivery?.partnerName
  switch (order.status) {
    case 'CREATED':
      return <p>{store} is reserving your items. This usually takes a second.</p>
    case 'AWAITING_PAYMENT':
      return null
    case 'CONFIRMED':
      return (
        <>
          <p>{store} has your order and will accept it in a few minutes.</p>
          {refundLine}
        </>
      )
    case 'ACCEPTED':
      return (
        <>
          <p>
            {store} accepted your order{order.acceptedAt ? ` at ${formatTime(order.acceptedAt)}` : ''}.
            {order.readyBy ? ` It should be ready by ${formatTime(order.readyBy)}.` : ''}
          </p>
          {refundLine}
        </>
      )
    case 'PREPARING':
      return (
        <>
          <p>
            {store} is {order.type === 'GROCERY' ? 'packing your order' : 'preparing your food'}.
            {order.readyBy ? ` It should be ready by ${formatTime(order.readyBy)}.` : ''}
          </p>
          {refundLine}
        </>
      )
    case 'READY_FOR_PICKUP':
      return (
        <>
          <p>
            {order.type === 'GROCERY'
              ? 'Packed and waiting for a delivery partner.'
              : 'Your food is ready and waiting for a delivery partner.'}
          </p>
          {partner && <p>{partner} will pick it up.</p>}
          {refundLine}
        </>
      )
    case 'OUT_FOR_DELIVERY':
      return (
        <p>
          {partner ?? 'Your delivery partner'} picked up your order
          {order.delivery?.pickedUpAt ? ` at ${formatTime(order.delivery.pickedUpAt)}` : ''} and is bringing it to you.
        </p>
      )
    case 'DELIVERED':
      return (
        <>
          <p>
            {order.delivery?.deliveredAt
              ? `Delivered at ${formatTime(order.delivery.deliveredAt)}`
              : 'Your order was delivered'}
            {partner ? ` by ${partner}` : ''}. Enjoy!
          </p>
          {refundLine}
        </>
      )
    case 'DELIVERY_FAILED':
      return (
        <>
          <p>Our support team will contact you about a refund.</p>
          {refundLine}
        </>
      )
    case 'REJECTED': {
      const items = order.rejection?.unavailableItems ?? []
      return (
        <>
          {items.length > 0 && (
            <ul aria-label="Unavailable items" className="my-2 space-y-1.5">
              {items.map((item) => (
                <li
                  key={item.itemId}
                  className="flex items-start gap-2 rounded-lg bg-white/80 px-3 py-2 ring-1 ring-rose-100"
                >
                  <Icon name="box" className="mt-0.5 size-4" />
                  <span>
                    {item.name}: you asked for {item.requested},{' '}
                    {item.available > 0
                      ? `only ${item.available} ${item.available === 1 ? 'is' : 'are'} left`
                      : 'it is sold out'}
                    .
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p>Nothing was reserved and you were not charged.</p>
        </>
      )
    }
    case 'CANCELLED': {
      const cancellation = order.cancellation
      // A store rejection's note reads "<reason>: <note>"; show it in plain words.
      const storeReason = cancellation?.reason === 'STORE_REJECTED' ? storeRejectionDetails(cancellation.note) : null
      return (
        <>
          <p>{cancellation ? cancellationText(cancellation) : 'This order was cancelled.'}</p>
          {cancellation?.reason === 'STORE_REJECTED'
            ? storeReason && <p>Reason: {storeReason}</p>
            : cancellation?.note && <p className="italic">“{cancellation.note}”</p>}
          {refund ? (
            refundLine
          ) : order.payment.status === 'PAID' ? (
            <Callout icon="refresh">
              A full refund of {formatPrice(order.total)} is on its way. It usually reaches your account in 5–7 working
              days.
            </Callout>
          ) : order.payment.status === 'NOT_REQUIRED' || order.payment.status === 'EXPIRED' ? (
            <p>You were not charged.</p>
          ) : null}
        </>
      )
    }
  }
}

/**
 * "Arriving in about 7 min": the live estimate from the delivery's tracking (moves with every partner position).
 * @param {{ minutes: number }} props
 */
function EtaTile({ minutes }) {
  return (
    <p
      role="status"
      className="flex shrink-0 items-center justify-between gap-3 rounded-xl bg-white px-4 py-2.5 shadow-sm ring-1 ring-emerald-100 @xl:flex-col @xl:items-end @xl:justify-center @xl:gap-0.5 @xl:px-5 @xl:py-3"
    >
      <span className="flex items-center gap-1.5 text-xs font-semibold text-slate-500">
        <Icon name="clock" className="size-4" />
        Arriving in about
      </span>{' '}
      <span className="text-2xl leading-none font-bold tracking-tight whitespace-nowrap text-emerald-700 tabular-nums">
        {etaValueText(minutes)}
      </span>
    </p>
  )
}

/**
 * Where the order is now: one title, a sentence or two, the arrival estimate while it is on its way.
 * @param {{ order: OrderView, eta: number | null }} props
 */
function StatusBlock({ order, eta }) {
  const headingId = useId()
  const look = orderStatusLook(order)
  const tone = TONES[look.tone]
  const failed = order.status === 'CANCELLED' || order.status === 'REJECTED'
  return (
    <div className={cn('animate-fade-up rounded-2xl bg-gradient-to-br p-4 ring-1 ring-inset @md:p-5', tone.box)}>
      <div className="flex flex-col gap-3.5 @xl:flex-row @xl:items-center @xl:gap-5">
        {/* Narrow cards: the icon beside the title only, the sentences under both (full width). */}
        <div
          role="status"
          aria-labelledby={headingId}
          className="grid min-w-0 flex-1 grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3.5 gap-y-2 @md:items-start @md:gap-y-0.5"
        >
          <span
            aria-hidden="true"
            className={cn(
              'flex size-11 shrink-0 items-center justify-center rounded-2xl bg-white shadow-sm ring-1 @md:row-span-2 @md:size-12',
              tone.icon,
            )}
          >
            {look.busy ? <Spinner size="md" /> : <Icon name={look.icon} className="size-6" />}
          </span>
          <h2 id={headingId} className="text-lg leading-snug font-bold tracking-tight text-slate-900 @md:text-xl">
            {look.title}
          </h2>
          <div className="col-span-2 min-w-0 space-y-0.5 text-sm leading-relaxed text-slate-600 @md:col-span-1 @md:col-start-2">
            <StatusDetails order={order} />
          </div>
        </div>
        {eta !== null && <EtaTile minutes={eta} />}
      </div>
      {failed && (
        <div className="mt-4 @md:pl-[3.875rem]">
          <Link
            to={storePath(order.store)}
            className={cn(
              shopAgainClass,
              order.status === 'REJECTED'
                ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25 hover:bg-emerald-700'
                : 'bg-white text-slate-700 shadow-xs ring-1 ring-slate-300 ring-inset hover:bg-slate-50',
            )}
          >
            <Icon name="bag" className="size-4" />
            Shop at {order.store.name} again
          </Link>
        </div>
      )}
    </div>
  )
}

/**
 * Phase 8: customers can cancel only until the store accepts. Phase 9: nobody can after pickup.
 * @param {{ order: OrderView }} props
 */
function CannotCancelNote({ order }) {
  if (order.cancellable || isTerminalStatus(order.status)) return null
  return (
    <p className="flex min-w-0 flex-1 basis-64 items-start gap-2 text-xs leading-relaxed text-slate-500 sm:text-sm">
      <Icon name="lock" className="mt-0.5 size-4" />
      <span>
        {isPastPickup(order.status) ? (
          'Your order has been picked up and is on its way, so it can no longer be cancelled.'
        ) : (
          <>
            {isWithStore(order.status)
              ? `${order.store.name} has already accepted this order, so it can no longer be cancelled here.`
              : 'This order can no longer be cancelled here.'}{' '}
            <span className="font-medium text-slate-700">Contact support to cancel.</span>
          </>
        )}
      </span>
    </p>
  )
}

/**
 * The progress row, then "Cancel order" (or why it can no longer be cancelled) and — behind "View all updates" —
 * every status change with its time.
 * @param {{ order: OrderView, onCancel?: () => void }} props
 */
function ProgressAndUpdates({ order, onCancel }) {
  const [open, setOpen] = useState(false)
  const listId = useId()
  return (
    <div>
      <OrderProgressTracker order={order} className="px-0.5" />
      <div className="mt-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t border-slate-100 pt-3">
        {onCancel ? (
          <Button variant="secondary" size="sm" className="text-red-700 hover:text-red-800" onClick={onCancel}>
            <Icon name="xCircle" className="size-4" />
            Cancel order
          </Button>
        ) : (
          <CannotCancelNote order={order} />
        )}
        <button
          type="button"
          aria-expanded={open}
          aria-controls={listId}
          onClick={() => setOpen((value) => !value)}
          className="-mx-2 ml-auto inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg px-2 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-50 hover:text-emerald-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          {open ? 'Hide updates' : 'View all updates'}
          <span className="rounded-full bg-emerald-100 px-1.5 text-xs leading-5 text-emerald-800 tabular-nums">
            <span className="sr-only">(</span>
            {order.timeline.length}
            <span className="sr-only">)</span>
          </span>
          <Icon
            name="chevronRight"
            className={cn('size-3.5 transition-transform', open ? '-rotate-90' : 'rotate-90')}
          />
        </button>
      </div>
      <div
        id={listId}
        hidden={!open}
        className="animate-fade-in mt-3 rounded-xl bg-slate-50/70 p-3.5 ring-1 ring-slate-100 ring-inset"
      >
        {open && <OrderUpdatesList timeline={order.timeline} placedAt={order.createdAt} />}
      </div>
    </div>
  )
}

/**
 * @typedef {object} OrderStatusHeroProps
 * @property {OrderView} order
 * @property {() => void} onPaymentChange Re-read the order (after a payment, a decline or a closed window).
 * @property {() => void} [onCancel] Opens the cancel dialog; absent when the customer can no longer cancel.
 * @property {() => void} onReturn Opens the return dialog (delivered orders).
 */

/**
 * The top of the order page — "where is my order": the order number, store and time it was placed, ONE current status
 * in plain words (with the arrival estimate while a partner has it, or the payment panel while it waits for payment),
 * the progress row with times, the full history behind a disclosure and, once delivered, rate / return.
 * @param {OrderStatusHeroProps} props
 */
export function OrderStatusHero({ order, onPaymentChange, onCancel, onReturn }) {
  const titleId = useId()
  const trackable = isTrackable(order)
  const tracking = useOrderTracking(order.id, trackable)
  const eta = trackable ? liveEtaMinutes(tracking.data) : null

  return (
    <section
      aria-labelledby={titleId}
      className="@container animate-fade-up flex min-w-0 flex-col overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.04]"
    >
      <div className="flex flex-1 flex-col gap-5 p-4 sm:p-5 @2xl:p-6">
        <header className="flex flex-col gap-3 @2xl:flex-row @2xl:items-start @2xl:justify-between @2xl:gap-6">
          <div className="flex min-w-0 items-start gap-3.5">
            <StoreTile type={order.type} className="@md:size-14" />
            <div className="min-w-0">
              <h1
                id={titleId}
                className="text-xl leading-tight font-bold tracking-tight break-words text-slate-900 @md:text-2xl"
              >
                Order {order.orderNumber}
              </h1>
              <p className="mt-1 text-sm text-slate-600">
                <Link
                  to={storePath(order.store)}
                  className="rounded font-semibold text-slate-900 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
                >
                  {order.store.name}
                </Link>
                <span aria-hidden="true" className="text-slate-300">
                  {' · '}
                </span>
                <span className="sr-only">, </span>
                {ORDER_TYPE_LABELS[order.type]} order
              </p>
              <p className="mt-0.5 flex items-center gap-1.5 text-xs text-slate-500">
                <Icon name="calendar" className="size-3.5" />
                Placed {formatDateTime(order.createdAt)}
              </p>
            </div>
          </div>
          <div
            role="group"
            aria-label="Total and payment"
            className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5 border-t border-slate-100 pt-3 @2xl:shrink-0 @2xl:justify-end @2xl:border-0 @2xl:pt-1"
          >
            <p className="text-lg leading-none font-bold text-slate-900 tabular-nums @2xl:text-xl">
              <span className="sr-only">Total </span>
              {formatPrice(order.total)}
            </p>
            <span className="text-xs text-slate-500">
              {order.itemCount} item{order.itemCount === 1 ? '' : 's'}
            </span>
            <PaymentStatusPill status={order.payment.status} />
          </div>
        </header>

        {order.status === 'AWAITING_PAYMENT' ? (
          <OrderPaymentPanel order={order} onPaymentChange={onPaymentChange} />
        ) : (
          <StatusBlock order={order} eta={eta} />
        )}

        <ProgressAndUpdates order={order} onCancel={onCancel} />
      </div>
      <AfterDeliveryActions order={order} onReturn={onReturn} className="border-t border-amber-100" />
    </section>
  )
}
