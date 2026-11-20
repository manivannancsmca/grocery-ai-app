import { useQuery } from '@tanstack/react-query'
import { fetchNearbyStores, fetchStore, storeKeys } from './api'
import { useDeliveryLocation } from './deliveryLocation'

/** @typedef {import('../restaurant-discovery/location').ShopperLocation} ShopperLocation */
/** @typedef {import('./api').StoreCard} StoreCard */

/** The stores list changes rarely (an approval, a suspension): a few minutes of cache is plenty. */
const STORES_STALE_MS = 5 * 60_000
/** catalog-query accepts at most this many `vendorId` values in one search. */
export const MAX_SCOPE_STORES = 50

/**
 * Approved stores that deliver to `location`, nearest first. Disabled without a location.
 * @param {Pick<ShopperLocation, 'latitude' | 'longitude'> | null} location
 */
export function useNearbyStores(location) {
  return useQuery({
    queryKey: storeKeys.nearby(location?.latitude ?? 0, location?.longitude ?? 0),
    queryFn: () => fetchNearbyStores(location?.latitude ?? 0, location?.longitude ?? 0),
    enabled: location !== null,
    staleTime: STORES_STALE_MS,
  })
}

/**
 * One store, with its distance from `location` when there is one.
 * @param {string} id
 * @param {Pick<ShopperLocation, 'latitude' | 'longitude'> | null} location
 */
export function useStore(id, location) {
  return useQuery({
    queryKey: storeKeys.store(id, location?.latitude, location?.longitude),
    queryFn: () => fetchStore(id, location ? { lat: location.latitude, lng: location.longitude } : undefined),
    staleTime: STORES_STALE_MS,
  })
}

/**
 * Which stores' products to show: `near` = only stores that deliver to the delivery location (the default once a
 * location is known), `all` = every store (the shopper asked for it), `unknown` = no location yet, or the store
 * list could not be loaded (then every store, as before stores were location-aware).
 * @typedef {'near' | 'all' | 'unknown'} StoreScopeMode
 */

/**
 * @typedef {object} StoreScope
 * @property {StoreScopeMode} mode
 * @property {ShopperLocation | null} location
 * @property {string[] | undefined} vendorIds `vendorId` values for the product search; undefined = every store. Never an empty list.
 * @property {boolean} isPending Location or stores still loading: wait before searching, so the results do not jump.
 * @property {boolean} noneDeliver A location is known and no store delivers there (show that, do not search).
 * @property {StoreCard[]} stores The stores that deliver to the location (empty while unknown).
 * @property {ReadonlyMap<string, number>} distances Store id → road km from the delivery location, for the product cards.
 * @property {number | null} deliveryLimitKm
 * @property {boolean} failed The store list failed: every store is shown and the shopper is told.
 */

/** @type {ReadonlyMap<string, number>} */
const NO_DISTANCES = new Map()

/**
 * The product search scope for the delivery location. `showAll` is the shopper's "Show all stores".
 * @param {boolean} showAll
 * @returns {StoreScope}
 */
export function useStoreScope(showAll) {
  const delivery = useDeliveryLocation()
  const location = delivery.location
  const nearby = useNearbyStores(location)
  const stores = nearby.data?.content ?? []
  const distances = nearby.data
    ? new Map(stores.flatMap((store) => (store.distanceKm === null ? [] : [[store.id, store.distanceKm]])))
    : NO_DISTANCES
  const base = { location, stores, distances, deliveryLimitKm: nearby.data?.deliveryLimitKm ?? null }

  if (delivery.isPending)
    return { ...base, mode: 'unknown', vendorIds: undefined, isPending: true, noneDeliver: false, failed: false }
  if (!location)
    return { ...base, mode: 'unknown', vendorIds: undefined, isPending: false, noneDeliver: false, failed: false }
  if (nearby.isPending)
    return { ...base, mode: 'near', vendorIds: undefined, isPending: true, noneDeliver: false, failed: false }
  if (nearby.isError)
    return { ...base, mode: 'unknown', vendorIds: undefined, isPending: false, noneDeliver: false, failed: true }
  if (showAll)
    return { ...base, mode: 'all', vendorIds: undefined, isPending: false, noneDeliver: false, failed: false }
  if (stores.length === 0)
    return { ...base, mode: 'near', vendorIds: undefined, isPending: false, noneDeliver: true, failed: false }
  return {
    ...base,
    mode: 'near',
    vendorIds: stores.slice(0, MAX_SCOPE_STORES).map((store) => store.id),
    isPending: false,
    noneDeliver: false,
    failed: false,
  }
}
