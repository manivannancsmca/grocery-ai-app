/** The cart page when it has items: lines, blockers that stop checkout, coupon and the order summary. */

import { useId, useState } from 'react'
import { Link } from 'react-router'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, ConfirmDialog, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDistanceKm } from '../../shared/utils/format'
import { CartCouponRow } from '../cart-coupons/CartCouponRow'
import { StoreTile } from '../orders/OrderVisuals'
import { CartDeliverTo } from './CartDeliverTo'
import { CartLineItem } from './CartLineItem'
import { CartCheckoutBar, CartOrderSummary } from './CartOrderSummary'
import { cartErrorMessage } from './errors'
import { itemsLabel, storePath } from './format'
import { useCartBusy, useClearCart } from './hooks'
import { issueText, lineIssue } from './issues'
import {
  cartBill,
  cartPageIssues,
  currentCoupon,
  hasTransientIssue,
  issuesAnnouncement,
  storeStatusLabel,
} from './summary'
import { CARD, COLUMNS } from './cartPageStyles'

/** @typedef {import('../customer/api').Address} Address */
/** @typedef {import('./api').CartIssue} CartIssue */
/** @typedef {import('./api').CartStore} CartStore */
/** @typedef {import('./api').CartView} CartView */
/** @typedef {import('./api').CheckoutPreview} CheckoutPreview */

/**
 * What blocks checkout, each in one sentence, above the items (only when something does).
 * @param {{ id: string, issues: readonly CartIssue[], context: Parameters<typeof issueText>[1], onRecheck?: () => void, rechecking: boolean }} props
 */
