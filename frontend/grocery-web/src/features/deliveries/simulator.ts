import { type GeoPoint, roundCoordinate } from '../../shared/components/map/types'

/**
 * The partner's location simulator (development and testing): a desktop browser far from the test data can
 * still play a partner. "Drive" moves the position along a straight line in equal steps.
 */

/** One step every 2 s… */
export const DRIVE_STEP_MS = 2_000
/** …and 20 steps per drive, so any trip takes 40 s. */
export const DRIVE_STEPS = 20

/** The point `fraction` (0–1) of the way from `from` to `to` (straight line; fine at city scale). */
export function interpolate(from: GeoPoint, to: GeoPoint, fraction: number): GeoPoint {
  const t = Math.min(1, Math.max(0, fraction))
  return {
    latitude: roundCoordinate(from.latitude + (to.latitude - from.latitude) * t),
    longitude: roundCoordinate(from.longitude + (to.longitude - from.longitude) * t),
  }
}

/** Where the simulated partner is after `step` of `steps` steps (the last step lands exactly on `to`). */
export function driveStep(from: GeoPoint, to: GeoPoint, step: number, steps = DRIVE_STEPS): GeoPoint {
  return step >= steps ? { latitude: roundCoordinate(to.latitude), longitude: roundCoordinate(to.longitude) } : interpolate(from, to, step / steps)
}

/**
 * The simulator exists only in development (the Vite dev server) and in test builds made with
 * `VITE_LOCATION_SIMULATOR=true`. A production build never offers it, so a real partner's position always comes from
 * their phone's GPS; a URL switch alone cannot turn it on.
 */
export function simulatorEnabled(dev: boolean = import.meta.env.DEV, buildFlag: string | undefined = import.meta.env.VITE_LOCATION_SIMULATOR): boolean {
  return dev || buildFlag === 'true'
}

/** `12.971600, 77.594600` */
export function formatCoordinates(point: GeoPoint): string {
  return `${point.latitude.toFixed(6)}, ${point.longitude.toFixed(6)}`
}
