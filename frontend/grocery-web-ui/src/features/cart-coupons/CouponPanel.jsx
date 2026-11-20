import { useId, useState } from 'react'
import { hasErrorCode, problemNumber, problemString } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, Input, Skeleton, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDate, formatPrice } from '../../shared/utils/format'
import { COUPON_CODE_PATTERN, COUPON_NOT_APPLICABLE } from '../cart/api'
import { cartErrorMessage } from '../cart/errors'
import { useApplyCoupon, useCartBusy, useRemoveCoupon } from '../cart/hooks'
import { useCouponOffers } from './hooks'
import { couponReasonText, couponSavingsText, normalizeCouponCode, offerBenefit } from './labels'

/** @typedef {import('../cart/api').CartCoupon} CartCoupon */
/** @typedef {import('./api').CouponOffer} CouponOffer */

/**
 * @typedef {object} CouponPanelProps
 * @property {string} storeId
 * @property {string} storeName
 * @property {number} subtotal
 * @property {CartCoupon | null | undefined} coupon The preview's coupon (null / absent = none applied).
 * @property {boolean} [updating] A newer preview is loading.
 * @property {boolean} [bare] Without its own card and heading, for a dialog that already has them (the cart page's "Offers & coupons").
 * @property {(code: string) => void} [onApplied] Called after a code was applied (the cart page closes its dialog).
 */

/**
 * The refused code's reason (422 COUPON_NOT_APPLICABLE), or another failure, as one sentence.
 * @param {unknown} error
 * @param {string} code
 * @param {number} subtotal
 * @param {string} storeName
 * @returns {string}
 */
function applyErrorText(error, code, subtotal, storeName) {
  if (hasErrorCode(error, COUPON_NOT_APPLICABLE)) {
    const reason = problemString(error, 'reason')
    return (
      couponReasonText(reason, {
        code,
        minOrderSubtotal: problemNumber(error, 'minOrderSubtotal'),
        subtotal,
        storeName,
      }) ?? 'This coupon can’t be used with this cart.'
    )
  }
  return cartErrorMessage(error)
}

/**
 * One offer as a ticket: a coloured stub, the code, what it gives, its conditions and "Apply" (or "Applied").
 * @param {{ offer: CouponOffer, applied: boolean, subtotal: number, disabled: boolean, applying: boolean, onApply: (code: string) => void }} props
 */
function OfferItem({ offer, applied, subtotal, disabled, applying, onApply }) {
  const minimum = offer.minOrderSubtotal ?? 0
  const missing = minimum > 0 ? Math.round((minimum - subtotal) * 100) / 100 : 0
  return (
    <li
      className={cn(
        'flex overflow-hidden rounded-xl border bg-white transition-colors',
        applied ? 'border-emerald-300 ring-1 ring-emerald-300' : 'border-slate-200 hover:border-amber-300',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex w-11 shrink-0 items-center justify-center border-r-2 border-dashed',
          applied
            ? 'border-emerald-200 bg-emerald-50'
            : 'border-amber-200 bg-gradient-to-b from-amber-50 to-orange-100',
        )}
      >
        <Icon name="tag" className="size-5" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-2.5 p-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0 space-y-0.5">
          <p className="flex flex-wrap items-center gap-2 text-sm">
            <span className="rounded-md border border-dashed border-emerald-400 bg-emerald-50/60 px-1.5 py-0.5 font-mono text-xs font-semibold text-emerald-800">
              {offer.code}
            </span>
            <span className="font-semibold text-slate-900">{offerBenefit(offer)}</span>
          </p>
          <p className="text-sm text-slate-700">{offer.name}</p>
          {offer.description && <p className="text-xs text-slate-500">{offer.description}</p>}
          <p className="text-xs text-slate-500">
            {[minimum > 0 ? `Min. order ${formatPrice(minimum)}` : null, `Valid until ${formatDate(offer.validUntil)}`]
              .filter(Boolean)
              .join(' · ')}
          </p>
          {missing > 0 && !applied && (
            <p className="text-xs font-semibold text-amber-700">Add {formatPrice(missing)} more to use it.</p>
          )}
        </div>
        {applied ? (
          <span className="inline-flex shrink-0 items-center gap-1 text-sm font-semibold text-emerald-700">
            <Icon name="check" className="size-4" />
            Applied
          </span>
        ) : (
          <Button
            variant="secondary"
            size="sm"
            className="shrink-0 self-start text-emerald-700 ring-emerald-300 hover:bg-emerald-50 sm:self-center"
            disabled={disabled}
            aria-label={`Apply coupon ${offer.code}`}
            onClick={() => onApply(offer.code)}
          >
            {applying && <Spinner size="sm" />}
            Apply
          </Button>
        )}
      </div>
    </li>
  )
}

/**
 * "Offers & coupons" at checkout: the store's public offers, a code field, and the applied coupon with what it saves
 * — or, when it no longer applies, why (it then blocks the order until removed or the cart is fixed).
 * @param {CouponPanelProps} props
 */
