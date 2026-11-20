import { useAuth } from '../../shared/auth/useAuth'
import { useAddresses } from '../customer/hooks'

/**
 * @typedef {object} ShopperLocation
 * @property {number} latitude
 * @property {number} longitude
 * @property {'address' | 'browser' | 'chosen'} source `address`: the default delivery address; `browser`: the device's GPS ("Use my location"); `chosen`: picked by hand — a saved address or a point on the map (when location access is refused, or to look elsewhere).
 * @property {string} label "Home" (address label), "your current location" or what was chosen ("Office", "the point you chose on the map").
 * @property {string} [detail] The area of a saved address ("Selaiyur, East Tambaram"), shown next to its label; absent for other locations.
 */

/**
 * The area of a saved address in a few words: its second line, else its city.
 * @param {{ line2?: string | null, city?: string | null }} address
 * @returns {string | undefined}
 */
export function addressDetail(address) {
  return address.line2?.trim() || address.city?.trim() || undefined
}

/**
 * Where restaurants are searched from, in words: "Near Home (your default address)".
 * @param {ShopperLocation} location
 * @returns {string}
 */
export function nearText(location) {
  if (location.source === 'address') return `Near ${location.label} (your default address)`
  if (location.source === 'browser') return 'Near your current location'
  return `Near ${location.label}`
}

/**
 * The URL parameters for a location (`?lat=&lng=` and, for a hand-picked one, `&near=` with its name).
 * @param {ShopperLocation} location
 * @returns {{ lat: string, lng: string, near: string | null }}
 */
export function locationParams(location) {
  return {
    lat: String(location.latitude),
    lng: String(location.longitude),
    near: location.source === 'chosen' ? location.label : null,
  }
}

/**
 * The signed-in customer's default delivery address as a search location (when it has coordinates).
 * Guests and other roles have none. `isPending` is true while the addresses load, so callers can
 * wait instead of searching twice (first without, then with a location).
 * @returns {{ location: ShopperLocation | null, isPending: boolean }}
 */
export function useDefaultAddressLocation() {
  const { status, area } = useAuth()
  const isCustomer = status === 'authenticated' && area === 'CUSTOMER'
  const addresses = useAddresses({ enabled: isCustomer })

  if (!isCustomer) return { location: null, isPending: status === 'initializing' }
  if (addresses.isPending) return { location: null, isPending: true }

  const address = addresses.data?.find((entry) => entry.isDefault) ?? null
  if (!address || address.latitude === null || address.longitude === null) return { location: null, isPending: false }
  return {
    location: {
      latitude: address.latitude,
      longitude: address.longitude,
      source: 'address',
      label: address.label,
      detail: addressDetail(address),
    },
    isPending: false,
  }
}

/**
 * `/restaurants` searching around this location (the full page reads it from the URL).
 * @param {ShopperLocation} location
 * @returns {string}
 */
export function restaurantsHref(location) {
  const { lat, lng, near } = locationParams(location)
  const params = new URLSearchParams({ lat, lng })
  if (near) params.set('near', near)
  return `/restaurants?${params.toString()}`
}

/**
 * Parses `?lat=&lng=` (both required and in range), as written by "Use my location", and `&near=` (a hand-picked
 * location's name, see `locationParams`).
 * @param {string | null} lat
 * @param {string | null} lng
 * @param {string | null} [near=null]
 * @returns {ShopperLocation | null}
 */
export function locationFromParams(lat, lng, near = null) {
  if (lat === null || lng === null) return null
  const latitude = Number(lat)
  const longitude = Number(lng)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null
  if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null
  const name = near?.trim().slice(0, 60)
  if (name) return { latitude, longitude, source: 'chosen', label: name }
  return { latitude, longitude, source: 'browser', label: 'your current location' }
}
