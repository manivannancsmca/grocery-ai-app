import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, ConfirmDialog, Skeleton, Spinner } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { EmptyCartIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'
import { formatDistanceKm } from '../../shared/utils/format'
import { CartCouponRow } from '../cart-coupons/CartCouponRow'
import type { Address } from '../customer/api'
import { useAddresses } from '../customer/hooks'
import { StoreTile } from '../orders/OrderVisuals'
import type { CartIssue, CartStore, CartView, CheckoutPreview } from './api'
import { CartDeliverTo } from './CartDeliverTo'
import { CartLineItem } from './CartLineItem'
import { CartCheckoutBar, CartOrderSummary } from './CartOrderSummary'
import { cartErrorMessage } from './errors'
import { itemsLabel, storePath } from './format'
import { useCart, useCartBusy, useCheckoutPreview, useClearCart } from './hooks'
import { IllustratedError } from './IllustratedError'
import { issueText, lineIssue } from './issues'
import { cartBill, cartPageIssues, currentCoupon, hasTransientIssue, issuesAnnouncement, storeStatusLabel } from './summary'

const PAGE = '@container/cart w-full min-w-0'
const CARD = 'rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]'
/** Two columns (items | sticky summary) once the page itself is wide enough, whatever the menu takes. */
const COLUMNS = 'grid gap-5 @4xl/cart:grid-cols-[minmax(0,1fr)_22.5rem] @4xl/cart:items-start @6xl/cart:grid-cols-[minmax(0,1fr)_24rem] @6xl/cart:gap-6'

function PageTitle({ description }: { description: string }) {
  return (
    <header className="animate-fade-up mb-5 flex min-w-0 items-center gap-3.5">
      <span
        aria-hidden="true"
        className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-white to-emerald-50 shadow-sm ring-1 shadow-emerald-900/5 ring-emerald-100"
      >
        <Icon name="cart" className="size-7" />
      </span>
      <div className="min-w-0">
        <h1 className="text-2xl leading-tight font-bold tracking-tight text-slate-900 sm:text-[1.65rem]">Your cart</h1>
        <p className="mt-0.5 text-sm break-words text-slate-500">{description}</p>
      </div>
    </header>
  )
}

function LoadingCart() {
  return (
    <div role="status" aria-label="Loading your cart" className={COLUMNS}>
      <div className={cn(CARD, 'min-w-0 p-4 sm:p-5')}>
        <div className="flex items-center gap-3">
          <Skeleton className="size-12 rounded-2xl" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-4 w-1/2" />
          </div>
        </div>
        <div className="mt-4 divide-y divide-slate-100">
          {Array.from({ length: 3 }, (_, index) => (
            <div key={index} className="flex gap-3 py-4">
              <Skeleton className="size-16 rounded-xl" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-1/2" />
                <Skeleton className="h-3 w-1/3" />
                <Skeleton className="h-8 w-28 rounded-xl" />
              </div>
            </div>
          ))}
        </div>
      </div>
      <Skeleton className="h-96 w-full rounded-2xl" />
    </div>
  )
}

function StartTile({ to, icon, title, text, tone }: { to: string; icon: IconName; title: string; text: string; tone: 'green' | 'amber' }) {
  return (
    <Link
      to={to}
      className="group flex items-center gap-3 rounded-2xl border border-slate-200/80 bg-white p-3.5 text-left shadow-xs transition hover:-translate-y-0.5 hover:border-emerald-300 hover:shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 motion-reduce:transition-none"
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex size-11 shrink-0 items-center justify-center rounded-xl ring-1 ring-inset',
          tone === 'green' ? 'bg-gradient-to-br from-emerald-50 to-emerald-100 ring-emerald-200/70' : 'bg-gradient-to-br from-amber-50 to-orange-100 ring-amber-200/70',
        )}
      >
        <Icon name={icon} className="size-6" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold text-slate-900">{title}</span>
        <span className="block text-xs text-slate-500">{text}</span>
      </span>
      <Icon name="arrowRight" className="size-4 text-slate-400 transition-transform group-hover:translate-x-0.5" />
    </Link>
  )
}

function EmptyCart() {
  return (
    <section
      aria-labelledby="empty-cart-title"
      className="animate-fade-up relative isolate overflow-hidden rounded-3xl border border-slate-200/80 bg-gradient-to-br from-white via-emerald-50/40 to-amber-50/50 px-5 py-10 text-center shadow-sm sm:px-8 sm:py-12"
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="absolute -top-20 -right-16 size-64 rounded-full bg-emerald-200/30 blur-3xl" />
        <div className="absolute -bottom-24 -left-10 size-56 rounded-full bg-amber-200/30 blur-3xl" />
      </div>
      <EmptyCartIllustration className="animate-float mx-auto w-40 sm:w-48" />
      <h2 id="empty-cart-title" className="mt-4 text-xl font-bold tracking-tight text-slate-900">
        Your cart is empty
      </h2>
      <p className="mx-auto mt-1.5 max-w-md text-sm leading-relaxed text-slate-500">
        Add groceries from one store, or dishes from one restaurant. Prices and stock are checked live when you come back.
      </p>
      <div className="mx-auto mt-6 grid max-w-2xl gap-3 sm:grid-cols-2">
        <StartTile to="/search" icon="bag" title="Browse products" text="Fresh produce, dairy and daily needs" tone="green" />
        <StartTile to="/restaurants" icon="cutlery" title="Find restaurants" text="Meals from restaurants near you" tone="amber" />
      </div>
    </section>
  )
}

