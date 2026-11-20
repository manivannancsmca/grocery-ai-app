import type { ReactNode } from 'react'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Badge } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatDistanceKm, formatPrice } from '../../shared/utils/format'
import { ProductImage } from '../catalog/ProductImage'
import type { OrderAddress, OrderItem, OrderPaymentStatus, OrderStatus, OrderTimelineEntry, OrderView } from './api'
import {
  ORDER_PAYMENT_STATUS_LABELS,
  ORDER_PAYMENT_STATUS_TONES,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TONES,
  timelineReasonText,
} from './labels'

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return (
    <Badge tone={ORDER_STATUS_TONES[status]} dot>
      {ORDER_STATUS_LABELS[status]}
    </Badge>
  )
}

export function OrderPaymentBadge({ status }: { status: OrderPaymentStatus }) {
  return <Badge tone={ORDER_PAYMENT_STATUS_TONES[status]}>{ORDER_PAYMENT_STATUS_LABELS[status]}</Badge>
}

/** The order's lines as they were priced when it was placed. */
export function OrderItemsList({ items, money = formatPrice }: { items: readonly OrderItem[]; money?: (value: number) => string }) {
  return (
    <ul aria-label="Ordered items" className="divide-y divide-slate-100">
      {items.map((line) => (
        <li key={`${line.type}-${line.itemId}`} className="flex gap-3 py-3 first:pt-0 last:pb-0">
          <ProductImage src={line.imageUrl} alt="" className="size-12 shrink-0 rounded-lg" iconClassName="size-5" />
          <div className="min-w-0 flex-1">
            <p className="flex items-start gap-1.5 text-sm font-semibold text-slate-900">
              <FoodTypeMark foodType={line.foodType} className="mt-0.5" />
              <span>{line.name}</span>
            </p>
            <p className="text-xs text-slate-500">
              {[line.unitLabel, `${line.quantity} × ${money(line.unitPrice)}`].filter(Boolean).join(' · ')}
              {line.mrp !== null && line.mrp > line.unitPrice && (
                <span className="ml-1 text-slate-400 line-through">
                  <span className="sr-only">MRP </span>
                  {money(line.mrp)}
                </span>
              )}
            </p>
          </div>
          <p className="shrink-0 text-sm font-semibold text-slate-900 tabular-nums">{money(line.lineTotal)}</p>
        </li>
      ))}
    </ul>
  )
}

function Row({ label, value, className }: { label: ReactNode; value: ReactNode; className?: string }) {
  return (
    <div className={cn('flex items-baseline justify-between gap-4', className)}>
      <dt className="text-slate-600">{label}</dt>
      <dd className="font-medium text-slate-900 tabular-nums">{value}</dd>
    </div>
  )
}

type Totals = Pick<OrderView, 'itemCount' | 'subtotal' | 'savings' | 'deliveryFee' | 'total' | 'deliveryDistanceKm'> &
  Partial<Pick<OrderView, 'couponCode' | 'discount' | 'deliveryFeeDiscount'>> & {
    payment?: Pick<OrderView['payment'], 'refundedAmount'>
  }

/** Item total, savings, delivery fee, the coupon's discounts (Phase 12) and total of the order snapshot (plus refunds). */
export function OrderTotals({ order, money = formatPrice }: { order: Totals; money?: (value: number) => string }) {
  const distance = formatDistanceKm(order.deliveryDistanceKm)
  const refunded = order.payment?.refundedAmount ?? 0
  const discount = order.discount ?? 0
  const deliveryFeeDiscount = order.deliveryFeeDiscount ?? 0
  const coupon = order.couponCode ? `Coupon ${order.couponCode}` : 'Coupon'
  return (
    <dl aria-label="Order totals" className="space-y-2 text-sm">
      <Row label={`Item total (${order.itemCount} item${order.itemCount === 1 ? '' : 's'})`} value={money(order.subtotal)} />
      {order.savings > 0 && (
        <Row label="Savings on MRP" value={money(order.savings)} className="[&_dd]:text-emerald-700 [&_dt]:text-emerald-700" />
      )}
      <Row
        label={
          <>
            Delivery fee
            {distance && <span className="text-slate-400"> · {distance}</span>}
          </>
        }
        value={order.deliveryFee === 0 ? <span className="font-semibold text-emerald-700">Free delivery</span> : money(order.deliveryFee)}
      />
      {discount > 0 && (
        <Row label={`${coupon} discount`} value={`−${money(discount)}`} className="[&_dd]:text-emerald-700 [&_dt]:text-emerald-700" />
      )}
      {deliveryFeeDiscount > 0 && (
        <Row label={`${coupon}: free delivery`} value={`−${money(deliveryFeeDiscount)}`} className="[&_dd]:text-emerald-700 [&_dt]:text-emerald-700" />
      )}
      <Row label={<span className="font-semibold text-slate-900">Total</span>} value={<span className="text-base font-bold">{money(order.total)}</span>} className="border-t border-slate-200 pt-2" />
      {refunded > 0 && <Row label="Refunded" value={money(refunded)} className="[&_dd]:text-violet-700 [&_dt]:text-violet-700" />}
    </dl>
  )
}

/** Recipient, phone and the postal address of the snapshot. */
export function OrderAddressBlock({ address }: { address: OrderAddress }) {
  const cityLine = [[address.city, address.state].filter(Boolean).join(', '), address.postalCode].filter(Boolean).join(' ')
  return (
    <address className="space-y-0.5 text-sm text-slate-700 not-italic">
      <p className="font-semibold text-slate-900">
        {address.recipientName}
        {address.label && <span className="ml-2 text-xs font-medium text-slate-500">{address.label}</span>}
      </p>
      <p>{[address.line1, address.line2].filter(Boolean).join(', ')}</p>
      {/* As the customer wrote it: "Near the temple", "Opposite the bus stop", "Behind the park". */}
      {address.landmark && <p>{address.landmark}</p>}
      <p>{cityLine}</p>
      <p className="text-slate-500">{address.phone}</p>
    </address>
  )
}

/** Status changes, oldest first. */
export function OrderTimeline({ timeline }: { timeline: readonly OrderTimelineEntry[] }) {
  if (timeline.length === 0) return <p className="text-sm text-slate-500">No status changes yet.</p>
  return (
    <ol aria-label="Status history" className="space-y-4 border-l border-slate-200 pl-4">
      {timeline.map((entry, index) => (
        <li key={`${entry.status}-${entry.at}-${index}`} className="relative">
          <span
            className={cn(
              'absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-white',
              index === timeline.length - 1 ? 'bg-emerald-500' : 'bg-slate-300',
            )}
            aria-hidden="true"
          />
          <OrderStatusBadge status={entry.status} />
          <p className="mt-1 text-xs text-slate-500">{formatDateTime(entry.at)}</p>
          {entry.reason && <p className="mt-0.5 text-sm text-slate-700">{timelineReasonText(entry.reason)}</p>}
        </li>
      ))}
    </ol>
  )
}
