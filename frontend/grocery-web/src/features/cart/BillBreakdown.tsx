import type { ReactNode } from 'react'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDistanceKm, formatPrice } from '../../shared/utils/format'
import type { CartCoupon, DeliveryQuote } from './api'
import { itemsLabel } from './format'
import type { FreeDeliveryProgress, MinimumOrderGap } from './summary'

/** The bill of the cart page and the checkout: the same rows, nudges and total on both. */

function Meter({ percent, tone }: { percent: number; tone: 'green' | 'amber' }) {
  return (
    <div aria-hidden="true" className={cn('mt-2 h-1.5 overflow-hidden rounded-full', tone === 'green' ? 'bg-emerald-100' : 'bg-amber-100')}>
      <div
        className={cn(
          'h-full rounded-full transition-[width] duration-500 motion-reduce:transition-none',
          tone === 'green' ? 'bg-gradient-to-r from-emerald-400 to-emerald-600' : 'bg-gradient-to-r from-amber-300 to-amber-500',
        )}
        style={{ width: `${percent}%` }}
      />
    </div>
  )
}

/** "₹434 of ₹499" under a meter, with an optional link on the right ("Add items"). */
function MeterCaption({ current, target, action, className }: { current: number; target: number; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('mt-1.5 flex items-center justify-between gap-3 text-xs', className)}>
      <span className="tabular-nums">
        {formatPrice(Math.round(current * 100) / 100)} of {formatPrice(target)}
      </span>
      {action}
    </div>
  )
}

/** "Add ₹65 more for free delivery" with a progress bar, or "Free delivery unlocked" once the threshold is reached. */
export function FreeDeliveryMeter({ progress, action }: { progress: FreeDeliveryProgress; action?: ReactNode }) {
  if (progress.kind === 'reached') {
    return (
      <p className="flex items-center gap-2 rounded-xl bg-emerald-50/80 px-3 py-2.5 text-sm font-medium text-emerald-800 ring-1 ring-emerald-100 ring-inset">
        <Icon name="truck" className="size-4.5" />
        <span className="min-w-0">Free delivery unlocked on this order</span>
      </p>
    )
  }
  return (
    <div className="rounded-xl bg-gradient-to-r from-emerald-50 to-teal-50/50 px-3 py-2.5 ring-1 ring-emerald-100 ring-inset">
      <p className="flex items-start gap-2 text-sm font-medium text-emerald-900">
        <Icon name="truck" className="size-4.5" />
        <span className="min-w-0">Add {formatPrice(progress.shortfall)} more for free delivery</span>
      </p>
      <Meter percent={progress.percent} tone="green" />
      <MeterCaption current={progress.freeAbove - progress.shortfall} target={progress.freeAbove} action={action} className="text-emerald-800/80" />
    </div>
  )
}

/** "Add ₹31 more to reach the ₹99 minimum order" with a progress bar (the order cannot be placed below it). */
export function MinimumOrderMeter({ gap, action }: { gap: MinimumOrderGap; action?: ReactNode }) {
  return (
    <div className="rounded-xl bg-gradient-to-r from-amber-50 to-orange-50/50 px-3 py-2.5 ring-1 ring-amber-200/70 ring-inset">
      <p className="flex items-start gap-2 text-sm font-medium text-amber-900">
        <Icon name="bag" className="size-4.5" />
        <span className="min-w-0">
          Add {formatPrice(gap.shortfall)} more to reach the {formatPrice(gap.minimum)} minimum order
        </span>
      </p>
      <Meter percent={gap.percent} tone="amber" />
      <MeterCaption current={gap.minimum - gap.shortfall} target={gap.minimum} action={action} className="text-amber-900/75" />
    </div>
  )
}