/** What blocks checkout, each in one sentence, above the items (only when something does). */
function Blockers({ id, issues, context, onRecheck, rechecking }: {
  id: string
  issues: readonly CartIssue[]
  context: Parameters<typeof issueText>[1]
  onRecheck?: () => void
  rechecking: boolean
}) {
  return (
    <div id={id} className="animate-fade-up rounded-2xl border border-red-200/80 bg-gradient-to-r from-red-50 to-rose-50/40 p-4 text-sm text-red-800 shadow-xs">
      <div className="flex items-start gap-3">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white ring-1 ring-red-100">
          <Icon name="alertCircle" className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold text-red-900">
            {issues.length === 1 ? 'One thing to fix before checkout' : `${issues.length} things to fix before checkout`}
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
 * `/cart` — the whole cart on one page: the store with its items (live prices and stock, issues on the rows), and an
 * order summary beside them on wide screens (address, nudges, coupon, the bill from the checkout preview, total and
 * "Proceed to checkout"); on narrower screens the summary follows the items and a bar with the total and the button
 * sticks to the bottom.
 */
export function CartPageView() {
  // Prices and stock are re-checked live on every read, so the cart refreshes whenever the page opens.
  const cart = useCart({ refetchOnMount: 'always' })
  const view = cart.data
  const hasItems = Boolean(view?.store && view.lines.length > 0)
  const addresses = useAddresses()
  const [chosenId, setChosenId] = useState<string | null>(null)
  const list = addresses.data ?? []
  // The default address is preselected; the customer's choice wins while it still exists.
  const selected = list.find((address) => address.id === chosenId) ?? list.find((address) => address.isDefault) ?? list[0] ?? null
  const preview = useCheckoutPreview(selected?.id ?? null, { enabled: hasItems && !addresses.isPending })

  // After a line disappears (remove, "−" at 1, clear), keep keyboard focus inside the cart.
  const rootRef = useRef<HTMLDivElement>(null)
  const [focusRequest, setFocusRequest] = useState(0)
  useEffect(() => {
    if (focusRequest > 0) rootRef.current?.focus()
  }, [focusRequest])
  const keepFocus = () => setFocusRequest((value) => value + 1)

  let description = 'Prices and stock are checked live every time you open your cart.'
  let body
  if (cart.isPending) {
    body = <LoadingCart />
  } else if (cart.isError) {
    body = (
      <IllustratedError
        title="Could not load your cart"
        message={cartErrorMessage(cart.error)}
        correlationId={cart.error instanceof ApiError ? cart.error.correlationId : null}
        onRetry={() => void cart.refetch()}
      />
    )
  } else if (!hasItems) {
    description = 'Pick a store or a restaurant to start an order.'
    body = <EmptyCart />
  } else {
    const data = cart.data
    const store = data.store as CartStore
    description = `${itemsLabel(data.itemCount)} from ${store.name}`
    body = (
      <CartWithItems
        cart={data}
        store={store}
        preview={preview.data}
        previewLoading={preview.isPending}
        previewUpdating={preview.isPlaceholderData || (preview.isFetching && !preview.isPending)}
        previewError={preview.isError && !preview.data ? preview.error : null}
        onRetryPreview={() => void preview.refetch()}
        onRecheck={() => {
          void cart.refetch()
          void preview.refetch()
        }}
        rechecking={cart.isFetching}
        addresses={{
          list,
          loading: addresses.isPending,
          failed: addresses.isError,
          retry: () => void addresses.refetch(),
          selected,
          choose: setChosenId,
        }}
        onLineRemoved={keepFocus}
      />
    )
  }

  return (
    <div ref={rootRef} tabIndex={-1} aria-busy={cart.isFetching || undefined} className={cn(PAGE, 'focus:outline-none')}>
      <PageTitle description={description} />
      <section aria-label="Cart" className="animate-fade-up">
        {body}
      </section>
    </div>
  )
}

interface CartWithItemsProps {
  cart: CartView
  store: CartStore
  preview: CheckoutPreview | undefined
  previewLoading: boolean
  previewUpdating: boolean
  previewError: unknown
  onRetryPreview: () => void
  onRecheck: () => void
  rechecking: boolean
  addresses: {
    list: readonly Address[]
    loading: boolean
    failed: boolean
    retry: () => void
    selected: Address | null
    choose: (id: string) => void
  }
  onLineRemoved: () => void
}

function CartWithItems({
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
}: CartWithItemsProps) {
  const toast = useToast()
  const clear = useClearCart()
  const busy = useCartBusy()
  const blockersId = useId()
  const storeTitleId = useId()
  const [confirmClear, setConfirmClear] = useState(false)
  const [clearError, setClearError] = useState<string | null>(null)

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
                <p className="text-xs font-medium text-slate-500">
                  {isRestaurant ? 'Restaurant' : 'Grocery store'}
                </p>
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
                <CartLineItem key={`${line.type}-${line.itemId}`} line={line} issue={lineIssue(cart, line)} storeName={store.name} onRemoved={onLineRemoved} />
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
            coupon={<CartCouponRow storeId={store.id} storeName={store.name} subtotal={cart.subtotal} coupon={coupon} updating={previewUpdating} />}
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
