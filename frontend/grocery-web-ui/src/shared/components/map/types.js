/**
 * A latitude / longitude pair as the APIs send it.
 * @typedef {object} GeoPoint
 * @property {number} latitude
 * @property {number} longitude
 */

/**
 * What a marker shows, each with its own picture and colour: a grocery store or a restaurant (the pickup), the
 * customer's home (the drop) or the delivery partner.
 * @typedef {'store' | 'restaurant' | 'home' | 'partner'} MapPointKind
 */

/**
 * @typedef {GeoPoint & { id: string, kind: MapPointKind, label: string }} MapPoint
 * - `id`: Stable id: the map fits its view again only when the set of ids changes, not when a point moves.
 * - `label`: Marker title and legend text, e.g. "Green Basket (store)".
 */

/**
 * @typedef {object} DeliveryMapProps
 * @property {readonly MapPoint[]} points
 * @property {string} label Accessible name of the map.
 * @property {(position: GeoPoint) => void} [onPick] Clicking the map picks a position (the partner's location simulator).
 * @property {GeoPoint} [fallbackCenter] Where the map opens when there is no point to show.
 * @property {string} [className] Height and rounding of the map box (default `h-64`).
 * @property {readonly string[]} [route] Ids of points to join with a route line, in travel order (e.g. partner → restaurant → home). Points that are not on the map are skipped. The line is straight from point to point: the map has no road routing.
 */

/**
 * Central Bengaluru: where the map opens when there is nothing to show yet (the seed data lives there).
 * @type {GeoPoint}
 */
export const DEFAULT_MAP_CENTER = { latitude: 12.9716, longitude: 77.5946 }

/**
 * Rounds to 6 decimals (~10 cm), like every coordinate the APIs store.
 * @param {number} value
 * @returns {number}
 */
export function roundCoordinate(value) {
  return Math.round(value * 1e6) / 1e6
}

const EARTH_RADIUS_KM = 6371.0088

/**
 * Straight-line (haversine) distance in km, the way delivery-service measures it.
 * @param {GeoPoint} from
 * @param {GeoPoint} to
 * @returns {number}
 */
export function distanceKm(from, to) {
  const toRad = (degrees) => (degrees * Math.PI) / 180
  const dLat = toRad(to.latitude - from.latitude)
  const dLon = toRad(to.longitude - from.longitude)
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(toRad(from.latitude)) * Math.cos(toRad(to.latitude)) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)))
}

/**
 * True for a finite latitude / longitude pair (the APIs may send null for an unknown position).
 * @param {{ latitude?: number | null, longitude?: number | null } | null | undefined} value
 * @returns {value is GeoPoint}
 */
export function isGeoPoint(value) {
  return (
    value !== null &&
    value !== undefined &&
    typeof value.latitude === 'number' &&
    typeof value.longitude === 'number' &&
    Number.isFinite(value.latitude) &&
    Number.isFinite(value.longitude)
  )
}
