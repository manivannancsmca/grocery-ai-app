import { type ReactNode, useId, useState } from 'react'
import { Link, useLocation } from 'react-router'
import type { CartStore } from '../../../features/cart/api'
import { CartLineItem } from '../../../features/cart/CartLineItem'
import { CheckoutAddressPicker } from '../../../features/cart/CheckoutAddressPicker'
import { CheckoutSummary } from '../../../features/cart/CheckoutSummary'
import { cartErrorMessage } from '../../../features/cart/errors'
import { itemsLabel, storePath } from '../../../features/cart/format'
import { useCheckoutPreview } from '../../../features/cart/hooks'
import { lineIssue } from '../../../features/cart/issues'
import { CouponPanel } from '../../../features/cart-coupons/CouponPanel'
import { useAddresses } from '../../../features/customer/hooks'
import { PlaceOrderSection } from '../../../features/orders/PlaceOrderSection'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { Alert, Button, EmptyState, ErrorState, PageHeader, Skeleton } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { EmptyCartIllustration, MapPinIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'

/** The address book, with a way back here afterwards. */
const MANAGE_ADDRESSES_PATH = `/account/addresses?returnTo=${encodeURIComponent('/checkout')}`
const linkClass =
  'rounded text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'
const primaryLinkClass =
  'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

type StepState = 'done' | 'current' | 'upcoming'

/** Address → Review → Pay, purely visual: derived from what the page shows (payment happens on the order page). */
function CheckoutSteps({ steps }: { steps: Array<{ label: string; hint: string; state: StepState }> }) {
  return (
    <ol aria-label="Checkout steps" className="animate-fade-up mb-6 flex items-center gap-2 rounded-2xl border border-slate-200/80 bg-white p-3 shadow-sm sm:gap-3 sm:p-4">
      {steps.map((step, index) => (
        <li key={step.label} aria-current={step.state === 'current' ? 'step' : undefined} className="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
          <span
            aria-hidden="true"
            className={cn(
              'flex size-8 shrink-0 items-center justify-center rounded-full text-sm font-bold transition-colors sm:size-9',
              step.state === 'done' && 'bg-emerald-600 text-white',
              step.state === 'current' && 'bg-emerald-50 text-emerald-700 ring-2 ring-emerald-600',
              step.state === 'upcoming' && 'bg-slate-100 text-slate-400',
            )}
          >
            {step.state === 'done' ? <Icon name="check" className="size-4" /> : index + 1}
          </span>
          <span className="min-w-0">
            <span className={cn('block truncate text-sm font-semibold', step.state === 'upcoming' ? 'text-slate-400' : 'text-slate-900')}>
              {step.label}
              {step.state === 'done' && <span className="sr-only"> (done)</span>}
            </span>
            <span className="hidden truncate text-xs text-slate-500 sm:block">{step.hint}</span>
          </span>
          {index < steps.length - 1 && (
            <span aria-hidden="true" className={cn('h-0.5 min-w-3 flex-1 rounded-full', step.state === 'done' ? 'bg-emerald-500' : 'bg-slate-200')} />
          )}
        </li>
      ))}
    </ol>
  )
}

/** A numbered checkout section: icon, heading, an optional link on the right. */
function StepCard({ icon, title, meta, actions, children }: { icon: ReactNode; title: string; meta?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  const titleId = useId()
  return (
    <section aria-labelledby={titleId} className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm shadow-slate-900/[0.03] sm:p-6">
      <header className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50 ring-1 ring-emerald-100">
            {icon}
          </span>
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-semibold text-slate-900">
              {title}
            </h2>
            {meta && <p className="text-xs text-slate-500">{meta}</p>}
          </div>
        </div>
        {actions}
      </header>
      {children}
    </section>
  )
}

/** The address chosen on the cart page (`<Link state={{ addressId }}>`), if any. */
function addressFromCart(state: unknown): string | null {
  const addressId = typeof state === 'object' && state !== null ? (state as { addressId?: unknown }).addressId : null
  return typeof addressId === 'string' ? addressId : null
}

/** `/checkout` — delivery address, the live checkout preview, the order total and "Place order" (Phase 6). */
export function CheckoutPage() {
  const addresses = useAddresses()
  const location = useLocation()
  // The cart page's choice is preselected (the default address otherwise).
  const [chosenId, setChosenId] = useState<string | null>(() => addressFromCart(location.state))

  const list = addresses.data ?? []
  // The default address is preselected; the customer's choice wins while it still exists.
  const selected = list.find((address) => address.id === chosenId) ?? list.find((address) => address.isDefault) ?? list[0] ?? null
  // Without saved addresses (or if they failed to load) the server falls back to the default / ADDRESS_REQUIRED.
  const preview = useCheckoutPreview(selected?.id ?? null, { enabled: !addresses.isPending })
  const updating = preview.isPlaceholderData || (preview.isFetching && !preview.isPending)

  let addressSection
  if (addresses.isPending) {
    addressSection = (
      <div role="status" aria-label="Loading your addresses" className="grid gap-3 sm:grid-cols-2">
        <Skeleton className="h-28 w-full rounded-2xl" />
        <Skeleton className="h-28 w-full rounded-2xl" />
      </div>
    )
  } else if (addresses.isError) {
    addressSection = (
      <ErrorState
        title="Could not load your addresses"
        message={addresses.error.message}
        correlationId={addresses.error instanceof ApiError ? addresses.error.correlationId : null}
        onRetry={() => void addresses.refetch()}
      />
    )
  } else if (list.length === 0) {
    addressSection = (
      <EmptyState
        illustration={<MapPinIllustration className="w-full" />}
        title="No saved addresses yet"
        description="Add where you want your order delivered to see the delivery fee."
        action={
          <Link to={MANAGE_ADDRESSES_PATH} className={primaryLinkClass}>
            Add a delivery address
          </Link>
        }
      />
    )
  } else {
    addressSection = <CheckoutAddressPicker addresses={list} selectedId={selected?.id ?? null} onSelect={setChosenId} />
  }

  let itemsSection
  let itemsMeta: string | undefined
  let summary = null
  let coupons = null
  if (preview.isPending) {
    itemsSection = (
      <div role="status" aria-label="Loading your order" className="space-y-3">
        <Skeleton className="h-20 w-full rounded-2xl" />
        <Skeleton className="h-20 w-full rounded-2xl" />
      </div>
    )
  } else if (preview.isError) {
    itemsSection = hasErrorCode(preview.error, 'ADDRESS_NOT_FOUND') ? (
      <Alert
        tone="danger"
        role="alert"
        title="This address could not be found"
        actions={
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              setChosenId(null)
              void addresses.refetch()
            }}
          >
            Refresh addresses
          </Button>
        }
      >
        It may have been deleted. Choose another address.
      </Alert>
    ) : (
      <ErrorState
        title="Could not prepare your checkout"
        message={cartErrorMessage(preview.error)}
        correlationId={preview.error instanceof ApiError ? preview.error.correlationId : null}
        onRetry={() => void preview.refetch()}
      />
    )
  } else if (preview.data.lines.length === 0 || !preview.data.store) {
    itemsSection = (
      <EmptyState
        illustration={<EmptyCartIllustration className="w-full" />}
        title="Your cart is empty"
        description="Add groceries from one store, or dishes from one restaurant, then come back to check out."
        action={
          <Link to="/search" className={primaryLinkClass}>
            Browse products
          </Link>
        }
      />
    )
  } else {
    const view = preview.data
    const store = view.store as CartStore
    const isRestaurant = store.type === 'RESTAURANT'
    itemsMeta = itemsLabel(view.itemCount)
    itemsSection = (
      <>
        <p className="mb-3 flex items-center gap-2.5 rounded-xl bg-slate-50 px-3 py-2.5 text-sm text-slate-600 ring-1 ring-slate-200/70 ring-inset">
          <span aria-hidden="true" className="flex">
            {isRestaurant ? <Icon name="cloche" className="size-5" /> : <Icon name="store" className="size-5" />}
          </span>
          <span>
            From{' '}
            <Link to={storePath(store)} className="font-semibold text-slate-900 hover:text-emerald-800 hover:underline">
              {store.name}
            </Link>
          </span>
        </p>
        <ul aria-label="Items in your order" className={cn('-mt-1 divide-y divide-slate-100 transition-opacity', updating && 'opacity-60')}>
          {view.lines.map((line) => (
            <CartLineItem key={`${line.type}-${line.itemId}`} line={line} issue={lineIssue(view, line)} storeName={store.name} />
          ))}
        </ul>
      </>
    )
    // Phase 12: offers of this store, a code field and the applied coupon.
    coupons = <CouponPanel storeId={store.id} storeName={store.name} subtotal={view.subtotal} coupon={view.coupon} updating={updating} />
    summary = (
      <CheckoutSummary
        preview={view}
        updating={updating}
        action={(blockersId) => (
          <PlaceOrderSection preview={view} addressId={selected?.id ?? null} updating={updating} blockersId={blockersId} />
        )}
      />
    )
  }

  const hasAddress = selected !== null
  const steps: Array<{ label: string; hint: string; state: StepState }> = [
    { label: 'Address', hint: 'Where to deliver', state: hasAddress ? 'done' : 'current' },
    { label: 'Review', hint: 'Items, offers & total', state: hasAddress ? 'current' : 'upcoming' },
    { label: 'Pay', hint: 'On the next screen', state: 'upcoming' },
  ]

  return (
    <div>
      <PageHeader title="Checkout" description="Choose where to deliver, check your items and review the total." />
      <CheckoutSteps steps={steps} />
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_23rem] xl:items-start">
        <div className="min-w-0 space-y-6">
          <StepCard
            icon={<Icon name="mapPin" className="size-5" />}
            title="Delivery address"
            actions={
              list.length > 0 ? (
                <Link to={MANAGE_ADDRESSES_PATH} className={linkClass}>
                  Add or edit addresses
                </Link>
              ) : undefined
            }
          >
            {addressSection}
          </StepCard>
          <StepCard icon={<Icon name="bag" className="size-5" />} title="Your items" meta={itemsMeta}>
            <div aria-busy={updating || undefined}>{itemsSection}</div>
          </StepCard>
          {coupons}
        </div>
        {summary && <div className="xl:sticky xl:top-20">{summary}</div>}
      </div>
    </div>
  )
}
