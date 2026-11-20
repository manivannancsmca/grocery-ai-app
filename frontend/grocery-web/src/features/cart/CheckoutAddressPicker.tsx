import { useId } from 'react'
import { Badge } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatAddress } from '../../shared/utils/format'
import type { Address } from '../customer/api'

interface CheckoutAddressPickerProps {
  addresses: readonly Address[]
  selectedId: string | null
  onSelect: (id: string) => void
}

/** Radio cards, one per saved address; the default address carries a badge, the chosen one a check. */
export function CheckoutAddressPicker({ addresses, selectedId, onSelect }: CheckoutAddressPickerProps) {
  const name = useId()
  return (
    <fieldset className="@container">
      <legend className="sr-only">Deliver to</legend>
      <div className="grid gap-3 @xl:grid-cols-2">
        {addresses.map((address) => {
          const checked = address.id === selectedId
          const noLocation = address.latitude === null || address.longitude === null
          return (
            <label
              key={address.id}
              className={cn(
                'relative flex cursor-pointer gap-3 rounded-2xl border-2 p-4 transition',
                'has-[input:focus-visible]:outline-2 has-[input:focus-visible]:outline-offset-2 has-[input:focus-visible]:outline-emerald-600',
                checked
                  ? 'border-emerald-500 bg-emerald-50/60 shadow-sm shadow-emerald-600/10'
                  : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/70',
              )}
            >
              <input
                type="radio"
                name={name}
                value={address.id}
                checked={checked}
                onChange={() => onSelect(address.id)}
                className="sr-only"
              />
              <span
                aria-hidden="true"
                className={cn(
                  'flex size-10 shrink-0 items-center justify-center rounded-xl transition-colors',
                  checked ? 'bg-emerald-50 ring-2 ring-emerald-500' : 'bg-slate-100',
                )}
              >
                <Icon name="mapPin" className="size-5" />
              </span>
              <span className="min-w-0 flex-1 pr-6 text-sm">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold text-slate-900">{address.label}</span>
                  {address.isDefault && (
                    <Badge tone="green" dot>
                      Default
                    </Badge>
                  )}
                </span>
                <span className="mt-1 block font-medium text-slate-700">
                  {address.recipientName} · {address.phone}
                </span>
                <span className="mt-0.5 block leading-relaxed text-slate-500">{formatAddress(address)}</span>
                {noLocation && (
                  <span className="mt-2 block rounded-lg bg-amber-50 px-2 py-1 text-xs font-medium text-amber-800">
                    No map location — the delivery fee cannot be worked out for this address.
                  </span>
                )}
              </span>
              <span
                aria-hidden="true"
                className={cn(
                  'absolute top-3.5 right-3.5 flex size-5 items-center justify-center rounded-full border-2 transition-colors',
                  checked ? 'border-emerald-600 bg-emerald-600 text-white' : 'border-slate-300 bg-white',
                )}
              >
                {checked && <Icon name="check" className="size-3" />}
              </span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
