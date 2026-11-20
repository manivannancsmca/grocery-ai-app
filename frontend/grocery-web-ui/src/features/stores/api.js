import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'

/**
 * The public store directory of vendor-service (`/api/stores/**`, no token needed). Distances are the road estimate
 * checkout uses (straight line × 1.3), so a store listed as delivering here is one checkout accepts.
 */
const PUBLIC = { skipAuth: true }

/**
 * An approved grocery store as shoppers see it (no registration numbers, contact details or status).
 * @typedef {object} StoreCard
 * @property {string} id
 * @property {string} name
 * @property {string | null} line1
 * @property {string | null} area The neighbourhood (address line 2), e.g. "West Tambaram".
 * @property {string | null} city
 * @property {string | null} postalCode
 * @property {number} latitude
 * @property {number} longitude
 * @property {number | null} distanceKm Road distance in km (1 decimal) from the point asked about; null when no point was given.
 * @property {boolean | null} deliversHere Whether the store delivers to that point; null when no point was given.
 */

/**
 * @typedef {object} NearbyStoresResponse
 * @property {number} deliveryLimitKm Stores deliver up to this road distance (cart-service's grocery limit).
 * @property {StoreCard[]} content Nearest first; only stores that deliver to the point.
 */

export const STORE_NOT_FOUND = 'STORE_NOT_FOUND'

/**
 * Coordinates in cache keys and requests: 5 decimals (about 1 m) so tiny float noise never refetches.
 * @param {number} value
 * @returns {number}
 */
export function roundCoordinate(value) {
  return Math.round(value * 1e5) / 1e5
}

export const storeKeys = {
  all: ['stores'],
  nearby: (lat, lng) => [...storeKeys.all, 'nearby', roundCoordinate(lat), roundCoordinate(lng)],
  store: (id, lat, lng) => [
    ...storeKeys.all,
    'store',
    id,
    lat === undefined ? null : roundCoordinate(lat),
    lng === undefined ? null : roundCoordinate(lng),
  ],
}

/**
 * @param {number} lat
 * @param {number} lng
 * @returns {Promise<NearbyStoresResponse>}
 */
export async function fetchNearbyStores(lat, lng) {
  return (
    await httpClient.get('/stores/nearby', {
      ...PUBLIC,
      params: { lat: roundCoordinate(lat), lng: roundCoordinate(lng) },
    })
  ).data
}

/**
 * One approved store; with a point, also its distance from there and whether it delivers there.
 * @param {string} id
 * @param {{ lat: number, lng: number }} [point]
 * @returns {Promise<StoreCard>}
 */
export async function fetchStore(id, point) {
  return (
    await httpClient.get(`/stores/${segment(id)}`, {
      ...PUBLIC,
      params: point ? { lat: roundCoordinate(point.lat), lng: roundCoordinate(point.lng) } : undefined,
    })
  ).data
}

/**
 * "West Tambaram, Chennai" — the store's area and city, whichever are known.
 * @param {Pick<StoreCard, 'area' | 'city'>} store
 * @returns {string}
 */
export function storePlace(store) {
  return [store.area, store.city].filter(Boolean).join(', ')
}

/**
 * "4.3 km" (one decimal, as checkout shows it).
 * @param {number} km
 * @returns {string}
 */
export function formatDistance(km) {
  return `${km.toLocaleString('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} km`
}
