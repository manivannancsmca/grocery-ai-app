/** My orders → orders still in progress: a card per order with its progress, payment timer and next action. */

import { useId } from 'react'
import { Link } from 'react-router'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCountdown, formatDateTime, formatPrice, formatRelativeTime } from '../../shared/utils/format'
import { activeOrderLine, exactPaymentDueAt, orderNextAction, summaryProgress } from './ordersListView'
import { PaymentStatusPill, StoreTile } from './OrderVisuals'
import { LINK_FOCUS, orderDescription, orderPath } from './orderListHelpers'
import { ItemsLine } from './OrderListParts'

/** @typedef {import('./ordersListView').OrderActionKind} OrderActionKind */
/** @typedef {import('./api').OrderSummary} OrderSummary */
/** @typedef {import('./statusSummary').StatusTone} StatusTone */

/** @type {Record<StatusTone, { card: string, bar: string, text: string, icon: string, segment: string }>} */
const TONES = {
  info: {
    card: 'ring-sky-200/80 hover:ring-sky-300',
    bar: 'from-sky-400 to-blue-500',
    text: 'text-sky-800',
    icon: 'ring-sky-100',
    segment: 'bg-sky-400',
  },
  success: {
    card: 'ring-emerald-200/80 hover:ring-emerald-300',
    bar: 'from-emerald-400 to-teal-500',
    text: 'text-emerald-800',
    icon: 'ring-emerald-100',
    segment: 'bg-emerald-400',
  },
  warning: {
    card: 'ring-amber-200 hover:ring-amber-300',
    bar: 'from-amber-400 to-orange-500',
    text: 'text-amber-800',
    icon: 'ring-amber-100',
    segment: 'bg-amber-400',
  },
  danger: {
    card: 'ring-rose-200 hover:ring-rose-300',
    bar: 'from-rose-400 to-red-500',
    text: 'text-rose-700',
    icon: 'ring-rose-100',
    segment: 'bg-rose-400',
  },
  neutral: {
    card: 'ring-slate-200 hover:ring-slate-300',
    bar: 'from-slate-300 to-slate-400',
    text: 'text-slate-800',
    icon: 'ring-slate-200',
    segment: 'bg-slate-400',
  },
}

/** @type {Record<OrderActionKind, string>} */
const ACTIONS = {
  pay: 'bg-amber-500 text-white shadow-sm shadow-amber-500/30 group-hover:bg-amber-600',
  track: 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25 group-hover:bg-emerald-700',
  view: 'bg-slate-100 text-slate-700 group-hover:bg-emerald-50 group-hover:text-emerald-800',
}

/**
 * Five slim segments, with the step names on wider cards (decorative: the card says where the order is in words).
 * @param {{ order: OrderSummary, tone: StatusTone }} props
 */
function MiniProgress({ order, tone }) {
  const steps = summaryProgress(order)
  return (
    <div aria-hidden="true">
      <div className="grid grid-cols-5 gap-1">
        {steps.map((step) => (
          <span
            key={step.key}
            className={cn(
              'h-1.5 rounded-full',
              step.state === 'done' && 'bg-emerald-500',
              step.state === 'current' && cn(TONES[tone].segment, 'animate-pulse motion-reduce:animate-none'),
              step.state === 'upcoming' && 'bg-slate-200',
            )}
          />
        ))}
      </div>
      {/* The step names only where the card has room for them (the card says the current one in words). */}
      <div className="mt-1.5 hidden grid-cols-5 gap-1 text-[11px] leading-tight font-medium @sm:grid">
        {steps.map((step) => (
          <span
            key={step.key}
            className={cn(
              'truncate',
              step.state === 'done' && 'text-slate-600',
              step.state === 'current' && cn('font-bold', TONES[tone].text),
              step.state === 'upcoming' && 'text-slate-400',
            )}
          >
            {step.label}
          </span>
        ))}
      </div>
    </div>
  )
}

/**
 * "⏱ 7:30": the live countdown to the payment due time, red in the last 3 minutes (as on the order page).
 * @param {{ remainingMs: number }} props
 */
function PaymentTimer({ remainingMs }) {
  const urgent = remainingMs < 3 * 60_000
  return (
    <p
      className={cn(
        'ml-auto inline-flex shrink-0 items-center gap-1.5 rounded-xl bg-white px-2.5 py-1.5 shadow-xs ring-1 ring-inset',
        urgent ? 'text-rose-700 ring-rose-200' : 'text-amber-900 ring-amber-200',
      )}
    >
      <Icon name="clock" className={cn('size-4', urgent && 'animate-pulse motion-reduce:animate-none')} />
      <span
        role="timer"
        aria-label="Time left to pay"
        className="font-mono text-base leading-none font-bold tabular-nums"
      >
        {formatCountdown(remainingMs)}
      </span>
    </p>
  )
}

/**
 * An order under way: the store, what is happening now, a small progress bar and the next step (pay / track).
 * @param {{ order: OrderSummary, now: number }} props
 */
