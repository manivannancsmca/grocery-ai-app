import { Link } from 'react-router'
import { Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { RestaurantCover } from '../restaurant-discovery/RestaurantCover'
import { formatDistance, type StoreCard as StoreCardData, storePlace } from './api'
import { storeTheme } from './theme'

/** Distance and whether the store delivers there, as a pill ("4.3 km · Delivers to you" / "Too far to deliver"). */
export function StoreDistance({ store, className }: { store: Pick<StoreCardData, 'distanceKm' | 'deliversHere'>; className?: string }) {
  if (store.distanceKm === null) return null
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset',
        store.deliversHere === false ? 'bg-amber-50 text-amber-800 ring-amber-200' : 'bg-emerald-50 text-emerald-800 ring-emerald-200',
        className,
      )}
    >
      <Icon name={store.deliversHere === false ? 'warning' : 'route'} className="size-3.5" />
      {formatDistance(store.distanceKm)}
      <span className="font-medium">· {store.deliversHere === false ? 'too far to deliver' : 'delivers to you'}</span>
    </span>
  )
}

/** A grocery store near the shopper: cover, name, area and distance. The whole card opens the store. */
export function StoreCard({ store, className }: { store: StoreCardData; className?: string }) {
  const theme = storeTheme(store.id)
  const place = storePlace(store)
  return (
    <article
      className={cn(
        'group relative flex h-full flex-col overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03] transition duration-200',
        'focus-within:ring-2 focus-within:ring-emerald-500/40 hover:-translate-y-0.5 hover:border-emerald-200 hover:shadow-md motion-reduce:hover:translate-y-0',
        className,
      )}
    >
      <RestaurantCover theme={theme} className="h-24" />
      <div className="flex flex-1 flex-col gap-1.5 p-3.5">
        <h3 className="line-clamp-2 text-[0.95rem] leading-snug font-semibold text-slate-900">
          <Link
            to={`/stores/${encodeURIComponent(store.id)}`}
            className="after:absolute after:inset-0 after:rounded-2xl focus:outline-none group-hover:text-emerald-800"
          >
            {store.name}
          </Link>
        </h3>
        {place && (
          <p className="flex min-w-0 items-center gap-1 text-xs text-slate-500">
            <Icon name="mapPin" className="size-3.5" />
            <span className="truncate">{place}</span>
          </p>
        )}
        <div className="mt-auto flex items-center justify-between gap-2 pt-1.5">
          <StoreDistance store={store} />
          <span
            aria-hidden="true"
            className="flex size-7 shrink-0 items-center justify-center rounded-full bg-slate-100 text-slate-500 transition group-hover:bg-emerald-600 group-hover:text-white"
          >
            <Icon name="arrowRight" className="size-3.5" />
          </span>
        </div>
      </div>
    </article>
  )
}

export function StoreCardSkeleton() {
  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
      <Skeleton className="h-24 w-full rounded-none" />
      <div className="space-y-2 p-3.5">
        <Skeleton className="h-4 w-3/4" />
        <Skeleton className="h-3 w-1/2" />
        <Skeleton className="mt-3 h-5 w-2/5 rounded-full" />
      </div>
    </div>
  )
}
