import { type ReactNode, useId } from 'react'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import type { CheckoutPreview } from './api'
import { BillRows, BillTotal, FreeDeliveryMeter, MinimumOrderMeter, SavingsNote } from './BillBreakdown'
import { issueText, roundMoney } from './issues'
import { freeDeliveryProgress, minimumOrderGap } from './summary'

interface CheckoutSummaryProps {
  preview: CheckoutPreview
  /** A newer preview is loading (address or cart changed). */
  updating?: boolean
  /**
   * The "Place order" area (Phase 6, rendered by the orders feature). Receives the id of the
   * "why you can't order yet" box when it is shown, for `aria-describedby`.
   */
  action?: (blockersId: string | undefined) => ReactNode
}

/**
 * Totals of the checkout preview: item total, delivery fee (free / "add ₹X more" with a progress bar), minimum order,
 * the coupon's discounts, total incl. taxes and what the customer saves, every issue (blocking ones in red) and the
 * "Place order" action. The bill itself is the cart page's (`BillBreakdown`).
 */
export function CheckoutSummary({ preview, updating = false, action }: CheckoutSummaryProps) {
  const titleId = useId()
  const reasonsId = useId()
  const context = { lines: preview.lines, store: preview.store, subtotal: preview.subtotal, delivery: preview.delivery, coupon: preview.coupon }
  const blocking = preview.issues.filter((issue) => issue.blocking)
  const notices = preview.issues.filter((issue) => !issue.blocking)
  const delivery = preview.delivery
  const belowMinimum = preview.issues.some((issue) => issue.code === 'BELOW_MINIMUM_ORDER')
  const showBlockers = blocking.length > 0 || !preview.canCheckout
  // Phase 12: a valid coupon's discount on the items and / or the delivery fee.
  const coupon = preview.coupon ?? null
  const discount = preview.discount ?? (coupon?.valid ? coupon.discount : 0)
  const deliveryFeeDiscount = preview.deliveryFeeDiscount ?? (coupon?.valid ? coupon.deliveryFeeDiscount : 0)
  // Everything the customer saves on this order: MRP savings, the coupon and a waived delivery fee.
  const totalSaved = roundMoney(preview.savings + discount + deliveryFeeDiscount)
  const minimum = minimumOrderGap(preview.subtotal, delivery)
  const freeDelivery = minimum ? null : freeDeliveryProgress(preview.subtotal, delivery, deliveryFeeDiscount)

  return (
    <section
      aria-labelledby={titleId}
      aria-busy={updating || undefined}
      className={cn(
        'space-y-4 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm shadow-slate-900/[0.03] transition-opacity sm:p-6',
        updating && 'opacity-60',
      )}
    >
      <h2 id={titleId} className="flex items-center gap-2 text-lg font-semibold text-slate-900">
        <Icon name="receipt" className="size-5" />
        Order summary
      </h2>

      {minimum && <MinimumOrderMeter gap={minimum} />}
      {freeDelivery && <FreeDeliveryMeter progress={freeDelivery} />}

      <BillRows
        itemCount={preview.itemCount}
        subtotal={preview.subtotal}
        mrpTotal={preview.mrpTotal}
        delivery={delivery}
        coupon={coupon}
        discount={discount}
        deliveryFeeDiscount={deliveryFeeDiscount}
      />

      {delivery && delivery.minimumOrder !== null && !belowMinimum && (
        <p className="text-xs text-slate-500">Minimum order {formatPrice(delivery.minimumOrder)}.</p>
      )}

      <BillTotal total={preview.total} />
      <SavingsNote amount={totalSaved} />

      {showBlockers && (
        <div id={reasonsId} className="rounded-xl border border-red-200 bg-red-50 p-3.5 text-sm text-red-800">
          <p className="flex items-center gap-2 font-semibold text-red-900">
            <Icon name="alertCircle" className="size-4.5" />
            You can’t place this order yet
          </p>
          {blocking.length > 0 ? (
            <ul className="mt-1.5 list-disc space-y-1 pl-10">
              {blocking.map((issue) => (
                <li key={`${issue.code}-${issue.itemId ?? 'cart'}`}>{issueText(issue, context)}</li>
              ))}
            </ul>
          ) : (
            <p className="mt-1 pl-6.5">Something in your cart needs attention. Please review it.</p>
          )}
        </div>
      )}
      {notices.length > 0 && (
        <ul className="space-y-1 rounded-xl border border-amber-200 bg-amber-50 p-3.5 text-sm text-amber-900">
          {notices.map((issue) => (
            <li key={`${issue.code}-${issue.itemId ?? 'cart'}`}>{issueText(issue, context)}</li>
          ))}
        </ul>
      )}

      {action?.(showBlockers ? reasonsId : undefined)}
    </section>
  )
}
