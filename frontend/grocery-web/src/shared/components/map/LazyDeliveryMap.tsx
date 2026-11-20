import { lazy, Suspense } from 'react'
import { cn } from '../../utils/cn'
import { Skeleton } from '../ui'
import type { DeliveryMapProps } from './types'

// Leaflet (~150 kB) is loaded only by the screens that show a map, when they show it.
const DeliveryMap = lazy(async () => ({ default: (await import('./DeliveryMap')).DeliveryMap }))

/** `DeliveryMap`, loaded on first use; a grey box of the same size holds its place meanwhile. */
export function LazyDeliveryMap(props: DeliveryMapProps) {
  return (
    <Suspense
      fallback={
        <div role="status" aria-label="Loading the map">
          <Skeleton className={cn('w-full rounded-xl', props.className ?? 'h-64')} />
        </div>
      }
    >
      <DeliveryMap {...props} />
    </Suspense>
  )
}
