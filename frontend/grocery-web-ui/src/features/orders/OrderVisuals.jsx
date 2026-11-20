import { useId } from 'react'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Badge } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatPrice, formatTime } from '../../shared/utils/format'
import { ProductImage } from '../catalog/ProductImage'
import {
  ORDER_PAYMENT_STATUS_LABELS,
  ORDER_PAYMENT_STATUS_TONES,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  timelineReasonText,
} from './labels'
import { ORDER_STATUS_ICONS, orderProgress, PAYMENT_STATUS_ICONS } from './progress'
import { updateNeedsDate } from './statusSummary'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').OrderItem} OrderItem */
/** @typedef {import('./api').OrderPaymentStatus} OrderPaymentStatus */
/** @typedef {import('./api').OrderStatus} OrderStatus */
/** @typedef {import('./api').OrderTimelineEntry} OrderTimelineEntry */
/** @typedef {import('./api').OrderType} OrderType */
/** @typedef {import('./api').OrderView} OrderView */
/** @typedef {import('./progress').ProgressState} ProgressState */

/** Customer-facing pieces of the orders screens: status pills with icons, the store tile, progress, updates, lines. */

/** @param {{ status: OrderStatus, className?: string }} props */
export function OrderStatusPill({ status, className }) {
  return (
    <Badge tone={ORDER_STATUS_TONES[status]} className={cn('gap-1 px-2.5 py-1 font-semibold', className)}>
      <Icon name={ORDER_STATUS_ICONS[status]} className="size-3.5" />
      {ORDER_STATUS_LABELS[status]}
    </Badge>
  )
}

/** @param {{ status: OrderPaymentStatus, className?: string }} props */
export function PaymentStatusPill({ status, className }) {
  return (
    <Badge tone={ORDER_PAYMENT_STATUS_TONES[status]} className={cn('gap-1 px-2.5 py-1', className)}>
      <Icon name={PAYMENT_STATUS_ICONS[status]} className="size-3.5" />
      {ORDER_PAYMENT_STATUS_LABELS[status]}
    </Badge>
  )
}

const STORE_TILE_SIZES = {
  sm: { tile: 'size-10 rounded-xl', icon: 'size-5' },
  md: { tile: 'size-12 rounded-2xl', icon: 'size-6' },
  lg: { tile: 'size-14 rounded-2xl', icon: 'size-7' },
}

/**
 * A rounded tile with a shopping bag (grocery store) or cutlery (restaurant). `sm` for dense list rows.
 * @param {{ type: OrderType, size?: 'sm' | 'md' | 'lg', className?: string }} props
 */
export function StoreTile({ type, size = 'md', className }) {
  const restaurant = type === 'RESTAURANT'
  return (
    <span
      aria-hidden="true"
      className={cn(
        'flex shrink-0 items-center justify-center ring-1 ring-inset',
        restaurant
          ? 'bg-gradient-to-br from-amber-50 to-orange-100 ring-amber-200/70'
          : 'bg-gradient-to-br from-emerald-50 to-emerald-100 ring-emerald-200/70',
        STORE_TILE_SIZES[size].tile,
        className,
      )}
    >
      <Icon name={restaurant ? 'cutlery' : 'bag'} className={STORE_TILE_SIZES[size].icon} />
    </span>
  )
}

/**
 * Light nodes so the colourful step icons stay readable; steps not reached yet show their icon greyed out.
 * @type {Record<ProgressState, string>}
 */
const NODE_CLASSES = {
  done: 'bg-emerald-50 ring-2 ring-emerald-500',
  current: 'bg-white ring-2 ring-emerald-500 shadow-md shadow-emerald-600/25',
  upcoming: 'bg-slate-100 ring-1 ring-slate-200',
  failed: 'bg-rose-50 ring-2 ring-rose-500 shadow-sm shadow-rose-600/20',
}

/** @type {Record<ProgressState, string>} */
const LABEL_CLASSES = {
  done: 'text-slate-800',
  current: 'text-emerald-700',
  upcoming: 'text-slate-400',
  failed: 'text-rose-700',
}

