import { useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../../shared/auth/useAuth'
import { LazyDeliveryMap } from '../../shared/components/map/LazyDeliveryMap'
import { DEFAULT_MAP_CENTER, type GeoPoint } from '../../shared/components/map/types'
import { Button, Dialog, Spinner } from '../../shared/components/ui'
import { useCurrentPosition } from '../../shared/hooks/useCurrentPosition'
import { Icon } from '../../shared/icons'
import { useAddresses } from '../customer/hooks'
import { addressDetail, type ShopperLocation } from './location'

interface LocationChooserProps {
  open: boolean
  onClose: () => void
  onChoose: (location: ShopperLocation) => void
  /** The location in use now: where the map opens. */
  current: ShopperLocation | null
}

/** The name a point picked on the map gets ("Near the point you chose on the map"). */
export const MAP_POINT_LABEL = 'the point you chose on the map'

/**
 * Where should restaurants deliver to? Three ways, so a refused location permission never leaves the customer stuck:
 * the device's location (the browser asks for permission), one of their saved addresses (customers), or a point
 * picked on the map (everyone, guests too). Nothing here is made up: every location is the customer's own.
 */
export function LocationChooser({ open, onClose, onChoose, current }: LocationChooserProps) {
  const { status, area } = useAuth()
  const isCustomer = status === 'authenticated' && area === 'CUSTOMER'
  const addresses = useAddresses({ enabled: open && isCustomer })
  const { locate, isLocating, error: locateError } = useCurrentPosition('pick a saved address or a point on the map below')
  const [picked, setPicked] = useState<GeoPoint | null>(null)

  function choose(location: ShopperLocation) {
    onChoose(location)
    setPicked(null)
    onClose()
  }

  async function locateDevice() {
    const coords = await locate()
    if (coords) choose({ ...coords, source: 'browser', label: 'your current location' })
  }

  const saved = addresses.data ?? []

  return (
    <Dialog open={open} onClose={onClose} size="lg" title="Choose your delivery location" description="Stores and restaurants that deliver here are shown first, by their distance.">
      <div className="space-y-6">
        <section aria-labelledby="where-device" className="space-y-2">
          <h3 id="where-device" className="text-sm font-semibold text-slate-900">
            Your current location
          </h3>
          <Button variant="secondary" onClick={() => void locateDevice()} disabled={isLocating}>
            {isLocating ? <Spinner size="sm" /> : <Icon name="crosshair" className="size-4" />}
            {isLocating ? 'Locating…' : 'Use my location'}
          </Button>
          {locateError && (
            <p role="alert" className="text-xs font-medium text-amber-700">
              {locateError}
            </p>
          )}
        </section>

        {isCustomer && (
          <section aria-labelledby="where-saved" className="space-y-2">
            <h3 id="where-saved" className="text-sm font-semibold text-slate-900">
              Your saved addresses
            </h3>
            {addresses.isPending ? (
              <Spinner size="sm" label="Loading your addresses" />
            ) : saved.length === 0 ? (
              <p className="text-sm text-slate-600">
                No saved addresses yet.{' '}
                <Link to="/account/addresses" className="font-semibold text-emerald-700 hover:underline">
                  Add one
                </Link>
              </p>
            ) : (
              <ul className="grid gap-2 sm:grid-cols-2">
                {saved.map((address) => {
                  const located = address.latitude !== null && address.longitude !== null
                  return (
                    <li key={address.id}>
                      <button
                        type="button"
                        disabled={!located}
                        onClick={() =>
                          located &&
                          choose({
                            latitude: address.latitude as number,
                            longitude: address.longitude as number,
                            source: 'chosen',
                            label: address.label,
                            detail: addressDetail(address),
                          })
                        }
                        className="flex w-full items-start gap-3 rounded-xl border border-slate-200 bg-white p-3 text-left transition-colors hover:border-emerald-300 hover:bg-emerald-50/60 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-slate-200 disabled:hover:bg-white"
                      >
                        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-emerald-50 ring-1 ring-emerald-100">
                          <Icon name="home" className="size-5" />
                        </span>
                        <span className="min-w-0">
                          <span className="block text-sm font-semibold text-slate-900">
                            {address.label}
                            {address.isDefault && <span className="ml-1.5 text-xs font-medium text-emerald-700">Default</span>}
                          </span>
                          <span className="block truncate text-xs text-slate-500">
                            {address.line1}, {address.city}
                          </span>
                          {!located && <span className="block text-xs text-amber-700">No map location: edit it in your addresses first.</span>}
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </section>
        )}

        <section aria-labelledby="where-map" className="space-y-2">
          <h3 id="where-map" className="text-sm font-semibold text-slate-900">
            Or tap a point on the map
          </h3>
          <LazyDeliveryMap
            points={picked ? [{ id: 'picked', kind: 'home', label: 'Chosen delivery location', ...picked }] : []}
            label="Map to choose your delivery location"
            onPick={setPicked}
            fallbackCenter={current ?? DEFAULT_MAP_CENTER}
            className="h-72"
          />
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-slate-500">{picked ? `Chosen: ${picked.latitude.toFixed(5)}, ${picked.longitude.toFixed(5)}` : 'Tap where the order should be delivered.'}</p>
            <Button disabled={!picked} onClick={() => picked && choose({ ...picked, source: 'chosen', label: MAP_POINT_LABEL })}>
              <Icon name="check" className="size-4" />
              Use this point
            </Button>
          </div>
        </section>
      </div>
    </Dialog>
  )
}
