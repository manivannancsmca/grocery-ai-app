import { type ReactNode, useId } from 'react'
import { Link } from 'react-router'
import { Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import { BillRows, BillTotal, FreeDeliveryMeter, MinimumOrderMeter, SavingsNote } from './BillBreakdown'
import { itemsLabel } from './format'
import type { CartBill } from './summary'

const ctaBase =
  'group/cta inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'
const ctaEnabled =
  'bg-gradient-to-b from-emerald-500 to-emerald-600 text-white shadow-md shadow-emerald-600/25 ring-1 ring-emerald-700/20 ring-inset hover:to-emerald-700 hover:shadow-lg hover:shadow-emerald-600/30'
const ctaDisabled = 'cursor-not-allowed bg-slate-200 text-slate-500'

interface CheckoutCtaProps {
  /** Something blocks checkout: the button is disabled and described by the list of what to fix. */
  blocked: boolean
  blockersId?: string
  /** The address chosen on the cart, preselected at checkout. */
  addressId: string | null
  className?: string
}

/** "Proceed to checkout" — a link to `/checkout` carrying the chosen address, or a disabled button while blocked. */
function CheckoutCta({ blocked, blockersId, addressId, className }: CheckoutCtaProps) {
  const content = (
    <>
      Proceed to checkout
      <Icon name="arrowRight" className="size-5 transition-transform group-hover/cta:translate-x-0.5" />
    </>
  )
  if (blocked) {
    return (
      <button type="button" disabled aria-describedby={blockersId} className={cn(ctaBase, ctaDisabled, className)}>
        {content}
      </button>
    )
  }
  return (
    <Link to="/checkout" state={{ addressId }} className={cn(ctaBase, ctaEnabled, className)}>
      {content}
    </Link>
  )
}

interface CartOrderSummaryProps {
  bill: CartBill
  /** The checkout preview is loading for the first time: the bill shows placeholders. */
  loading: boolean
  /** A newer preview is loading (address or cart changed): the bill is dimmed. */
  updating: boolean
  /** "Deliver to" (address and its problems). */
  deliverTo: ReactNode
  /** The coupon line. */
  coupon: ReactNode
  /** A note when the preview could not be loaded (the bill then comes from the cart). */
  previewProblem?: ReactNode
  /** Under "Delivery fee" while there is no fee (no address, no quote). */
  noDeliveryHint: string
  /** "Add items" next to a nudge (the store's page). */
  addMoreHref: string
  blocked: boolean
  blockersId: string
  addressId: string | null
}

/**
 * The cart page's order summary (sticky beside the items on wide screens): where it goes, the nudges (minimum order,
 * free delivery), the coupon, the bill and the total, what the customer saves and — on wide screens — "Proceed to
 * checkout" (narrower screens get `CartCheckoutBar` instead).
 */
export function CartOrderSummary({
  bill,
  loading,
  updating,
  deliverTo,
  coupon,
  previewProblem,
  noDeliveryHint,
  addMoreHref,
  blocked,
  blockersId,
  addressId,
}: CartOrderSummaryProps) {
  const titleId = useId()
  const addMore = (
    <Link
      to={addMoreHref}
      className="shrink-0 rounded text-xs font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
    >
      Add items
    </Link>
  )

  return (
    <section
      aria-labelledby={titleId}
      className="@container/summary overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]"
    >
      <header className="flex items-center gap-3 border-b border-slate-100 bg-gradient-to-r from-emerald-50/70 via-white to-white px-4 py-3.5 sm:px-5">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white ring-1 ring-emerald-100">
          <Icon name="receipt" className="size-[1.15rem]" />
        </span>
        <h2 id={titleId} className="min-w-0 flex-1 text-base font-semibold text-slate-900">
          Order summary
        </h2>
      </header>

      {/* Under the items (tablets, narrow windows) the card is wide: where and offers on the left, the bill on the right. */}
      <div className="grid gap-5 p-4 sm:p-5 @2xl/summary:grid-cols-2 @2xl/summary:items-start @2xl/summary:gap-x-8">
        <div className="min-w-0 space-y-5">
          {deliverTo}

          {(bill.minimum || bill.freeDelivery) && (
            <div className={cn('transition-opacity', updating && 'opacity-60')}>
              {bill.minimum ? (
                <MinimumOrderMeter gap={bill.minimum} action={addMore} />
              ) : (
                bill.freeDelivery && <FreeDeliveryMeter progress={bill.freeDelivery} action={bill.freeDelivery.kind === 'short' ? addMore : undefined} />
              )}
            </div>
          )}

          {coupon}
        </div>

        <div className="min-w-0 space-y-5">
          <div aria-busy={updating || loading || undefined} className={cn('space-y-4 transition-opacity', updating && 'opacity-60')}>
            <p className="text-xs font-semibold tracking-wider text-slate-500 uppercase">Bill details</p>
            {loading ? (
              <div role="status" aria-label="Working out the delivery fee" className="space-y-3">
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-5/6" />
                <Skeleton className="h-8 w-full" />
              </div>
            ) : (
              <>
                <BillRows
                  itemCount={bill.itemCount}
                  subtotal={bill.subtotal}
                  mrpTotal={bill.mrpTotal}
                  delivery={bill.delivery}
                  coupon={bill.coupon}
                  discount={bill.discount}
                  deliveryFeeDiscount={bill.deliveryFeeDiscount}
                  noDeliveryHint={noDeliveryHint}
                />
                {previewProblem}
                <BillTotal
                  total={bill.total}
                  label={bill.complete ? 'Total' : 'Total before delivery'}
                  note={bill.complete ? 'Inclusive of all taxes' : 'Inclusive of all taxes · delivery fee at checkout'}
                />
                <SavingsNote amount={bill.saved} />
              </>
            )}
          </div>

          {/* Narrower screens: the sticky bar at the bottom has the button. */}
          <div className="hidden space-y-2 @4xl/cart:block">
            <CheckoutCta blocked={blocked} blockersId={blockersId} addressId={addressId} className="h-12 w-full text-base" />
            <p className={cn('flex items-center justify-center gap-1.5 text-xs', blocked ? 'font-medium text-red-700' : 'text-slate-500')}>
              {blocked ? (
                <>
                  <Icon name="alertCircle" className="size-3.5" />
                  Fix the issues in your cart to continue
                </>
              ) : (
                <>
                  <Icon name="shield" className="size-3.5" />
                  You pay on the next screen
                </>
              )}
            </p>
          </div>
        </div>
      </div>
    </section>
  )
}

interface CartCheckoutBarProps {
  bill: CartBill
  loading: boolean
  /** How many things block checkout (said under the total instead of the savings). */
  blockers: number
  blocked: boolean
  blockersId: string
  addressId: string | null
}

/**
 * Phones, tablets and narrow windows: the total and "Proceed to checkout" stuck to the bottom of the screen, above the
 * shoppers' tab bar and the home indicator, with a slim free-delivery / minimum-order meter. It sits at the end of
 * the page, so once scrolled to the bottom it covers nothing.
 */
export function CartCheckoutBar({ bill, loading, blockers, blocked, blockersId, addressId }: CartCheckoutBarProps) {
  const short = bill.minimum
    ? { text: `Add ${formatPrice(bill.minimum.shortfall)} more to reach the minimum order`, percent: bill.minimum.percent, tone: 'amber' as const }
    : bill.freeDelivery?.kind === 'short'
      ? { text: `Add ${formatPrice(bill.freeDelivery.shortfall)} more for free delivery`, percent: bill.freeDelivery.percent, tone: 'green' as const }
      : null
  return (
    <section
      aria-label="Total and checkout"
      className="sticky bottom-[calc(var(--tabbar-h,0px)+env(safe-area-inset-bottom,0px)+0.5rem)] z-20 mt-5 md:bottom-3 @4xl/cart:hidden"
    >
      <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white/95 shadow-[0_12px_40px_-12px_rgb(15_23_42/0.35)] backdrop-blur-md">
        {short && (
          // The order summary says the same in full; this strip is a reminder for the eyes only.
          <div aria-hidden="true" className={cn('px-3.5 pt-2.5', short.tone === 'green' ? 'text-emerald-800' : 'text-amber-800')}>
            <p className="flex items-center gap-1.5 text-xs font-semibold">
              <Icon name={short.tone === 'green' ? 'truck' : 'bag'} className="size-3.5" />
              <span className="min-w-0 truncate">{short.text}</span>
            </p>
            <div className={cn('mt-1.5 h-1 overflow-hidden rounded-full', short.tone === 'green' ? 'bg-emerald-100' : 'bg-amber-100')}>
              <div
                className={cn('h-full rounded-full transition-[width] duration-500', short.tone === 'green' ? 'bg-emerald-500' : 'bg-amber-400')}
                style={{ width: `${short.percent}%` }}
              />
            </div>
          </div>
        )}
        <div className="flex items-center gap-3 p-3">
          <div className="min-w-0 flex-1">
            <p className="truncate text-[11px] font-medium text-slate-500">
              {bill.complete ? 'Total' : 'Total before delivery'} · {itemsLabel(bill.itemCount)}
            </p>
            {loading ? (
              <Skeleton className="mt-1 h-6 w-20" />
            ) : (
              <p className="text-lg leading-tight font-bold text-slate-900 tabular-nums">{formatPrice(bill.total)}</p>
            )}
            {blockers > 0 ? (
              <p className="truncate text-[11px] font-semibold text-red-700">
                {blockers === 1 ? '1 issue to fix' : `${blockers} issues to fix`}
              </p>
            ) : (
              bill.saved > 0 &&
              !loading && <p className="truncate text-[11px] font-semibold text-emerald-700">You save {formatPrice(bill.saved)}</p>
            )}
          </div>
          <CheckoutCta blocked={blocked} blockersId={blockersId} addressId={addressId} className="h-11 shrink-0 px-4 text-sm" />
        </div>
      </div>
    </section>
  )
}