/** @type {Record<ProgressState, string>} */
const STATE_TEXT = {
  done: 'done',
  current: 'in progress',
  upcoming: 'not yet',
  failed: 'the order ended here',
}

/**
 * Placed → Confirmed → Packing / Preparing → On the way → Delivered, or how the order ended (from its status): one
 * compact row of steps with the time each was reached.
 * @param {{ order: Pick<OrderView, 'status' | 'type' | 'timeline' | 'updatedAt'>, className?: string }} props
 */
export function OrderProgressTracker({ order, className }) {
  const steps = orderProgress(order)
  return (
    <ol aria-label="Order progress" className={cn('flex items-start', className)}>
      {steps.map((step, index) => (
        <li
          key={step.key}
          aria-current={step.state === 'current' ? 'step' : undefined}
          className="relative flex min-w-0 flex-1 flex-col items-center text-center"
        >
          {index > 0 && (
            // From the previous node to this one, with a little air on both sides.
            <span
              aria-hidden="true"
              className={cn(
                'absolute top-[1.125rem] right-[calc(50%+1.375rem)] left-[calc(-50%+1.375rem)] h-1 -translate-y-1/2 rounded-full',
                step.state === 'failed'
                  ? 'bg-rose-200'
                  : step.state === 'current'
                    ? 'bg-gradient-to-r from-emerald-500 to-emerald-300'
                    : step.state === 'done'
                      ? 'bg-emerald-500'
                      : 'bg-slate-200',
              )}
            />
          )}
          <span
            className={cn(
              'relative flex size-9 items-center justify-center rounded-full transition-colors',
              NODE_CLASSES[step.state],
            )}
          >
            {step.state === 'current' && (
              <span
                aria-hidden="true"
                className="absolute inset-0 animate-ping rounded-full bg-emerald-400/30 motion-reduce:hidden"
              />
            )}
            <Icon
              name={step.icon}
              className={cn('relative size-[1.1rem]', step.state === 'upcoming' && 'opacity-45 grayscale')}
            />
            {step.state === 'done' && (
              <span
                aria-hidden="true"
                className="absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full bg-emerald-500 text-white ring-2 ring-white"
              >
                <Icon name="check" className="size-2.5" />
              </span>
            )}
          </span>
          <span
            className={cn('mt-2 px-0.5 text-[11px] leading-tight font-semibold sm:text-xs', LABEL_CLASSES[step.state])}
          >
            {step.label}
            <span className="sr-only"> ({STATE_TEXT[step.state]})</span>
          </span>
          {step.at && (
            <time dateTime={step.at} className="mt-0.5 text-[11px] leading-tight text-slate-500 tabular-nums">
              {formatTime(step.at)}
            </time>
          )}
        </li>
      ))}
    </ol>
  )
}

/**
 * Every status change of the order, oldest first, with why it happened when there is a reason (the details behind
 * "View all updates" on the order page). Times only, plus the date for changes on a later day than the order.
 * @param {{ timeline: readonly OrderTimelineEntry[], placedAt: string }} props
 */
