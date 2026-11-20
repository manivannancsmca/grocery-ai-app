import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { RestaurantIllustration } from '../../shared/illustrations'
import { Alert, Button, Spinner } from '../../shared/components/ui'
import { useCurrentPosition } from '../../shared/hooks/useCurrentPosition'
import { DEFAULT_RADIUS_KM } from './api'
import { useRestaurantSearch } from './hooks'
import { useDeliveryLocation } from '../stores/deliveryLocation'
import { nearText, restaurantsHref } from './location'
import { RestaurantCard, RestaurantCardSkeleton } from './RestaurantCard'

const STRIP_SIZE = 6

/**
 * Storefront strip: up to six restaurants near the customer's default address, near the browser's location after
 * "Use my location", or near a saved address or map point picked with "Choose location" (also the way out when
 * location access is refused). Without a location it lists restaurants without distances.
 */
export function NearbyRestaurants() {
  // Phase 15: the app-wide delivery location (the header's "Deliver to"); choosing here changes it everywhere.
  const delivery = useDeliveryLocation()
  const {
    locate,
    isLocating,
    error: locateError,
  } = useCurrentPosition('use "Choose location" to pick a saved address or a point on the map')
  const location = delivery.location
  const chosenLocation = delivery.isChosen ? delivery.location : null

  const search = useRestaurantSearch(
    { lat: location?.latitude, lng: location?.longitude, page: 0, size: STRIP_SIZE },
    { enabled: !delivery.isPending },
  )

  async function locateMe() {
    const coords = await locate()
    if (coords) delivery.choose({ ...coords, source: 'browser', label: 'your current location' })
  }

  // "See all" keeps the location: the full page reads it from the URL.
  const seeAllHref = chosenLocation ? restaurantsHref(chosenLocation) : '/restaurants'
  const restaurants = search.data?.content ?? []

  return (
    <section aria-labelledby="restaurants-near-you" className="@container">
      {/* Container (not viewport) breakpoints: beside the sidebar the section is much narrower than the screen. */}
      <div className="mb-4 flex flex-col gap-3 @2xl:flex-row @2xl:items-end @2xl:justify-between">
        <div className="flex items-start gap-3">
          <span
            aria-hidden="true"
            className="mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-xl bg-orange-50 ring-1 ring-orange-100"
          >
            <Icon name="cloche" className="size-5" />
          </span>
          <div className="min-w-0">
            <h2 id="restaurants-near-you" className="text-lg font-semibold text-slate-900">
              Restaurants near you
            </h2>
            <p className="mt-0.5 flex items-center gap-1 text-sm text-slate-500">
              <Icon name="mapPin" className="size-3.5" />
              {location ? nearText(location) : 'Share your location to see the closest restaurants first.'}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {location?.source !== 'browser' && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void locateMe()}
              disabled={isLocating}
              className="rounded-full"
            >
              {isLocating ? <Spinner size="sm" /> : <Icon name="crosshair" className="size-4" />}
              {isLocating ? 'Locating…' : 'Use my location'}
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={delivery.openChooser} className="rounded-full">
            <Icon name="mapPin" className="size-4" />
            Choose location
          </Button>
          <Link
            to={seeAllHref}
            className="group/link inline-flex h-8 items-center gap-1 rounded-full px-2 text-sm font-semibold text-emerald-700 hover:text-emerald-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
          >
            See all restaurants
            <Icon name="arrowRight" className="size-4 transition-transform group-hover/link:translate-x-0.5" />
          </Link>
        </div>
      </div>

      {locateError && (
        <p role="alert" className="mb-3 text-xs font-medium text-amber-700">
          {locateError}
        </p>
      )}

      {delivery.isPending || search.isPending ? (
        <div role="status" aria-label="Loading restaurants" className="grid gap-4 @lg:grid-cols-2 @3xl:grid-cols-3">
          {Array.from({ length: 3 }, (_, index) => (
            <RestaurantCardSkeleton key={index} compact />
          ))}
        </div>
      ) : search.isError ? (
        <Alert
          tone="warning"
          title="Restaurants could not be loaded"
          actions={
            <Button variant="secondary" size="sm" onClick={() => void search.refetch()}>
              Try again
            </Button>
          }
        >
          {search.error.message}
        </Alert>
      ) : restaurants.length === 0 ? (
        <div className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-slate-300 bg-white/70 px-5 py-6 text-center @md:flex-row @md:text-left">
          <RestaurantIllustration className="w-24 shrink-0" />
          <p className="text-sm text-slate-600">
            {location ? `No restaurants within ${DEFAULT_RADIUS_KM} km yet. ` : 'No restaurants are listed yet. '}
            <Link
              to="/restaurants?location=none"
              className="font-semibold text-emerald-700 hover:text-emerald-800 hover:underline"
            >
              Browse all restaurants
            </Link>
          </p>
        </div>
      ) : (
        <ul className="grid gap-4 @lg:grid-cols-2 @3xl:grid-cols-3">
          {restaurants.map((restaurant) => (
            <li key={restaurant.id}>
              <RestaurantCard restaurant={restaurant} compact />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