export function CouponPanel({ storeId, storeName, subtotal, coupon, updating = false, bare = false, onApplied }) {
  const titleId = useId()
  const inputId = useId()
  const errorId = useId()
  const toast = useToast()
  const offers = useCouponOffers(storeId)
  const apply = useApplyCoupon()
  const remove = useRemoveCoupon()
  const busy = useCartBusy()
  const [code, setCode] = useState('')
  const [error, setError] = useState(null)
  const [applyingCode, setApplyingCode] = useState(null)

  function applyCode(raw) {
    const normalized = normalizeCouponCode(raw)
    setError(null)
    if (!normalized) {
      setError('Enter a coupon code.')
      return
    }
    if (!COUPON_CODE_PATTERN.test(normalized)) {
      setError('Coupon codes have 4–20 letters, digits or dashes.')
      return
    }
    setApplyingCode(normalized)
    apply.mutate(normalized, {
      onSuccess: () => {
        setCode('')
        toast.success(`Coupon ${normalized} applied.`)
        onApplied?.(normalized)
      },
      onError: (failure) => setError(applyErrorText(failure, normalized, subtotal, storeName)),
      onSettled: () => setApplyingCode(null),
    })
  }

  function submit(event) {
    event.preventDefault()
    applyCode(code)
  }

  function removeCoupon(current) {
    setError(null)
    remove.mutate(undefined, {
      onSuccess: () => toast.success(`Coupon ${current.code} removed.`),
      onError: (failure) => setError(cartErrorMessage(failure)),
    })
  }

  const offerList = offers.data ?? []

  const content = (
    <>
      {coupon ? (
        <div
          role="status"
          className={cn(
            'flex flex-col gap-3 rounded-xl border-2 border-dashed p-3.5 text-sm sm:flex-row sm:items-center sm:justify-between',
            coupon.valid
              ? 'border-emerald-300 bg-emerald-50 text-emerald-900'
              : 'border-red-300 bg-red-50 text-red-900',
          )}
        >
          <div className="flex min-w-0 items-start gap-3">
            <Icon name={coupon.valid ? 'checkCircle' : 'alertCircle'} className="size-8" />
            <div className="min-w-0">
              <p className="font-semibold">
                <span className="font-mono">{coupon.code}</span> {coupon.valid ? 'applied' : 'can’t be used'}
              </p>
              <p className="mt-0.5">
                {coupon.valid
                  ? couponSavingsText(coupon)
                  : (couponReasonText(coupon.reason, {
                      code: coupon.code,
                      minOrderSubtotal: coupon.minOrderSubtotal,
                      subtotal,
                      storeName,
                    }) ??
                    coupon.message ??
                    'It no longer applies to this cart.')}
              </p>
            </div>
          </div>
          <Button
            variant={coupon.valid ? 'secondary' : 'danger'}
            size="sm"
            className="shrink-0 self-start sm:self-center"
            disabled={busy}
            onClick={() => removeCoupon(coupon)}
            aria-label={`Remove coupon ${coupon.code}`}
          >
            {remove.isPending && <Spinner size="sm" />}
            Remove
          </Button>
        </div>
      ) : (
        <form noValidate onSubmit={submit} className="space-y-1.5">
          <label htmlFor={inputId} className="block text-sm font-medium text-slate-700">
            Coupon code
          </label>
          <div className="flex gap-2">
            <div className="relative min-w-0 flex-1">
              <Icon name="tag" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
              <Input
                id={inputId}
                value={code}
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                maxLength={20}
                placeholder="e.g. WELCOME50"
                invalid={Boolean(error)}
                aria-describedby={error ? errorId : undefined}
                className="h-11 pl-9 font-mono uppercase placeholder:font-sans placeholder:normal-case"
                onChange={(event) => {
                  setCode(event.target.value)
                  setError(null)
                }}
              />
            </div>
            <Button
              type="submit"
              variant="secondary"
              className="h-11 shrink-0 px-5 text-emerald-700 ring-emerald-300 hover:bg-emerald-50"
              disabled={busy}
            >
              {apply.isPending && applyingCode === normalizeCouponCode(code) && <Spinner size="sm" />}
              Apply
            </Button>
          </div>
        </form>
      )}
      {error && (
        <p id={errorId} role="alert" className="mt-2 text-sm font-medium text-red-700">
          {error}
        </p>
      )}

      <div className="mt-5">
        <h3 className="text-xs font-semibold tracking-wider text-slate-500 uppercase">Offers for {storeName}</h3>
        {offers.isPending ? (
          <Skeleton className="mt-2.5 h-20 w-full rounded-xl" />
        ) : offers.isError ? (
          <p className="mt-2 text-sm text-slate-500">
            Offers could not be loaded right now. You can still enter a code.
          </p>
        ) : offerList.length === 0 ? (
          <p className="mt-2.5 rounded-xl border border-dashed border-slate-200 px-3 py-3 text-sm text-slate-500">
            No offers for this store right now.
          </p>
        ) : (
          <ul aria-label={`Offers for ${storeName}`} className="mt-2.5 space-y-2.5">
            {offerList.map((offer) => (
              <OfferItem
                key={offer.code}
                offer={offer}
                applied={coupon?.code === offer.code}
                subtotal={subtotal}
                disabled={busy}
                applying={applyingCode === offer.code}
                onApply={applyCode}
              />
            ))}
          </ul>
        )}
      </div>
    </>
  )

  if (bare) return <div aria-busy={updating || undefined}>{content}</div>
  return (
    <section
      aria-labelledby={titleId}
      aria-busy={updating || undefined}
      className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm shadow-slate-900/[0.03] sm:p-6"
    >
      <h2 id={titleId} className="mb-4 flex items-center gap-2.5 text-lg font-semibold text-slate-900">
        <span
          aria-hidden="true"
          className="flex size-9 items-center justify-center rounded-xl bg-amber-50 ring-1 ring-amber-100"
        >
          <Icon name="tag" className="size-5" />
        </span>
        Offers &amp; coupons
      </h2>
      {content}
    </section>
  )
}
