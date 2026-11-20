import { useId, useState } from 'react'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCountdown, formatPrice, formatRelativeTime, formatTime } from '../../shared/utils/format'
import { READY_IN_CHOICES, STORE_KIND_CONFIG } from './api'
import {
  acceptWindowLeft,
  boardStep,
  foldItems,
  FRESH_MS,
  ITEMS_SHOWN,
  minutesLate,
  minutesUntil,
  URGENT_MS,
} from './board'
import { storeOrderActionError } from './errors'
import { useStoreOrderAction } from './hooks'
import { BOARD_COLUMNS, boardColumnStyle, storeOrderStatusLabel } from './labels'
import { PickupPartner } from './PickupPartner'

/** @typedef {import('./api').StoreDelivery} StoreDelivery */
/** @typedef {import('./api').StoreKind} StoreKind */
/** @typedef {import('./api').StoreOrderItem} StoreOrderItem */
/** @typedef {import('./api').StoreOrderView} StoreOrderView */

/**
 * @typedef {object} StoreOrderCardProps
 * @property {StoreKind} kind
 * @property {StoreOrderView} order
 * @property {number} now Reference time for the countdown (ticks every second on the board).
 * @property {(order: StoreOrderView) => void} onReject
 * @property {StoreDelivery} [delivery] Who collects this order (accepted orders); undefined while unknown.
 * @property {boolean} [saving] Its last action is still on the way to the server (the card has moved already).
 */

/**
 * @param {number} count
 * @returns {string}
 */
function minutes(count) {
  return `${count} min`
}

/**
 * New → Accepted → Preparing / Packing → Ready, as a thin bar on top of the card.
 * @param {{ kind: StoreKind, order: StoreOrderView }} props
 */
function StepBar({ kind, order }) {
  const step = boardStep(order.status)
  const style = boardColumnStyle(kind, order.status)
  const steps = BOARD_COLUMNS.map((status) => storeOrderStatusLabel(kind, status))
  return (
    <div className="mb-3" title={steps.join(' → ')}>
      <p className="sr-only">
        Step {step} of {steps.length}: {storeOrderStatusLabel(kind, order.status)}
      </p>
      <div aria-hidden="true" className="flex gap-1">
        {steps.map((label, index) => (
          <span key={label} className={cn('h-1 flex-1 rounded-full', index < step ? style.dot : 'bg-slate-200/80')} />
        ))}
      </div>
    </div>
  )
}

/**
 * Quantity pills, the first few items, and "+N more" for long orders.
 * @param {{ items: readonly StoreOrderItem[] }} props
 */
function OrderItems({ items }) {
  const [expanded, setExpanded] = useState(false)
  const { shown, hidden } = foldItems(items, expanded)
  const foldable = items.length > ITEMS_SHOWN + 1
  return (
    <div>
      <ul aria-label="Items" className="space-y-1.5">
        {shown.map((item) => (
          <li key={item.itemId} className="flex items-start gap-2 text-sm leading-5 text-slate-800">
            <span
              className={cn(
                'inline-flex h-5 min-w-7 shrink-0 items-center justify-center rounded-md px-1.5 text-xs font-bold tabular-nums',
                // More than one of an item is easy to miss: those pills are dark.
                item.quantity > 1
                  ? 'bg-slate-800 text-white'
                  : 'bg-slate-100 text-slate-700 ring-1 ring-slate-200/80 ring-inset',
              )}
            >
              {item.quantity}×
            </span>
            <span className="min-w-0 break-words text-pretty">{item.name}</span>
          </li>
        ))}
      </ul>
      {foldable && (
        <button
          type="button"
          aria-expanded={expanded}
          onClick={() => setExpanded((open) => !open)}
          className="mt-1.5 ml-9 rounded-md text-xs font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          {expanded ? 'Show fewer items' : `+${hidden} more item${hidden === 1 ? '' : 's'}`}
        </button>
      )}
    </div>
  )
}

/**
 * Time left to accept a NEW order: a big `m:ss` and a bar that drains, red under a minute, "Time is up" at the deadline.
 * @param {{ order: StoreOrderView, now: number }} props
 */
