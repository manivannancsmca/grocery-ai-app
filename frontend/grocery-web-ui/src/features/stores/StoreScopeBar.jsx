import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { deliveryPlaceText, useDeliveryLocation } from './deliveryLocation'

/** @typedef {import('./hooks').StoreScope} StoreScope */

const LINK_BUTTON =
  'inline-flex h-8 items-center gap-1 rounded-full px-3 text-xs font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * @typedef {object} StoreScopeBarProps
 * @property {StoreScope} scope
 * @property {(showAll: boolean) => void} onShowAll Switches between "only stores that deliver here" (false) and "every store" (true).
 * @property {string} [className]
 */

/**
 * One line above a product grid saying whose products these are: the stores that deliver to the delivery location
 * (with "Show all stores"), every store (with the way back), or an invitation to set a location.
 * @param {StoreScopeBarProps} props
 */
export function StoreScopeBar({ scope, onShowAll, className }) {
  const { openChooser } = useDeliveryLocation()
  if (scope.isPending) return null

  const place = scope.location ? deliveryPlaceText(scope.location) : null
  const count = scope.stores.length
  const frame = cn('flex flex-wrap items-center gap-x-3 gap-y-2 rounded-2xl border px-3.5 py-2.5 text-sm', className)
  const change = (
    <button
      type="button"
      onClick={openChooser}
      className={cn(LINK_BUTTON, 'bg-white text-slate-700 ring-1 ring-slate-200 ring-inset hover:bg-slate-50')}
    >
      <Icon name="mapPin" className="size-3.5" />
      {scope.location ? 'Change location' : 'Choose location'}
    </button>
  )

  if (scope.failed) {
    return (
      <p role="status" className={cn(frame, 'border-amber-200 bg-amber-50/70 text-amber-900')}>
        <Icon name="warning" className="size-4 shrink-0" />
        <span className="min-w-0 flex-1">
          Stores near you could not be checked, so products from every store are shown.
        </span>
      </p>
    )
  }

  if (!scope.location) {
    return (
      <div className={cn(frame, 'border-slate-200 bg-white/80 text-slate-700')}>
        <Icon name="mapPin" className="size-4 shrink-0" />
        <span className="min-w-0 flex-1">Set your delivery location to see only the stores that deliver to you.</span>
        {change}
      </div>
    )
  }

  if (scope.mode === 'all') {
    return (
      <div className={cn(frame, 'border-slate-200 bg-white/80 text-slate-700')}>
        <Icon name="globe" className="size-4 shrink-0" />
        <span className="min-w-0 flex-1">
          Products from <strong className="font-semibold text-slate-900">every store</strong>. Some may not deliver to{' '}
          {place}.
        </span>
        <button
          type="button"
          onClick={() => onShowAll(false)}
          className={cn(LINK_BUTTON, 'bg-emerald-600 text-white hover:bg-emerald-700')}
        >
          <Icon name="route" className="size-3.5" />
          Only stores that deliver here
        </button>
      </div>
    )
  }

  return (
    <div className={cn(frame, 'border-emerald-200/80 bg-emerald-50/70 text-emerald-950')}>
      <Icon name="route" className="size-4 shrink-0" />
      <span className="min-w-0 flex-1">
        {scope.noneDeliver ? (
          <>
            No store delivers to <strong className="font-semibold">{place}</strong> yet.
          </>
        ) : (
          <>
            From{' '}
            <strong className="font-semibold">
              {count} store{count === 1 ? '' : 's'}
            </strong>{' '}
            that deliver to <strong className="font-semibold">{place}</strong>
            {scope.deliveryLimitKm !== null && (
              <span className="text-emerald-800/80"> (within {scope.deliveryLimitKm} km)</span>
            )}
          </>
        )}
      </span>
      <span className="flex flex-wrap items-center gap-2">
        {change}
        <button
          type="button"
          onClick={() => onShowAll(true)}
          className={cn(LINK_BUTTON, 'text-emerald-800 hover:bg-emerald-100')}
        >
          Show all stores
        </button>
      </span>
    </div>
  )
}
