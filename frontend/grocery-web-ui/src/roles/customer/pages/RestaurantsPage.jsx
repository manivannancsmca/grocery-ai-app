import { useId, useState } from 'react'
import { IllustratedError } from '../../../features/cart/IllustratedError'
import { MAX_SEARCH_RESULTS } from '../../../features/catalog/api'
import {
  DEFAULT_RADIUS_KM,
  RADIUS_OPTIONS_KM,
  RESTAURANT_PAGE_SIZE,
  TOP_RATED_MIN,
} from '../../../features/restaurant-discovery/api'
import { useRestaurantSearch } from '../../../features/restaurant-discovery/hooks'
import { locationFromParams, locationParams, nearText } from '../../../features/restaurant-discovery/location'
import { LocationChooser } from '../../../features/restaurant-discovery/LocationChooser'
import { RestaurantCard, RestaurantCardSkeleton } from '../../../features/restaurant-discovery/RestaurantCard'
import { cuisineIcon, cuisineTheme } from '../../../features/restaurant-discovery/theme'
import { useDeliveryLocation } from '../../../features/stores/deliveryLocation'
import { ApiError } from '../../../shared/api/problemDetail'
import { FoodTypeMark } from '../../../shared/components/FoodTypeMark'
import { Button, EmptyState, Input, Pagination, Select, Spinner } from '../../../shared/components/ui'
import { useCurrentPosition } from '../../../shared/hooks/useCurrentPosition'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { MapPinIllustration, RestaurantIllustration, SearchIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { humanizeToken } from '../../../shared/utils/format'
import { PillToggle, ShopperPageHeader } from '../components/ShopperPageHeader'

/**
 * @param {string | null} value
 * @returns {number}
 */
function radiusFromParam(value) {
  const parsed = Number(value)
  return RADIUS_OPTIONS_KM.includes(parsed) ? parsed : DEFAULT_RADIUS_KM
}

/** What the location error suggests instead of the browser's location. */
const CHOOSE_INSTEAD = 'use "Choose location" to pick a saved address or a point on the map'

const LOCATION_BUTTON =
  'inline-flex h-9 items-center gap-1.5 rounded-full bg-white px-3.5 text-sm font-semibold text-slate-700 shadow-xs ring-1 ring-slate-200 transition ring-inset hover:bg-emerald-50 hover:text-emerald-800 hover:ring-emerald-200 disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * `/restaurants` — restaurant discovery, nearest first. Location: `?lat=&lng=` (the browser's location, or with
 * `&near=` a saved address or map point chosen by hand), `?location=none` (everywhere), otherwise the app's
 * delivery location (the header's "Deliver to": the default address unless another was chosen). Choosing a location
 * here also changes the delivery location. Query, cuisines, toggles, radius and page are in the URL too.
 */
export function RestaurantsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const radiusId = useId()
  const sortId = useId()
  const { locate, isLocating, error: locateError } = useCurrentPosition(CHOOSE_INSTEAD)
  const delivery = useDeliveryLocation()
  const [choosing, setChoosing] = useState(false)

  const queryText = searchParams.get('q') ?? ''
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)
  const cuisines = searchParams.getAll('cuisine').filter(Boolean)
  const pureVeg = searchParams.get('pureVeg') === 'true'
  const openNow = searchParams.get('openNow') === 'true'
  // Phase 12: "Top rated first" (`sort=rating`) and "Rated 4.0+" (`minRating=4`).
  const sortByRating = searchParams.get('sort') === 'rating'
  const topRated = searchParams.get('minRating') === String(TOP_RATED_MIN)
  const page = pageFromParam(searchParams.get('page'))
  const radiusKm = radiusFromParam(searchParams.get('radiusKm'))
  const everywhere = searchParams.get('location') === 'none'
  const urlLocation = locationFromParams(searchParams.get('lat'), searchParams.get('lng'), searchParams.get('near'))
  const location = everywhere ? null : (urlLocation ?? delivery.location)
  const waitingForAddress = !everywhere && !urlLocation && delivery.isPending

  const params = {
    lat: location?.latitude,
    lng: location?.longitude,
    radiusKm: location ? radiusKm : undefined,
    q: debouncedQuery || undefined,
    cuisine: [...cuisines].sort(),
    pureVeg,
    openNow,
    sort: sortByRating ? 'rating' : undefined,
    minRating: topRated ? TOP_RATED_MIN : undefined,
    page,
    size: RESTAURANT_PAGE_SIZE,
  }
  const search = useRestaurantSearch(params, { enabled: !waitingForAddress })
  const data = search.data
  const hasFilters = Boolean(debouncedQuery) || cuisines.length > 0 || pureVeg || openNow || topRated

  const cuisineOptions = [
    ...(data?.facets.cuisines ?? []),
    ...cuisines
      .filter((value) => !data?.facets.cuisines.some((facet) => facet.value === value))
      .map((value) => ({ value, count: 0 })),
  ]

  function toggleCuisine(value) {
    const next = cuisines.includes(value) ? cuisines.filter((c) => c !== value) : [...cuisines, value]
    updateParams({ cuisine: next, page: null })
  }

  /** Searches near `chosen` and makes it the app's delivery location too. */
  function applyLocation(chosen) {
    updateParams({ ...locationParams(chosen), location: null, page: null })
    delivery.choose(chosen)
  }

  async function locateBrowser() {
    const coords = await locate()
    if (coords) applyLocation({ ...coords, source: 'browser', label: 'your current location' })
  }

  function backToDefaultAddress() {
    updateParams({ lat: null, lng: null, near: null, location: null, page: null })
    delivery.resetToDefault()
  }

  function clearFilters() {
    updateParams({ q: null, cuisine: null, pureVeg: null, openNow: null, minRating: null, page: null })
  }

  function searchEverywhere() {
    updateParams({ lat: null, lng: null, near: null, location: 'none', radiusKm: null, page: null })
  }

  const locationText = location
    ? nearText(location)
    : waitingForAddress
      ? 'Finding your default address…'
      : 'Everywhere — distances are not shown'
  const canUseDefault = Boolean(delivery.defaultLocation) && (Boolean(urlLocation) || everywhere || delivery.isChosen)

  const emptyIllustration = hasFilters ? (
    <SearchIllustration className="w-full" />
  ) : location ? (
    <MapPinIllustration className="w-full" />
  ) : (
    <RestaurantIllustration className="w-full" />
  )

  return (
    <div className="space-y-6">
      <ShopperPageHeader
        icon="cloche"
        eyebrow="Order food"
        title="Restaurants"
        description="Order from kitchens near you. Search by restaurant, cuisine or dish."
      />

      <div className="animate-fade-up overflow-hidden rounded-3xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]">
        <section
          aria-label="Location"
          className="bg-gradient-to-r from-emerald-50 via-teal-50/70 to-sky-50/60 px-4 py-3.5 sm:px-5"
        >
          <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div className="flex min-w-0 flex-wrap items-center gap-3">
              <p className="flex min-w-0 items-center gap-3 text-sm font-semibold text-slate-800">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-emerald-100">
                  <Icon name="mapPin" className="size-[1.15rem]" />
                </span>
                {locationText}
              </p>
              {location && (
                <span className="flex items-center gap-2">
                  <label htmlFor={radiusId} className="text-sm text-slate-600">
                    Within
                  </label>
                  <Select
                    id={radiusId}
                    className="h-9 w-24 rounded-full bg-white"
                    value={radiusKm}
                    onChange={(event) => {
                      const value = Number(event.target.value)
                      updateParams({ radiusKm: value === DEFAULT_RADIUS_KM ? null : String(value), page: null })
                    }}
                  >
                    {RADIUS_OPTIONS_KM.map((km) => (
                      <option key={km} value={km}>
                        {km} km
                      </option>
                    ))}
                  </Select>
                </span>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {location?.source !== 'browser' && (
                <button
                  type="button"
                  className={LOCATION_BUTTON}
                  onClick={() => void locateBrowser()}
                  disabled={isLocating}
                >
                  {isLocating ? <Spinner size="sm" /> : <Icon name="crosshair" className="size-4" />}
                  {isLocating ? 'Locating…' : 'Use my location'}
                </button>
              )}
              <button type="button" className={LOCATION_BUTTON} onClick={() => setChoosing(true)}>
                <Icon name="mapPin" className="size-4" />
                Choose location
              </button>
              {canUseDefault && (
                <button type="button" className={LOCATION_BUTTON} onClick={backToDefaultAddress}>
                  <Icon name="home" className="size-4" />
                  Use my default address
                </button>
              )}
              {location && (
                <button
                  type="button"
                  className={cn(LOCATION_BUTTON, 'bg-transparent shadow-none ring-transparent')}
                  onClick={searchEverywhere}
                >
                  <Icon name="globe" className="size-4" />
                  Search everywhere
                </button>
              )}
            </div>
          </div>
          {locateError && (
            <p role="alert" className="mt-2 text-xs font-medium text-amber-700">
              {locateError}
            </p>
          )}
          <LocationChooser
            open={choosing}
            onClose={() => setChoosing(false)}
            current={location}
            onChoose={applyLocation}
          />
        </section>

        <div className="space-y-4 p-4 sm:p-5">
          <label className="relative block">
            <span className="sr-only">Search restaurants or dishes</span>
            <Icon name="search" className="pointer-events-none absolute top-1/2 left-4 size-5 -translate-y-1/2" />
            <Input
              type="search"
              value={queryText}
              placeholder="Search restaurants or dishes"
              className="h-12 rounded-full bg-slate-50/80 pl-12 text-base focus:bg-white sm:text-sm"
              onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
            />
          </label>

          <div className="flex flex-wrap items-center gap-2">
            <PillToggle
              role="switch"
              pressed={pureVeg}
              onClick={() => updateParams({ pureVeg: pureVeg ? null : 'true', page: null })}
              icon={<FoodTypeMark foodType="VEG" />}
            >
              Pure veg
            </PillToggle>
            <PillToggle
              role="switch"
              pressed={openNow}
              onClick={() => updateParams({ openNow: openNow ? null : 'true', page: null })}
              icon={<span className="size-2 rounded-full bg-emerald-500" />}
            >
              Open now
            </PillToggle>
            <PillToggle
              role="switch"
              pressed={topRated}
              onClick={() => updateParams({ minRating: topRated ? null : String(TOP_RATED_MIN), page: null })}
              icon={<Icon name="star" className="size-4" />}
            >
              {`Rated ${TOP_RATED_MIN}.0+`}
            </PillToggle>
            {hasFilters && (
              <button
                type="button"
                onClick={clearFilters}
                className="h-9 rounded-full px-2 text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
              >
                Clear filters
              </button>
            )}
            <span className="flex w-full items-center gap-2 pt-1 sm:ml-auto sm:w-auto sm:pt-0">
              <label htmlFor={sortId} className="text-sm whitespace-nowrap text-slate-600">
                Sort by
              </label>
              <Select
                id={sortId}
                className="h-9 flex-1 rounded-full sm:w-44 sm:flex-none"
                value={sortByRating ? 'rating' : 'recommended'}
                onChange={(event) =>
                  updateParams({ sort: event.target.value === 'rating' ? 'rating' : null, page: null })
                }
              >
                <option value="recommended">Recommended</option>
                <option value="rating">Top rated first</option>
              </Select>
            </span>
          </div>
        </div>
      </div>

      {cuisineOptions.length > 0 && (
        <section aria-labelledby="cuisines-heading" className="animate-fade-up">
          <h2 id="cuisines-heading" className="mb-3 text-lg font-semibold text-slate-900">
            What’s on your mind?
          </h2>
          {/* relative: the cards' screen-reader-only text is absolutely positioned; without a positioned scroller it
              escaped the scrolling and widened the whole page on phones. */}
          <ul
            aria-label="Cuisines"
            className="relative -mx-1 flex gap-3 overflow-x-auto px-1 pb-2 [scrollbar-width:thin]"
          >
            {cuisineOptions.map((facet) => {
              const selected = cuisines.includes(facet.value)
              const theme = cuisineTheme([facet.value], facet.value)
              return (
                <li key={facet.value} className="shrink-0">
                  <button
                    type="button"
                    aria-pressed={selected}
                    onClick={() => toggleCuisine(facet.value)}
                    className="group flex w-24 flex-col items-center gap-1.5 rounded-2xl p-1.5 text-center focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
                  >
                    <span
                      aria-hidden="true"
                      className={cn(
                        'relative flex size-16 items-center justify-center rounded-full bg-gradient-to-br ring-2 transition duration-200 group-hover:-translate-y-0.5 group-hover:shadow-md',
                        theme.cover,
                        selected ? 'shadow-md ring-emerald-600 ring-offset-2' : 'ring-white',
                      )}
                    >
                      <span className="flex size-11 items-center justify-center rounded-full bg-white/85">
                        <Icon name={cuisineIcon(facet.value)} className="size-7" />
                      </span>
                      {selected && (
                        <span className="absolute -top-0.5 -right-0.5 flex size-5 items-center justify-center rounded-full bg-emerald-600 text-white shadow-sm ring-2 ring-white">
                          <Icon name="check" className="size-3" />
                        </span>
                      )}
                    </span>
                    <span
                      className={cn(
                        'text-xs leading-tight font-semibold',
                        selected ? 'text-emerald-800' : 'text-slate-700',
                      )}
                    >
                      {humanizeToken(facet.value)}
                    </span>
                    {facet.count > 0 && (
                      <span className="text-[0.68rem] text-slate-500 tabular-nums">
                        {facet.count} <span className="sr-only">restaurants</span>
                      </span>
                    )}
                  </button>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      {waitingForAddress || search.isPending ? (
        <div className="@container">
          <div
            role="status"
            aria-label="Loading restaurants"
            className="grid gap-5 @lg:grid-cols-2 @3xl:grid-cols-3 @6xl:grid-cols-4"
          >
            {Array.from({ length: 6 }, (_, index) => (
              <RestaurantCardSkeleton key={index} />
            ))}
          </div>
        </div>
      ) : search.isError ? (
        <IllustratedError
          title="Could not load restaurants"
          message={search.error.message}
          correlationId={search.error instanceof ApiError ? search.error.correlationId : null}
          onRetry={() => void search.refetch()}
        />
      ) : data && data.content.length === 0 ? (
        <EmptyState
          size="lg"
          illustration={emptyIllustration}
          title="No restaurants found"
          description={
            hasFilters
              ? 'Nothing matches these filters. Try another dish or cuisine, or clear the filters.'
              : location
                ? `There are no restaurants within ${radiusKm} km of this location yet.`
                : 'No restaurants are listed yet. Check back soon.'
          }
          action={
            hasFilters ? (
              <Button onClick={clearFilters}>Clear filters</Button>
            ) : location ? (
              <Button onClick={searchEverywhere}>
                <Icon name="globe" className="size-4" />
                Search everywhere
              </Button>
            ) : undefined
          }
        />
      ) : data ? (
        <div className="animate-fade-up @container">
          <div className="mb-3 flex items-baseline justify-between gap-3">
            <p className="text-sm font-semibold text-slate-700" aria-live="polite">
              {data.totalElements} restaurant{data.totalElements === 1 ? '' : 's'}
              {location && <span className="font-normal text-slate-500"> within {radiusKm} km</span>}
            </p>
          </div>
          <ul
            aria-label="Restaurants"
            className={cn(
              'grid gap-5 transition-opacity @lg:grid-cols-2 @3xl:grid-cols-3 @6xl:grid-cols-4',
              search.isPlaceholderData && 'opacity-60',
            )}
          >
            {data.content.map((restaurant) => (
              <li key={restaurant.id}>
                <RestaurantCard restaurant={restaurant} />
              </li>
            ))}
          </ul>
          <Pagination
            page={data.page}
            size={data.size}
            // Contract: page × size may not exceed 1,000 results.
            totalElements={Math.min(data.totalElements, MAX_SEARCH_RESULTS)}
            totalPages={Math.min(data.totalPages, Math.floor(MAX_SEARCH_RESULTS / RESTAURANT_PAGE_SIZE))}
            disabled={search.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      ) : null}
    </div>
  )
}
