import { type ReactNode, useCallback, useMemo, useState } from 'react'
import { useAuth } from '../../shared/auth/useAuth'
import { LocationChooser } from '../restaurant-discovery/LocationChooser'
import { type ShopperLocation, useDefaultAddressLocation } from '../restaurant-discovery/location'
import {
  DELIVERY_LOCATION_STORAGE_PREFIX,
  DeliveryLocationContext,
  type DeliveryLocationValue,
  readStoredLocation,
  useDeliveryLocation,
  writeStoredLocation,
} from './deliveryLocation'

/** Provides the app-wide delivery location (see `useDeliveryLocation`). Needs the auth and query providers. */
export function DeliveryLocationProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const defaults = useDefaultAddressLocation()
  const storageKey = DELIVERY_LOCATION_STORAGE_PREFIX + (user?.id ?? 'guest')
  // Keyed by the storage key, so the next person signing in on this browser never sees the previous one's choice.
  const [chosen, setChosen] = useState<{ key: string; location: ShopperLocation | null }>(() => ({
    key: storageKey,
    location: readStoredLocation(storageKey),
  }))
  const [chooserOpen, setChooserOpen] = useState(false)
  if (chosen.key !== storageKey) setChosen({ key: storageKey, location: readStoredLocation(storageKey) })
  const current = chosen.key === storageKey ? chosen.location : null

  const choose = useCallback(
    (location: ShopperLocation) => {
      // A saved address picked by hand is still a choice: "Use my default address" brings the default back.
      const next: ShopperLocation = location.source === 'address' ? { ...location, source: 'chosen' } : location
      writeStoredLocation(storageKey, next)
      setChosen({ key: storageKey, location: next })
    },
    [storageKey],
  )
  const resetToDefault = useCallback(() => {
    writeStoredLocation(storageKey, null)
    setChosen({ key: storageKey, location: null })
  }, [storageKey])
  const openChooser = useCallback(() => setChooserOpen(true), [])
  const closeChooser = useCallback(() => setChooserOpen(false), [])

  const value = useMemo<DeliveryLocationValue>(
    () => ({
      location: current ?? defaults.location,
      isPending: !current && defaults.isPending,
      defaultLocation: defaults.location,
      isChosen: current !== null,
      choose,
      resetToDefault,
      chooserOpen,
      openChooser,
      closeChooser,
    }),
    [current, defaults.location, defaults.isPending, choose, resetToDefault, chooserOpen, openChooser, closeChooser],
  )
  return <DeliveryLocationContext.Provider value={value}>{children}</DeliveryLocationContext.Provider>
}

/** The one "Choose your delivery location" dialog of the app (needs the router, so the layout renders it). */
export function DeliveryLocationDialog() {
  const { location, chooserOpen, closeChooser, choose } = useDeliveryLocation()
  return <LocationChooser open={chooserOpen} onClose={closeChooser} current={location} onChoose={choose} />
}
