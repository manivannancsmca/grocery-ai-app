import { createContext, useContext } from 'react'
import { useDefaultAddressLocation } from '../restaurant-discovery/location'

/** @typedef {import('../restaurant-discovery/location').ShopperLocation} ShopperLocation */

/**
 * Where the shopper wants things delivered: one location for the whole app (the header's "Deliver to", the stores
 * and products near you, restaurants). It is the customer's default address until they choose another one (their
 * device's location, another saved address or a point on the map); the choice is remembered per person in this
 * browser and can be undone with "Use my default address". The provider is `DeliveryLocationProvider`.
 * @typedef {object} DeliveryLocationValue
 * @property {ShopperLocation | null} location The chosen location, else the default address (when it has coordinates), else null.
 * @property {boolean} isPending The default address is still loading and nothing was chosen: wait instead of searching twice.
 * @property {ShopperLocation | null} defaultLocation The customer's default address as a location (null for guests or an address without coordinates).
 * @property {boolean} isChosen A location was chosen by hand (so "Use my default address" makes sense).
 * @property {(location: ShopperLocation) => void} choose
 * @property {() => void} resetToDefault
 * @property {boolean} chooserOpen The location dialog (rendered once, by `DeliveryLocationDialog` in the layout).
 * @property {() => void} openChooser
 * @property {() => void} closeChooser
 */

export const DeliveryLocationContext = createContext(null)

export const DELIVERY_LOCATION_STORAGE_PREFIX = 'freshcart.deliverTo.v1:'
/** @type {ReadonlyArray<ShopperLocation['source']>} */
const SOURCES = ['browser', 'chosen']

/**
 * A remembered location, or null when there is none or it is not valid any more. Storage may be blocked.
 * @param {string} key
 * @returns {ShopperLocation | null}
 */
export function readStoredLocation(key) {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return null
    const value = JSON.parse(raw)
    const { latitude, longitude, source, label, detail } = value
    if (
      typeof latitude !== 'number' ||
      typeof longitude !== 'number' ||
      !Number.isFinite(latitude) ||
      !Number.isFinite(longitude)
    )
      return null
    if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null
    if (!source || !SOURCES.includes(source) || typeof label !== 'string' || !label.trim()) return null
    return {
      latitude,
      longitude,
      source,
      label: label.slice(0, 60),
      ...(typeof detail === 'string' && detail ? { detail: detail.slice(0, 80) } : {}),
    }
  } catch {
    return null
  }
}

/**
 * @param {string} key
 * @param {ShopperLocation | null} location
 * @returns {void}
 */
export function writeStoredLocation(key, location) {
  try {
    if (location) window.localStorage.setItem(key, JSON.stringify(location))
    else window.localStorage.removeItem(key)
  } catch {
    // Private windows or blocked storage: the choice simply lasts until the page is reloaded.
  }
}

const noop = () => {}

/**
 * The shared delivery location. Outside the provider (isolated component tests) it is the default address only.
 * @returns {DeliveryLocationValue}
 */
export function useDeliveryLocation() {
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

/**
 * "Home · Selaiyur, East Tambaram", "Your current location" or a chosen place, for sentences and buttons.
 * @param {ShopperLocation} location
 * @returns {string}
 */
export function deliveryPlaceText(location) {
  if (location.source === 'browser') return 'Your current location'
  return location.detail ? `${location.label} · ${location.detail}` : location.label
}
