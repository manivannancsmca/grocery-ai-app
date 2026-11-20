import { roundCoordinate } from '../../shared/components/map/types'

/** @typedef {import('../../shared/components/map/types').GeoPoint} GeoPoint */

/**
 * The partner's location simulator (development and testing): a desktop browser far from the test data can
 * still play a partner. "Drive" moves the position along a straight line in equal steps.
 */

/** One step every 2 s… */
export const DRIVE_STEP_MS = 2_000
/** …and 20 steps per drive, so any trip takes 40 s. */
export const DRIVE_STEPS = 20

/**
 * The point `fraction` (0–1) of the way from `from` to `to` (straight line; fine at city scale).
 * @param {GeoPoint} from
 * @param {GeoPoint} to
 * @param {number} fraction
 * @returns {GeoPoint}
 */
export function interpolate(from, to, fraction) {
  const t = Math.min(1, Math.max(0, fraction))
  return {
    latitude: roundCoordinate(from.latitude + (to.latitude - from.latitude) * t),
    longitude: roundCoordinate(from.longitude + (to.longitude - from.longitude) * t),
  }
}

/**
 * Where the simulated partner is after `step` of `steps` steps (the last step lands exactly on `to`).
 * @param {GeoPoint} from
 * @param {GeoPoint} to
 * @param {number} step
 * @param [steps=DRIVE_STEPS]
 * @returns {GeoPoint}
 */
export function driveStep(from, to, step, steps = DRIVE_STEPS) {
  return step >= steps
    ? { latitude: roundCoordinate(to.latitude), longitude: roundCoordinate(to.longitude) }
    : interpolate(from, to, step / steps)
}

/**
 * The simulator exists only in development (the Vite dev server) and in test builds made with
 * `VITE_LOCATION_SIMULATOR=true`. A production build never offers it, so a real partner's position always comes from
 * their phone's GPS; a URL switch alone cannot turn it on.
 * @param {boolean} [dev=import.meta.env.DEV]
 * @param {string | undefined} [buildFlag]
 * @returns {boolean}
 */
export function simulatorEnabled(dev = import.meta.env.DEV, buildFlag = import.meta.env.VITE_LOCATION_SIMULATOR) {
  return dev || buildFlag === 'true'
}

/**
 * `12.971600, 77.594600`
 * @param {GeoPoint} point
 * @returns {string}
 */
export function formatCoordinates(point) {
  return `${point.latitude.toFixed(6)}, ${point.longitude.toFixed(6)}`
}