function Blockers({ id, issues, context, onRecheck, rechecking }) {
  return (
    <div
      id={id}
      className="animate-fade-up rounded-2xl border border-red-200/80 bg-gradient-to-r from-red-50 to-rose-50/40 p-4 text-sm text-red-800 shadow-xs"
    >
      <div className="flex items-start gap-3">
        <span
          aria-hidden="true"
          className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white ring-1 ring-red-100"
        >
          <Icon name="alertCircle" className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold text-red-900">
            {issues.length === 1
              ? 'One thing to fix before checkout'
              : `${issues.length} things to fix before checkout`}
          </p>
          <ul className="mt-1.5 space-y-1">
            {issues.map((issue) => (
              <li key={`${issue.code}-${issue.itemId ?? 'cart'}`} className="flex gap-2">
                <span aria-hidden="true" className="mt-[0.45rem] size-1.5 shrink-0 rounded-full bg-red-400" />
                <span className="min-w-0">{issueText(issue, context)}</span>
              </li>
            ))}
          </ul>
          {onRecheck && (
            <Button variant="secondary" size="sm" className="mt-3" disabled={rechecking} onClick={onRecheck}>
              {rechecking ? <Spinner size="sm" /> : <Icon name="refresh" className="size-4" />}
              Check again
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * @typedef {object} CartWithItemsProps
 * @property {CartView} cart
 * @property {CartStore} store
 * @property {CheckoutPreview | undefined} preview
 * @property {boolean} previewLoading
 * @property {boolean} previewUpdating
 * @property {unknown} previewError
 * @property {() => void} onRetryPreview
 * @property {() => void} onRecheck
 * @property {boolean} rechecking
 * @property {{ list: readonly Address[], loading: boolean, failed: boolean, retry: () => void, selected: Address | null, choose: (id: string) => void }} addresses
 * @property {() => void} onLineRemoved
 */

/** @param {CartWithItemsProps} props */
export function CartWithItems({
  cart,
  store,
  preview,
  previewLoading,
  previewUpdating,
  previewError,
  onRetryPreview,
  onRecheck,
  rechecking,
  addresses,
  onLineRemoved,
}) {
  const toast = useToast()
  const clear = useClearCart()
  const busy = useCartBusy()
  const blockersId = useId()
  const storeTitleId = useId()
  const [confirmClear, setConfirmClear] = useState(false)
  const [clearError, setClearError] = useState(null)

  const isRestaurant = store.type === 'RESTAURANT'
  const coupon = currentCoupon(cart.coupon, preview?.coupon)
  const bill = cartBill(cart, preview, coupon)
  const issues = cartPageIssues(cart, preview)
  const blocking = issues.filter((issue) => issue.blocking)
  const notices = issues.filter((issue) => !issue.blocking && !issue.itemId)
  const context = { lines: cart.lines, store, subtotal: cart.subtotal, delivery: preview?.delivery ?? null, coupon }
  const storeStatus = storeStatusLabel(issues)
  const distance = formatDistanceKm(preview?.delivery?.distanceKm)
  const addressNotFound = hasErrorCode(previewError, 'ADDRESS_NOT_FOUND')

  const previewProblem = previewError ? (
    <p className="flex items-start gap-2 rounded-xl bg-amber-50 px-3 py-2.5 text-xs text-amber-900 ring-1 ring-amber-200/70 ring-inset">
      <Icon name="info" className="mt-px size-4" />
      <span className="min-w-0 flex-1">
        {addressNotFound
          ? 'This address could not be found — it may have been deleted. Choose another one.'
          : 'We could not work out the delivery fee just now.'}{' '}
        <button
          type="button"
          onClick={() => {
            if (addressNotFound) addresses.retry()
            onRetryPreview()
          }}
          className="rounded font-semibold text-amber-900 underline underline-offset-2 hover:text-amber-950 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-600"
        >
          Try again
        </button>
      </span>
    </p>
  ) : null

  return (
    <>
      <div className={COLUMNS}>
        <div className="min-w-0 space-y-4">
          {blocking.length > 0 && (
            <Blockers
              id={blockersId}
              issues={blocking}
              context={context}
              onRecheck={hasTransientIssue(blocking) ? onRecheck : undefined}
              rechecking={rechecking}
            />
          )}
          {notices.map((issue) => (
            <p
              key={`${issue.code}-cart`}
              className="flex items-start gap-2 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
            >
              <Icon name="info" className="mt-0.5 size-4" />
              <span className="min-w-0">{issueText(issue, context)}</span>
            </p>
          ))}

          <section aria-labelledby={storeTitleId} className={cn(CARD, 'min-w-0')}>
            <header className="flex flex-wrap items-center gap-x-3 gap-y-2.5 border-b border-slate-100 px-4 py-3.5 sm:px-5">
              <StoreTile type={store.type} size="md" />
              <div className="min-w-[11rem] flex-1">
                <p className="text-xs font-medium text-slate-500">{isRestaurant ? 'Restaurant' : 'Grocery store'}</p>
                <h2 id={storeTitleId} className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                  <Link
                    to={storePath(store)}
                    className="line-clamp-2 min-w-0 text-base leading-snug break-words font-semibold text-slate-900 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
                  >
                    {store.name}
                  </Link>
                  {storeStatus && (
                    <span className="shrink-0 rounded-full bg-red-50 px-2 py-0.5 text-[11px] font-semibold text-red-700 ring-1 ring-red-200 ring-inset">
                      {storeStatus}
                    </span>
                  )}
                </h2>
                {distance && addresses.selected && (
                  <p className="mt-0.5 flex min-w-0 items-center gap-1 text-xs text-slate-500">
                    <Icon name="route" className="size-3.5" />
                    <span className="truncate">
                      {distance} away · delivering to {addresses.selected.label}
                    </span>
                  </p>
                )}
              </div>
              <Link
                to={storePath(store)}
                className="inline-flex h-9 shrink-0 items-center gap-1.5 rounded-xl bg-emerald-50 px-3 text-sm font-semibold text-emerald-700 ring-1 ring-emerald-200/80 transition-colors ring-inset hover:bg-emerald-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
              >
                <Icon name="plus" className="size-4" />
                Add more items
              </Link>
            </header>

            <ul aria-label="Items in your cart" className="divide-y divide-slate-100 px-4 sm:px-5">
              {cart.lines.map((line) => (
                <CartLineItem
                  key={`${line.type}-${line.itemId}`}
                  line={line}
                  issue={lineIssue(cart, line)}
                  storeName={store.name}
                  onRemoved={onLineRemoved}
                />
              ))}
            </ul>

            <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 px-4 py-2.5 sm:px-5">
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={() => {
                  setClearError(null)
                  setConfirmClear(true)
                }}
                className="-ml-2 text-red-600 hover:bg-red-50 hover:text-red-700"
              >
                <Icon name="trash" className="size-4" />
                Clear cart
              </Button>
              <p className="flex items-center gap-1.5 text-xs text-slate-500">
                {rechecking ? <Spinner size="sm" /> : <Icon name="checkCircle" className="size-3.5" />}
                {rechecking ? 'Checking prices and stock…' : 'Prices and stock checked live'}
              </p>
            </footer>
          </section>
        </div>

        <div className="min-w-0 @4xl/cart:sticky @4xl/cart:top-20">
          <CartOrderSummary
            bill={bill}
            loading={previewLoading && !previewError}
            updating={previewUpdating}
            deliverTo={
              <CartDeliverTo
                addresses={addresses.list}
                loading={addresses.loading}
                failed={addresses.failed}
                onRetry={addresses.retry}
                selected={addresses.selected}
                onSelect={addresses.choose}
                delivery={preview?.delivery}
                issues={issues}
              />
            }
            coupon={
              <CartCouponRow
                storeId={store.id}
                storeName={store.name}
                subtotal={cart.subtotal}
                coupon={coupon}
                updating={previewUpdating}
              />
            }
            previewProblem={previewProblem}
            noDeliveryHint={
              !addresses.selected && !addresses.failed
                ? 'Add a delivery address to see the fee'
                : previewError
                  ? 'Worked out at checkout'
                  : 'Not available for this address'
            }
            addMoreHref={storePath(store)}
            blocked={blocking.length > 0}
            blockersId={blockersId}
            addressId={addresses.selected?.id ?? null}
          />
        </div>
      </div>

      <CartCheckoutBar
        bill={bill}
        loading={previewLoading && !previewError}
        blockers={blocking.length}
        blocked={blocking.length > 0}
        blockersId={blockersId}
        addressId={addresses.selected?.id ?? null}
      />

      {/* Read out after every re-check (opening the page, a quantity change): price changes, sold-out items, … */}
      <p role="status" className="sr-only">
        {issuesAnnouncement(issues, context)}
      </p>

      <ConfirmDialog
        open={confirmClear}
        title="Clear your cart?"
        confirmLabel="Clear cart"
        cancelLabel="Keep items"
        tone="danger"
        pending={clear.isPending}
        error={clearError}
        onClose={() => setConfirmClear(false)}
        onConfirm={() =>
          clear.mutate(undefined, {
            onSuccess: () => {
              setConfirmClear(false)
              onLineRemoved()
              toast.success('Your cart is now empty.')
            },
            onError: (error) => setClearError(cartErrorMessage(error)),
          })
        }
      >
        <p>
          This removes all {itemsLabel(cart.itemCount)} from {store.name}.
        </p>
      </ConfirmDialog>
    </>
  )
}
