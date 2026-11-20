import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { MenuItemCartControl } from '../../../features/cart/CartItemControl'
import { IllustratedError } from '../../../features/cart/IllustratedError'
import { DAYS_OF_WEEK } from '../../../features/restaurant/api'
import { RESTAURANT_NOT_FOUND } from '../../../features/restaurant-discovery/api'
import { useRestaurantDetail } from '../../../features/restaurant-discovery/hooks'
import { hoursForDay, todayInIndia } from '../../../features/restaurant-discovery/openingHours'
import { RestaurantStatusBadges } from '../../../features/restaurant-discovery/RestaurantCard'
import { RestaurantCover } from '../../../features/restaurant-discovery/RestaurantCover'
import { RestaurantMenu } from '../../../features/restaurant-discovery/RestaurantMenu'
import { cuisineTheme } from '../../../features/restaurant-discovery/theme'
import { RatingLink, REVIEWS_ANCHOR } from '../../../features/reviews/RatingLink'
import { ReviewsSection } from '../../../features/reviews/ReviewsSection'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { Alert, EmptyState, Skeleton } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { RestaurantIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { formatAddress, humanizeToken } from '../../../shared/utils/format'

const chip = 'inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700 ring-1 ring-slate-200/70 ring-inset'

/** `/restaurants/:restaurantId` — public restaurant page with its menu. */
export function RestaurantDetailPage() {
  const { restaurantId = '' } = useParams()
  const restaurant = useRestaurantDetail(restaurantId)
  // Evaluated once per visit, in India time like the backend's `openNow`.
  const [today] = useState(() => todayInIndia())

  if (restaurant.isPending) {
    return (
      <div role="status" aria-label="Loading restaurant" className="space-y-6">
        <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white">
          <Skeleton className="h-36 w-full rounded-none sm:h-44" />
          <div className="space-y-3 p-6">
            <Skeleton className="h-7 w-1/3" />
            <Skeleton className="h-4 w-1/4" />
            <Skeleton className="h-6 w-1/2 rounded-full" />
          </div>
        </div>
        <div className="grid gap-3 lg:grid-cols-2">
          <Skeleton className="h-32 w-full rounded-2xl" />
          <Skeleton className="h-32 w-full rounded-2xl" />
        </div>
      </div>
    )
  }

  if (restaurant.isError) {
    if (hasErrorCode(restaurant.error, RESTAURANT_NOT_FOUND) || (restaurant.error instanceof ApiError && restaurant.error.status === 404)) {
      return (
        <div className="mx-auto max-w-xl py-8">
          <EmptyState
            size="lg"
            illustration={<RestaurantIllustration className="w-full" />}
            title="Restaurant not found"
            description="It may have closed on FreshCart, or the link is wrong."
            action={
              <Link
                to="/restaurants"
                className="inline-flex h-10 items-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
              >
                Browse restaurants
              </Link>
            }
          />
        </div>
      )
    }
    return (
      <IllustratedError
        title="Could not load this restaurant"
        message={restaurant.error.message}
        correlationId={restaurant.error instanceof ApiError ? restaurant.error.correlationId : null}
        onRetry={() => void restaurant.refetch()}
      />
    )
  }

  const detail = restaurant.data
  const todaysHours = hoursForDay(detail.openingHours, today)
  const address = formatAddress(detail.address)
  const locality = [detail.address?.line2, detail.address?.city].filter(Boolean).join(', ')
  const theme = cuisineTheme(detail.cuisines, detail.id)

  return (
    <div className="space-y-6">
      <nav aria-label="Breadcrumb">
        <ol className="flex flex-wrap items-center gap-1.5 text-sm text-slate-500">
          <li>
            <Link to="/restaurants" className="rounded font-medium hover:text-emerald-700 hover:underline">
              Restaurants
            </Link>
          </li>
          <li className="flex items-center gap-1.5">
            <span aria-hidden="true" className="text-slate-300">
              /
            </span>
            <span aria-current="page" className="text-slate-700">
              {detail.name}
            </span>
          </li>
        </ol>
      </nav>

      <header className="animate-fade-up overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]">
        <RestaurantCover theme={theme} disc="none" muted={!detail.openNow} className="h-32 sm:h-44">
          <Icon name={theme.icon} className="absolute -right-4 -bottom-8 size-44 opacity-30 sm:right-8 sm:size-56" />
        </RestaurantCover>

        <div className="@container relative px-5 pb-5 sm:px-7 sm:pb-6">
          <span
            aria-hidden="true"
            className={cn(
              '-mt-10 flex size-20 items-center justify-center rounded-2xl bg-white shadow-lg ring-4 shadow-slate-900/10 ring-white sm:-mt-12 sm:size-24',
            )}
          >
            <Icon name={theme.icon} className="size-11 sm:size-12" />
          </span>

          <div className="mt-3 grid gap-5 @3xl:grid-cols-[minmax(0,1fr)_19rem] @3xl:items-start">
            <div className="min-w-0 space-y-2.5">
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">{detail.name}</h1>
                {detail.cuisines.length > 0 && (
                  <p className="mt-1 text-sm font-medium text-slate-600">{detail.cuisines.map(humanizeToken).join(' · ')}</p>
                )}
                <RatingLink rating={detail.rating} count={detail.ratingCount} />
              </div>
              {detail.description && <p className="max-w-2xl text-sm leading-relaxed text-slate-600">{detail.description}</p>}
              <div className="flex flex-wrap items-center gap-1.5 pt-1">
                <RestaurantStatusBadges openNow={detail.openNow} acceptingOrders={detail.acceptingOrders} pureVeg={detail.pureVeg} />
                <span className={chip}>
                  <Icon name="clock" className="size-3.5" />
                  <span className="text-slate-500">Today</span>
                  <span className="font-semibold text-slate-800 tabular-nums">{todaysHours ?? 'Closed today'}</span>
                </span>
                {locality && (
                  <span className={chip}>
                    <Icon name="mapPin" className="size-3.5" />
                    {locality}
                  </span>
                )}
              </div>
            </div>

            {(address || detail.openingHours.length > 0) && (
              <div className="space-y-3 rounded-xl bg-slate-50 p-4 text-sm ring-1 ring-slate-200/70 ring-inset">
                {address && (
                  <div className="flex gap-2.5">
                    <Icon name="mapPin" className="mt-0.5 size-4" />
                    <p>
                      <span className="block text-xs font-semibold tracking-wide text-slate-500 uppercase">Address</span>
                      <span className="text-slate-800">{address}</span>
                    </p>
                  </div>
                )}
                {detail.openingHours.length > 0 && (
                  <details className="group/hours">
                    <summary className="flex cursor-pointer list-none items-center gap-2.5 rounded font-semibold text-emerald-700 hover:text-emerald-800 [&::-webkit-details-marker]:hidden">
                      <Icon name="clock" className="size-4" />
                      Opening hours
                      <Icon name="arrowRight" className="size-3.5 transition-transform group-open/hours:rotate-90" />
                    </summary>
                    <table className="mt-2 ml-6.5 text-sm">
                      <caption className="sr-only">Weekly opening hours (India time)</caption>
                      <tbody>
                        {DAYS_OF_WEEK.map((day) => (
                          <tr key={day} className={cn(day === today && 'font-semibold text-emerald-800')}>
                            <th scope="row" className={cn('py-0.5 pr-6 text-left font-normal', day === today ? 'text-emerald-800' : 'text-slate-500')}>
                              {humanizeToken(day)}
                            </th>
                            <td className="py-0.5 text-slate-800 tabular-nums">{hoursForDay(detail.openingHours, day) ?? 'Closed'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </details>
                )}
              </div>
            )}
          </div>
        </div>
      </header>

      {!detail.acceptingOrders && (
        <Alert tone="warning" title="Not accepting orders right now">
          The restaurant has paused new orders for a while. You can still browse the menu.
        </Alert>
      )}
      {detail.acceptingOrders && !detail.openNow && (
        <Alert tone="info" title="Closed right now">
          You can browse the menu. Dishes can be added to your cart once the restaurant opens.
        </Alert>
      )}

      <RestaurantMenu
        menu={detail.menu}
        pureVeg={detail.pureVeg}
        itemAction={(item) => <MenuItemCartControl restaurant={detail} item={item} />}
      />

      <ReviewsSection id={REVIEWS_ANCHOR} targetType="RESTAURANT" targetId={detail.id} storeName={detail.name} />
    </div>
  )
}
