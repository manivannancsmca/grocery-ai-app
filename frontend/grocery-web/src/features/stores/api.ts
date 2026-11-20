import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'

/**
 * The public store directory of vendor-service (`/api/stores/**`, no token needed). Distances are the road estimate
 * checkout uses (straight line × 1.3), so a store listed as delivering here is one checkout accepts.
 */
const PUBLIC = { skipAuth: true } as const

/** An approved grocery store as shoppers see it (no registration numbers, contact details or status). */
export interface StoreCard {
  id: string
  name: string
  line1: string | null
  /** The neighbourhood (address line 2), e.g. "West Tambaram". */
  area: string | null
  city: string | null
  postalCode: string | null
  latitude: number
  longitude: number
  /** Road distance in km (1 decimal) from the point asked about; null when no point was given. */
  distanceKm: number | null
  /** Whether the store delivers to that point; null when no point was given. */
  deliversHere: boolean | null
}

export interface NearbyStoresResponse {
  /** Stores deliver up to this road distance (cart-service's grocery limit). */
  deliveryLimitKm: number
  /** Nearest first; only stores that deliver to the point. */
  content: StoreCard[]
}

export const STORE_NOT_FOUND = 'STORE_NOT_FOUND'

/** Coordinates in cache keys and requests: 5 decimals (about 1 m) so tiny float noise never refetches. */
export function roundCoordinate(value: number): number {
  return Math.round(value * 1e5) / 1e5
}

export const storeKeys = {
  all: ['stores'] as const,
  nearby: (lat: number, lng: number) => [...storeKeys.all, 'nearby', roundCoordinate(lat), roundCoordinate(lng)] as const,
  store: (id: string, lat?: number, lng?: number) =>
    [...storeKeys.all, 'store', id, lat === undefined ? null : roundCoordinate(lat), lng === undefined ? null : roundCoordinate(lng)] as const,
}

export async function fetchNearbyStores(lat: number, lng: number): Promise<NearbyStoresResponse> {
  return (
    await httpClient.get<NearbyStoresResponse>('/stores/nearby', {
      ...PUBLIC,
      params: { lat: roundCoordinate(lat), lng: roundCoordinate(lng) },
    })
  ).data
}

/** One approved store; with a point, also its distance from there and whether it delivers there. */
export async function fetchStore(id: string, point?: { lat: number; lng: number }): Promise<StoreCard> {
  return (
    await httpClient.get<StoreCard>(`/stores/${segment(id)}`, {
      ...PUBLIC,
      params: point ? { lat: roundCoordinate(point.lat), lng: roundCoordinate(point.lng) } : undefined,
    })
  ).data
}

/** "West Tambaram, Chennai" — the store's area and city, whichever are known. */
export function storePlace(store: Pick<StoreCard, 'area' | 'city'>): string {
  return [store.area, store.city].filter(Boolean).join(', ')
}

/** "4.3 km" (one decimal, as checkout shows it). */
export function formatDistance(km: number): string {
  return `${km.toLocaleString('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} km`
}