export function OrderUpdatesList({ timeline, placedAt }) {
  if (timeline.length === 0) return <p className="text-sm text-slate-500">No status changes yet.</p>
  return (
    <ol aria-label="Status history">
      {timeline.map((entry, index) => {
        const latest = index === timeline.length - 1
        return (
          <li key={`${entry.status}-${entry.at}-${index}`} className="relative flex gap-3 pb-3 last:pb-0">
            {!latest && (
              <span aria-hidden="true" className="absolute top-8 bottom-0 left-[0.9375rem] w-px bg-slate-200" />
            )}
            <span
              aria-hidden="true"
              className={cn(
                'relative flex size-[1.875rem] shrink-0 items-center justify-center rounded-full ring-1 ring-inset',
                latest ? 'bg-emerald-50 ring-emerald-200' : 'bg-slate-50 ring-slate-200',
              )}
            >
              <Icon name={ORDER_STATUS_ICONS[entry.status]} className="size-4" />
            </span>
            <div className="min-w-0 flex-1 pt-1">
              <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                <p className={cn('text-sm font-semibold', latest ? 'text-slate-900' : 'text-slate-700')}>
                  {ORDER_STATUS_LABELS[entry.status]}
                </p>
                <time dateTime={entry.at} className="text-xs text-slate-500 tabular-nums">
                  {updateNeedsDate(entry, placedAt) ? formatDateTime(entry.at) : formatTime(entry.at)}
                </time>
              </div>
              {entry.reason && <p className="mt-0.5 text-sm text-slate-600">{timelineReasonText(entry.reason)}</p>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

/**
 * @typedef {object} PanelProps
 * @property {string} title
 * @property {IconName} icon
 * @property {ReactNode} [aside] Next to the title (a count, a link).
 * @property {ReactNode} children
 * @property {string} [className]
 */

/**
 * A white card with an icon and an `h2` title — the sections of the order page.
 * @param {PanelProps} props
 */
export function Panel({ title, icon, aside, children, className }) {
  const headingId = useId()
  return (
    <section
      aria-labelledby={headingId}
      className={cn(
        'rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm shadow-slate-900/[0.03] sm:p-5',
        className,
      )}
    >
      <header className="mb-4 flex items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50 ring-1 ring-emerald-100 ring-inset"
        >
          <Icon name={icon} className="size-[1.15rem]" />
        </span>
        <h2 id={headingId} className="min-w-0 flex-1 text-base font-semibold text-slate-900">
          {title}
        </h2>
        {aside && <div className="shrink-0 text-sm">{aside}</div>}
      </header>
      {children}
    </section>
  )
}

/**
 * The order's lines with their photos, as priced when it was placed (customer order page): tiles, two side by side
 * once the surrounding container (the items card) is wide enough.
 * @param {{ items: readonly OrderItem[] }} props
 */
export function OrderLinesList({ items }) {
  return (
    <ul aria-label="Ordered items" className={cn('grid gap-2.5', items.length > 1 && '@2xl:grid-cols-2')}>
      {items.map((line) => {
        const saved = line.mrp !== null && line.mrp > line.unitPrice ? (line.mrp - line.unitPrice) * line.quantity : 0
        return (
          <li
            key={`${line.type}-${line.itemId}`}
            className="flex min-w-0 items-center gap-3 rounded-xl bg-slate-50/60 p-2.5 ring-1 ring-slate-200/60 ring-inset sm:gap-4"
          >
            <ProductImage
              src={line.imageUrl}
              alt=""
              className="size-14 shrink-0 rounded-lg bg-white ring-1 ring-slate-200/70 sm:size-16"
              iconClassName="size-6"
            />
            <div className="min-w-0 flex-1">
              <p className="flex items-start gap-1.5 text-sm font-semibold text-slate-900">
                <FoodTypeMark foodType={line.foodType} className="mt-0.5" />
                <span className="break-words">{line.name}</span>
              </p>
              <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
                <span className="rounded-md bg-slate-100 px-1.5 py-0.5 font-semibold text-slate-700 tabular-nums">
                  × {line.quantity}
                </span>
                {line.unitLabel && <span>{line.unitLabel}</span>}
                <span className="tabular-nums">
                  {formatPrice(line.unitPrice)} each
                  {line.mrp !== null && line.mrp > line.unitPrice && (
                    <span className="ml-1 text-slate-400 line-through">
                      <span className="sr-only">MRP </span>
                      {formatPrice(line.mrp)}
                    </span>
                  )}
                </span>
              </p>
            </div>
            <div className="shrink-0 pr-1 text-right">
              <p className="text-sm font-bold text-slate-900 tabular-nums">{formatPrice(line.lineTotal)}</p>
              {saved > 0 && (
                <p className="mt-0.5 text-xs font-medium text-emerald-700 tabular-nums">Saved {formatPrice(saved)}</p>
              )}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
