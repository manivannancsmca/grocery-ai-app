import { Link } from 'react-router'
import { useAuth } from '../../shared/auth/useAuth'
import { Button } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { deliveryPlaceText, useDeliveryLocation } from './deliveryLocation'
import { useNearbyStores } from './hooks'
import { StoreCard, StoreCardSkeleton } from './StoreCard'

const STRIP_SIZE = 8
const GRID = 'grid grid-cols-2 gap-3 sm:gap-4 @2xl:grid-cols-3 @4xl:grid-cols-4 @5xl:grid-cols-5'

/**
 * Storefront strip: the grocery stores that deliver to the delivery location, nearest first. Without a location it
 * asks for one; the header's "Deliver to" and the "Change" button here open the same location dialog.
 */
export function NearbyStores() {
  const { status, area } = useAuth()
  const { location, isPending: locationPending, openChooser } = useDeliveryLocation()
  const nearby = useNearbyStores(location)
  const stores = nearby.data?.content ?? []
  const isCustomer = status === 'authenticated' && area === 'CUSTOMER'

  let body
  if (locationPending || (location && nearby.isPending)) {
    body = (
      <div role="status" aria-label="Loading stores near you" className={GRID}>
        {Array.from({ length: 4 }, (_, index) => (
          <StoreCardSkeleton key={index} />
        ))}
      </div>
    )
  } else if (!location) {
    body = (
      <div className="flex flex-col items-start gap-3 rounded-2xl border border-dashed border-emerald-300 bg-emerald-50/50 p-5 sm:flex-row sm:items-center">
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-emerald-100"
        >
          <Icon name="mapPin" className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold text-slate-900">Where should we deliver?</p>
          <p className="text-sm text-slate-600">
            Pick a location to see the grocery stores that deliver there and shop from them.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" onClick={openChooser}>
            <Icon name="crosshair" className="size-4" />
            Choose location
          </Button>
          {isCustomer && (
            <Link
              to="/account/addresses"
              className="inline-flex h-8 items-center rounded-lg px-3 text-sm font-semibold text-emerald-700 hover:bg-emerald-100/60 focus-visible:outline-2 focus-visible:outline-emerald-600"
            >
              Add an address
            </Link>
          )}
        </div>
      </div>
    )
  } else if (nearby.isError) {
    body = (
      <div
        role="alert"
        className="flex flex-wrap items-center gap-3 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
      >
        <Icon name="warning" className="size-5 shrink-0" />
        <span className="min-w-0 flex-1">Stores near you could not be loaded.</span>
        <Button variant="secondary" size="sm" onClick={() => void nearby.refetch()}>
          Try again
        </Button>
      </div>
    )
  } else if (stores.length === 0) {
    body = (
      <div className="flex flex-col items-start gap-3 rounded-2xl border border-slate-200 bg-white p-5 sm:flex-row sm:items-center">
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-slate-50 ring-1 ring-slate-200"
        >
          <Icon name="store" className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-semibold text-slate-900">No store delivers here yet</p>
          <p className="text-sm text-slate-600">
            Stores deliver up to {nearby.data?.deliveryLimitKm ?? 15} km. Try another address, or browse every store
            below.
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={openChooser}>
          Choose another location
        </Button>
      </div>
    )
  } else {
    body = (
      <ul aria-label="Grocery stores near you" className={GRID}>
        {stores.slice(0, STRIP_SIZE).map((store) => (
          <li key={store.id} className="flex">
            <StoreCard store={store} className="flex-1" />
          </li>
        ))}
      </ul>
    )
  }

  return (
    <section aria-labelledby="stores-near-you" className="animate-fade-up @container">
      <div className="mb-4 flex flex-col gap-3 @2xl:flex-row @2xl:items-end @2xl:justify-between">
        <div className="flex items-start gap-3">
          <span
            aria-hidden="true"
            className="mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-xl bg-emerald-50 ring-1 ring-emerald-100"
          >
            <Icon name="store" className="size-5" />
          </span>
          <div className="min-w-0">
            <h2 id="stores-near-you" className="text-lg font-semibold text-slate-900">
              Grocery stores near you
            </h2>
            <p className="mt-0.5 flex items-center gap-1 text-sm text-slate-500">
              <Icon name="mapPin" className="size-3.5" />
              {location
                ? `Delivering to ${deliveryPlaceText(location)}${stores.length > 0 ? ` · ${stores.length} store${stores.length === 1 ? '' : 's'}` : ''}`
                : 'Stores deliver to addresses near them.'}
            </p>
          </div>
        </div>
        {location && (
          <Button variant="ghost" size="sm" onClick={openChooser} className="self-start rounded-full @2xl:self-auto">
            <Icon name="mapPin" className="size-4" />
            Change location
          </Button>
        )}
      </div>
      {body}
    </section>
  )
}
