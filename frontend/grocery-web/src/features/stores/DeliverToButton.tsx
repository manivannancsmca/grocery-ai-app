import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { deliveryPlaceText, useDeliveryLocation } from './deliveryLocation'

/**
 * The header's "Deliver to" button (shoppers): where products, stores and restaurants are shown for. Opens the
 * location dialog. On the dark header chrome.
 */
export function DeliverToButton({ className }: { className?: string }) {
  const { location, isPending, openChooser } = useDeliveryLocation()
  const place = location ? deliveryPlaceText(location) : isPending ? 'Finding your address…' : 'Choose a location'
  return (
    <button
      type="button"
      onClick={openChooser}
      aria-label={location ? `Deliver to ${place}. Change the delivery location` : 'Choose your delivery location'}
      className={cn(
        'group flex min-w-0 items-center gap-2 rounded-xl py-1.5 pr-2 pl-1.5 text-left text-white transition-colors hover:bg-white/10',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white',
        className,
      )}
    >
      <span aria-hidden="true" className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-white/15 ring-1 ring-white/20 ring-inset">
        <Icon name="mapPin" className="size-[1.1rem]" />
      </span>
      <span className="min-w-0">
        <span className="block text-[0.68rem] leading-tight font-medium tracking-wide text-emerald-100/80 uppercase">Deliver to</span>
        <span className="flex min-w-0 items-center gap-0.5 text-sm leading-tight font-semibold">
          <span className="truncate">{place}</span>
          <Icon name="chevronRight" className="size-3.5 shrink-0 rotate-90 opacity-70 transition-transform group-hover:translate-y-px" />
        </span>
      </span>
    </button>
  )
}