function Row({ label, hint, value, saving = false }: { label: ReactNode; hint?: ReactNode; value: ReactNode; saving?: boolean }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <dt className={cn('min-w-0', saving ? 'text-emerald-700' : 'text-slate-600')}>
        {label}
        {hint && <span className="mt-0.5 block text-xs text-slate-400">{hint}</span>}
      </dt>
      <dd className={cn('shrink-0 text-right tabular-nums', saving ? 'font-semibold text-emerald-700' : 'font-medium text-slate-900')}>{value}</dd>
    </div>
  )
}

/** Why the delivery fee is what it is, in a few words. */
function deliveryHint(delivery: DeliveryQuote): string {
  if (delivery.freeAbove !== null) return `Free on orders above ${formatPrice(delivery.freeAbove)}`
  return 'Based on the distance to you'
}

interface BillRowsProps {
  itemCount: number
  subtotal: number
  /** The items at MRP: struck through before the item total when it is higher. */
  mrpTotal: number
  /** The quote for the chosen address; null without a usable address. */
  delivery: DeliveryQuote | null
  coupon?: CartCoupon | null
  discount?: number
  deliveryFeeDiscount?: number
  /** Under "Delivery fee" while there is no quote ("Choose a delivery address to see the fee"). */
  noDeliveryHint?: string
}

/** Item total (MRP struck through), delivery fee (distance, why, free) and the coupon's discounts. */
export function BillRows({ itemCount, subtotal, mrpTotal, delivery, coupon, discount = 0, deliveryFeeDiscount = 0, noDeliveryHint }: BillRowsProps) {
  const distance = formatDistanceKm(delivery?.distanceKm)
  const couponLabel = coupon ? `Coupon ${coupon.code}` : 'Coupon'
  return (
    <dl className="space-y-3 text-sm">
      <Row
        label={`Item total (${itemsLabel(itemCount)})`}
        value={
          <>
            {mrpTotal > subtotal && (
              <s className="mr-1.5 text-xs font-normal text-slate-400">
                <span className="sr-only">MRP </span>
                {formatPrice(mrpTotal)}
              </s>
            )}
            {formatPrice(subtotal)}
          </>
        }
      />
      <Row
        label={
          <>
            Delivery fee
            {distance && <span className="text-slate-400"> · {distance}</span>}
          </>
        }
        hint={delivery ? deliveryHint(delivery) : noDeliveryHint}
        value={
          delivery ? (
            delivery.fee === 0 ? (
              <span className="font-semibold text-emerald-700">Free</span>
            ) : (
              formatPrice(delivery.fee)
            )
          ) : (
            <span className="text-slate-400">—</span>
          )
        }
      />
      {discount > 0 && <Row label={`${couponLabel} discount`} value={`−${formatPrice(discount)}`} saving />}
      {deliveryFeeDiscount > 0 && <Row label={`${couponLabel}: free delivery`} value={`−${formatPrice(deliveryFeeDiscount)}`} saving />}
    </dl>
  )
}

/** "Total ₹616 · Inclusive of all taxes". */
export function BillTotal({ total, label = 'Total', note = 'Inclusive of all taxes' }: { total: number; label?: string; note?: string }) {
  return (
    <div className="border-t border-dashed border-slate-200 pt-4">
      <div className="flex items-baseline justify-between gap-4">
        <span className="text-base font-semibold text-slate-900">{label}</span>
        <span className="text-2xl font-bold tracking-tight text-slate-900 tabular-nums">{formatPrice(total)}</span>
      </div>
      <p className="mt-0.5 text-xs text-slate-500">{note}</p>
    </div>
  )
}

/** "You save ₹117 on this order" (MRP, coupon and a waived delivery fee together). */
export function SavingsNote({ amount, className }: { amount: number; className?: string }) {
  if (amount <= 0) return null
  return (
    <p
      className={cn(
        'flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-50 to-amber-50 px-3 py-2.5 text-sm font-semibold text-emerald-800 ring-1 ring-emerald-100 ring-inset',
        className,
      )}
    >
      <Icon name="sparkle" className="size-4.5" />
      You save {formatPrice(amount)} on this order
    </p>
  )
}
