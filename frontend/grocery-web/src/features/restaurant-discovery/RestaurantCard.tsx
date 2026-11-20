import { Link } from 'react-router'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDistanceKm, formatPrice, humanizeToken } from '../../shared/utils/format'
import { RatingBadge } from '../reviews/StarRating'
import type { RestaurantCard as RestaurantCardData } from './api'
import { RestaurantCover } from './RestaurantCover'
import { cuisineTheme } from './theme'

const pill = 'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold whitespace-nowrap ring-1 ring-inset'

/** "Open now" (green dot) or "Closed" (grey dot) — the dot is decoration, the word carries the meaning. */
function OpenPill({ openNow, className }: { openNow: boolean; className?: string }) {
  return (
    <span className={cn(pill, openNow ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-slate-100 text-slate-600 ring-slate-200', className)}>
      <span aria-hidden="true" className={cn('size-1.5 rounded-full', openNow ? 'animate-pulse bg-emerald-500' : 'bg-slate-400')} />
      {openNow ? 'Open now' : 'Closed'}
    </span>
  )
}

function PausedPill({ className }: { className?: string }) {
  return (
    <span className={cn(pill, 'bg-amber-50 text-amber-800 ring-amber-200', className)}>
      <Icon name="pauseCircle" className="size-3.5" />
      Not accepting orders
    </span>
  )
}

function PureVegPill({ className }: { className?: string }) {
  return (
    <span className={cn(pill, 'bg-green-50 text-green-800 ring-green-200', className)}>
      <span aria-hidden="true" className="flex">
        <FoodTypeMark foodType="VEG" />
      </span>
      Pure veg
    </span>
  )
}

/** Open / closed / not-accepting-orders / pure-veg pills shared by cards and the restaurant page. */
export function RestaurantStatusBadges({
  openNow,
  acceptingOrders,
  pureVeg,
  className,
}: Pick<RestaurantCardData, 'openNow' | 'acceptingOrders' | 'pureVeg'> & { className?: string }) {
  return (
    <div className={cn('flex flex-wrap items-center gap-1.5', className)}>
      <OpenPill openNow={openNow} />
      {!acceptingOrders && <PausedPill />}
      {pureVeg && <PureVegPill />}
    </div>
  )
}

interface RestaurantCardProps {
  restaurant: RestaurantCardData
  /** Smaller variant for the storefront strip (shorter cover, no description). */
  compact?: boolean
}

/**
 * One restaurant search result: a tinted cover (cuisine icon, open state, pure veg), the name — which links to the
 * restaurant page and covers the whole card —, rating, cuisines, place and distance, and matching dishes.
 */
export function RestaurantCard({ restaurant, compact = false }: RestaurantCardProps) {
  const place = [restaurant.locality, restaurant.city].filter(Boolean).join(', ')
  const distance = formatDistanceKm(restaurant.distanceKm)
  const theme = cuisineTheme(restaurant.cuisines, restaurant.id)

  return (
    <article
      className={cn(
        'group relative flex h-full flex-col overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03] transition duration-200',
        'hover:-translate-y-0.5 hover:border-emerald-200 hover:shadow-md focus-within:ring-2 focus-within:ring-emerald-500/50',
      )}
    >
      <RestaurantCover theme={theme} muted={!restaurant.openNow} className={compact ? 'h-24' : 'h-28'}>
        <div className="absolute inset-x-3 top-3 flex items-start justify-between gap-2">
          <OpenPill openNow={restaurant.openNow} className="bg-white/90 shadow-sm ring-white/60 backdrop-blur-sm" />
          {restaurant.pureVeg && <PureVegPill className="bg-white/90 shadow-sm ring-white/60 backdrop-blur-sm" />}
        </div>
      </RestaurantCover>

      <div className="flex flex-1 flex-col p-4">
        <div className="flex items-start justify-between gap-3">
          <h3 className="min-w-0 text-base leading-snug font-semibold text-slate-900">
            <Link
              to={`/restaurants/${encodeURIComponent(restaurant.id)}`}
              className="after:absolute after:inset-0 after:rounded-2xl focus:outline-none group-hover:text-emerald-800"
            >
              {restaurant.name}
            </Link>
          </h3>
          <RatingBadge
            rating={restaurant.rating}
            count={restaurant.ratingCount}
            className="shrink-0 rounded-full bg-amber-50 px-2 py-0.5 ring-1 ring-amber-200/70 ring-inset"
          />
        </div>
        {restaurant.cuisines.length > 0 && (
          <p className="mt-0.5 truncate text-sm text-slate-600">{restaurant.cuisines.map(humanizeToken).join(' · ')}</p>
        )}
        {(place || distance) && (
          <p className="mt-1.5 flex min-w-0 items-center gap-1.5 text-xs text-slate-500">
            <Icon name="mapPin" className="size-3.5" />
            {place && <span className="truncate">{place}</span>}
            {place && distance && (
              <span aria-hidden="true" className="text-slate-300">
                •
              </span>
            )}
            {distance && <span className="shrink-0 font-medium text-slate-700">{distance} away</span>}
          </p>
        )}

        {!compact && restaurant.description && <p className="mt-2.5 line-clamp-2 text-sm text-slate-600">{restaurant.description}</p>}

        {!restaurant.acceptingOrders && (
          <div className="mt-3">
            <PausedPill />
          </div>
        )}

        {restaurant.matchingItems.length > 0 && (
          <div className="mt-3 rounded-xl bg-slate-50 p-3">
            <p className="text-[11px] font-semibold tracking-wider text-slate-500 uppercase">Matching dishes</p>
            <ul className="mt-1.5 space-y-1">
              {restaurant.matchingItems.map((item) => (
                <li key={item.id} className="flex items-center gap-2 text-sm">
                  <FoodTypeMark foodType={item.foodType} />
                  <span className="min-w-0 flex-1 truncate text-slate-700">{item.name}</span>
                  <span className="font-semibold text-slate-900 tabular-nums">{formatPrice(item.price)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </article>
  )
}

export function RestaurantCardSkeleton({ compact = false }: { compact?: boolean }) {
  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
      <Skeleton className={cn('w-full rounded-none', compact ? 'h-24' : 'h-28')} />
      <div className="space-y-2 p-4">
        <Skeleton className="h-4 w-2/3" />
        <Skeleton className="h-3 w-1/2" />
        <Skeleton className="h-3 w-1/3" />
      </div>
    </div>
  )
}
