/** A latitude / longitude pair as the APIs send it. */
export interface GeoPoint {
  latitude: number
  longitude: number
}

/**
 * What a marker shows, each with its own picture and colour: a grocery store or a restaurant (the pickup), the
 * customer's home (the drop) or the delivery partner.
 */
export type MapPointKind = 'store' | 'restaurant' | 'home' | 'partner'

export interface MapPoint extends GeoPoint {
  /** Stable id: the map fits its view again only when the set of ids changes, not when a point moves. */
  id: string
  kind: MapPointKind
  /** Marker title and legend text, e.g. "Green Basket (store)". */
  label: string
}

export interface DeliveryMapProps {
  points: readonly MapPoint[]
  /** Accessible name of the map. */
  label: string
  /** Clicking the map picks a position (the partner's location simulator). */
  onPick?: (position: GeoPoint) => void
  /** Where the map opens when there is no point to show. */
  fallbackCenter?: GeoPoint
  /** Height and rounding of the map box (default `h-64`). */
  className?: string
  /**
   * Ids of points to join with a route line, in travel order (e.g. partner → restaurant → home). Points that are
   * not on the map are skipped. The line is straight from point to point: the map has no road routing.
   */
  route?: readonly string[]
}

/** Central Bengaluru: where the map opens when there is nothing to show yet (the seed data lives there). */
export const DEFAULT_MAP_CENTER: GeoPoint = { latitude: 12.9716, longitude: 77.5946 }

/** Rounds to 6 decimals (~10 cm), like every coordinate the APIs store. */
export function roundCoordinate(value: number): number {
  return Math.round(value * 1e6) / 1e6
}

const EARTH_RADIUS_KM = 6371.0088

/** Straight-line (haversine) distance in km, the way delivery-service measures it. */
export function distanceKm(from: GeoPoint, to: GeoPoint): number {
  const toRad = (degrees: number) => (degrees * Math.PI) / 180
  const dLat = toRad(to.latitude - from.latitude)
  const dLon = toRad(to.longitude - from.longitude)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(from.latitude)) * Math.cos(toRad(to.latitude)) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)))
}

/** True for a finite latitude / longitude pair (the APIs may send null for an unknown position). */
export function isGeoPoint(value: { latitude?: number | null; longitude?: number | null } | null | undefined): value is GeoPoint {
  return (
    value !== null &&
    value !== undefined &&
    typeof value.latitude === 'number' &&
    typeof value.longitude === 'number' &&
    Number.isFinite(value.latitude) &&
    Number.isFinite(value.longitude)
  )
}
