import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, ConfirmDialog, EmptyState, ErrorState, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { EmptyCartIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'
import { CartLineItem } from './CartLineItem'
import { cartErrorMessage } from './errors'
import { itemsLabel, storePath } from './format'
import { useCart, useCartBusy, useClearCart } from './hooks'
import { cartLevelIssues, issueText, lineIssue } from './issues'

const primaryLinkClass =
  'inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-5 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'
const secondaryLinkClass =
  'inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-white px-5 text-sm font-semibold text-slate-700 shadow-xs ring-1 ring-slate-300 transition-colors ring-inset hover:bg-slate-50 hover:ring-slate-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-400'
const textLinkClass =
  'inline-flex items-center gap-1 rounded text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * @typedef {object} CartContentsProps
 * @property {() => void} [onNavigate] Called when a link inside navigates away (the drawer closes itself).
 */

/**
 * The header's cart drawer: store, lines with live prices and issues, "Add more items" / "Clear cart" and the coupon
 * ticket. The totals and "Checkout" are `CartDrawerSummary`, in the drawer's pinned footer. `/cart` is `CartPageView`.
 * @param {CartContentsProps} props
 */
export function CartContents({ onNavigate }) {
  // Prices and stock are re-checked live on every read, so the cart refreshes whenever it is opened.
  const cart = useCart({ refetchOnMount: 'always' })
  const clear = useClearCart()
  const busy = useCartBusy()
  const toast = useToast()
  const [confirmClear, setConfirmClear] = useState(false)
  const [clearError, setClearError] = useState(null)

  // After a line disappears (remove, "−" at 1, clear), keep keyboard focus inside the cart.
  const rootRef = useRef(null)
  const [focusRequest, setFocusRequest] = useState(0)
  useEffect(() => {
    if (focusRequest > 0) rootRef.current?.focus()
  }, [focusRequest])
  const keepFocus = () => setFocusRequest((value) => value + 1)

  let body
  if (cart.isPending) {
    body = (
      <div role="status" aria-label="Loading your cart" className="grid gap-6">
        <div className="space-y-3">
          <Skeleton className="h-16 w-full rounded-2xl" />
          {Array.from({ length: 3 }, (_, index) => (
            <div key={index} className="flex gap-3 p-3">
              <Skeleton className="size-18 rounded-xl" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-1/2" />
                <Skeleton className="h-3 w-1/4" />
                <Skeleton className="h-8 w-28 rounded-xl" />
              </div>
            </div>
          ))}
        </div>
      </div>
    )
  } else if (cart.isError) {
    const failure = {
      title: 'Could not load your cart',
      message: cartErrorMessage(cart.error),
      correlationId: cart.error instanceof ApiError ? cart.error.correlationId : null,
      onRetry: () => void cart.refetch(),
    }
    body = <ErrorState {...failure} />
  } else if (!cart.data.store || cart.data.lines.length === 0) {
    body = (
      <EmptyState
        illustration={<EmptyCartIllustration className="animate-float w-full" />}
        title="Your cart is empty"
        description="Add groceries from one store, or dishes from one restaurant."
        action={
          <>
            <Link to="/search" onClick={onNavigate} className={primaryLinkClass}>
              <Icon name="bag" className="size-4" />
              Browse products
            </Link>
            <Link to="/restaurants" onClick={onNavigate} className={secondaryLinkClass}>
              <Icon name="cloche" className="size-4" />
              Find restaurants
            </Link>
          </>
        }
      />
    )
  } else {
    const view = cart.data
    const store = view.store
    const isRestaurant = store.type === 'RESTAURANT'
    const context = { lines: view.lines, store, subtotal: view.subtotal }
    const issues = cartLevelIssues(view)

    const storeHeader = (
      <div className="flex items-center gap-3">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-11 shrink-0 items-center justify-center rounded-xl ring-1',
            isRestaurant ? 'bg-orange-50 ring-orange-100' : 'bg-emerald-50 ring-emerald-100',
          )}
        >
          {isRestaurant ? <Icon name="cloche" className="size-5.5" /> : <Icon name="store" className="size-5.5" />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium text-slate-500">
            {isRestaurant ? 'From the restaurant' : 'From the store'}
          </p>
          <Link
            to={storePath(store)}
            onClick={onNavigate}
            className="block truncate text-base font-semibold text-slate-900 hover:text-emerald-800 hover:underline"
          >
            {store.name}
          </Link>
        </div>
        <span className="shrink-0 rounded-full bg-white px-2.5 py-1 text-xs font-semibold text-slate-600 tabular-nums ring-1 ring-slate-200 ring-inset">
          {itemsLabel(view.itemCount)}
        </span>
      </div>
    )

    const issueAlerts =
      issues.length > 0 ? (
        <div className="space-y-2">
          {issues.map((issue) => (
            <Alert key={`${issue.code}-${issue.itemId ?? 'cart'}`} tone={issue.blocking ? 'danger' : 'warning'}>
              {issueText(issue, context)}
            </Alert>
          ))}
        </div>
      ) : null

    const lineList = (
      <ul aria-label="Items in your cart" className="divide-y divide-slate-100">
        {view.lines.map((line) => (
          <CartLineItem
            key={`${line.type}-${line.itemId}`}
            line={line}
            issue={lineIssue(view, line)}
            storeName={store.name}
            onRemoved={keepFocus}
          />
        ))}
      </ul>
    )

    const listActions = (
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link to={storePath(store)} onClick={onNavigate} className={textLinkClass}>
          <span aria-hidden="true" className="text-base leading-none">
            +
          </span>
          Add more items
        </Link>
        <Button
          variant="ghost"
          size="sm"
          disabled={busy}
          onClick={() => {
            setClearError(null)
            setConfirmClear(true)
          }}
          className="text-red-600 hover:bg-red-50 hover:text-red-700"
        >
          <Icon name="trash" className="size-4" />
          Clear cart
        </Button>
      </div>
    )

    // Phase 12: coupons are applied (and their discount shown) at checkout; the drawer shows a ticket that leads there.
    const coupon = view.coupon ?? null
    const couponTone = !coupon
      ? 'border-amber-300 bg-gradient-to-r from-amber-50/80 to-white hover:border-amber-400 hover:from-amber-50'
      : coupon.valid
        ? 'border-emerald-300 bg-emerald-50/70 hover:border-emerald-400 hover:bg-emerald-50'
        : 'border-red-300 bg-red-50/70 hover:border-red-400 hover:bg-red-50'
    const couponTicket = (
      <Link
        to="/checkout"
        onClick={onNavigate}
        className={cn(
          'group/coupon flex w-full items-center gap-3 rounded-xl border border-dashed px-3 py-2.5 text-left transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
          couponTone,
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            'flex size-9 shrink-0 items-center justify-center rounded-lg bg-white ring-1',
            !coupon ? 'ring-amber-200' : coupon.valid ? 'ring-emerald-200' : 'ring-red-200',
          )}
        >
          <Icon name={coupon && !coupon.valid ? 'alertCircle' : 'tag'} className="size-5" />
        </span>
        <span className="min-w-0 flex-1">
          {coupon ? (
            <span className={cn('block text-sm font-semibold', coupon.valid ? 'text-emerald-900' : 'text-red-900')}>
              <span className="font-mono break-all">{coupon.code}</span> {coupon.valid ? 'applied' : 'can’t be used'}
            </span>
          ) : (
            <span className="block text-sm font-semibold text-slate-900">Have a coupon?</span>
          )}
          <span
            className={cn(
              'block text-xs',
              coupon?.valid ? 'text-emerald-800' : coupon ? 'text-red-800' : 'text-slate-500',
            )}
          >
            {!coupon
              ? 'Apply it at checkout.'
              : coupon.valid
                ? 'Your discount is shown at checkout.'
                : 'Remove or change it at checkout.'}
          </span>
        </span>
        <Icon
          name="chevronRight"
          className="size-4 shrink-0 text-slate-400 transition-transform group-hover/coupon:translate-x-0.5"
        />
      </Link>
    )

    const clearDialog = (
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
              keepFocus()
              toast.success('Your cart is now empty.')
            },
            onError: (error) => setClearError(cartErrorMessage(error)),
          })
        }
      >
        <p>
          This removes all {itemsLabel(view.itemCount)} from {store.name}.
        </p>
      </ConfirmDialog>
    )

    body = (
      <div className="space-y-4">
        <div className="rounded-2xl bg-slate-50 p-3 ring-1 ring-slate-200/70 ring-inset">{storeHeader}</div>
        {issueAlerts}
        <div className="-mt-2">{lineList}</div>
        {listActions}
        {couponTicket}
        {clearDialog}
      </div>
    )
  }

  return (
    <div ref={rootRef} tabIndex={-1} aria-busy={cart.isFetching || undefined} className="focus:outline-none">
      {body}
    </div>
  )
}
