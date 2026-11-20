import { useEffect, useId, useState } from 'react'
import { useNavigate } from 'react-router'
import {
  ApiError,
  errorMessage,
  hasErrorCode,
  problemNumber,
  problemProperty,
  problemString,
} from '../../shared/api/problemDetail'
import { Alert, Button, Spinner, TextareaField } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatPrice } from '../../shared/utils/format'
import { issueText } from '../cart/issues'
import { couponReasonText } from '../cart-coupons/labels'
import { ORDER_ERRORS, ORDER_LIMITS } from './api'
import { checkoutFingerprint, discardStaleCheckoutKey } from './checkoutAttempt'
import { usePlaceOrder } from './hooks'

/** @typedef {import('../cart/api').CartIssue} CartIssue */
/** @typedef {import('../cart/api').CheckoutPreview} CheckoutPreview */

/**
 * @typedef {(
 *   | { kind: 'totalChanged', from: number | null, to: number | null }
 *   | { kind: 'blocked', issues: CartIssue[] }
 *   | { kind: 'error', message: string }
 * )} Notice
 */

/**
 * The `issues` property of `409 CHECKOUT_BLOCKED` (the preview's Issue[]).
 * @param {unknown} error
 * @returns {CartIssue[]}
 */
function blockedIssues(error) {
  const raw = problemProperty(error, 'issues')
  if (!Array.isArray(raw)) return []
  return raw.filter((entry) => typeof entry === 'object' && entry !== null && typeof entry.code === 'string')
}

/**
 * @param {unknown} error
 * @param {CheckoutPreview} preview
 * @returns {Notice}
 */
function noticeFor(error, preview) {
  if (hasErrorCode(error, ORDER_ERRORS.couponNotApplicable)) {
    // Phase 12: redeeming the coupon failed at placement (e.g. its last use was just taken); nothing was ordered.
    const code = preview.coupon?.code ?? null
    const reason = couponReasonText(problemString(error, 'reason'), {
      code,
      minOrderSubtotal: problemNumber(error, 'minOrderSubtotal'),
      subtotal: preview.subtotal,
      storeName: preview.store?.name,
    })
    return {
      kind: 'error',
      message: `${code ? `Coupon ${code}` : 'Your coupon'} could not be used, so nothing was ordered.${reason ? ` ${reason}` : ''} Remove it or try another coupon.`,
    }
  }
  if (hasErrorCode(error, ORDER_ERRORS.totalChanged)) {
    return {
      kind: 'totalChanged',
      from: problemNumber(error, 'expectedTotal'),
      to: problemNumber(error, 'currentTotal'),
    }
  }
  if (hasErrorCode(error, ORDER_ERRORS.checkoutBlocked)) return { kind: 'blocked', issues: blockedIssues(error) }
  if (hasErrorCode(error, ORDER_ERRORS.dependencyUnavailable)) {
    return {
      kind: 'error',
      message:
        'We could not check prices and stock just now. Please try again in a moment — you will not be charged twice.',
    }
  }
  if (hasErrorCode(error, ORDER_ERRORS.addressNotFound)) {
    return {
      kind: 'error',
      message: 'This delivery address could not be found. It may have been deleted — choose another address.',
    }
  }
  if (error instanceof ApiError && (error.kind !== 'http' || (error.status !== null && error.status >= 500))) {
    return {
      kind: 'error',
      message:
        'We could not confirm that your order was placed. Please try again — you will not get the same order twice.',
    }
  }
  return { kind: 'error', message: errorMessage(error) }
}

/**
 * @typedef {object} PlaceOrderSectionProps
 * @property {CheckoutPreview} preview
 * @property {string | null} addressId The address the preview was priced for (null = the default address).
 * @property {boolean} updating A newer preview is loading: never order with a total the customer is not looking at.
 * @property {string} [blockersId] Id of the "why you can't order yet" box, if shown.
 */

/**
 * Delivery instructions and "Place order": `POST /api/orders` with the previewed total and one
 * Idempotency-Key per checkout attempt, then on to the order page where the customer pays.
 * @param {PlaceOrderSectionProps} props
 */
