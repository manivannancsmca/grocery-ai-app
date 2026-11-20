import { useEffect, useRef, useState } from 'react'
import { useToast } from '../../shared/components/toast/useToast'
import { Dialog, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import type { CartCoupon } from '../cart/api'
import { cartErrorMessage } from '../cart/errors'
import { useCartBusy, useRemoveCoupon } from '../cart/hooks'
import { CouponPanel } from './CouponPanel'
import { useCouponOffers } from './hooks'
import { couponReasonText, couponSavingsText, offerBenefit } from './labels'

interface CartCouponRowProps {
  storeId: string
  storeName: string
  subtotal: number
  /** The coupon on the cart (null = none). */
  coupon: CartCoupon | null
  /** A newer preview is loading. */
  updating?: boolean
}

/**
 * The cart page's coupon line in the order summary: the applied coupon as a ticket (what it saves, or why it can't be
 * used any more) with "Remove", or "Apply a coupon" — the store's best usable offer as a teaser — opening the offers
 * and the code field in a dialog.
 */
export function CartCouponRow({ storeId, storeName, subtotal, coupon, updating = false }: CartCouponRowProps) {
  const toast = useToast()
  const offers = useCouponOffers(storeId)
  const remove = useRemoveCoupon()
  const busy = useCartBusy()
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const rowRef = useRef<HTMLDivElement>(null)
  // After a code is applied in the dialog its "Apply a coupon" button turns into the ticket: focus goes there.
  const focusTicket = useRef(false)
  const appliedCode = coupon?.code ?? null
  useEffect(() => {
    if (appliedCode && focusTicket.current) {
      focusTicket.current = false
      rowRef.current?.focus()
    }
  }, [appliedCode])

  const offerList = offers.data ?? []
  const usable = offerList.find((offer) => (offer.minOrderSubtotal ?? 0) <= subtotal)
  const teaser = usable
    ? `${offerBenefit(usable)} with ${usable.code}`
    : offerList.length > 0
      ? `${offerList.length} offer${offerList.length === 1 ? '' : 's'} for ${storeName}`
      : 'Have a code? Enter it here'

  function removeCoupon(current: CartCoupon) {
    setError(null)
    remove.mutate(undefined, {
      onSuccess: () => toast.success(`Coupon ${current.code} removed.`),
      onError: (failure) => setError(cartErrorMessage(failure)),
    })
  }

  return (
    <div ref={rowRef} tabIndex={-1} aria-busy={updating || undefined} className="rounded-xl focus:outline-none">
      {coupon ? (
        <div className={cn('rounded-xl border border-dashed px-3 py-2.5', coupon.valid ? 'border-emerald-300 bg-emerald-50/70' : 'border-red-300 bg-red-50/70')}>
          <div className="flex items-center gap-3">
            <span
              aria-hidden="true"
              className={cn('flex size-9 shrink-0 items-center justify-center rounded-lg bg-white ring-1', coupon.valid ? 'ring-emerald-200' : 'ring-red-200')}
            >
              <Icon name={coupon.valid ? 'tag' : 'alertCircle'} className="size-5" />
            </span>
            <p className={cn('min-w-0 flex-1 text-sm font-semibold', coupon.valid ? 'text-emerald-900' : 'text-red-900')}>
              <span className="font-mono">{coupon.code}</span> {coupon.valid ? 'applied' : 'can’t be used'}
            </p>
            <button
              type="button"
              disabled={busy}
              onClick={() => removeCoupon(coupon)}
              aria-label={`Remove coupon ${coupon.code}`}
              className={cn(
                'inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg px-2.5 text-xs font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 disabled:opacity-50',
                coupon.valid
                  ? 'text-emerald-800 hover:bg-emerald-100 focus-visible:outline-emerald-600'
                  : 'bg-white text-red-700 shadow-xs ring-1 ring-red-200 ring-inset hover:bg-red-50 focus-visible:outline-red-500',
              )}
            >
              {remove.isPending && <Spinner size="sm" />}
              Remove
            </button>
          </div>
          <p className={cn('mt-1 pl-12 text-xs', coupon.valid ? 'text-emerald-800' : 'text-red-800')}>
            {coupon.valid
              ? couponSavingsText(coupon)
              : (couponReasonText(coupon.reason, { code: coupon.code, minOrderSubtotal: coupon.minOrderSubtotal, subtotal, storeName }) ??
                coupon.message ??
                'It no longer applies to this cart.')}
          </p>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => {
            setError(null)
            setOpen(true)
          }}
          aria-haspopup="dialog"
          className="group flex w-full items-center gap-3 rounded-xl border border-dashed border-amber-300 bg-gradient-to-r from-amber-50/80 to-white px-3 py-2.5 text-left transition-colors hover:border-amber-400 hover:from-amber-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white ring-1 ring-amber-200">
            <Icon name="tag" className="size-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-semibold text-slate-900">Apply a coupon</span>
            <span className="block truncate text-xs text-slate-500">{teaser}</span>
          </span>
          <Icon name="chevronRight" className="size-4 text-slate-400 transition-transform group-hover:translate-x-0.5" />
        </button>
      )}
      {error && (
        <p role="alert" className="mt-2 text-xs font-medium text-red-700">
          {error}
        </p>
      )}
      <Dialog open={open} onClose={() => setOpen(false)} title="Offers & coupons" description={`For your order from ${storeName}`} icon="tag">
        <CouponPanel
          bare
          storeId={storeId}
          storeName={storeName}
          subtotal={subtotal}
          coupon={coupon}
          updating={updating}
          onApplied={() => {
            focusTicket.current = true
            setOpen(false)
          }}
        />
      </Dialog>
    </div>
  )
}
