import { createContext, useContext } from 'react'
import { type ShopperLocation, useDefaultAddressLocation } from '../restaurant-discovery/location'

/**
 * Where the shopper wants things delivered: one location for the whole app (the header's "Deliver to", the stores
 * and products near you, restaurants). It is the customer's default address until they choose another one (their
 * device's location, another saved address or a point on the map); the choice is remembered per person in this
 * browser and can be undone with "Use my default address". The provider is `DeliveryLocationProvider`.
 */
export interface DeliveryLocationValue {
  /** The chosen location, else the default address (when it has coordinates), else null. */
  location: ShopperLocation | null
  /** The default address is still loading and nothing was chosen: wait instead of searching twice. */
  isPending: boolean
  /** The customer's default address as a location (null for guests or an address without coordinates). */
  defaultLocation: ShopperLocation | null
  /** A location was chosen by hand (so "Use my default address" makes sense). */
  isChosen: boolean
  choose: (location: ShopperLocation) => void
  resetToDefault: () => void
  /** The location dialog (rendered once, by `DeliveryLocationDialog` in the layout). */
  chooserOpen: boolean
  openChooser: () => void
  closeChooser: () => void
}

export const DeliveryLocationContext = createContext<DeliveryLocationValue | null>(null)

export const DELIVERY_LOCATION_STORAGE_PREFIX = 'freshcart.deliverTo.v1:'
const SOURCES: ReadonlyArray<ShopperLocation['source']> = ['browser', 'chosen']

/** A remembered location, or null when there is none or it is not valid any more. Storage may be blocked. */
export function readStoredLocation(key: string): ShopperLocation | null {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<ShopperLocation>
    const { latitude, longitude, source, label, detail } = value
    if (typeof latitude !== 'number' || typeof longitude !== 'number' || !Number.isFinite(latitude) || !Number.isFinite(longitude)) return null
    if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null
    if (!source || !SOURCES.includes(source) || typeof label !== 'string' || !label.trim()) return null
    return { latitude, longitude, source, label: label.slice(0, 60), ...(typeof detail === 'string' && detail ? { detail: detail.slice(0, 80) } : {}) }
  } catch {
    return null
  }
}

export function writeStoredLocation(key: string, location: ShopperLocation | null): void {
  try {
    if (location) window.localStorage.setItem(key, JSON.stringify(location))
    else window.localStorage.removeItem(key)
  } catch {
    // Private windows or blocked storage: the choice simply lasts until the page is reloaded.
  }
}

const noop = () => {}

/** The shared delivery location. Outside the provider (isolated component tests) it is the default address only. */
export function useDeliveryLocation(): DeliveryLocationValue {
  const context = useContext(DeliveryLocationContext)
  const defaults = useDefaultAddressLocation()
  if (context) return context
  return {
    location: defaults.location,
    isPending: defaults.isPending,
    defaultLocation: defaults.location,
    isChosen: false,
    choose: noop,
    resetToDefault: noop,
    chooserOpen: false,
    openChooser: noop,
    closeChooser: noop,
  }
}

/** "Home · Selaiyur, East Tambaram", "Your current location" or a chosen place, for sentences and buttons. */
export function deliveryPlaceText(location: ShopperLocation): string {
  if (location.source === 'browser') return 'Your current location'
  return location.detail ? `${location.label} · ${location.detail}` : location.label
}