export function PlaceOrderSection({ preview, addressId, updating, blockersId }) {
  const navigate = useNavigate()
  const place = usePlaceOrder()
  const hintId = useId()
  const [note, setNote] = useState('')
  const [notice, setNotice] = useState(null)
  const fingerprint = checkoutFingerprint(addressId, preview.total)

  // Another address or a new total is a new checkout attempt: the old key must not be reused.
  useEffect(() => {
    discardStaleCheckoutKey(fingerprint)
  }, [fingerprint])

  const noteTooLong = note.trim().length > ORDER_LIMITS.note
  const disabled = !preview.canCheckout || updating || place.isPending || noteTooLong
  const context = {
    lines: preview.lines,
    store: preview.store,
    subtotal: preview.subtotal,
    delivery: preview.delivery,
    coupon: preview.coupon,
  }

  function submit() {
    if (disabled) return
    setNotice(null)
    const trimmed = note.trim()
    const body = {
      ...(addressId ? { addressId } : {}),
      expectedTotal: preview.total,
      ...(trimmed ? { note: trimmed } : {}),
    }
    place.mutate(
      { body, fingerprint },
      {
        onSuccess: (order) => void navigate(`/orders/${encodeURIComponent(order.id)}`),
        onError: (error) => setNotice(noticeFor(error, preview)),
      },
    )
  }

  return (
    <div className="space-y-4 border-t border-slate-100 pt-4">
      <TextareaField
        label={
          <span className="inline-flex items-center gap-1.5">
            <Icon name="note" className="size-4" />
            Delivery instructions <span className="font-normal text-slate-500">(optional)</span>
          </span>
        }
        rows={2}
        maxLength={ORDER_LIMITS.note}
        placeholder="e.g. Ring the bell twice"
        value={note}
        error={noteTooLong ? `Use at most ${ORDER_LIMITS.note} characters.` : undefined}
        onChange={(event) => setNote(event.target.value)}
      />

      {notice?.kind === 'totalChanged' && (
        <Alert tone="warning" role="alert" title="Your total has changed">
          {notice.from !== null && notice.to !== null
            ? `The total changed from ${formatPrice(notice.from)} to ${formatPrice(notice.to)} because prices or the delivery fee were updated.`
            : 'Prices or the delivery fee were updated.'}{' '}
          Check the new total and place your order again.
        </Alert>
      )}
      {notice?.kind === 'blocked' && (
        <Alert tone="danger" role="alert" title="Your order can’t be placed yet">
          {notice.issues.length > 0 ? (
            <ul className="list-disc space-y-1 pl-5">
              {notice.issues.map((issue) => (
                <li key={`${issue.code}-${issue.itemId ?? 'cart'}`}>{issueText(issue, context)}</li>
              ))}
            </ul>
          ) : (
            'Something in your cart changed. Please review it.'
          )}
        </Alert>
      )}
      {notice?.kind === 'error' && (
        <Alert tone="danger" role="alert">
          {notice.message}
        </Alert>
      )}

      <div className="space-y-2">
        <Button
          className="h-12 w-full text-base shadow-md shadow-emerald-600/30 disabled:shadow-none"
          disabled={disabled}
          onClick={submit}
          aria-describedby={[blockersId, hintId].filter(Boolean).join(' ')}
        >
          {place.isPending ? (
            <Spinner size="sm" className="[&_svg]:text-white" />
          ) : (
            <Icon name="lock" className="size-5" />
          )}
          {place.isPending ? 'Placing your order…' : `Place order · ${formatPrice(preview.total)}`}
        </Button>
        <p id={hintId} className="text-center text-xs text-slate-500">
          You pay on the next screen. Unpaid orders are cancelled after 15 minutes.
        </p>
      </div>

      <div className="flex items-start gap-3 rounded-xl bg-slate-50 px-3.5 py-3 ring-1 ring-slate-200/70 ring-inset">
        <Icon name="shield" className="mt-0.5 size-5" />
        <p className="text-xs leading-relaxed text-slate-600">
          <span className="block font-semibold text-slate-800">Safe and secure payment</span>
          Prices and stock are checked again when you place the order, and you are never charged twice for it.
        </p>
      </div>
    </div>
  )
}