function ActiveOrderCard({ order, now: pageNow }) {
  const detailsId = useId()
  const dueAt = exactPaymentDueAt(order)
  // With the server's payment due time the card keeps its own clock, ticking every second (the page's moves every
  // 30 s): only the few cards waiting for payment re-render that often.
  const clock = useNow(1_000, dueAt !== null)
  const now = Math.max(pageNow, clock)
  const line = activeOrderLine(order, now)
  const action = orderNextAction(order, now)
  const paymentTimeLeft = action.kind === 'pay' && dueAt !== null ? dueAt - now : 0
  const tone = TONES[line.tone]
  const placed = formatRelativeTime(order.createdAt, now)
  return (
    <li className="@container min-w-0">
      <Link
        to={orderPath(order)}
        aria-label={`Order ${order.orderNumber} from ${order.store.name}`}
        aria-describedby={detailsId}
        className={cn(
          'group relative flex h-full flex-col gap-4 overflow-hidden rounded-2xl bg-white p-4 pt-5 shadow-sm ring-1 shadow-slate-900/[0.05] transition ring-inset',
          'hover:-translate-y-0.5 hover:shadow-md motion-reduce:hover:translate-y-0',
          LINK_FOCUS,
          tone.card,
        )}
      >
        <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', tone.bar)} />
        <span id={detailsId} className="sr-only">
          {orderDescription(order, line.title)}
        </span>

        <div className="flex items-start gap-3">
          <StoreTile type={order.type} />
          <div className="min-w-0 flex-1">
            {/* The total sits on the name's line, so the lines under it have the card's full width. */}
            <div className="flex items-baseline justify-between gap-3">
              <p className="truncate font-semibold text-slate-900 group-hover:text-emerald-800">{order.store.name}</p>
              <p className="shrink-0 text-lg leading-tight font-bold text-slate-900 tabular-nums">
                {formatPrice(order.total)}
              </p>
            </div>
            <ItemsLine order={order} />
            <p className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-1.5 text-xs text-slate-500">
              <span className="font-mono">{order.orderNumber}</span>
              <span aria-hidden="true">·</span>
              <span className="whitespace-nowrap">
                Placed{' '}
                <time dateTime={order.createdAt} title={formatDateTime(order.createdAt)}>
                  {placed.charAt(0).toLowerCase() + placed.slice(1)}
                </time>
              </span>
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <span
            aria-hidden="true"
            className={cn(
              'flex size-10 shrink-0 items-center justify-center rounded-full bg-white shadow-xs ring-1',
              tone.icon,
            )}
          >
            <Icon name={line.icon} className="size-5" />
          </span>
          <div className="min-w-0 flex-1">
            <p className={cn('text-base leading-snug font-bold', tone.text)}>{line.title}</p>
            {line.hint && <p className="text-sm leading-snug text-slate-600">{line.hint}</p>}
          </div>
          {paymentTimeLeft > 0 && <PaymentTimer remainingMs={paymentTimeLeft} />}
        </div>

        <MiniProgress order={order} tone={line.tone} />

        <div className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 pt-3">
          <PaymentStatusPill status={order.payment.status} />
          <span
            className={cn(
              'ml-auto inline-flex h-9 items-center gap-1.5 rounded-xl pr-3 pl-3.5 text-sm font-semibold transition-colors',
              ACTIONS[action.kind],
            )}
          >
            {action.label}
            <Icon
              name="chevronRight"
              className="size-4 transition-transform group-hover:translate-x-0.5 motion-reduce:transition-none"
            />
          </span>
        </div>
      </Link>
    </li>
  )
}

/** "● Live": the strip follows the live stream (without it the list re-reads itself every 30 seconds). */
function LiveBadge() {
  return (
    <span
      title="Status changes show up here as they happen"
      className="inline-flex items-center gap-1.5 rounded-full bg-white px-2.5 py-1 text-xs font-semibold text-emerald-700 shadow-xs ring-1 ring-emerald-200 ring-inset"
    >
      <span aria-hidden="true" className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75 motion-reduce:hidden" />
        <span className="relative inline-flex size-2 rounded-full bg-emerald-500" />
      </span>
      Live
    </span>
  )
}

/**
 * The highlighted strip of orders under way: one card each, two or three across on wide screens.
 * @param {{ orders: readonly OrderSummary[], now: number, live: boolean }} props
 */
export function ActiveOrders({ orders, now, live }) {
  const headingId = useId()
  return (
    <div className="@container rounded-3xl bg-gradient-to-br from-emerald-50 via-white to-sky-50/80 p-3 ring-1 ring-emerald-100 ring-inset sm:p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2 px-1">
        <h2 id={headingId} className="flex items-center gap-2 text-base font-bold text-slate-900">
          <Icon name="pulse" className="size-5" />
          In progress
          <span
            aria-hidden="true"
            className="rounded-full bg-emerald-600 px-2 py-0.5 text-xs font-bold text-white tabular-nums"
          >
            {orders.length}
          </span>
        </h2>
        {live && <LiveBadge />}
      </div>
      <ul aria-label="Orders in progress" className="grid gap-3 @2xl:grid-cols-2 @6xl:grid-cols-3">
        {orders.map((order) => (
          <ActiveOrderCard key={order.id} order={order} now={now} />
        ))}
      </ul>
    </div>
  )
}