function AcceptCountdown({ order, now }) {
  const deadline = Date.parse(order.acceptBy)
  if (!Number.isFinite(deadline)) return null
  const remaining = deadline - now
  if (remaining <= 0) {
    return (
      <p className="flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2.5 text-sm font-semibold text-red-700 ring-1 ring-red-200 ring-inset">
        <Icon name="hourglass" className="mt-px size-4" />
        Time to accept is up — the order is being rejected automatically.
      </p>
    )
  }
  const urgent = remaining < URGENT_MS
  return (
    <div
      className={cn(
        'rounded-lg px-3 py-2.5 ring-1 ring-inset',
        urgent ? 'bg-red-50 ring-red-200' : 'bg-amber-50/80 ring-amber-200/70',
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <p
          className={cn(
            'flex items-center gap-1.5 text-xs font-semibold whitespace-nowrap',
            urgent ? 'text-red-700' : 'text-amber-800',
          )}
        >
          <Icon name="hourglass" className={cn('size-4', urgent && 'animate-pulse motion-reduce:animate-none')} />
          Accept within
        </p>
        <span
          role="timer"
          aria-label="Time left to accept"
          className={cn(
            'text-2xl leading-none font-bold tracking-tight tabular-nums',
            urgent ? 'text-red-700' : 'text-amber-900',
          )}
        >
          {formatCountdown(remaining)}
        </span>
      </div>
      <div aria-hidden="true" className="mt-2 h-1.5 overflow-hidden rounded-full bg-white ring-1 ring-black/5">
        <div
          className={cn(
            'h-full rounded-full transition-[width] duration-1000 ease-linear motion-reduce:transition-none',
            urgent ? 'bg-red-500' : 'bg-amber-400',
          )}
          style={{ width: `${acceptWindowLeft(order, now) * 100}%` }}
        />
      </div>
      <p className="mt-1.5 text-[11px] text-slate-600">Auto-rejects at {formatTime(order.acceptBy)}</p>
    </div>
  )
}

/**
 * "Ready by 1:06 am" and "in 18 min" (red, "3 min late" once that time has passed). In a narrow column the second part
 * moves under the first instead of breaking inside it.
 * @param {{ order: StoreOrderView, now: number }} props
 */
function ReadyByChip({ order, now }) {
  if (!order.readyBy) return null
  const late = minutesLate(order, now)
  const left = minutesUntil(order.readyBy, now)
  return (
    <p
      className={cn(
        'flex items-start gap-2 rounded-lg px-2.5 py-2 text-[13px] leading-5 ring-1 ring-inset',
        late > 0 ? 'bg-red-50 text-red-800 ring-red-200' : 'bg-slate-50 text-slate-600 ring-slate-200/70',
      )}
    >
      <Icon name="clock" className="mt-0.5 size-4" />
      <span className="flex min-w-0 flex-1 flex-wrap items-baseline justify-between gap-x-2">
        <span className="whitespace-nowrap">
          Ready by{' '}
          <span className={cn('font-semibold tabular-nums', late > 0 ? 'text-red-900' : 'text-slate-900')}>
            {formatTime(order.readyBy)}
          </span>
        </span>{' '}
        <span
          className={cn(
            'text-xs whitespace-nowrap tabular-nums',
            late > 0 ? 'font-bold text-red-700' : 'font-semibold text-slate-500',
          )}
        >
          {late > 0 ? `${minutes(late)} late` : left > 0 ? `in ${minutes(left)}` : 'due now'}
        </span>
      </span>
    </p>
  )
}

/**
 * "Ready in" as one row of quick choices (radio buttons): the chosen time is sent with Accept.
 * @param {{ value: number, onChange: (value: number) => void, disabled: boolean }} props
 */
function ReadyInChoices({ value, onChange, disabled }) {
  const name = useId()
  return (
    <fieldset disabled={disabled} className="min-w-0">
      <legend className="mb-1.5 text-xs font-semibold text-slate-600">
        Ready in <span className="font-normal text-slate-500">· minutes</span>
      </legend>
      <div className="grid grid-cols-6 gap-1">
        {READY_IN_CHOICES.map((choice) => (
          <label
            key={choice}
            className={cn(
              'flex h-8 cursor-pointer items-center justify-center rounded-md bg-white text-sm font-semibold text-slate-700 tabular-nums ring-1 ring-slate-200 transition-colors ring-inset select-none',
              'hover:bg-emerald-50 hover:ring-emerald-300',
              'has-[:checked]:bg-emerald-600 has-[:checked]:text-white has-[:checked]:shadow-sm has-[:checked]:ring-emerald-600',
              'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-emerald-600',
              'has-[:disabled]:cursor-not-allowed has-[:disabled]:opacity-50',
            )}
          >
            <input
              type="radio"
              name={name}
              value={choice}
              checked={value === choice}
              onChange={() => onChange(choice)}
              className="sr-only"
            />
            {/* "15 min" to assistive technology; the "min" shows only where the card is wide enough. */}
            {choice}{' '}
            <span className="sr-only @min-[17rem]:not-sr-only @min-[17rem]:ml-0.5 @min-[17rem]:text-xs @min-[17rem]:font-medium">
              min
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

/**
 * One order on the board: number, total, customer (name and area only), items, note, a state block — the accept
 * countdown, the promised ready time, or who collects it — and the one next step of its column: NEW: Accept with a
 * "ready in" time (or Reject) · ACCEPTED: Start preparing / packing (or skip to ready) · PREPARING: Ready for pickup ·
 * READY_FOR_PICKUP: nothing; the delivery partner takes it.
 * @param {StoreOrderCardProps} props
 */
export function StoreOrderCard({ kind, order, now, onReject, delivery, saving = false }) {
  const toast = useToast()
  const action = useStoreOrderAction(kind)
  const config = STORE_KIND_CONFIG[kind]
  const [readyIn, setReadyIn] = useState(config.defaultReadyInMinutes)
  const busy = action.isPending || saving
  const isNew = order.status === 'NEW'
  const fresh = isNew && now - Date.parse(order.receivedAt) < FRESH_MS
  const urgent = isNew && Date.parse(order.acceptBy) - now < URGENT_MS
  const late = (order.status === 'ACCEPTED' || order.status === 'PREPARING') && minutesLate(order, now) >= 1

  // mutateAsync: the card moves to another column (a new component) at once; the request settles afterwards.
  async function run(storeAction, success) {
    try {
      await action.mutateAsync({ orderId: order.orderId, action: storeAction })
      toast.success(success)
    } catch (error) {
      const failure = storeOrderActionError(kind, order, error)
      toast.show(failure.tone, failure.message, { description: failure.description })
    }
  }

  const accept = () =>
    void run(
      { type: 'accept', readyInMinutes: readyIn },
      `Order ${order.orderNumber} accepted — ready in ${minutes(readyIn)}.`,
    )
  const startPreparing = () =>
    void run({ type: 'preparing' }, `Order ${order.orderNumber}: ${config.preparingLabel.toLowerCase()} started.`)
  const markReady = () => void run({ type: 'ready' }, `Order ${order.orderNumber} is ready for pickup.`)
  const pending = action.isPending ? <Spinner size="sm" className="[&_svg]:text-white" /> : null

  return (
    <article
      aria-label={`Order ${order.orderNumber}`}
      aria-busy={busy || undefined}
      className={cn(
        'animate-pop-in @container rounded-xl border bg-white p-3 shadow-sm shadow-slate-900/[0.04] transition-shadow hover:shadow-md',
        isNew
          ? urgent
            ? 'border-red-300 ring-2 ring-red-100'
            : 'border-amber-300 ring-2 ring-amber-100'
          : late
            ? 'border-red-200'
            : 'border-slate-200/80',
      )}
    >
      <StepBar kind={kind} order={order} />

      <div className="space-y-2.5">
        <header className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="text-[15px] leading-tight font-bold tracking-tight break-all text-slate-900">
              {order.orderNumber}
            </h3>
            <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-xs text-slate-500">
              {saving ? (
                <span role="status" className="inline-flex items-center gap-1 font-semibold text-slate-700">
                  <Spinner size="sm" />
                  Saving…
                </span>
              ) : fresh ? (
                <span className="inline-flex items-center gap-1 rounded-full bg-amber-500 px-1.5 text-[11px] leading-4 font-semibold text-white">
                  <span aria-hidden="true" className="size-1.5 animate-pulse rounded-full bg-white" />
                  Just in
                </span>
              ) : (
                <span className="whitespace-nowrap">
                  <span className="sr-only">Received </span>
                  {formatRelativeTime(order.receivedAt, now).toLowerCase()}
                </span>
              )}
              <span aria-hidden="true">·</span>
              <time dateTime={order.receivedAt} className="whitespace-nowrap tabular-nums">
                {formatTime(order.receivedAt)}
              </time>
            </p>
          </div>
          <div className="shrink-0 text-right">
            <p className="text-[15px] leading-tight font-bold text-slate-900 tabular-nums">
              {formatPrice(order.subtotal, order.currency)}
            </p>
            <p className="mt-1 text-xs whitespace-nowrap text-slate-500">
              {order.itemCount} item{order.itemCount === 1 ? '' : 's'}
            </p>
          </div>
        </header>

        {(order.customer?.name || order.customer?.area) && (
          <p className="flex items-start gap-1.5 text-xs text-slate-600">
            <Icon name="user" className="mt-px size-3.5" />
            {/* Name and area wrap as whole parts in a narrow column. */}
            <span className="min-w-0">
              <span className="sr-only">Customer: </span>
              {order.customer.name && (
                <span className="inline-block font-medium text-slate-700">{order.customer.name}</span>
              )}
              {order.customer.name && order.customer.area && ' · '}
              {order.customer.area && <span className="inline-block">{order.customer.area}</span>}
            </span>
          </p>
        )}

        <OrderItems items={order.items} />

        {order.note && (
          <p className="flex items-start gap-2 rounded-md border-l-[3px] border-amber-400 bg-amber-50 py-1.5 pr-2.5 pl-2 text-[13px] leading-snug text-amber-950">
            <Icon name="note" className="mt-px size-3.5" />
            <span className="min-w-0 break-words">
              <span className="font-semibold">Note:</span> {order.note}
            </span>
          </p>
        )}
      </div>

      <div className="mt-3 space-y-2.5 border-t border-slate-100 pt-3">
        {isNew && (
          <>
            <AcceptCountdown order={order} now={now} />
            <ReadyInChoices value={readyIn} onChange={setReadyIn} disabled={busy} />
            <div className="flex flex-col gap-2 @min-[17rem]:flex-row">
              <Button onClick={accept} disabled={busy} className="w-full whitespace-nowrap @min-[17rem]:flex-1">
                {pending ?? <Icon name="check" className="size-4" />}
                Accept <span className="font-medium tabular-nums opacity-85">· {minutes(readyIn)}</span>
              </Button>
              <button
                type="button"
                onClick={() => onReject(order)}
                disabled={busy}
                className="inline-flex h-9 w-full items-center justify-center gap-1.5 rounded-xl bg-white px-3 text-sm font-semibold whitespace-nowrap text-red-700 shadow-xs ring-1 ring-slate-300 transition-colors ring-inset hover:bg-red-50 hover:ring-red-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-600 disabled:cursor-not-allowed disabled:opacity-50 @min-[17rem]:h-10 @min-[17rem]:w-auto @min-[17rem]:px-4"
              >
                <Icon name="close" className="size-3.5" />
                Reject
              </button>
            </div>
          </>
        )}

        {(order.status === 'ACCEPTED' || order.status === 'PREPARING') && (
          <>
            <ReadyByChip order={order} now={now} />
            <PickupPartner delivery={delivery} now={now} />
          </>
        )}

        {order.status === 'ACCEPTED' && (
          <div className="space-y-1">
            <Button onClick={startPreparing} disabled={busy} className="w-full whitespace-nowrap">
              {pending ?? <Icon name="arrowRight" className="size-4" />}
              {config.startPreparingLabel}
            </Button>
            <button
              type="button"
              onClick={markReady}
              disabled={busy}
              title={`Mark it ready for pickup without ${config.preparingLabel.toLowerCase()} first — for small orders`}
              className="flex h-8 w-full items-center justify-center gap-1 rounded-lg text-[13px] font-semibold whitespace-nowrap text-slate-600 transition-colors hover:bg-slate-50 hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Skip to ready
              <Icon name="chevronRight" className="size-3" />
            </button>
          </div>
        )}

        {order.status === 'PREPARING' && (
          <Button onClick={markReady} disabled={busy} className="w-full whitespace-nowrap">
            {pending ?? <Icon name="check" className="size-4" />}
            Ready for pickup
          </Button>
        )}

        {order.status === 'READY_FOR_PICKUP' && (
          <>
            <p className="flex items-center gap-1.5 text-xs text-emerald-800">
              <Icon name="checkCircle" className="size-4" />
              <span className="flex min-w-0 flex-1 flex-wrap items-baseline justify-between gap-x-2">
                <span className="font-semibold whitespace-nowrap">
                  Ready{order.readyAt ? ` since ${formatTime(order.readyAt)}` : ''}
                </span>{' '}
                {order.readyAt && (
                  <span className="whitespace-nowrap text-slate-500">
                    {formatRelativeTime(order.readyAt, now).toLowerCase()}
                  </span>
                )}
              </span>
            </p>
            <PickupPartner delivery={delivery} now={now} variant="full" />
          </>
        )}
      </div>
    </article>
  )
}
